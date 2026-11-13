package ch.og.dictation

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
        internal var recorderFactory: () -> Recorder = ::Recorder
        internal var postCaptureEnd: (Handler, Runnable) -> Unit = { handler, callback -> handler.post(callback) }
        /** Asks Android to show this input method's own panel on the current input session. */
        internal var requestPanel: (InputMethodService) -> Unit = { it.requestShowSelf(0) }
        /** Where takes are kept; device demos point it at an isolated root, never the owner's History. */
        internal var historyRootFor: (android.content.Context) -> java.io.File = ::historyRoot
    }
    private val tag = "DictationIme"
    private var status: TextView? = null
    private var preview: TextView? = null
    private var microphone: ImageButton? = null
    private var ring: RecordingRingView? = null
    private var feedback: RecordingFeedback? = null
    private var captureEnded: () -> Boolean = { false }
    private var finishing = false
    private var statusText = R.string.status_opening
    private var previewText = ""
    private var microphoneEnabled = false
    private var microphoneLabel = R.string.status_opening
    private var banner: View? = null
    private var retentionNotice: TextView? = null
    private var editorRetentionEligible = false
    /** Kept for panel recreation throughout this take; never recomputed from later focus. */
    private var takeRetentionEligible: Boolean? = null
    /** Only saving audio needs a banner; experiment receipts remain out of the normal panel. */
    private var saveTake = false
    private val session = InputSession()
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
    private var rotationTrace: RotationTrace? = null
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
    /** A Stop-wait rotation hold holds the result before dispatch until the panel has settled. */
    private var deliveryHeldForRotation = false
    /** This take went through a rotation hold (also a self-requested panel) since its capture began. */
    private var takeHeldForRotation = false
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
            if (take != null) takeLocked = true // Also after Stop, until finished(): its text is refused, after unlock too.
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
                rotationTrace = RotationTrace(File(filesDir, "lab/rotation-probe.jsonl"))
                lastConfiguration = Configuration(resources.configuration)
                rotationEvent(RotationEvent.SERVICE_CREATE)
                listenToDisplay()
            }
            startup = StartupTrace(applicationContext).also {
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
        val ready = Engine.warmUp(this)
        startup?.event("warmUpReturned")
        ready.whenComplete { _, failure ->
            startup?.event(if (failure == null) "modelReady" else "modelFailed",
                ns = Engine.modelReadyNs ?: System.nanoTime())
        }
        startup?.event("serviceCreateEnd")
    }

    override fun onCreateInputView(): View {
        rotationView++
        held?.warned = false // A rotation cancels the Back warning.
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
                if (!drawn && rotationTrace != null) {
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
        status = v.findViewById(R.id.status)
        preview = v.findViewById<TextView>(R.id.preview).also { PanelPreview.bind(it, previewText) }
        showStatus(statusText)
        banner = v.findViewById(R.id.benchmark_banner)
        retentionNotice = v.findViewById<TextView>(R.id.retention_notice)
        banner?.visibility = if (saveTake) View.VISIBLE else View.GONE
        showPanelDisclosure(if (takeRetentionEligible == false) R.string.retention_field_off else null)
        ring = v.findViewById<RecordingRingView>(R.id.recording_ring).also { it.follow(feedback) }
        microphone = v.findViewById<ImageButton>(R.id.microphone).apply {
            setOnClickListener {
                if (MicrophoneOwner.state().releaseFailed) {
                    releaseRetry = false
                    showStatus(R.string.status_stopping_recording)
                    setMicrophone(false, R.string.status_stopping_recording)
                    MicrophoneOwner.retryRelease()
                } else if (takeActive) stopAndInsert() else startRecording()
            }
        }
        setMicrophone(microphoneEnabled, microphoneLabel)
        v.findViewById<ImageButton>(R.id.back).setOnClickListener { cancelAndReturn() }
        v.findViewById<View>(R.id.choice_safe).setOnClickListener { onSafeAction() }
        v.findViewById<View>(R.id.choice_stop).setOnClickListener { chooseStop() }
        v.findViewById<View>(R.id.choice_discard).setOnClickListener { onDiscardAction() }
        renderChoice()
        startup?.event("inputViewCreateEnd")
        return v
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        attachRotationTrace()
        rotationEvent(RotationEvent.INPUT_START, restarting)
        super.onStartInput(attribute, restarting)
        observeGeometry()
        Log.i(tag, "input start: restarting=$restarting take=$takeActive")
        editorRetention = EditorRetentionPolicy.classify(attribute)
        editorRetentionEligible = editorRetention.eligible
        editorPackage = attribute?.packageName
        inputStarted = true
        held?.warned = false
        if (held != null && editorPackage != held?.from) { clearHeld(); showIdle() } // A different app's editor.
        previewAllowed = false
        showPreview("")
        // Any input start ends the previous session (InputSession.start).
        session.start()
        editorEvent(EditorEvent.INPUT_START)
        rotationEvent(RotationEvent.INPUT_START_DONE, restarting, retention = editorRetention)
    }

    private fun attachRotationTrace() {
        if ((rotationTrace == null || rotationTrace?.closed == true) && resources.getBoolean(R.bool.benchmark_receipts) &&
            RotationTrace.probeActive()) {
            rotationTrace = RotationTrace(File(filesDir, "lab/rotation-probe.jsonl"))
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
        startupTake = startup?.beginTake(Engine.modelReadyNs != null)
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
            .put("bannerVisible", banner?.visibility == View.VISIBLE)
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
        ring?.follow(null)
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
        rotationTrace?.let { trace ->
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
        if (layoutChanged && panel != null) {
            ring?.follow(null)
            setInputView(onCreateInputView())
        }
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
            rotation = rotationChanges > rotationsWhenVisible))
        // A rotation never moves a take to another app: an input start from a different package
        // ends the hold, whatever the rotation window says (no authority, no panel there).
        val decision = if (decided == EditorDecision.REVOKE_AUTHORITY && event == EditorEvent.INPUT_START && anotherApp())
            EditorDecision.INTERRUPT else decided
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
            ring?.follow(null)
        }
        // An open choice is take state: the returned panel renders it again; nothing to move.
        // Stop wait: hold the result before dispatch; an accepted commit is never undone.
        if (finishing && !deliveryHeldForRotation && take?.holdDelivery() == true) deliveryHeldForRotation = true
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
        if (holding && inputStarted && !inputViewVisible && !destroyed) requestPanel(this)
    }

    /** The panel settled after a rotation: release a result held for it, unless the finishing choice holds it. */
    private fun restoreAfterRotation() {
        val heldForRotation = deliveryHeldForRotation
        deliveryHeldForRotation = false
        if (!takeActive || interruptedView) return
        if (heldForRotation && choice != PanelChoice.FINISHING) take?.resumeDelivery()
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
        authorityMoves = false
        if (takeActive && !interruptedView && editorRetention.excludesSettledTake) excludeTakeFromHistory()
        restoreAfterRotation()
    }

    /**
     * Take start, end or interruption: ends hold, gap and settling, and forgets everything a
     * rotation decided for the take: moving authority and the rotation's delivery hold flag (the
     * take itself releases or drops its held result).
     */
    private fun endHoldAndClearDeliveryHold() {
        endHold()
        authorityMoves = false
        deliveryHeldForRotation = false
    }

    /**
     * The panel is visible on the live session for this take; a held take continues there. The
     * rotation stays identified until the panel has stayed visible for ROTATION_SETTLE_MS, because
     * a recreated editor can still hide it right after Android showed it (#56 trace).
     */
    private fun panelVisible() {
        recordingToken = session.token()
        // Stop's authority moves with the identified rotation to the session the panel returned to.
        if (authorityMoves && finishing && !interruptedView) authority = recordingToken
        if (editorRetention.positivelyPrivate) excludeTakeFromHistory()
        if (holding) {
            holding = false
            if (!interruptedView) {
                previewAllowed = true
                showPreview(latestPreview)
                if (!finishing && choice == null) ring?.follow(feedback)
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
        showPanelDisclosure(R.string.retention_field_off)
    }

    private fun settleAfterRotation() {
        if (!takeActive || holding || !inputViewVisible || rotationChanges <= rotationsWhenVisible) return
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
                              retention: EditorRetention? = null) {
        val trace = rotationTrace ?: return
        val rotation = observeRotation()
        trace.event(event, takeActive, recordingToken != null && session.matches(recordingToken),
            flag, rotationView.takeIf { it > 0 }, rotationTake.takeIf { it > 0 }, rotation, rotationChanges,
            configDiff, handledConfig, retention)
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
        endHold()
        getSystemService(android.media.AudioManager::class.java)?.unregisterAudioRecordingCallback(recordingCallback)
        MicrophoneOwner.remove(microphoneOwnerListener)
        unregisterReceiver(screenOff)
        session.invalidate()
        if (takeActive) interruptTake()
        if (releaseRetry) MicrophoneOwner.retryRelease()
        feedback?.stop()
        ring?.follow(null)
        super.onDestroy()
    }

    private fun startRecording() {
        if (takeActive || held != null) return // Held text is never dropped by starting a take.
        if (MicrophoneOwner.state().occupied) {
            interruptedView = true
            updateSharedMicrophone()
            return
        }
        MicrophoneOwner.acceptExplicitStart()
        interruptedView = false
        choice = null
        renderChoice()
        previewAllowed = true
        if (!hasMicPermission()) {
            showStatus(R.string.status_no_permission)
            setMicrophone(false, R.string.status_no_permission)
            return
        }
        // Latch current policy for each new take, including explicit restarts.
        val liveCheck = Engine.liveReceipt() != null
        saveTake = !RotationTrace.probeActive() && BenchmarkStore.recordingEnabled(BenchmarkStore.isEnabled(this), liveCheck) && editorRetentionEligible
        banner?.visibility = if (saveTake) View.VISIBLE else View.GONE
        if (startupTake == null) startupTake = startup?.beginTake(Engine.modelReadyNs != null)
        rotationTake++
        rotationEvent(RotationEvent.CAPTURE_START)
        try {
            val liveTakeId = startupTake
            val generation = ++takeGeneration
            val currentRotationTake = rotationTake
            recordingToken = session.token()
            takeHeldForRotation = false
            takePackage = editorPackage
            takeLocked = false
            authority = null
            fun authorityForThisTake() = authority.takeIf { generation == takeGeneration }
            val save = saveTake
            val retentionEligible = editorRetentionEligible
            takeRetentionEligible = retentionEligible
            showPanelDisclosure(if (retentionEligible) null else R.string.retention_field_off)
            var identity: () -> org.json.JSONObject? = { null }
            var attempted = false
            val output = object : TakeOutput {
                override fun preview(text: String) = publishPreview(generation, text)
                override fun captureReleaseFailed() {
                    if (generation != takeGeneration) return
                    releaseRetry = true
                    showStatus(R.string.status_release_failed)
                    setMicrophone(true, R.string.retry_release)
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
                    rotationTrace?.event(when (result.outcome) {
                        TakeOutcome.COMPLETED -> RotationEvent.TERMINAL_COMPLETED
                        TakeOutcome.CANCELLED -> RotationEvent.TERMINAL_CANCELLED
                        TakeOutcome.INPUT_CHANGED -> RotationEvent.TERMINAL_INPUT_CHANGED
                        TakeOutcome.FAILED -> RotationEvent.TERMINAL_FAILED
                        TakeOutcome.COMMIT_REJECTED -> RotationEvent.TERMINAL_COMMIT_REJECTED
                    }, take = currentRotationTake)
                    identity()?.let { startup?.liveResult(liveTakeId, it, result) }
                    if (!result.interrupted) saveBenchmarkTake(save && !RotationTrace.probeActive(), pcm, result)
                    if (generation != takeGeneration) return
                    recordingToken = null
                    authority = null
                    startupTake = null
                    latestPreview = ""
                    endHoldAndClearDeliveryHold()
                    if (result.interrupted) {
                        take = null
                        finishing = false
                        choice = null
                        showPreview("")
                        // Whether History kept it shows on the History page; the private-field line says when it cannot.
                        val keep = textToHold(result)
                        if (keep != null) holdText(keep, result.retentionEligible) else {
                            showStatus(R.string.status_unable_insert)
                            setMicrophone(true, R.string.start_recording)
                        }
                        return
                    }
                    choice = null
                    showIdle()
                    val keep = textToHold(result)
                    if (keep != null) {
                        // The text is in History where the field allows it; the panel also holds it for Copy.
                        holdText(keep, result.retentionEligible)
                    } else if (notInsertedText(result) != null) {
                        // Not this app's panel any more, or the screen went off: status only, History where eligible.
                        showStatus(R.string.status_unable_insert)
                        setMicrophone(true, R.string.start_recording)
                    } else if (result.outcome == TakeOutcome.FAILED || result.outcome == TakeOutcome.COMMIT_REJECTED) {
                        Log.e(tag, "take failed: ${result.outcome}")
                        showStatus(R.string.status_failed)
                        setMicrophone(true, R.string.retry_recording)
                    }
                    if (MicrophoneOwner.state().occupied) updateSharedMicrophone()
                }
            }
            showStatus(R.string.status_opening)
            showPreview("")
            latestPreview = ""
            // The panel is visible now; only rotations after this point count for this take.
            observeRotation()
            rotationsWhenVisible = rotationChanges
            endHoldAndClearDeliveryHold()
            finishing = false
            setMicrophone(false, R.string.status_opening)
            val currentFeedback = RecordingFeedback()
            feedback?.stop()
            feedback = currentFeedback
            ring?.follow(currentFeedback)
            val trace = startup
            val traceTake = startupTake
            val traceRotation = rotationTrace
            val traceRotationTake = rotationTake
            val unexpectedEnd = AtomicBoolean(false)
            captureEnded = { unexpectedEnd.get() }
            val microphoneCapture = MicrophoneCapture(onStarted = {
                traceRotation?.event(RotationEvent.CAPTURE_STARTED, take = traceRotationTake)
                trace?.event("recordingStarted", traceTake)
                mainHandler.post {
                    if (feedback === currentFeedback && takeActive && !finishing) {
                        showStatus(R.string.status_recording)
                        setMicrophone(true, R.string.stop_recording)
                        ring?.invalidate()
                    }
                }
            }, feedback = currentFeedback, onInterrupted = {
                unexpectedEnd.set(true)
                traceRotation?.event(RotationEvent.CAPTURE_INTERRUPTED, take = traceRotationTake)
                postCaptureEnd(mainHandler, Runnable {
                    if (feedback === currentFeedback && takeActive) {
                        // Unexpected capture end is a system interruption: revoke insertion,
                        // release capture, then finish only the audio already accepted.
                        interruptTake()
                    }
                })
            }, recorder = recorderFactory())
            val capture = object : TakeCapture {
                override fun start(accept: (FloatArray) -> Unit) {
                    try { microphoneCapture.start(accept) }
                    catch (failure: Throwable) {
                        traceRotation?.event(RotationEvent.CAPTURE_FAILED, take = traceRotationTake)
                        throw failure
                    }
                }
                override fun stop(done: (Result<ByteArray>) -> Unit) {
                    microphoneCapture.stop { result ->
                        if (result.isFailure) traceRotation?.event(RotationEvent.CAPTURE_FAILED, take = traceRotationTake)
                        else traceRotation?.event(RotationEvent.CAPTURE_RELEASED, take = traceRotationTake)
                        done(result)
                    }
                }
                override fun cancel(done: () -> Unit) {
                    microphoneCapture.cancel {
                        traceRotation?.event(RotationEvent.CAPTURE_RELEASED, take = traceRotationTake)
                        done()
                    }
                }
                override fun pause() = microphoneCapture.pause()
                override fun resume() = microphoneCapture.resume()
                override fun cancelWithResult(done: (Result<Unit>) -> Unit) {
                    microphoneCapture.cancelWithResult { result ->
                        traceRotation?.event(if (result.isSuccess) RotationEvent.CAPTURE_RELEASED else RotationEvent.CAPTURE_FAILED, take = traceRotationTake)
                        done(result)
                    }
                }
            }
            val observer = if (trace == null && traceRotation == null) null else TimelineObserver { event ->
                val rotationEvent = when (event.phase) {
                    "stopRequested" -> RotationEvent.STOP_REQUESTED
                    "captureStopped" -> RotationEvent.CAPTURE_STOPPED
                    "finalReady" -> RotationEvent.FINAL_READY
                    "takeEnded" -> RotationEvent.TAKE_ENDED
                    else -> null
                }
                if (rotationEvent != null) traceRotation?.event(rotationEvent, take = traceRotationTake)
                if (event.phase in setOf("captureStart", "firstAudio", "takeEnded")) {
                    trace?.event(event.phase, traceTake, event.ns)
                }
                if (event.phase in setOf("scenario", "captureStart", "captureSampleAnchor", "captureStopAnchor", "chunkReady", "chunkEnqueue",
                        "chunkWorkerStart", "chunkWorkerEnd", "checkpointQueued", "checkpointDurable", "checkpointFailed",
                        "stopRequested", "producerStop", "captureStopped", "finishCall", "finishEnqueue",
                        "finishWorkerStart", "finishWorkerEnd", "finalReady", "commitAccepted", "commitRejected",
                        "returnRequested", "takeEnded")) {
                    startup?.timing(traceTake, event)
                }
            }
            val startedTake = Engine.openTake(this) { runtime, receipt ->
                identity = receipt
                runtime.open(capture, HistoryTakeOutput(output,
                    { HistoryIo.store(historyRootFor(this)) }, HistoryIo.executor,
                    { recordHistoryNotice(HistoryNotice.SAVE) }, delivery = mainExecutor,
                    onDeleteFailure = { recordHistoryNotice(HistoryNotice.DELETE) },
                    onReceiptFailure = { recordHistoryNotice(HistoryNotice.RECEIPT) },
                    onTakeFailure = { hasPrefix -> if (hasPrefix) recordHistoryNotice(HistoryNotice.INCOMPLETE) },
                    timeline = observer), mainExecutor, observer,
                    retentionEligible = retentionEligible).also { it.start() }
            }
            take = startedTake
            renderChoice()
            microphoneCapture.setReleaseRetry { startedTake.retryCaptureRelease() }
            Log.i(tag, "recording started")
        } catch (e: Exception) {
            rotationEvent(RotationEvent.CAPTURE_FAILED)
            rotationEvent(RotationEvent.CAPTURE_NOT_ACQUIRED)
            rotationEvent(RotationEvent.TERMINAL_FAILED)
            startup?.event("recordingFailed", startupTake)
            startupTake = null
            feedback?.stop()
            showIdle()
            Log.e(tag, "start failed")
            showStatus(R.string.status_failed)
            setMicrophone(true, R.string.retry_recording)
            if (MicrophoneOwner.state().occupied) updateSharedMicrophone()
        }
    }

    private fun stopAndInsert() {
        if (!takeActive || finishing) return
        // Stop settles the take on this editor.
        if (editorRetention.excludesSettledTake) excludeTakeFromHistory()
        // Stop grants authority to the input session the visible panel is attached to.
        authority = recordingToken?.takeIf { session.matches(it) }
        authorityMoves = false
        finishing = true
        feedback?.stop()
        showStatus(R.string.status_finishing)
        setMicrophone(false, R.string.status_finishing)
        ring?.follow(null)
        take?.stop()
    }

    /**
     * The only path that writes to a field. Commits on the main thread and only
     * while the session that started the take is still the live one.
     */
    private fun commitForSession(token: Long?, text: String, allowed: () -> Boolean = { true }, onAttempt: () -> Unit): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper()) { "commit off the main thread" }
        rotationEvent(RotationEvent.COMMIT_ATTEMPT)
        val result = commitToInput(session, token, currentInputConnection, { currentInputConnection }, allowed,
            { it.getTextBeforeCursor(1, 0) }, { ic, content -> onAttempt(); ic.commitText(content, 1) }, text,
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
        endHoldAndClearDeliveryHold()
        MicrophoneOwner.requireExplicitStart()
        revokeAuthority()
        // A system interruption overrides an open choice (paused audio already captured goes to History).
        choice = null
        if (interruptedView) { renderChoice(); return }
        interruptedView = true
        val generation = takeGeneration
        session.invalidate()
        finishing = true
        latestPreview = ""
        showPreview("")
        feedback?.stop()
        ring?.follow(null)
        // Truthful while the microphone is released; then "Unable to insert text" with its marker.
        showStatus(R.string.status_stopping_recording)
        setMicrophone(false, R.string.status_stopping_recording)
        old.interrupt {
            if (generation == takeGeneration) {
                // Decode and History may continue, but capture ownership has ended.
                releaseRetry = false
                take = null
                recordingToken = null
                authority = null
                startupTake = null
                showStatus(R.string.status_unable_insert)
                setMicrophone(true, R.string.start_recording)
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
        rotationTrace?.event(RotationEvent.CAPTURE_PAUSED, take = rotationTake)
        choice = PanelChoice.RECORDING
        ring?.follow(null)
        showStatus(R.string.status_paused)
        setMicrophone(false, R.string.status_paused)
        renderChoice()
    }

    /** Cancel during the Stop wait holds the result before dispatch while the choice is open. */
    private fun openFinishingChoice() {
        if (choice != null || !takeActive || !finishing || interruptedView) return
        if (take?.holdDelivery() != true) return
        choice = PanelChoice.FINISHING
        showStatus(R.string.status_finishing_choice)
        renderChoice()
    }

    /** Continue recording (resumes the same take) or Keep finishing. Also system Back and the Back arrow. */
    private fun chooseSafe() {
        when (choice) {
            PanelChoice.RECORDING -> {
                choice = null
                if (take?.resume() != true) { renderChoice(); interruptTake(); return }
                rotationTrace?.event(RotationEvent.CAPTURE_RESUMED, take = rotationTake)
                showStatus(R.string.status_recording)
                setMicrophone(true, R.string.stop_recording)
                if (!holding) ring?.follow(feedback)
                renderChoice()
            }
            PanelChoice.FINISHING -> {
                choice = null
                showStatus(R.string.status_finishing)
                renderChoice()
                // A rotation that still holds the result releases it once the panel has settled.
                if (!deliveryHeldForRotation) take?.resumeDelivery()
            }
            null -> Unit
        }
    }

    /** Stop and insert: exactly Stop, from the paused take. */
    private fun chooseStop() {
        if (choice != PanelChoice.RECORDING) return
        choice = null
        renderChoice()
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

    /** Renders the take's state (choice, Preparing text, marker, glyph, Back label) on the current panel. */
    private fun renderChoice() {
        // Held text owns the status line (what to do, and whether History keeps it); otherwise the take's status.
        val shown = held?.status ?: statusText
        status?.setText(shown)
        val view = panel ?: return
        val open = choice
        val running = takeActive && !interruptedView
        renderPanel(view, PanelFrame(
            choice = open,
            preparing = running && finishing && open == null,
            stopGlyph = running && !finishing,
            unableToInsert = statusText == R.string.status_unable_insert,
            held = held != null,
            copied = held?.copied == true,
            backLabel = when {
                open == PanelChoice.RECORDING -> R.string.continue_recording
                open == PanelChoice.FINISHING -> R.string.keep_finishing
                held?.warned == true -> R.string.back_discard_text
                running && finishing -> R.string.back_hold_and_choose
                running -> R.string.discard_recording
                else -> R.string.back_to_keyboard
            }))
    }

    private fun cancelAndReturn() {
        if (held != null) {
            // Back never writes to the clipboard. Uncopied text: the first Back only warns, the second
            // discards and leaves; after a copy Back leaves at once.
            val state = held
            if (state != null && !state.copied && !state.warned) { state.warned = true; renderChoice(); return }
            val token = session.token()
            clearHeld()
            MicrophoneOwner.requireExplicitStart()
            showIdle()
            if (token != null && session.matches(token)) returnToKeyboard()
            return
        }
        if (choice != null) { chooseSafe(); return }
        if (takeActive && !interruptedView) { if (finishing) openFinishingChoice() else openRecordingChoice(); return }
        renderChoice()
        // An interrupted take keeps finishing into History; Back only leaves.
        if (takeActive) { returnToKeyboard(); return }
        // Idle (no take, or it already ended): Back belongs to the live editor session.
        val token = recordingToken ?: session.token()
        discardTake("by user"); showIdle(); if (session.matches(token)) returnToKeyboard()
    }

    /** The text of a take that was not inserted; the panel holds it for Copy. [from] is the editor's app. */
    private class HeldText(
        val text: String, val from: String?,
        /** History keeps the take (the editor is retention-eligible); the status says where to find it. */
        val inHistory: Boolean,
        var copied: Boolean = false,
        /** The first Back warned that a second one discards the uncopied text. */
        var warned: Boolean = false,
    ) {
        val status: Int get() = when {
            copied -> R.string.status_copied
            warned -> R.string.status_back_warning
            inHistory -> R.string.status_unable_history
            else -> R.string.status_unable_unsaved
        }
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
    private fun holdText(text: String, historyKeepsIt: Boolean) {
        held = HeldText(text, takePackage, historyKeepsIt)
        MicrophoneOwner.requireExplicitStart() // A panel shown again never starts a take over the held text.
        showPreview("")
        setMicrophone(true, R.string.start_recording)
        renderChoice()
    }

    private fun clearHeld() { held = null }

    /** The panel's filled button: Copy text while text is held, otherwise the open choice's safe action. */
    private fun onSafeAction() { if (held != null) copyHeld() else chooseSafe() }

    /** The panel's dashed button: Discard text while text is held, otherwise Discard take. */
    private fun onDiscardAction() { if (held != null) discardHeld() else chooseDiscard() }

    private fun copyHeld() {
        val state = held ?: return
        copyTranscript(this, state.text)
        state.copied = true
        state.warned = false
        renderChoice()
    }

    /** Discard text: gone at once; the panel is idle and a new take needs the microphone tap. */
    private fun discardHeld() {
        clearHeld()
        MicrophoneOwner.requireExplicitStart()
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
        ring?.follow(null)
        finishing = false
        showPreview("")
        if (!takeActive) releaseRetry = false
        when {
            releaseRetry && takeActive -> {
                showStatus(R.string.status_release_failed)
                setMicrophone(true, R.string.retry_release)
            }
            takeActive -> {
                showStatus(R.string.status_stopping_recording)
                setMicrophone(false, R.string.status_stopping_recording)
            }
            MicrophoneOwner.state().occupied -> updateSharedMicrophone()
            else -> {
                showStatus(R.string.status_idle)
                setMicrophone(true, R.string.start_recording)
            }
        }
        renderChoice()
    }

    private fun updateSharedMicrophone() {
        val owner = MicrophoneOwner.state()
        if (takeActive) return
        if (owner.occupied) {
            interruptedView = true
            if (owner.releaseFailed) {
                showStatus(R.string.status_release_failed)
                setMicrophone(true, R.string.retry_release)
            } else {
                showStatus(R.string.status_stopping_recording)
                setMicrophone(false, R.string.status_stopping_recording)
            }
        } else if (interruptedView) showIdle()
    }

    /** Secondary disclosure has its own view so recording/finishing status stays truthful. */
    private fun showPanelDisclosure(message: Int?) {
        retentionNotice?.let { PanelDisclosure.bind(it, message) }
    }

    /** History warnings are shown on the History page only, never on the recording panel. */
    private fun recordHistoryNotice(notice: HistoryNotice) {
        historyNotices(this).record(notice)
    }

    private fun showStatus(label: Int) {
        statusText = label
        renderChoice()
    }

    private fun setMicrophone(enabled: Boolean, label: Int) {
        microphoneEnabled = enabled
        microphoneLabel = label
        // One disk colour in every state (#36): the glyph and the status say what it does.
        microphone?.apply {
            isEnabled = enabled
            contentDescription = getString(label)
        }
    }

    /** The end of the text so far; the view is a few lines high. */
    private fun publishPreview(generation: Long, text: String) {
        if (generation != takeGeneration || interruptedView) return
        latestPreview = text
        if (previewAllowed) showPreview(text)
    }

    private fun showPreview(text: String) {
        previewText = PanelPreview.tail(held?.text ?: text)
        preview?.let { PanelPreview.bind(it, previewText) }
    }

    private fun hasMicPermission() =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private var permissionAsked = false

    /** Ask once per service lifetime; after "don't ask again" we only show the message. */
    private fun requestMicPermission() {
        showStatus(R.string.status_no_permission)
        setMicrophone(false, R.string.status_no_permission)
        if (permissionAsked) return
        permissionAsked = true
        startActivity(Intent(this, PermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
