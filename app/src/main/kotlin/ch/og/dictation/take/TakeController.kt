package ch.og.dictation.take

import ch.og.dictation.audio.MicrophoneOwner
import ch.og.dictation.audio.RecordingFeedback
import ch.og.dictation.diagnostics.RotationEvent
import ch.og.dictation.diagnostics.RotationTrace
import ch.og.dictation.history.HistoryDelivery
import ch.og.dictation.history.HistoryNotice
import ch.og.dictation.history.HistoryTakeOutput
import ch.og.dictation.ime.InputSession
import ch.og.dictation.ime.commitToInput
import ch.og.dictation.panel.HeldPanel
import ch.og.dictation.panel.PanelChoice
import ch.og.dictation.panel.PanelPhase
import ch.og.dictation.panel.PanelPreview
import ch.og.dictation.panel.PanelState
import ch.og.dictation.panel.panelFrame
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The take controller: all take state and every take decision (ADR-010). The input method service
 * forwards its events here, in its callbacks' order, and carries out the effects asked of it through
 * the ports. Main thread only (the take scheduler's thread); nothing here is Android.
 *
 * Recording starts when the panel opens; speech is decoded while recording continues and shown in
 * the panel only. Stop commits the whole text once into the field that started the take and returns
 * to the previous keyboard. Nothing is written to a field before Stop.
 */
class TakeController(
    private val host: TakeHost,
    private val scheduler: TakeScheduler,
    private val opener: TakeOpener,
    /** The editor port: the only way take code writes to or reads from the editor. */
    private val editor: EditorPort,
    /** Take diagnostics: every traced event gets the take's facts as arguments (see [traceFacts]). */
    private val diagnostics: TakeDiagnostics,
    private val rotations: RotationCounter,
    private val lab: TakeLab,
    private val log: TakeLog,
) {
    companion object {
        /** The second read-back after a write that was not seen in the field at once (ADR-010). */
        const val CONFIRM_DELAY_MS = 100L
    }
    private var feedback: RecordingFeedback? = null
    /** The ring follows [feedback]'s live loudness; otherwise it rests. */
    private var ringLive = false
    private var captureEnded: () -> Boolean = { false }
    /** What the panel says and what its microphone does; stored at each change and read only for the frame ([panelState]). */
    private var phase = PanelPhase.OPENING
    private var previewText = ""
    private var editorRetentionEligible = false
    /** Kept for panel recreation throughout this take; never recomputed from later focus. */
    private var takeRetentionEligible: Boolean? = null
    /** Only saving audio needs a banner; experiment receipts remain out of the normal panel. */
    private var saveTake = false
    private val session = InputSession()
    /** Set while a hand-back to the keyboard is pending, so the panel does not start a new take. */
    private var returning = false
    /** Input session the current take's panel is attached to. */
    private var recordingToken: Long? = null
    /** Authority to insert: granted by Stop to the panel's session; text goes there or nowhere. */
    private var authority: Long? = null
    private var take: TakeSession? = null
    /**
     * The per-take record: made when a take starts and kept, as [latest], until the next one starts.
     * Its identity is the take's: every callback of a take holds its record, and one whose record is
     * no longer [latest] is stale and changes nothing.
     */
    private class Take(
        /** The take's number in the trace, counted from 1. */
        val number: Long,
    ) {
        /** The field the take started in; after a rotation hold only this field may regain insert authority (ADR-010). */
        var field: FieldIdentity? = null
        var connection: EditorConnection? = null
        /** The app whose editor the take started in; its text is only ever held and shown for that app. */
        var appPackage: String? = null
        /** The screen went off while this take was active: a lock never keeps its text. */
        var locked = false
        /** This take went through a rotation hold (also a self-requested panel) since its capture began. */
        var heldForRotation = false
        /** A system interruption ended this take: no authority, no choice, no preview; its audio so far goes to History. */
        var interrupted = false
        /** The panel went to rest under this take ([endLiveParts]): its capture's late callbacks change nothing. */
        var atRest = false
        /**
         * The Stop wait: Stop was pressed and the take waits for its text. A system interruption is not a
         * Stop ([interrupted] says that). Discard take ends the wait at once, also while the microphone is
         * still being released ([chooseDiscard]).
         */
        var stopWait = false
        /** The read-back wait: this take ended with a write that was not seen in the field at once; the second look is pending ([confirmLater]). */
        var readBackWait = false
    }
    /** The newest take's record, also after it ended; null before the first take. */
    private var latest: Take? = null
    /**
     * Another capture was seen owning the microphone since the last start: the panel says so, and
     * returns to rest when the microphone is free ([updateSharedMicrophone]).
     */
    private var microphoneBusy = false
    /**
     * The auto-start latch: after an interruption, a busy microphone or a required explicit start, a
     * panel shown again starts no take by itself; only the microphone press does ([startRecording]).
     */
    private var autoStartLatched = false
    private var releaseRetry = false
    private var previewAllowed = false
    private var destroyed = false
    /** The service registers and removes it with [MicrophoneOwner]. */
    val microphoneOwnerListener: (MicrophoneOwner.State) -> Unit = {
        scheduler.post { if (!destroyed) updateSharedMicrophone() }
    }
    private var inputViewVisible = false
    /** Retention class of the editor that last started input. */
    private var editorRetention = EditorRetention.NO_EDITOR
    /**
     * The rotation count when the panel was last visible (settled, see ROTATION_SETTLE_MS); a higher
     * count identifies a rotation since.
     */
    private var rotationsWhenVisible = 0L
    /** Rotation hold: authority revoked, preview hidden, capture continues until the panel returns. */
    private var holding = false
    /** From the first hold of a rotation until the panel has settled; one gap for all of it. */
    private var rotationEpisode = false
    /** Stop granted authority and a rotation hold revoked it: it moves to the session the panel returns to. */
    private var authorityMoves = false
    /** The editor that last started input, as identity values only. */
    private var editorIdentity: FieldIdentity? = null
    /**
     * A rotation hold's input start was not the take's field (same app): the take keeps recording but has no
     * authority, and Stop writes nothing (the text is held). While the hold is open the take's own field starting
     * input again (the automatic rotation sequence) returns it; once the hold settled on a mismatch it is final.
     */
    private var fieldMismatch = false
    /** The rotation hold settled on a mismatch: it is final, the take's field never regains authority. */
    private var mismatchFinal = false
    /** The take's field is back inside the hold: the rotation stays identified until the panel has settled. */
    private var retargetSettle = false
    /**
     * The take whose outcome is not in yet: from its start until finished() has decided what the panel
     * keeps of it ([outcomeIn]), or until the second look of its read-back wait. No new take starts meanwhile.
     */
    private var outcomePending: Take? = null
    /** The read-back of a write that could not be confirmed at once; the second look is pending. */
    private var pendingConfirm: PendingConfirm? = null
    /** The History side of the current take: a late pass of the read-back corrects its delivery receipt. */
    private var takeHistory: HistoryTakeOutput? = null
    /**
     * The finished text of a take that could not be inserted (plan B): held in memory only while
     * the Unable to insert text state shows; never written or logged. Cleared by Discard text, Back
     * after a copy, screen off and a different app's editor; kept across a rotation, an input
     * restart and a tap on the field. While held, the status is the held state's own ([HeldPanel.status]).
     */
    private var held: HeldText? = null
    private var editorPackage: String? = null
    /** An input is started (between onStartInput and onFinishInput); a panel is only requested then. */
    private var inputStarted = false
    /**
     * The open Cancel choice, part of the take's state: every panel (also one recreated for a
     * rotation) renders it inline. RECORDING: the take is paused. FINISHING: delivery is held.
     */
    private var choice: PanelChoice? = null
    private val gapExpired = Runnable {
        if (!rotationEpisode) return@Runnable
        if (holding) { log.info("rotation hold: panel did not return"); rotationEvent(RotationEvent.HOLD_GAP_EXPIRED); interruptTake() }
        else rotationSettled()
    }
    private var gapExpiredPosted: TakeScheduler.Cancellable? = null
    private val panelSettled = Runnable {
        if (!holding && inputViewVisible && session.matches(recordingToken)) rotationSettled()
    }
    private var panelSettledPosted: TakeScheduler.Cancellable? = null
    /** Newest preview of the current take, shown again when the panel returns from a hold. */
    private var latestPreview = ""

    /** A take exists from the start of recording until its text is committed or dropped. */
    val takeActive: Boolean get() = take?.active == true

    /** The current take was ended by a system interruption (it can still be active while it finishes into History). */
    private val interrupted: Boolean get() = latest?.interrupted == true

    /**
     * The newest take ended on the panel and its outcome is not in: it was interrupted and still finishes
     * into History, or its write is looked at once more. The panel offers no new take until it is in.
     */
    private val awaitingOutcome: Boolean
        get() = latest?.let { outcomePending === it && !takeActive && (it.interrupted || it.readBackWait) } == true

    /** The current take is in the Stop wait (it may be interrupted there, and then finishes into History). */
    private val stopWait: Boolean get() = latest?.stopWait == true

    /** Android may silence a client whose window is hidden; a silenced hold cannot keep listening. */
    fun recordingConfigChanged(silenced: () -> Boolean) {
        if (holding && !stopWait && silenced()) silencedDuringHold()
    }

    /** A locked phone must not keep listening. */
    fun screenOff() {
        // Also after Stop and during the read-back wait, until finished(): its text is refused, after unlock too.
        outcomePending?.locked = true
        if (held != null) { clearHeld(); showIdle() }
        previewAllowed = false
        showPreview("")
        editorEvent(EditorEvent.SCREEN_OFF)
    }

    /** A panel view is being created: a rotation cancels the Back warning. */
    fun panelCreated() {
        held?.warn(false)
    }

    /** The panel's microphone disk. */
    fun microphonePressed() {
        if (MicrophoneOwner.state().releaseFailed) {
            releaseRetry = false
            showPhase(PanelPhase.STOPPING)
            MicrophoneOwner.retryRelease()
        } else if (takeActive) stopAndInsert() else startRecording()
    }

    /** onStartInput, after the framework's own handling: the editor's facts as plain values. */
    fun inputStart(retention: EditorRetention, packageName: String?, identity: FieldIdentity?) {
        editorRetention = retention
        editorRetentionEligible = editorRetention.eligible
        editorPackage = packageName
        editorIdentity = identity
        inputStarted = true
        held?.warn(false)
        if (held != null && editorPackage != held?.from) { clearHeld(); showIdle() } // A different app's editor.
        previewAllowed = false
        showPreview("")
        // Any input start ends the previous session (InputSession.start).
        session.start()
        editorEvent(EditorEvent.INPUT_START)
    }

    /** onStartInputView, after the framework's own handling. */
    fun inputViewStart() {
        inputViewVisible = true
        session.resume()
        editorEvent(EditorEvent.INPUT_VIEW_START)
        if (returning || takeActive) return
        if (MicrophoneOwner.state().occupied) {
            noteMicrophoneBusy()
            updateSharedMicrophone()
            return
        }
        if (held != null) { // A panel shown again never starts a take over held text.
            showIdle()
            return
        }
        if (MicrophoneOwner.state().explicitStartRequired) {
            autoStartLatched = true
            showIdle()
            return
        }
        if (autoStartLatched) return
        takeRetentionEligible = null
        lab.startupTake = lab.beginStartupTake()
        lab.observeGeometry(lab.startupTake)
        showIdle()
        if (host.hasMicPermission()) startRecording() else requestMicPermission()
    }

    /** onFinishInputView, first part; the service then reports [EditorEvent.INPUT_VIEW_FINISH] to [editorEvent]. */
    fun inputViewHidden() {
        inputViewVisible = false
        setRingLive(false)
        // Focus moved or keyboard hidden: never insert late, never keep the mic.
        session.invalidate()
        previewAllowed = false
        showPreview("")
    }

    /** onFinishInput, before the framework's own handling. */
    fun inputFinish() {
        inputStarted = false
        session.invalidate()
        previewAllowed = false
        showPreview("")
        editorEvent(EditorEvent.INPUT_FINISH)
    }

    /** onWindowHidden, before the framework's own handling. */
    fun windowHidden() {
        previewAllowed = false
        showPreview("")
        session.invalidate()
        editorEvent(EditorEvent.WINDOW_HIDDEN)
    }

    /** The six editor-event callbacks decide the take's fate here and nowhere else. */
    fun editorEvent(event: EditorEvent): EditorDecision {
        // Identification uses only what this app observed, at or before this event.
        rotations.observe()
        val decision = EditorEventPolicy.decide(event, TakeFacts(
            active = takeActive, returning = returning, sameSession = session.matches(recordingToken),
            capturing = takeActive && !stopWait && !interrupted,
            finishing = takeActive && stopWait && !interrupted,
            rotation = rotationIdentified(event), otherApp = anotherApp()))
        if (event == EditorEvent.INPUT_START && takeActive && decision == EditorDecision.REVOKE_AUTHORITY) judgeField()
        when (decision) {
            EditorDecision.NOTHING -> if (event == EditorEvent.INPUT_VIEW_START && takeActive && !returning) panelVisible()
            EditorDecision.REVOKE_AUTHORITY -> {
                if (authority != null) authorityMoves = true
                revokeAuthority()
                // A positively private rebound editor applies now, not only if the panel returns.
                if (event == EditorEvent.INPUT_START && editorRetention.positivelyPrivate) excludeTakeFromHistory()
                enterRotationHold(event)
            }
            EditorDecision.INTERRUPT -> interruptTake()
        }
        return decision
    }

    /** A rotation since the panel was last visible, or the take is without authority for another field. */
    private fun rotationIdentified(event: EditorEvent? = null): Boolean {
        if (rotations.changes > rotationsWhenVisible || retargetSettle) return true
        if (!fieldMismatch) return false
        if (!mismatchFinal) return true // The hold is still open: the editor is still reacting to the rotation.
        // Settled on a mismatch: a start is still judged, but a plain hide is not a rotation (ADR-010: no timer after it).
        return when (event) {
            EditorEvent.INPUT_VIEW_FINISH, EditorEvent.INPUT_FINISH, EditorEvent.WINDOW_HIDDEN -> holding
            else -> true
        }
    }

    /**
     * An input started inside a hold of this take. Only the take's own field keeps the take's insert
     * authority; another editor of the app (a search box, a view that is no editor) leaves the take
     * recording without it for good, and Stop then holds the text. Nothing here is an interruption.
     */
    private fun judgeField() {
        val now = editorIdentity
        val same = now?.isSameFieldAs(latest?.field) == true
        rotationEvent(RotationEvent.FIELD_JUDGED, same, detail = mapOf(
            "sameField" to same, "typeNull" to (now?.typeNull ?: true), "selValid" to (now?.selValid ?: false),
            "connectionChanged" to (editor.live() != latest?.connection)))
        if (!same && !fieldMismatch) {
            fieldMismatch = true
            log.info("authority withheld: input is not the take's field")
        } else if (same && fieldMismatch && !mismatchFinal) {
            fieldMismatch = false
            retargetSettle = true
            // A Stop pressed meanwhile gets its authority back with the panel on this field's session.
            if (stopWait) authorityMoves = true
            log.info("authority returned: the take's field, inside the hold")
        }
    }

    private fun anotherApp(): Boolean {
        val mine = latest?.appPackage
        val current = editorPackage
        return mine != null && current != null && current != mine
    }

    /** Enters (or stays in) the rotation hold. The gap starts once and is never extended. */
    private fun enterRotationHold(event: EditorEvent) {
        panelSettledPosted?.cancel()
        latest?.heldForRotation = true
        if (!holding) {
            holding = true
            rotationEvent(RotationEvent.HOLD_ENTERED)
            log.info("rotation hold")
            setRingLive(false)
        }
        // An open choice is take state: the returned panel renders it again; nothing to move.
        // Stop wait: hold the result before dispatch; an accepted commit is never undone.
        if (stopWait) take?.holdDelivery(DeliveryHold.ROTATION)
        if (!rotationEpisode) {
            rotationEpisode = true
            gapExpiredPosted = scheduler.postDelayed(ROTATION_HOLD_GAP_MS, gapExpired)
        }
        if (stopWait) { requestOwnPanelForNewEditor(event); return } // The microphone is already off.
        val silenced = host.captureSilenced()
        if (silenced) { silencedDuringHold(); return }
        requestOwnPanelForNewEditor(event)
    }

    /**
     * A hold is on and our panel is not showing: ask for it. Asked on every hold entry (the new
     * editor's input start, and again when it hides the view or the window), because the editor's
     * hide can land after the first request ran, when the hold had briefly ended (Signal order,
     * 4 in 12 probe runs). One request is pending at a time, and the hold's gap bounds the asking:
     * after the gap, a real leave, or the panel's return, nothing is requested.
     */
    private fun requestOwnPanelForNewEditor(event: EditorEvent) {
        if (event != EditorEvent.INPUT_START && event != EditorEvent.INPUT_VIEW_FINISH && event != EditorEvent.WINDOW_HIDDEN) return
        panelRequestPosted?.cancel()
        panelRequestPosted = scheduler.postDelayed(0, panelRequest)
    }

    private val panelRequest = Runnable {
        if (holding && inputStarted && !inputViewVisible && !destroyed) host.requestPanel()
    }
    private var panelRequestPosted: TakeScheduler.Cancellable? = null

    /** The panel settled after a rotation: the rotation no longer holds the result (the finishing choice still may). */
    private fun restoreAfterRotation() {
        if (!takeActive || interrupted) return
        take?.releaseDelivery(DeliveryHold.ROTATION)
    }

    private fun silencedDuringHold() {
        log.info("rotation hold: capture silenced")
        rotationEvent(RotationEvent.HOLD_SILENCED)
        interruptTake()
    }

    /** Ends hold, gap and settling without deciding anything about the take. */
    private fun endHold() {
        holding = false
        rotationEpisode = false
        gapExpiredPosted?.cancel()
        panelSettledPosted?.cancel()
    }

    /** The panel stayed visible after the rotation: later editor events are judged without it. */
    private fun rotationSettled() {
        if (rotationEpisode) rotationEvent(RotationEvent.HOLD_SETTLED)
        endHold()
        rotationsWhenVisible = rotations.changes
        retargetSettle = false
        if (fieldMismatch) mismatchFinal = true
        authorityMoves = false
        if (takeActive && !interrupted && editorRetention.excludesSettledTake) excludeTakeFromHistory()
        restoreAfterRotation()
    }

    /**
     * Take start, end or interruption: ends hold, gap and settling, and forgets everything a
     * rotation decided for the take here: moving authority and the field judgement (the take
     * session owns what holds its result, and releases or drops it itself).
     */
    private fun endHoldAndForgetRotation() {
        endHold()
        fieldMismatch = false
        mismatchFinal = false
        retargetSettle = false
        render()
        authorityMoves = false
    }

    /**
     * The panel is visible on the live session for this take; a held take continues there. The
     * rotation stays identified until the panel has stayed visible for ROTATION_SETTLE_MS, because
     * a recreated editor can still hide it right after Android showed it (#56 trace).
     */
    private fun panelVisible() {
        recordingToken = session.token()
        // Stop's authority moves with the identified rotation to the session the panel returned to.
        if (authorityMoves && stopWait && !interrupted && !fieldMismatch) authority = recordingToken
        if (editorRetention.positivelyPrivate) excludeTakeFromHistory()
        if (holding) {
            holding = false
            if (!interrupted) {
                previewAllowed = true
                showPreview(latestPreview)
                if (!stopWait && choice == null) setRingLive(true)
            }
        }
        settleAfterRotation()
    }

    /**
     * Retention stays latched from the starting editor and only tightens: a positively private
     * editor (password, no-learning) seen while the take rebinds, or an excluded editor the take
     * settles on (panel settled after a rotation, or Stop), excludes the take and removes what was
     * saved, whatever ends the take afterwards. An input start without an editor (NO_EDITOR, e.g.
     * TYPE_NULL while an app recreates its activity) never tightens by itself (#61).
     */
    private fun excludeTakeFromHistory() {
        if (takeRetentionEligible != true) return
        takeRetentionEligible = false
        take?.excludeFromHistory()
        render()
    }

    /** Also from the service: after its own configuration change and a display change. */
    fun settleAfterRotation() {
        if (!takeActive || holding || !inputViewVisible || !rotationIdentified()) return
        panelSettledPosted?.cancel()
        panelSettledPosted = scheduler.postDelayed(ROTATION_SETTLE_MS, panelSettled)
    }

    /** No text may be written for this take until Stop grants authority again; capture is untouched. */
    private fun revokeAuthority() {
        authority = null
        previewAllowed = false
        showPreview("")
    }

    /** A traced event; the service's own events (also without a take) come through here too. */
    fun rotationEvent(event: RotationEvent, flag: Boolean? = null, configDiff: Int? = null, handledConfig: Int? = null,
                      retention: EditorRetention? = null, detail: Map<String, Any>? = null) {
        if (!diagnostics.tracing) return
        diagnostics.event(event, traceFacts(), flag, configDiff, handledConfig, retention, detail)
    }

    /** The take's facts for a traced event, read now. Reads the display rotation, as every traced event did. */
    private fun traceFacts(): TakeTraceFacts {
        val rotation = rotations.observe()
        return TakeTraceFacts(active = takeActive, liveSession = recordingToken != null && session.matches(recordingToken),
            view = rotations.view, take = latest?.number, rotation = rotation, rotations = rotations.changes)
    }

    /** onDestroy, first part: nothing scheduled runs for this service any more. */
    fun beginDestroy() {
        destroyed = true
        outcomePending = null
        endHold()
    }

    /** onDestroy, second part, after the service removed its listeners. */
    fun finishDestroy() {
        session.invalidate()
        if (takeActive) interruptTake()
        if (releaseRetry) MicrophoneOwner.retryRelease()
        feedback?.stop()
    }

    private fun startRecording() {
        if (takeActive || held != null || outcomePending != null) return // Held text is never dropped by starting a take.
        if (MicrophoneOwner.state().occupied) {
            noteMicrophoneBusy()
            updateSharedMicrophone()
            return
        }
        MicrophoneOwner.acceptExplicitStart()
        microphoneBusy = false
        autoStartLatched = false
        choice = null
        render()
        previewAllowed = true
        if (!host.hasMicPermission()) {
            showPhase(PanelPhase.NO_PERMISSION)
            return
        }
        // Latch current policy for each new take, including explicit restarts.
        // Read once per take: a take keeps the Wiring it started with (its History root above all).
        val opening = opener.begin()
        val liveCheck = opening.liveCheck()
        saveTake = !RotationTrace.probeActive() && lab.recordingEnabled(liveCheck) && editorRetentionEligible
        render()
        if (lab.startupTake == null) lab.startupTake = lab.beginStartupTake()
        val record = Take((latest?.number ?: 0L) + 1)
        latest = record
        rotationEvent(RotationEvent.CAPTURE_START)
        var started = false // This start produced a take that will deliver finished().
        try {
            val liveTakeId = lab.startupTake
            outcomePending = record
            recordingToken = session.token()
            record.field = host.currentField()
            record.connection = editor.live()
            record.appPackage = editorPackage
            authority = null
            fun authorityForThisTake() = authority.takeIf { record === latest }
            val save = saveTake
            val retentionEligible = editorRetentionEligible
            takeRetentionEligible = retentionEligible
            render()
            var identity: () -> org.json.JSONObject? = { null }
            var attempted = false
            val output = object : TakeOutput {
                override fun preview(text: String) = publishPreview(record, text)
                override fun captureReleaseFailed() {
                    if (record !== latest) return
                    releaseRetry = true
                    showPhase(PanelPhase.RELEASE_FAILED)
                }
                override fun commit(text: String) = commitForSession(record, authorityForThisTake(), text) { attempted = true }
                override fun commit(text: String, allowed: () -> Boolean) = commitForSession(record, authorityForThisTake(), text, allowed) { attempted = true }
                override fun commitAttempted() = attempted
                override fun returnToKeyboard(): Boolean {
                    if (!session.matches(authorityForThisTake())) return false
                    // Switching the IME can synchronously invalidate input. The result has
                    // already been submitted; do not turn that hand-back into cancellation.
                    returning = true
                    return try { host.returnToKeyboard() }
                    finally { scheduler.post { returning = false } }
                }
                override fun finished(result: TakeResult, pcm: ByteArray) {
                    // Synchronous on the main thread; the outcome is in once the panel's part is decided below ([outcomeIn]).
                    // A write that is still being read back reports its terminal event at the second look.
                    val lateLook = pendingConfirm != null && !result.interrupted && result.outcome == TakeOutcome.COMMIT_REJECTED
                    if (!lateLook) diagnostics.terminal(result.outcome, record.number)
                    identity()?.let { lab.startup?.liveResult(liveTakeId, it, result) }
                    if (!result.interrupted) lab.saveRecording(save && !RotationTrace.probeActive(), pcm, result)
                    if (record !== latest) { outcomeIn(record); return }
                    // The take's History output is dropped with the take; only a write awaiting its second look keeps it.
                    if (pendingConfirm == null || result.interrupted || result.outcome != TakeOutcome.COMMIT_REJECTED) takeHistory = null
                    recordingToken = null
                    authority = null
                    lab.startupTake = null
                    latestPreview = ""
                    endHoldAndForgetRotation()
                    if (result.interrupted) {
                        pendingConfirm = null
                        take = null
                        choice = null
                        showPreview("")
                        // Whether History kept it shows on the History page; the private-field line says when it cannot.
                        val keep = textToHold(record, result)
                        if (keep != null) hold(record, keep, result.historySaved)
                        outcomeIn(record)
                        showPhase(PanelPhase.NOT_INSERTED)
                        return
                    }
                    choice = null
                    showIdle(ended = record)
                    val pending = pendingConfirm
                    pendingConfirm = null
                    if (pending != null && result.outcome == TakeOutcome.COMMIT_REJECTED) {
                        // The write may still show up: the held text, or the hand-back, waits for the second look.
                        confirmLater(pending, result, record)
                        return
                    }
                    val keep = textToHold(record, result)
                    if (keep != null) {
                        // The text is in History where the field allows it; the panel also holds it for Copy.
                        hold(record, keep, result.historySaved)
                        showPhase(PanelPhase.NOT_INSERTED)
                    } else if (notInsertedText(result) != null) {
                        // Not this app's panel any more, or the screen went off: status only, History where eligible.
                        showPhase(PanelPhase.NOT_INSERTED)
                    } else if (result.outcome == TakeOutcome.FAILED || result.outcome == TakeOutcome.COMMIT_REJECTED) {
                        log.error("take failed: ${result.outcome}")
                        showPhase(PanelPhase.FAILED)
                    }
                    if (MicrophoneOwner.state().occupied) updateSharedMicrophone()
                }
            }
            showPhase(PanelPhase.OPENING)
            showPreview("")
            latestPreview = ""
            // The panel is visible now; only rotations after this point count for this take.
            rotations.observe()
            rotationsWhenVisible = rotations.changes
            endHoldAndForgetRotation()
            val currentFeedback = RecordingFeedback()
            feedback?.stop()
            feedback = currentFeedback
            setRingLive(true)
            val takeTrace = diagnostics.forTake(record.number, lab.startup, lab.startupTake)
            val unexpectedEnd = AtomicBoolean(false)
            captureEnded = { unexpectedEnd.get() }
            val microphoneCapture = opening.capture(onStarted = {
                takeTrace.captureStarted()
                scheduler.post {
                    if (liveOnPanel(record) && takeActive && !record.stopWait && !record.interrupted) {
                        showPhase(PanelPhase.RECORDING)
                    }
                }
            }, feedback = currentFeedback, onInterrupted = {
                unexpectedEnd.set(true)
                takeTrace.event(RotationEvent.CAPTURE_INTERRUPTED)
                // The capture's own thread: the end is decided on the take's.
                scheduler.post {
                    if (liveOnPanel(record) && takeActive) {
                        // Unexpected capture end is a system interruption: revoke insertion,
                        // release capture, then finish only the audio already accepted.
                        interruptTake()
                    }
                }
            })
            val capture = takeTrace.traced(microphoneCapture)
            val observer = takeTrace.observer
            val startedTake = opening.open(output, capture, scheduler.executor, observer, retentionEligible,
                { recordHistoryNotice(it) }) { history, receipt ->
                identity = receipt
                takeHistory = history
            }
            take = startedTake
            started = true
            render()
            microphoneCapture.setReleaseRetry { startedTake.retryCaptureRelease() }
            log.info("recording started")
        } catch (e: Exception) {
            rotationEvent(RotationEvent.CAPTURE_FAILED)
            rotationEvent(RotationEvent.CAPTURE_NOT_ACQUIRED)
            rotationEvent(RotationEvent.TERMINAL_FAILED)
            lab.startup?.event("recordingFailed", lab.startupTake)
            if (!started) outcomePending = null
            lab.startupTake = null
            feedback?.stop()
            showIdle()
            log.error("start failed")
            showPhase(PanelPhase.FAILED)
            if (MicrophoneOwner.state().occupied) updateSharedMicrophone()
        }
    }

    private fun stopAndInsert() {
        if (!takeActive || stopWait || interrupted) return
        // Stop settles the take on this editor.
        if (editorRetention.excludesSettledTake) excludeTakeFromHistory()
        // Stop grants authority to the input session the visible panel is attached to.
        authority = recordingToken?.takeIf { session.matches(it) && !fieldMismatch }
        authorityMoves = false
        latest?.stopWait = true
        render()
        feedback?.stop()
        setRingLive(false)
        showPhase(PanelPhase.FINISHING)
        take?.stop()
    }

    /**
     * The only path that writes to a field. Commits on the main thread and only
     * while the session that started the take is still the live one.
     */
    private fun commitForSession(record: Take, token: Long?, text: String, allowed: () -> Boolean = { true }, onAttempt: () -> Unit): Boolean {
        scheduler.checkThread("commit")
        rotationEvent(RotationEvent.COMMIT_ATTEMPT)
        val result = commitToInput(session, token, editor.live(), { editor.live() }, allowed,
            { it.textBeforeCursor(1) }, { connection, content -> onAttempt(); writeAndVerify(record, connection, content, token) }, text,
            requireLive = record.heldForRotation, refused = {
                onAttempt()
                log.warn("commit refused: connection inactive")
                rotationEvent(RotationEvent.COMMIT_REFUSED_INACTIVE)
            })
        if (result == null) return false
        return result.also {
            rotationEvent(if (it) RotationEvent.COMMIT_ACCEPTED else RotationEvent.COMMIT_REJECTED)
        }
    }

    /** A write after a rotation hold whose read-back did not show the text at once; the second look is at [CONFIRM_DELAY_MS]. */
    private class PendingConfirm(val connection: EditorConnection, val content: String, val extractedBefore: Int?, val token: Long?)

    /**
     * The one write. A take that went through a rotation hold is read back at once (ADR-010): a write's
     * return value and a non-null read prove nothing, so it only counts as inserted when the text is seen
     * in the field. Not seen yet: the write is reported rejected for now and looked at once more shortly
     * (see [confirmLater]), without blocking the main thread.
     */
    private fun writeAndVerify(record: Take, connection: EditorConnection, content: String, token: Long?): Boolean {
        if (!record.heldForRotation) return connection.write(content)
        val extractedBefore = InsertReadBack.extractedLength(connection)
        if (!connection.write(content)) return false
        val check = InsertReadBack.look(connection, content, extractedBefore)
        if (check != InsertCheck.NONE) { traceVerified(true, check); return true }
        pendingConfirm = PendingConfirm(connection, content, extractedBefore, token)
        return false
    }

    private fun traceVerified(verified: Boolean, check: InsertCheck) =
        rotationEvent(RotationEvent.COMMIT_VERIFIED, verified, detail = mapOf("verified" to verified, "check" to check.ordinal))

    /**
     * The take ended with a write that was not confirmed yet. Look once more; seen now, it was inserted (hand
     * back, nothing held); otherwise hold the text with a status that admits the uncertainty.
     */
    private fun confirmLater(pending: PendingConfirm, result: TakeResult, record: Take) {
        // The panel is at rest; until the second look it offers no new take.
        record.readBackWait = true
        outcomePending = record
        render()
        val history = takeHistory
        scheduler.postDelayed(CONFIRM_DELAY_MS, Runnable {
            takeHistory = null
            record.readBackWait = false
            if (outcomePending === record) { outcomeIn(record); render() }
            if (destroyed) { diagnostics.takeEvent(RotationEvent.TERMINAL_COMMIT_REJECTED, record.number); return@Runnable }
            val live = editor.live() == pending.connection && session.matches(pending.token)
            val check = if (live) InsertReadBack.look(pending.connection, pending.content, pending.extractedBefore) else InsertCheck.NONE
            traceVerified(check != InsertCheck.NONE, check)
            if (check != InsertCheck.NONE) {
                // The write was seen after all: the receipt the rejected write left is corrected, and the take completed.
                history?.receipt(HistoryDelivery.ACCEPTED)
                diagnostics.takeEvent(RotationEvent.TERMINAL_COMPLETED, record.number)
                if (held == null) host.returnToKeyboard()
                return@Runnable
            }
            diagnostics.takeEvent(RotationEvent.TERMINAL_COMMIT_REJECTED, record.number)
            val keep = textToHold(record, result)
            if (keep != null) hold(record, keep, result.historySaved, unconfirmed = true)
            showPhase(PanelPhase.NOT_INSERTED)
        })
    }

    /** System interruption: revokes authority to insert, then stops capture. */
    private fun interruptTake() {
        val old = take ?: return
        // A system interruption overrides any open or moved choice; the take goes to History.
        endHoldAndForgetRotation()
        MicrophoneOwner.requireExplicitStart()
        revokeAuthority()
        // A system interruption overrides an open choice (paused audio already captured goes to History).
        choice = null
        val record = latest
        if (record == null || record.interrupted) { render(); return }
        record.interrupted = true
        // A panel shown again never resumes or restarts an interrupted take by itself.
        autoStartLatched = true
        session.invalidate()
        latestPreview = ""
        showPreview("")
        feedback?.stop()
        setRingLive(false)
        // Truthful while the microphone is released; then "Unable to insert text" with its marker.
        showPhase(PanelPhase.STOPPING)
        old.interrupt {
            if (record === latest) {
                captureReleasedAfterInterruption()
                // "Unable to insert text"; the microphone stays off until the outcome is in ([awaitingOutcome]).
                showPhase(PanelPhase.NOT_INSERTED)
            }
        }
    }

    /** Changes take state only: the interrupted take's capture ownership has ended; decode and History may continue. */
    private fun captureReleasedAfterInterruption() {
        releaseRetry = false
        take = null
        recordingToken = null
        authority = null
        lab.startupTake = null
    }

    /** Changes take state only: [record]'s outcome is in, so the panel may offer a new take again. */
    private fun outcomeIn(record: Take) {
        if (outcomePending === record) outcomePending = null
    }

    /** The system Back key: true when the take handled it. */
    fun backKey(): Boolean {
        if (held != null) { cancelAndReturn(); return true }
        if (!takeActive) return false
        // Back on an open choice is its safe default; otherwise it opens the choice (an interrupted take has none).
        if (choice != null) chooseSafe() else openChoice()
        return true
    }

    /** Cancel on a take without an open choice: the Stop wait gets the finishing choice, a recording take is paused for its own. */
    private fun openChoice() {
        if (stopWait) openFinishingChoice() else openRecordingChoice()
    }

    /**
     * Cancel while recording pauses the take (#61): the platform recording stops, the microphone
     * stays owned by this take, and the choice opens inline in the panel. No window opens, so the
     * editor sees no focus change and restarts nothing. If capture already ended or cannot pause,
     * this is a system interruption (History).
     */
    private fun openRecordingChoice() {
        if (choice != null || !takeActive || stopWait || interrupted) return
        if (!pauseForChoice()) { interruptTake(); return }
        setRingLive(false)
        showPhase(choicePhase(PanelChoice.RECORDING))
    }

    /** Changes take state only: pauses the capture and opens the recording choice; false when the capture already ended or cannot pause. */
    private fun pauseForChoice(): Boolean {
        if (captureEnded() || take?.pause() != true) return false
        diagnostics.takeEvent(RotationEvent.CAPTURE_PAUSED, latest?.number ?: 0L)
        choice = PanelChoice.RECORDING
        return true
    }

    /** Cancel during the Stop wait holds the result before dispatch while the choice is open. */
    private fun openFinishingChoice() {
        if (choice != null || !takeActive || !stopWait || interrupted) return
        if (!holdForChoice()) return
        showPhase(choicePhase(PanelChoice.FINISHING))
    }

    /** Changes take state only: holds the result for the finishing choice and opens it; false when the result can no longer be held. */
    private fun holdForChoice(): Boolean {
        if (take?.holdDelivery(DeliveryHold.FINISHING_CHOICE) != true) return false
        choice = PanelChoice.FINISHING
        return true
    }

    /** Picks the phase of an open choice, and changes nothing. */
    private fun choicePhase(open: PanelChoice): PanelPhase = when (open) {
        PanelChoice.RECORDING -> PanelPhase.PAUSED
        PanelChoice.FINISHING -> PanelPhase.FINISHING_CHOICE
    }

    /** Picks the phase of the take a choice returned to by its safe action, and changes nothing. */
    private fun continuedPhase(closed: PanelChoice): PanelPhase = when (closed) {
        PanelChoice.RECORDING -> PanelPhase.RECORDING
        PanelChoice.FINISHING -> PanelPhase.FINISHING
    }

    /** Continue recording (resumes the same take) or Keep finishing. Also system Back and the Back arrow. */
    private fun chooseSafe() {
        when (val open = choice) {
            PanelChoice.RECORDING -> {
                if (!resumeFromChoice()) { render(); interruptTake(); return }
                showPhase(continuedPhase(open))
                if (!holding) setRingLive(true)
            }
            PanelChoice.FINISHING -> {
                choice = null
                showPhase(continuedPhase(open))
                // A rotation that still holds the result releases it once the panel has settled.
                take?.releaseDelivery(DeliveryHold.FINISHING_CHOICE)
            }
            null -> Unit
        }
    }

    /** Changes take state only: closes the recording choice and resumes the same capture; false when it cannot resume. */
    private fun resumeFromChoice(): Boolean {
        choice = null
        if (take?.resume() != true) return false
        diagnostics.takeEvent(RotationEvent.CAPTURE_RESUMED, latest?.number ?: 0L)
        return true
    }

    /** Stop and insert: exactly Stop, from the paused take. */
    fun chooseStop() {
        if (choice != PanelChoice.RECORDING) return
        choice = null
        render()
        if (captureEnded()) interruptTake() else stopAndInsert()
    }

    /**
     * Discard take: final at once (#36, owner 2026-11-11). Insertion revoked, saved text removed,
     * capture released; late callbacks cannot bring the take back. The panel stays, idle, and a
     * new take needs the microphone tap: a panel shown again (a rotation) never starts one itself.
     */
    private fun chooseDiscard() {
        if (choice == null) return
        val current = take ?: return
        rotationEvent(RotationEvent.CANCEL_REQUESTED)
        choice = null
        authority = null
        MicrophoneOwner.requireExplicitStart()
        current.cancel()
        log.info("cancelled: by user")
        // The take stays active until its microphone is released, and is not in the Stop wait meanwhile.
        latest?.stopWait = false
        showIdle()
    }

    /** The take's state as far as the panel shows it. */
    private fun panelState() = PanelState(
        phase = phase,
        choice = choice,
        running = takeActive && !interrupted,
        finishing = stopWait,
        held = held?.panel,
        awaitingOutcome = awaitingOutcome,
        preview = previewText,
        privateField = takeRetentionEligible == false,
        banner = saveTake,
        ringLive = ringLive,
    )

    /** Shows the take's state on the current panel; the only way the panel's views change. */
    fun render() {
        host.show(panelFrame(panelState()), feedback)
    }

    private fun setRingLive(live: Boolean) {
        ringLive = live
        render()
    }

    /** The panel's Back arrow. */
    fun cancelAndReturn() {
        if (held != null) {
            // Back never writes to the clipboard: the first Back only warns, the second discards and leaves.
            val state = held
            if (state != null && !state.panel.warned) { state.warn(true); render(); return }
            val token = session.token()
            clearHeld()
            MicrophoneOwner.acceptExplicitStart()
            showIdle()
            if (token != null && session.matches(token)) host.returnToKeyboard()
            return
        }
        if (choice != null) { chooseSafe(); return }
        if (takeActive && !interrupted) { openChoice(); return }
        render()
        // An interrupted take keeps finishing into History; Back only leaves.
        if (takeActive) { host.returnToKeyboard(); return }
        // Idle (no take, or it already ended): Back belongs to the live editor session.
        val token = recordingToken ?: session.token()
        showIdle()
        if (session.matches(token)) host.returnToKeyboard()
    }

    /**
     * The text of a take that was not inserted; the panel holds it for Copy. [from] is the editor's app.
     * [panel] is what the panel shows of it: whether History keeps it, the Back warning, an unconfirmed write.
     */
    private class HeldText(val text: String, val from: String?, var panel: HeldPanel) {
        /** The first Back warns that a second one discards the uncopied text; a rotation or a new input start takes the warning back. */
        fun warn(on: Boolean) { panel = panel.copy(warned = on) }
    }

    /**
     * Every outcome of a take that has text and did not insert it: the commit was rejected (the
     * write returned false, or the connection was dead), failed after decoding before an accepted
     * commit, or the input changed under it. Recognition worked, so these never say "Recognition
     * failed"; only an outcome with no text does.
     */
    private fun notInsertedText(result: TakeResult): String? = when (result.outcome) {
        TakeOutcome.COMMIT_REJECTED, TakeOutcome.INPUT_CHANGED -> result.text
        TakeOutcome.FAILED -> result.text.takeIf { result.commitAccepted != true }
        TakeOutcome.COMPLETED, TakeOutcome.CANCELLED -> null
    }?.takeIf { it.isNotBlank() }

    /**
     * The text this panel may hold for the take: only for the app the take started in and still
     * shown in (never another app's panel), and never after the screen went off during the take.
     */
    private fun textToHold(record: Take, result: TakeResult): String? =
        notInsertedText(result)?.takeIf { !record.locked && editorPackage == record.appPackage }

    /**
     * Changes take state only: holds the text of a take that was not inserted. The preview shows it, Copy
     * and Discard text are offered, and it owns the status line; the caller picks the phase underneath
     * (the take was not inserted).
     */
    private fun hold(record: Take, text: String, historyKeepsIt: Boolean, unconfirmed: Boolean = false) {
        held = HeldText(text, record.appPackage, HeldPanel(inHistory = historyKeepsIt, unconfirmed = unconfirmed))
        showPreview("")
    }

    private fun clearHeld() { held = null }

    /** The panel's filled button: Copy text while text is held, otherwise the open choice's safe action. */
    fun onSafeAction() { if (held != null) copyHeld() else chooseSafe() }

    /** The panel's dashed button: Discard text while text is held, otherwise Discard take. */
    fun onDiscardAction() { if (held != null) discardHeld() else chooseDiscard() }

    /** Copy text: on the clipboard, the held text gone, back to the previous keyboard at once. */
    private fun copyHeld() {
        val state = held ?: return
        host.copy(state.text)
        clearHeld()
        MicrophoneOwner.acceptExplicitStart()
        showIdle()
        host.returnToKeyboard()
    }

    /**
     * Discard text: gone at once and the panel is idle. No explicit start is required any more, so the next
     * panel records by itself, except after an interruption: that latched the automatic start
     * ([autoStartLatched]), and only the microphone press starts the next take.
     */
    private fun discardHeld() {
        clearHeld()
        MicrophoneOwner.acceptExplicitStart()
        log.info("held text discarded")
        showIdle()
    }

    /**
     * The panel at rest: the take's live parts end, then the phase is picked from what is left.
     * [ended] is the take whose outcome this rest brings in.
     */
    private fun showIdle(ended: Take? = null) {
        endLiveParts()
        render()
        if (ended != null) outcomeIn(ended)
        val rest = restPhase()
        // Another capture still owns the microphone: the shared-microphone path says so.
        if (rest == null) updateSharedMicrophone() else showPhase(rest)
        render()
    }

    /** Changes take state only: nothing of the take is live on the panel any more (ring, preview). */
    private fun endLiveParts() {
        latest?.atRest = true
        feedback?.stop()
        feedback = null
        ringLive = false
        previewText = PanelPreview.tail(held?.text ?: "")
        if (!takeActive) releaseRetry = false
    }

    /** Picks the phase of a panel at rest and changes nothing; null while another capture owns the microphone. */
    private fun restPhase(): PanelPhase? = when {
        releaseRetry && takeActive -> PanelPhase.RELEASE_FAILED
        takeActive || outcomePending != null -> PanelPhase.STOPPING
        MicrophoneOwner.state().occupied -> null
        else -> PanelPhase.IDLE
    }

    /** Another capture owns the microphone while no take of this controller is active. */
    private fun updateSharedMicrophone() {
        val owner = MicrophoneOwner.state()
        if (takeActive) return
        if (owner.occupied) {
            noteMicrophoneBusy()
            showPhase(busyPhase(owner))
        } else if (microphoneBusy) showIdle()
    }

    /** Changes take state only: the microphone was seen busy, which also latches the automatic start. */
    private fun noteMicrophoneBusy() {
        microphoneBusy = true
        autoStartLatched = true
    }

    /** Picks the phase while another capture owns the microphone, and changes nothing. */
    private fun busyPhase(owner: MicrophoneOwner.State): PanelPhase =
        if (owner.releaseFailed) PanelPhase.RELEASE_FAILED else PanelPhase.STOPPING

    /** [record] is the newest take and the panel has not gone to rest under it: its capture's callbacks still count. */
    private fun liveOnPanel(record: Take) = record === latest && !record.atRest

    /** History warnings are shown on the History page only, never on the recording panel. */
    private fun recordHistoryNotice(notice: HistoryNotice) {
        host.historyNotice(notice)
    }

    private fun showPhase(next: PanelPhase) {
        phase = next
        render()
    }

    /** The end of the text so far; the view is a few lines high. */
    private fun publishPreview(record: Take, text: String) {
        if (record !== latest || record.interrupted) return
        latestPreview = text
        if (previewAllowed) showPreview(text)
    }

    private fun showPreview(text: String) {
        previewText = PanelPreview.tail(held?.text ?: text)
        render()
    }

    private var permissionAsked = false

    /** Ask once per service lifetime; after "don't ask again" we only show the message. */
    private fun requestMicPermission() {
        showPhase(PanelPhase.NO_PERMISSION)
        if (permissionAsked) return
        permissionAsked = true
        host.askPermission()
    }
}
