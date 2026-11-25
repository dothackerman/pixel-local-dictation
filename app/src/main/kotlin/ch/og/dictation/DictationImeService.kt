package ch.og.dictation

import ch.og.dictation.audio.MicrophoneCapture
import ch.og.dictation.audio.MicrophoneOwner
import ch.og.dictation.audio.Recorder
import ch.og.dictation.audio.RecordingFeedback
import ch.og.dictation.diagnostics.BenchmarkStore
import ch.og.dictation.diagnostics.RotationEvent
import ch.og.dictation.diagnostics.RotationTrace
import ch.og.dictation.diagnostics.StartupTrace
import ch.og.dictation.history.HistoryDelivery
import ch.og.dictation.history.HistoryIo
import ch.og.dictation.history.HistoryNotice
import ch.og.dictation.history.HistoryTakeOutput
import ch.og.dictation.history.copyTranscript
import ch.og.dictation.history.historyNotices
import ch.og.dictation.ime.InputSession
import ch.og.dictation.ime.PermissionActivity
import ch.og.dictation.ime.commitToInput
import ch.og.dictation.ime.editorPort
import ch.og.dictation.ime.fieldIdentityOf
import ch.og.dictation.ime.retentionOf
import ch.og.dictation.panel.PanelChoice
import ch.og.dictation.panel.HeldPanel
import ch.og.dictation.panel.PanelFrame
import ch.og.dictation.panel.PanelPhase
import ch.og.dictation.panel.PanelState
import ch.og.dictation.panel.panelFrame
import ch.og.dictation.panel.PanelPreview
import ch.og.dictation.panel.renderPanel
import ch.og.dictation.recognition.SAMPLE_RATE
import ch.og.dictation.take.DeliveryHold
import ch.og.dictation.take.EditorConnection
import ch.og.dictation.take.EditorDecision
import ch.og.dictation.take.EditorEvent
import ch.og.dictation.take.EditorEventPolicy
import ch.og.dictation.take.EditorRetention
import ch.og.dictation.take.FieldIdentity
import ch.og.dictation.take.InsertCheck
import ch.og.dictation.take.InsertReadBack
import ch.og.dictation.take.ROTATION_HOLD_GAP_MS
import ch.og.dictation.take.ROTATION_SETTLE_MS
import ch.og.dictation.take.TakeDiagnostics
import ch.og.dictation.take.TakeFacts
import ch.og.dictation.take.TakeTraceFacts
import ch.og.dictation.take.TakeOutcome
import ch.og.dictation.take.TakeOutput
import ch.og.dictation.take.TakeResult
import ch.og.dictation.take.TakeSession
import android.Manifest
import android.view.KeyEvent
import android.view.Display
import android.hardware.display.DisplayManager
import android.view.WindowManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.res.Configuration
import java.io.File
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ImageButton
import android.widget.TextView
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/**
 * A voice-only input method FUTO hands off to. Recording starts when the panel opens;
 * speech is decoded on the phone while recording continues and shown in the panel only.
 * Stop commits the whole text once into the field that started the take and returns to
 * the previous keyboard. Nothing is written to a field before Stop.
 */
class DictationImeService : InputMethodService() {
    companion object {
        /** The second read-back after a write that was not seen in the field at once (ADR-010). */
        internal const val CONFIRM_DELAY_MS = 100L
    }
    private val tag = "DictationIme"
    private var feedback: RecordingFeedback? = null
    /** The ring follows [feedback]'s live loudness; otherwise it rests. */
    private var ringLive = false
    /** The frame the current panel shows; null for a panel not rendered yet. */
    private var shownFrame: PanelFrame? = null
    private var captureEnded: () -> Boolean = { false }
    private var finishing = false
    /** What the panel says and what its microphone does; stored at each change, never derived. */
    private var phase = PanelPhase.OPENING
    private var previewText = ""
    private var editorRetentionEligible = false
    /** Kept for panel recreation throughout this take; never recomputed from later focus. */
    private var takeRetentionEligible: Boolean? = null
    /** Only saving audio needs a banner; experiment receipts remain out of the normal panel. */
    private var saveTake = false
    private val session = InputSession()
    /** The editor port: the only way take code writes to or reads from the editor. */
    private val editor = editorPort { currentInputConnection }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { mainHandler.post(it) }
    /** Set while a hand-back to the keyboard is pending, so the panel does not start a new take. */
    private var returning = false
    /** Input session the current take's panel is attached to. */
    private var recordingToken: Long? = null
    /** Authority to insert: granted by Stop to the panel's session; text goes there or nowhere. */
    private var authority: Long? = null
    private var take: TakeSession? = null
    private var takeGeneration = 0L
    private var interruptedView = false
    private var releaseRetry = false
    private var previewAllowed = false
    private var destroyed = false
    private val microphoneOwnerListener: (MicrophoneOwner.State) -> Unit = {
        mainHandler.post { if (!destroyed) updateSharedMicrophone() }
    }
    private var inputViewVisible = false
    /** Take diagnostics: every traced event gets the take's facts as arguments (see [traceFacts]). */
    private val diagnostics = TakeDiagnostics()
    /** Retention class of the editor that last started input. */
    private var editorRetention = EditorRetention.NO_EDITOR
    private var rotationView = 0L
    private var rotationTake = 0L
    /** Last observed display rotation and the number of rotation changes this service saw. */
    private var displayRotation: Int? = null
    private var rotationChanges = 0L
    /** Orientation of the last configuration this service saw; a change counts as a rotation. */
    private var configurationOrientation: Int? = null
    private var rotationsAtConfiguration = 0L
    /**
     * rotationChanges when the panel was last visible (settled, see ROTATION_SETTLE_MS); a higher
     * count identifies a rotation since.
     */
    private var rotationsWhenVisible = 0L
    /** Rotation hold: authority revoked, preview hidden, capture continues until the panel returns. */
    private var holding = false
    /** From the first hold of a rotation until the panel has settled; one gap for all of it. */
    private var rotationEpisode = false
    /** Stop granted authority and a rotation hold revoked it: it moves to the session the panel returns to. */
    private var authorityMoves = false
    /** This take went through a rotation hold (also a self-requested panel) since its capture began. */
    private var takeHeldForRotation = false
    /** The field the take started in; after a rotation hold only this field may regain insert authority (ADR-010). */
    private var takeField: FieldIdentity? = null
    private var takeConnection: EditorConnection? = null
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
    /** The read-back of a write that could not be confirmed at once; the second look is pending. */
    /** Generation of the take whose outcome has not been delivered yet: from start until finished() (or its late read-back look) ends. */
    private var outcomePending: Long? = null
    private var pendingConfirm: PendingConfirm? = null
    /** The History side of the current take: a late pass of the read-back corrects its delivery receipt. */
    private var takeHistory: HistoryTakeOutput? = null
    /**
     * The finished text of a take that could not be inserted (plan B): held in memory only while
     * the Unable to insert text state shows; never written or logged. Cleared by Discard text, Back
     * after a copy, screen off and a different app's editor; kept across a rotation, an input
     * restart and a tap on the field. While held, the status is the held state's own (see showStatus).
     */
    private var held: HeldText? = null
    private var editorPackage: String? = null
    /** The app whose editor the take started in; its text is only ever held and shown for that app. */
    private var takePackage: String? = null
    /** The screen went off while this take was active: a lock never keeps its text. */
    private var takeLocked = false
    /** An input is started (between onStartInput and onFinishInput); a panel is only requested then. */
    private var inputStarted = false
    /**
     * The open Cancel choice, part of the take's state: every panel (also one recreated for a
     * rotation) renders it inline. RECORDING: the take is paused. FINISHING: delivery is held.
     */
    private var choice: PanelChoice? = null
    private val gapExpired = Runnable {
        if (!rotationEpisode) return@Runnable
        if (holding) { Log.i(tag, "rotation hold: panel did not return"); rotationEvent(RotationEvent.HOLD_GAP_EXPIRED); interruptTake() }
        else rotationSettled()
    }
    /** Android may silence a client whose window is hidden; a silenced hold cannot keep listening. */
    private val recordingCallback = object : android.media.AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<android.media.AudioRecordingConfiguration>?) {
            if (holding && !finishing && configs.orEmpty().any { it.isClientSilenced }) silencedDuringHold()
        }
    }
    private val panelSettled = Runnable {
        if (!holding && inputViewVisible && session.matches(recordingToken)) rotationSettled()
    }
    /** Newest preview of the current take, shown again when the panel returns from a hold. */
    private var latestPreview = ""
    private var startup: StartupTrace? = null
    private var startupTake: String? = null
    private var panel: View? = null
    /** Layout configuration the current panel was inflated for. */
    private var panelConfiguration: Configuration? = null
    // Observation is separate from take completion, but ends when the input view is hidden.
    private var geometryTake: String? = null
    private var geometryUntilNs = 0L
    private var geometryRows = 0
    private val lastGeometry = mutableMapOf<String, String>()
    private var contentTopInsets: Int? = null
    private var visibleTopInsets: Int? = null
    private var touchableInsets: Int? = null

    /** A take exists from the start of recording until its text is committed or dropped. */
    private val takeActive: Boolean get() = take?.active == true

    /** A locked phone must not keep listening. */
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            rotationEvent(RotationEvent.SCREEN_OFF)
            // Also after Stop and during the read-back wait, until finished(): its text is refused, after unlock too.
            if (outcomePending != null) takeLocked = true
            if (held != null) { clearHeld(); showIdle() }
            previewAllowed = false
            showPreview("")
            editorEvent(EditorEvent.SCREEN_OFF)
        }
    }

    override fun onCreate() {
        val createdNs = System.nanoTime()
        val createdUptimeMs = SystemClock.uptimeMillis()
        super.onCreate()
        val superReturnedNs = System.nanoTime()
        val superReturnedUptimeMs = SystemClock.uptimeMillis()
        if (resources.getBoolean(R.bool.benchmark_receipts)) {
            if (RotationTrace.probeActive()) {
                diagnostics.attach(RotationTrace(File(wiring.storage.labFolder, "rotation-probe.jsonl")))
                lastConfiguration = Configuration(resources.configuration)
                rotationEvent(RotationEvent.SERVICE_CREATE)
                listenToDisplay()
            }
            startup = StartupTrace(wiring.storage.labFolder).also {
                it.event("serviceCreate", ns = createdNs, uptimeMs = createdUptimeMs)
                it.event("serviceCreateSuperReturned", ns = superReturnedNs, uptimeMs = superReturnedUptimeMs)
            }
            observeGeometry()
        }
        listenToDisplay()
        observeRotation()
        configurationOrientation = resources.configuration.orientation
        getSystemService(android.media.AudioManager::class.java)?.registerAudioRecordingCallback(recordingCallback, mainHandler)
        registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), Context.RECEIVER_NOT_EXPORTED)
        MicrophoneOwner.observe(microphoneOwnerListener)
        val engine = wiring.engine
        val ready = engine.warmUp()
        startup?.event("warmUpReturned")
        ready.whenComplete { _, failure ->
            startup?.event(if (failure == null) "modelReady" else "modelFailed",
                ns = engine.modelReadyNs ?: System.nanoTime())
        }
        startup?.event("serviceCreateEnd")
    }

    override fun onCreateInputView(): View {
        rotationView++
        held?.warn(false) // A rotation cancels the Back warning.
        rotationEvent(RotationEvent.VIEW_CREATE)
        startup?.event("inputViewCreateBegin")
        // The panel uses the one application theme (#36); the service's own theme is the platform's.
        val v = LayoutInflater.from(ContextThemeWrapper(this, R.style.Theme_Dictation)).inflate(R.layout.ime, null)
        startup?.event("inputViewInflated")
        panel = v
        panelConfiguration = Configuration(resources.configuration)
        observeGeometry(startupTake)
        if (resources.getBoolean(R.bool.benchmark_receipts)) {
            var drawn = false
            v.viewTreeObserver.addOnDrawListener {
                if (!drawn && diagnostics.tracing) {
                    drawn = true
                    // A draw listener must not change the tree; record after this frame.
                    mainHandler.post { rotationEvent(RotationEvent.PANEL_DRAWN) }
                }
            }
        }
        if (startup != null) {
            v.viewTreeObserver.addOnPreDrawListener {
                recordGeometry("preDraw")
                true
            }
            var drawnTake: String? = null
            v.viewTreeObserver.addOnDrawListener {
                val id = startupTake
                if (id != null && id != drawnTake) {
                    drawnTake = id
                    // Render submission, NOT proof of the physical display time or microphone-key time.
                    startup?.event("panelDraw", id)
                }
            }
        }
        shownFrame = null
        v.findViewById<ImageButton>(R.id.microphone).apply {
            setOnClickListener {
                if (MicrophoneOwner.state().releaseFailed) {
                    releaseRetry = false
                    showPhase(PanelPhase.STOPPING)
                    MicrophoneOwner.retryRelease()
                } else if (takeActive) stopAndInsert() else startRecording()
            }
        }
        v.findViewById<ImageButton>(R.id.back).setOnClickListener { cancelAndReturn() }
        v.findViewById<View>(R.id.choice_safe).setOnClickListener { onSafeAction() }
        v.findViewById<View>(R.id.choice_stop).setOnClickListener { chooseStop() }
        v.findViewById<View>(R.id.choice_discard).setOnClickListener { onDiscardAction() }
        render()
        startup?.event("inputViewCreateEnd")
        return v
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        attachRotationTrace()
        rotationEvent(RotationEvent.INPUT_START, restarting)
        super.onStartInput(attribute, restarting)
        observeGeometry()
        Log.i(tag, "input start: restarting=$restarting take=$takeActive")
        editorRetention = retentionOf(attribute)
        editorRetentionEligible = editorRetention.eligible
        editorPackage = attribute?.packageName
        editorIdentity = fieldIdentityOf(attribute)
        inputStarted = true
        held?.warn(false)
        if (held != null && editorPackage != held?.from) { clearHeld(); showIdle() } // A different app's editor.
        previewAllowed = false
        showPreview("")
        // Any input start ends the previous session (InputSession.start).
        session.start()
        editorEvent(EditorEvent.INPUT_START)
        rotationEvent(RotationEvent.INPUT_START_DONE, restarting, retention = editorRetention)
    }

    private fun attachRotationTrace() {
        if (diagnostics.attachable && resources.getBoolean(R.bool.benchmark_receipts) &&
            RotationTrace.probeActive()) {
            diagnostics.attach(RotationTrace(File(wiring.storage.labFolder, "rotation-probe.jsonl")))
            rotationEvent(RotationEvent.SERVICE_ATTACH)
            listenToDisplay()
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        attachRotationTrace()
        rotationEvent(RotationEvent.INPUT_VIEW_START, restarting)
        super.onStartInputView(info, restarting)
        inputViewVisible = true
        session.resume()
        editorEvent(EditorEvent.INPUT_VIEW_START)
        if (returning || takeActive) { rotationEvent(RotationEvent.INPUT_VIEW_START_DONE, restarting); return }
        if (MicrophoneOwner.state().occupied) {
            interruptedView = true
            updateSharedMicrophone()
            rotationEvent(RotationEvent.INPUT_VIEW_START_DONE, restarting)
            return
        }
        if (held != null) { // A panel shown again never starts a take over held text.
            showIdle()
            rotationEvent(RotationEvent.INPUT_VIEW_START_DONE, restarting)
            return
        }
        if (MicrophoneOwner.state().explicitStartRequired) {
            interruptedView = true
            showIdle()
            rotationEvent(RotationEvent.INPUT_VIEW_START_DONE, restarting)
            return
        }
        if (interruptedView) {
            rotationEvent(RotationEvent.INPUT_VIEW_START_DONE, restarting)
            return
        }
        takeRetentionEligible = null
        startupTake = startup?.beginTake(wiring.engine.modelReadyNs != null)
        observeGeometry(startupTake)
        showIdle()
        if (hasMicPermission()) startRecording() else requestMicPermission()
        rotationEvent(RotationEvent.INPUT_VIEW_START_DONE, restarting)
    }

    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        // Observe the framework result; never change the editor's exported insets.
        if (startup != null) {
            contentTopInsets = outInsets.contentTopInsets
            visibleTopInsets = outInsets.visibleTopInsets
            touchableInsets = outInsets.touchableInsets
            recordGeometry("computeInsets")
        }
    }

    private fun observeGeometry(takeId: String? = null) {
        if (startup == null) return
        geometryTake = takeId
        geometryUntilNs = System.nanoTime() + 2_000_000_000L
        geometryRows = 0
        lastGeometry.clear()
        contentTopInsets = null
        visibleTopInsets = null
        touchableInsets = null
    }

    private fun recordGeometry(phase: String) {
        val trace = startup ?: return
        val id = geometryTake
        val ns = System.nanoTime()
        if (ns > geometryUntilNs || geometryRows >= 256) return
        val view = panel
        val decor = window?.window?.decorView ?: return
        val inWindow = IntArray(2)
        val onScreen = IntArray(2)
        val decorOnScreen = IntArray(2)
        view?.getLocationInWindow(inWindow)
        view?.getLocationOnScreen(onScreen)
        decor.getLocationOnScreen(decorOnScreen)
        val geometry = JSONObject()
            .put("decorHeightPx", decor.height)
            .put("decorTopScreenPx", decorOnScreen[1])
            .put("panelMeasuredHeightPx", view?.measuredHeight ?: JSONObject.NULL)
            .put("panelHeightPx", view?.height ?: JSONObject.NULL)
            .put("panelWidthPx", view?.width ?: JSONObject.NULL)
            .put("panelTopWindowPx", if (view != null) inWindow[1] else JSONObject.NULL)
            .put("panelTopScreenPx", if (view != null) onScreen[1] else JSONObject.NULL)
            .put("bannerVisible", view != null && shownFrame?.banner == true)
            .put("contentTopInsetsPx", contentTopInsets ?: JSONObject.NULL)
            .put("visibleTopInsetsPx", visibleTopInsets ?: JSONObject.NULL)
            .put("touchableInsets", touchableInsets ?: JSONObject.NULL)
        val key = geometry.toString()
        if (lastGeometry[phase] == key) return
        lastGeometry[phase] = key
        geometryRows++
        trace.geometry(phase, id, ns, geometry)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        rotationEvent(RotationEvent.INPUT_VIEW_FINISH, finishingInput)
        inputViewVisible = false
        setRingLive(false)
        geometryTake = null
        geometryUntilNs = 0L
        // Focus moved or keyboard hidden: never insert late, never keep the mic.
        session.invalidate()
        previewAllowed = false
        showPreview("")
        Log.i(tag, "input view finished: finishingInput=$finishingInput take=$takeActive")
        editorEvent(EditorEvent.INPUT_VIEW_FINISH)
        super.onFinishInputView(finishingInput)
        rotationEvent(RotationEvent.INPUT_VIEW_FINISH_DONE, finishingInput)
    }

    override fun onFinishInput() {
        rotationEvent(RotationEvent.INPUT_FINISH)
        inputStarted = false
        geometryTake = null
        geometryUntilNs = 0L
        session.invalidate()
        previewAllowed = false
        showPreview("")
        editorEvent(EditorEvent.INPUT_FINISH)
        super.onFinishInput()
        rotationEvent(RotationEvent.INPUT_FINISH_DONE)
    }

    /**
     * method.xml declares orientation and screen-size changes as handled; rebuild only our panel
     * for the new layout, the take lives in this service and the panel renders its state. Android
     * may still restart input inside super.onConfigurationChanged (measured on the Pixel on every
     * rotation, ADR-010); the rotation counted here identifies that restart for the rotation hold.
     * Never starts, cancels or interrupts a take by itself.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        observeRotation()
        // Own configuration counts too, unless the display already reported a rotation since the last one.
        if (rotationChanges == rotationsAtConfiguration && configurationOrientation != null &&
            configurationOrientation != newConfig.orientation) rotationChanges++
        configurationOrientation = newConfig.orientation
        rotationsAtConfiguration = rotationChanges
        if (diagnostics.tracing) {
            val diff = lastConfiguration?.diff(newConfig)
            val handled = runCatching {
                getSystemService(InputMethodManager::class.java)?.inputMethodList
                    ?.firstOrNull { it.packageName == packageName }?.configChanges
            }.getOrNull()
            rotationEvent(RotationEvent.CONFIGURATION, configDiff = diff, handledConfig = handled)
        }
        lastConfiguration = Configuration(newConfig)
        super.onConfigurationChanged(newConfig)
        val layoutChanged = panelConfiguration?.let {
            it.orientation != newConfig.orientation || it.screenWidthDp != newConfig.screenWidthDp ||
                it.screenHeightDp != newConfig.screenHeightDp
        } ?: false
        if (layoutChanged && panel != null) setInputView(onCreateInputView())
        // An editor that keeps focus sends no event; the visible panel settles the rotation.
        settleAfterRotation()
        rotationEvent(RotationEvent.CONFIGURATION_DONE)
    }

    /** Extract mode would hide the editor and our panel; landscape uses the compact panel. */
    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onWindowShown() {
        rotationEvent(RotationEvent.WINDOW_SHOWN)
        super.onWindowShown()
    }

    override fun onWindowHidden() {
        rotationEvent(RotationEvent.WINDOW_HIDDEN)
        previewAllowed = false
        showPreview("")
        session.invalidate()
        editorEvent(EditorEvent.WINDOW_HIDDEN)
        super.onWindowHidden()
    }

    /** The six editor-event callbacks decide the take's fate here and nowhere else. */
    private fun editorEvent(event: EditorEvent): EditorDecision {
        // Identification uses only what this app observed, at or before this event.
        observeRotation()
        val decided = EditorEventPolicy.decide(event, TakeFacts(
            active = takeActive, returning = returning, sameSession = session.matches(recordingToken),
            capturing = takeActive && !finishing && !interruptedView,
            finishing = takeActive && finishing && !interruptedView,
            rotation = rotationIdentified(event)))
        // A rotation never moves a take to another app: an input start from a different package
        // ends the hold, whatever the rotation window says (no authority, no panel there).
        val decision = if (decided == EditorDecision.REVOKE_AUTHORITY && event == EditorEvent.INPUT_START && anotherApp())
            EditorDecision.INTERRUPT else decided
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
        if (rotationChanges > rotationsWhenVisible || retargetSettle) return true
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
        val same = now?.isSameFieldAs(takeField) == true
        rotationEvent(RotationEvent.FIELD_JUDGED, same, detail = mapOf(
            "sameField" to same, "typeNull" to (now?.typeNull ?: true), "selValid" to (now?.selValid ?: false),
            "connectionChanged" to (editor.live() != takeConnection)))
        if (!same && !fieldMismatch) {
            fieldMismatch = true
            Log.i(tag, "authority withheld: input is not the take's field")
        } else if (same && fieldMismatch && !mismatchFinal) {
            fieldMismatch = false
            retargetSettle = true
            // A Stop pressed meanwhile gets its authority back with the panel on this field's session.
            if (finishing) authorityMoves = true
            Log.i(tag, "authority returned: the take's field, inside the hold")
        }
    }

    private fun anotherApp(): Boolean {
        val mine = takePackage
        val current = editorPackage
        return mine != null && current != null && current != mine
    }

    /** Enters (or stays in) the rotation hold. The gap starts once and is never extended. */
    private fun enterRotationHold(event: EditorEvent) {
        mainHandler.removeCallbacks(panelSettled)
        takeHeldForRotation = true
        if (!holding) {
            holding = true
            rotationEvent(RotationEvent.HOLD_ENTERED)
            Log.i(tag, "rotation hold")
            setRingLive(false)
        }
        // An open choice is take state: the returned panel renders it again; nothing to move.
        // Stop wait: hold the result before dispatch; an accepted commit is never undone.
        if (finishing) take?.holdDelivery(DeliveryHold.ROTATION)
        if (!rotationEpisode) {
            rotationEpisode = true
            mainHandler.postDelayed(gapExpired, ROTATION_HOLD_GAP_MS)
        }
        if (finishing) { requestOwnPanelForNewEditor(event); return } // The microphone is already off.
        val silenced = runCatching {
            getSystemService(android.media.AudioManager::class.java)?.activeRecordingConfigurations.orEmpty().any { it.isClientSilenced }
        }.getOrDefault(false)
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
        mainHandler.removeCallbacks(panelRequest)
        mainHandler.post(panelRequest)
    }

    private val panelRequest = Runnable {
        if (holding && inputStarted && !inputViewVisible && !destroyed) wiring.requestPanel(this)
    }

    /** The panel settled after a rotation: the rotation no longer holds the result (the finishing choice still may). */
    private fun restoreAfterRotation() {
        if (!takeActive || interruptedView) return
        take?.releaseDelivery(DeliveryHold.ROTATION)
    }

    private fun silencedDuringHold() {
        Log.i(tag, "rotation hold: capture silenced")
        rotationEvent(RotationEvent.HOLD_SILENCED)
        interruptTake()
    }

    /** Ends hold, gap and settling without deciding anything about the take. */
    private fun endHold() {
        holding = false
        rotationEpisode = false
        mainHandler.removeCallbacks(gapExpired)
        mainHandler.removeCallbacks(panelSettled)
    }

    /** The panel stayed visible after the rotation: later editor events are judged without it. */
    private fun rotationSettled() {
        if (rotationEpisode) rotationEvent(RotationEvent.HOLD_SETTLED)
        endHold()
        rotationsWhenVisible = rotationChanges
        retargetSettle = false
        if (fieldMismatch) mismatchFinal = true
        authorityMoves = false
        if (takeActive && !interruptedView && editorRetention.excludesSettledTake) excludeTakeFromHistory()
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
        if (authorityMoves && finishing && !interruptedView && !fieldMismatch) authority = recordingToken
        if (editorRetention.positivelyPrivate) excludeTakeFromHistory()
        if (holding) {
            holding = false
            if (!interruptedView) {
                previewAllowed = true
                showPreview(latestPreview)
                if (!finishing && choice == null) setRingLive(true)
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

    private fun settleAfterRotation() {
        if (!takeActive || holding || !inputViewVisible || !rotationIdentified()) return
        mainHandler.removeCallbacks(panelSettled)
        mainHandler.postDelayed(panelSettled, ROTATION_SETTLE_MS)
    }

    /** No text may be written for this take until Stop grants authority again; capture is untouched. */
    private fun revokeAuthority() {
        authority = null
        previewAllowed = false
        showPreview("")
    }

    private fun rotationEvent(event: RotationEvent, flag: Boolean? = null, configDiff: Int? = null, handledConfig: Int? = null,
                              retention: EditorRetention? = null, detail: Map<String, Any>? = null) {
        if (!diagnostics.tracing) return
        diagnostics.event(event, traceFacts(), flag, configDiff, handledConfig, retention, detail)
    }

    /** The take's facts for a traced event, read now. Reads the display rotation, as every traced event did. */
    private fun traceFacts(): TakeTraceFacts {
        val rotation = observeRotation()
        return TakeTraceFacts(active = takeActive, liveSession = recordingToken != null && session.matches(recordingToken),
            view = rotationView.takeIf { it > 0 }, take = rotationTake.takeIf { it > 0 }, rotation = rotation, rotations = rotationChanges)
    }

    /** Trace only: the configuration this service last saw, to report what a change differs in. */
    private var lastConfiguration: Configuration? = null

    /**
     * Reads the default display's rotation and counts every change this service observes.
     * Comparing with the take's start rotation would miss rotating there and back.
     */
    private fun observeRotation(): Int? {
        val current = runCatching {
            getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation
        }.getOrNull() ?: return displayRotation
        val last = displayRotation
        if (last != null && last != current) rotationChanges++
        displayRotation = current
        return current
    }

    /** Counts display rotations as soon as Android reports them, before the editor reacts. */
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            // The value can still be the old one here (#56 trace); configuration and editor events read it again.
            observeRotation()
            settleAfterRotation()
            rotationEvent(RotationEvent.DISPLAY_CHANGED)
        }
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
    }
    private var displayListening = false

    private fun listenToDisplay() {
        if (displayListening) return
        displayListening = true
        getSystemService(DisplayManager::class.java)?.registerDisplayListener(displayListener, mainHandler)
    }

    override fun onDestroy() {
        rotationEvent(RotationEvent.SERVICE_DESTROY)
        if (displayListening) getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(displayListener)
        destroyed = true
        outcomePending = null
        endHold()
        getSystemService(android.media.AudioManager::class.java)?.unregisterAudioRecordingCallback(recordingCallback)
        MicrophoneOwner.remove(microphoneOwnerListener)
        unregisterReceiver(screenOff)
        session.invalidate()
        if (takeActive) interruptTake()
        if (releaseRetry) MicrophoneOwner.retryRelease()
        feedback?.stop()
        super.onDestroy()
    }

    private fun startRecording() {
        if (takeActive || held != null || outcomePending != null) return // Held text is never dropped by starting a take.
        if (MicrophoneOwner.state().occupied) {
            interruptedView = true
            updateSharedMicrophone()
            return
        }
        MicrophoneOwner.acceptExplicitStart()
        interruptedView = false
        choice = null
        render()
        previewAllowed = true
        if (!hasMicPermission()) {
            showPhase(PanelPhase.NO_PERMISSION)
            return
        }
        // Latch current policy for each new take, including explicit restarts.
        // Read once per take: a take keeps the Wiring it started with (its History root above all).
        val wiring = this.wiring
        val liveCheck = wiring.engine.liveReceipt() != null
        saveTake = !RotationTrace.probeActive() && BenchmarkStore.recordingEnabled(BenchmarkStore.isEnabled(this), liveCheck) && editorRetentionEligible
        render()
        if (startupTake == null) startupTake = startup?.beginTake(wiring.engine.modelReadyNs != null)
        rotationTake++
        rotationEvent(RotationEvent.CAPTURE_START)
        var started = false // This start produced a take that will deliver finished().
        try {
            val liveTakeId = startupTake
            val generation = ++takeGeneration
            outcomePending = generation
            val currentRotationTake = rotationTake
            recordingToken = session.token()
            takeHeldForRotation = false
            takeField = fieldIdentityOf(currentInputEditorInfo)
            takeConnection = editor.live()
            takePackage = editorPackage
            takeLocked = false
            authority = null
            fun authorityForThisTake() = authority.takeIf { generation == takeGeneration }
            val save = saveTake
            val retentionEligible = editorRetentionEligible
            takeRetentionEligible = retentionEligible
            render()
            var identity: () -> org.json.JSONObject? = { null }
            var attempted = false
            val output = object : TakeOutput {
                override fun preview(text: String) = publishPreview(generation, text)
                override fun captureReleaseFailed() {
                    if (generation != takeGeneration) return
                    releaseRetry = true
                    showPhase(PanelPhase.RELEASE_FAILED)
                }
                override fun commit(text: String) = commitForSession(authorityForThisTake(), text) { attempted = true }
                override fun commit(text: String, allowed: () -> Boolean) = commitForSession(authorityForThisTake(), text, allowed) { attempted = true }
                override fun commitAttempted() = attempted
                override fun returnToKeyboard(): Boolean {
                    if (!session.matches(authorityForThisTake())) return false
                    // Switching the IME can synchronously invalidate input. The result has
                    // already been submitted; do not turn that hand-back into cancellation.
                    returning = true
                    return try { this@DictationImeService.returnToKeyboard() }
                    finally { mainHandler.post { returning = false } }
                }
                override fun finished(result: TakeResult, pcm: ByteArray) {
                    // Synchronous on the main thread; only a late read-back look marks it pending again.
                    if (outcomePending == generation) outcomePending = null
                    // A write that is still being read back reports its terminal event at the second look.
                    val lateLook = pendingConfirm != null && !result.interrupted && result.outcome == TakeOutcome.COMMIT_REJECTED
                    if (!lateLook) diagnostics.terminal(result.outcome, currentRotationTake)
                    identity()?.let { startup?.liveResult(liveTakeId, it, result) }
                    if (!result.interrupted) saveBenchmarkTake(save && !RotationTrace.probeActive(), pcm, result)
                    if (generation != takeGeneration) return
                    // The take's History output is dropped with the take; only a write awaiting its second look keeps it.
                    if (pendingConfirm == null || result.interrupted || result.outcome != TakeOutcome.COMMIT_REJECTED) takeHistory = null
                    recordingToken = null
                    authority = null
                    startupTake = null
                    latestPreview = ""
                    endHoldAndForgetRotation()
                    if (result.interrupted) {
                        pendingConfirm = null
                        take = null
                        finishing = false
                        choice = null
                        showPreview("")
                        // Whether History kept it shows on the History page; the private-field line says when it cannot.
                        val keep = textToHold(result)
                        if (keep != null) holdText(keep, result.historySaved) else {
                            showPhase(PanelPhase.NOT_INSERTED)
                        }
                        return
                    }
                    choice = null
                    showIdle()
                    val pending = pendingConfirm
                    pendingConfirm = null
                    if (pending != null && result.outcome == TakeOutcome.COMMIT_REJECTED) {
                        // The write may still show up: the held text, or the hand-back, waits for the second look.
                        confirmLater(pending, result, currentRotationTake, generation)
                        return
                    }
                    val keep = textToHold(result)
                    if (keep != null) {
                        // The text is in History where the field allows it; the panel also holds it for Copy.
                        holdText(keep, result.historySaved)
                    } else if (notInsertedText(result) != null) {
                        // Not this app's panel any more, or the screen went off: status only, History where eligible.
                        showPhase(PanelPhase.NOT_INSERTED)
                    } else if (result.outcome == TakeOutcome.FAILED || result.outcome == TakeOutcome.COMMIT_REJECTED) {
                        Log.e(tag, "take failed: ${result.outcome}")
                        showPhase(PanelPhase.FAILED)
                    }
                    if (MicrophoneOwner.state().occupied) updateSharedMicrophone()
                }
            }
            showPhase(PanelPhase.OPENING)
            showPreview("")
            latestPreview = ""
            // The panel is visible now; only rotations after this point count for this take.
            observeRotation()
            rotationsWhenVisible = rotationChanges
            endHoldAndForgetRotation()
            finishing = false
            val currentFeedback = RecordingFeedback()
            feedback?.stop()
            feedback = currentFeedback
            setRingLive(true)
            val takeTrace = diagnostics.forTake(rotationTake, startup, startupTake)
            val unexpectedEnd = AtomicBoolean(false)
            captureEnded = { unexpectedEnd.get() }
            val microphoneCapture = MicrophoneCapture(onStarted = {
                takeTrace.captureStarted()
                mainHandler.post {
                    if (feedback === currentFeedback && takeActive && !finishing) {
                        showPhase(PanelPhase.RECORDING)
                    }
                }
            }, feedback = currentFeedback, onInterrupted = {
                unexpectedEnd.set(true)
                takeTrace.event(RotationEvent.CAPTURE_INTERRUPTED)
                wiring.postCaptureEnd(mainHandler, Runnable {
                    if (feedback === currentFeedback && takeActive) {
                        // Unexpected capture end is a system interruption: revoke insertion,
                        // release capture, then finish only the audio already accepted.
                        interruptTake()
                    }
                })
            }, recorder = wiring.recorderFactory())
            val capture = takeTrace.traced(microphoneCapture)
            val observer = takeTrace.observer
            val historyRoot = wiring.storage.historyRoot
            val startedTake = wiring.engine.openTake { runtime, receipt ->
                identity = receipt
                val history = HistoryTakeOutput(output,
                    { HistoryIo.store(historyRoot) }, HistoryIo.executor,
                    { recordHistoryNotice(HistoryNotice.SAVE) }, delivery = mainExecutor,
                    onDeleteFailure = { recordHistoryNotice(HistoryNotice.DELETE) },
                    onReceiptFailure = { recordHistoryNotice(HistoryNotice.RECEIPT) },
                    onTakeFailure = { hasPrefix -> if (hasPrefix) recordHistoryNotice(HistoryNotice.INCOMPLETE) },
                    timeline = observer)
                takeHistory = history
                runtime.open(capture, history, mainExecutor, observer,
                    retentionEligible = retentionEligible).also { it.start() }
            }
            take = startedTake
            started = true
            render()
            microphoneCapture.setReleaseRetry { startedTake.retryCaptureRelease() }
            Log.i(tag, "recording started")
        } catch (e: Exception) {
            rotationEvent(RotationEvent.CAPTURE_FAILED)
            rotationEvent(RotationEvent.CAPTURE_NOT_ACQUIRED)
            rotationEvent(RotationEvent.TERMINAL_FAILED)
            startup?.event("recordingFailed", startupTake)
            if (!started) outcomePending = null
            startupTake = null
            feedback?.stop()
            showIdle()
            Log.e(tag, "start failed")
            showPhase(PanelPhase.FAILED)
            if (MicrophoneOwner.state().occupied) updateSharedMicrophone()
        }
    }

    private fun stopAndInsert() {
        if (!takeActive || finishing) return
        // Stop settles the take on this editor.
        if (editorRetention.excludesSettledTake) excludeTakeFromHistory()
        // Stop grants authority to the input session the visible panel is attached to.
        authority = recordingToken?.takeIf { session.matches(it) && !fieldMismatch }
        authorityMoves = false
        finishing = true
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
    private fun commitForSession(token: Long?, text: String, allowed: () -> Boolean = { true }, onAttempt: () -> Unit): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper()) { "commit off the main thread" }
        rotationEvent(RotationEvent.COMMIT_ATTEMPT)
        val result = commitToInput(session, token, editor.live(), { editor.live() }, allowed,
            { it.textBeforeCursor(1) }, { connection, content -> onAttempt(); writeAndVerify(connection, content, token) }, text,
            requireLive = takeHeldForRotation, refused = {
                onAttempt()
                Log.w(tag, "commit refused: connection inactive")
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
    private fun writeAndVerify(connection: EditorConnection, content: String, token: Long?): Boolean {
        if (!takeHeldForRotation) return connection.write(content)
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
    private fun confirmLater(pending: PendingConfirm, result: TakeResult, rotationTake: Long, generation: Long) {
        outcomePending = generation
        if (phase == PanelPhase.IDLE) showPhase(PanelPhase.IDLE_WAITING)
        val history = takeHistory
        mainHandler.postDelayed({
            takeHistory = null
            if (outcomePending == generation) { outcomePending = null; if (phase == PanelPhase.IDLE_WAITING) showPhase(PanelPhase.IDLE) }
            if (destroyed) { diagnostics.takeEvent(RotationEvent.TERMINAL_COMMIT_REJECTED, rotationTake); return@postDelayed }
            val live = editor.live() == pending.connection && session.matches(pending.token)
            val check = if (live) InsertReadBack.look(pending.connection, pending.content, pending.extractedBefore) else InsertCheck.NONE
            traceVerified(check != InsertCheck.NONE, check)
            if (check != InsertCheck.NONE) {
                // The write was seen after all: the receipt the rejected write left is corrected, and the take completed.
                history?.receipt(HistoryDelivery.ACCEPTED)
                diagnostics.takeEvent(RotationEvent.TERMINAL_COMPLETED, rotationTake)
                if (held == null) returnToKeyboard()
                return@postDelayed
            }
            diagnostics.takeEvent(RotationEvent.TERMINAL_COMMIT_REJECTED, rotationTake)
            val keep = textToHold(result)
            if (keep != null) holdText(keep, result.historySaved, unconfirmed = true) else {
                showPhase(PanelPhase.NOT_INSERTED)
            }
        }, CONFIRM_DELAY_MS)
    }

    private fun saveBenchmarkTake(enabled: Boolean, pcm: ByteArray, result: TakeResult) {
        fun ms(from: String, to: String): Long {
            val start = result.eventsNs[from]
            val end = result.eventsNs[to]
            return if (start != null && end != null) (end - start) / 1_000_000 else 0
        }
        try {
            val json = JSONObject()
                .put("app", packageManager.getPackageInfo(packageName, 0).versionName)
                .put("recognizer", "parakeet-v3-matmul8 sherpa-onnx-1.13.8")
                .put("text", result.text ?: JSONObject.NULL)
                .put("audioSeconds", pcm.size / 2.0 / SAMPLE_RATE)
                .put("recordingMs", ms("captureStart", "stopRequested"))
                .put("stopToFinalMs", ms("stopRequested", "finalReady"))
                .put("timingProtocol", "dictation-session-v1")
                .put("eventsNs", JSONObject(result.eventsNs))
                .put("outcome", result.outcome.name)
                .put("commitAccepted", result.commitAccepted ?: JSONObject.NULL)
                .put("returnAccepted", result.returnAccepted ?: JSONObject.NULL)
            val name = BenchmarkStore.of(this).saveIfEligible(enabled, result, pcm, SAMPLE_RATE, json.toString(2))
            if (name != null) Log.i(tag, "benchmark take saved: $name")
        } catch (e: Exception) {
            Log.e(tag, "benchmark save failed", e)
        }
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
        if (interruptedView) { render(); return }
        interruptedView = true
        val generation = takeGeneration
        session.invalidate()
        finishing = true
        latestPreview = ""
        showPreview("")
        feedback?.stop()
        setRingLive(false)
        // Truthful while the microphone is released; then "Unable to insert text" with its marker.
        showPhase(PanelPhase.STOPPING)
        old.interrupt {
            if (generation == takeGeneration) {
                // Decode and History may continue, but capture ownership has ended.
                releaseRetry = false
                take = null
                recordingToken = null
                authority = null
                startupTake = null
                // The microphone stays off until the outcome is in; finished() then offers it.
                showPhase(if (outcomePending == null) PanelPhase.NOT_INSERTED else PanelPhase.NOT_INSERTED_WAITING)
            }
        }
    }

    private fun discardTake(reason: String) {
        if (!takeActive) return
        rotationEvent(RotationEvent.CANCEL_REQUESTED)
        take?.cancel(inputChanged = reason != "by user")
        Log.i(tag, "cancelled: $reason")
    }

    /**
     * The system took the field away mid-take. Drop it and hand back to the keyboard,
     * so the panel never reappears recording without the microphone key.
     */
    private fun discardAndReturn(reason: String) {
        if (returning) return
        val token = recordingToken
        discardTake(reason)
        showIdle()
        if (!session.matches(token)) return
        returning = true
        mainHandler.post { if (session.matches(token)) returnToKeyboard(); returning = false }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && held != null) { cancelAndReturn(); return true }
        if (keyCode == KeyEvent.KEYCODE_BACK && takeActive) {
            // Back on an open choice is its safe default; otherwise it opens the choice.
            if (choice != null) chooseSafe() else if (finishing) openFinishingChoice() else openRecordingChoice()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    /**
     * Cancel while recording pauses the take (#61): the platform recording stops, the microphone
     * stays owned by this take, and the choice opens inline in the panel. No window opens, so the
     * editor sees no focus change and restarts nothing. If capture already ended or cannot pause,
     * this is a system interruption (History).
     */
    private fun openRecordingChoice() {
        if (choice != null || !takeActive || finishing || interruptedView) return
        if (captureEnded()) { interruptTake(); return }
        if (take?.pause() != true) { interruptTake(); return }
        diagnostics.takeEvent(RotationEvent.CAPTURE_PAUSED, rotationTake)
        choice = PanelChoice.RECORDING
        setRingLive(false)
        showPhase(PanelPhase.PAUSED)
    }

    /** Cancel during the Stop wait holds the result before dispatch while the choice is open. */
    private fun openFinishingChoice() {
        if (choice != null || !takeActive || !finishing || interruptedView) return
        if (take?.holdDelivery(DeliveryHold.FINISHING_CHOICE) != true) return
        choice = PanelChoice.FINISHING
        showPhase(PanelPhase.FINISHING_CHOICE)
    }

    /** Continue recording (resumes the same take) or Keep finishing. Also system Back and the Back arrow. */
    private fun chooseSafe() {
        when (choice) {
            PanelChoice.RECORDING -> {
                choice = null
                if (take?.resume() != true) { render(); interruptTake(); return }
                diagnostics.takeEvent(RotationEvent.CAPTURE_RESUMED, rotationTake)
                showPhase(PanelPhase.RECORDING)
                if (!holding) setRingLive(true)
            }
            PanelChoice.FINISHING -> {
                choice = null
                showPhase(PanelPhase.FINISHING)
                // A rotation that still holds the result releases it once the panel has settled.
                take?.releaseDelivery(DeliveryHold.FINISHING_CHOICE)
            }
            null -> Unit
        }
    }

    /** Stop and insert: exactly Stop, from the paused take. */
    private fun chooseStop() {
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
        Log.i(tag, "cancelled: by user")
        showIdle()
    }

    /** The take's state as far as the panel shows it. */
    private fun panelState() = PanelState(
        phase = phase,
        choice = choice,
        running = takeActive && !interruptedView,
        finishing = finishing,
        held = held?.panel,
        preview = previewText,
        privateField = takeRetentionEligible == false,
        banner = saveTake,
        ringLive = ringLive,
    )

    /** Renders the take's state on the current panel; the only way this service changes panel views. */
    private fun render() {
        val view = panel ?: return
        shownFrame = renderPanel(view, panelFrame(panelState()), shownFrame, feedback)
    }

    private fun setRingLive(live: Boolean) {
        ringLive = live
        render()
    }

    private fun cancelAndReturn() {
        if (held != null) {
            // Back never writes to the clipboard: the first Back only warns, the second discards and leaves.
            val state = held
            if (state != null && !state.panel.warned) { state.warn(true); render(); return }
            val token = session.token()
            clearHeld()
            MicrophoneOwner.acceptExplicitStart()
            showIdle()
            if (token != null && session.matches(token)) returnToKeyboard()
            return
        }
        if (choice != null) { chooseSafe(); return }
        if (takeActive && !interruptedView) { if (finishing) openFinishingChoice() else openRecordingChoice(); return }
        render()
        // An interrupted take keeps finishing into History; Back only leaves.
        if (takeActive) { returnToKeyboard(); return }
        // Idle (no take, or it already ended): Back belongs to the live editor session.
        val token = recordingToken ?: session.token()
        discardTake("by user"); showIdle(); if (session.matches(token)) returnToKeyboard()
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
    private fun textToHold(result: TakeResult): String? =
        notInsertedText(result)?.takeIf { !takeLocked && editorPackage == takePackage }

    /** Holds the text of a take that was not inserted: the preview shows it, Copy and Discard text are offered. */
    private fun holdText(text: String, historyKeepsIt: Boolean, unconfirmed: Boolean = false) {
        held = HeldText(text, takePackage, HeldPanel(inHistory = historyKeepsIt, unconfirmed = unconfirmed))
        showPreview("")
        // Held text owns the status line; underneath, the take was not inserted and the microphone is free.
        showPhase(PanelPhase.NOT_INSERTED)
    }

    private fun clearHeld() { held = null }

    /** The panel's filled button: Copy text while text is held, otherwise the open choice's safe action. */
    private fun onSafeAction() { if (held != null) copyHeld() else chooseSafe() }

    /** The panel's dashed button: Discard text while text is held, otherwise Discard take. */
    private fun onDiscardAction() { if (held != null) discardHeld() else chooseDiscard() }

    /** Copy text: on the clipboard, the held text gone, back to the previous keyboard at once. */
    private fun copyHeld() {
        val state = held ?: return
        copyTranscript(this, state.text)
        clearHeld()
        MicrophoneOwner.acceptExplicitStart()
        showIdle()
        returnToKeyboard()
    }

    /** Discard text: gone at once; the panel is idle, the next time it opens it records. */
    private fun discardHeld() {
        clearHeld()
        MicrophoneOwner.acceptExplicitStart()
        Log.i(tag, "held text discarded")
        showIdle()
    }

    private fun returnToKeyboard(): Boolean {
        val switched = switchToPreviousInputMethod()
        if (!switched) {
            Log.w(tag, "no previous IME to return to"); requestHideSelf(0)
        }
        return switched
    }

    private fun showIdle() {
        feedback?.stop()
        feedback = null
        ringLive = false
        finishing = false
        showPreview("")
        if (!takeActive) releaseRetry = false
        when {
            releaseRetry && takeActive -> {
                showPhase(PanelPhase.RELEASE_FAILED)
            }
            takeActive || outcomePending != null -> {
                showPhase(PanelPhase.STOPPING)
            }
            MicrophoneOwner.state().occupied -> updateSharedMicrophone()
            else -> {
                showPhase(PanelPhase.IDLE)
            }
        }
        render()
    }

    private fun updateSharedMicrophone() {
        val owner = MicrophoneOwner.state()
        if (takeActive) return
        if (owner.occupied) {
            interruptedView = true
            if (owner.releaseFailed) {
                showPhase(PanelPhase.RELEASE_FAILED)
            } else {
                showPhase(PanelPhase.STOPPING)
            }
        } else if (interruptedView) showIdle()
    }

    /** History warnings are shown on the History page only, never on the recording panel. */
    private fun recordHistoryNotice(notice: HistoryNotice) {
        historyNotices(this).record(notice)
    }

    private fun showPhase(next: PanelPhase) {
        phase = next
        render()
    }

    /** The end of the text so far; the view is a few lines high. */
    private fun publishPreview(generation: Long, text: String) {
        if (generation != takeGeneration || interruptedView) return
        latestPreview = text
        if (previewAllowed) showPreview(text)
    }

    private fun showPreview(text: String) {
        previewText = PanelPreview.tail(held?.text ?: text)
        render()
    }

    private fun hasMicPermission() =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private var permissionAsked = false

    /** Ask once per service lifetime; after "don't ask again" we only show the message. */
    private fun requestMicPermission() {
        showPhase(PanelPhase.NO_PERMISSION)
        if (permissionAsked) return
        permissionAsked = true
        startActivity(Intent(this, PermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
