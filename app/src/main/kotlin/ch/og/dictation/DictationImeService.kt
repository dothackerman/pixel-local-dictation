package ch.og.dictation

import android.Manifest
import android.app.AlertDialog
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
    private var recordingPrompt: AlertDialog? = null
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
    /** A Cancel choice that was open when the rotation hold began; shown again once the panel settles. */
    private var pendingPrompt: Prompt? = null
    private enum class Prompt { RECORDING, FINISHING }
    private var openPrompt: Prompt? = null
    /** A prompt closed for a rotation: its dismissal decides nothing. */
    private var movedPrompt: AlertDialog? = null
    private val gapExpired = Runnable {
        if (!rotationEpisode) return@Runnable
        if (holding) { Log.i(tag, "rotation hold: panel did not return"); interruptTake() }
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
        rotationEvent(RotationEvent.VIEW_CREATE)
        startup?.event("inputViewCreateBegin")
        val v = layoutInflater.inflate(R.layout.ime, null)
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
        startup?.event("inputViewCreateEnd")
        return v
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        attachRotationTrace()
        rotationEvent(RotationEvent.INPUT_START, restarting)
        super.onStartInput(attribute, restarting)
        observeGeometry()
        Log.i(tag, "input start: restarting=$restarting take=$takeActive")
        editorRetentionEligible = EditorRetentionPolicy.isEligible(attribute)
        previewAllowed = false
        showPreview("")
        // Any input start ends the previous session (InputSession.start).
        session.start()
        editorEvent(EditorEvent.INPUT_START)
        rotationEvent(RotationEvent.INPUT_START_DONE, restarting)
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
     * method.xml declares orientation and screen-size changes as handled, so Android neither
     * resets the views nor restarts input for them. Rebuild only our panel for the new layout;
     * the take lives in this service and the panel renders its state. Never starts, cancels or
     * interrupts a take.
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
        val decision = EditorEventPolicy.decide(event, TakeFacts(
            active = takeActive, returning = returning, sameSession = session.matches(recordingToken),
            capturing = takeActive && !finishing && !interruptedView,
            finishing = takeActive && finishing && !interruptedView,
            rotation = rotationChanges > rotationsWhenVisible))
        when (decision) {
            EditorDecision.NOTHING -> if (event == EditorEvent.INPUT_VIEW_START && takeActive && !returning) panelVisible()
            EditorDecision.REVOKE_AUTHORITY -> {
                if (authority != null) authorityMoves = true
                revokeAuthority()
                hold(event)
            }
            EditorDecision.INTERRUPT -> interruptTake()
        }
        return decision
    }

    /** Enters (or stays in) the rotation hold. The gap starts once and is never extended. */
    private fun hold(event: EditorEvent) {
        mainHandler.removeCallbacks(panelSettled)
        if (!holding) {
            holding = true
            Log.i(tag, "rotation hold")
            ring?.follow(null)
        }
        // The prompt's window belongs to the panel that is going away; the choice stays open.
        movePromptAside()
        // Stop wait: hold the result before dispatch; an accepted commit is never undone.
        if (finishing && !deliveryHeldForRotation && take?.holdDelivery() == true) deliveryHeldForRotation = true
        if (!rotationEpisode) {
            rotationEpisode = true
            mainHandler.postDelayed(gapExpired, ROTATION_HOLD_GAP_MS)
        }
        if (finishing) { requestPanelAfter(event); return } // The microphone is already off.
        val silenced = runCatching {
            getSystemService(android.media.AudioManager::class.java)?.activeRecordingConfigurations.orEmpty().any { it.isClientSilenced }
        }.getOrDefault(false)
        if (silenced) { silencedDuringHold(); return }
        requestPanelAfter(event)
    }

    /** A new editor took input without showing the keyboard: ask for our own panel. */
    private fun requestPanelAfter(event: EditorEvent) {
        if (event == EditorEvent.INPUT_START) mainHandler.post {
            if (holding && !inputViewVisible && !destroyed) requestPanel(this)
        }
    }

    /** Closes an open Cancel choice without choosing; it is shown again when the panel settles. */
    private fun movePromptAside() {
        val dialog = recordingPrompt ?: return
        if (!dialog.isShowing) return
        pendingPrompt = openPrompt
        movedPrompt = dialog
        dismissRecordingPrompt()
    }

    /**
     * The panel settled after a rotation: show a moved choice again, or apply its safe default
     * (Continue recording, Keep finishing) when it cannot be shown; then release a held result.
     */
    private fun restoreAfterRotation() {
        val prompt = pendingPrompt
        pendingPrompt = null
        val heldForRotation = deliveryHeldForRotation
        deliveryHeldForRotation = false
        if (!takeActive || interruptedView) return
        when {
            prompt == Prompt.RECORDING && !finishing -> showRecordingPrompt(reopen = true)
            prompt == Prompt.FINISHING && finishing -> showFinishingPrompt(reopen = true)
        }
        if ((heldForRotation || prompt == Prompt.FINISHING) && openPrompt != Prompt.FINISHING) take?.resumeDelivery()
    }

    private fun silencedDuringHold() {
        Log.i(tag, "rotation hold: capture silenced")
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
        endHold()
        rotationsWhenVisible = rotationChanges
        authorityMoves = false
        restoreAfterRotation()
    }

    /** The take ended or was interrupted: nothing of a rotation may decide for it any more. */
    private fun forgetRotationChoice() {
        authorityMoves = false
        deliveryHeldForRotation = false
        pendingPrompt = null
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
        // Retention stays latched from the starting editor and only tightens for a rebound one.
        if (!editorRetentionEligible && takeRetentionEligible == true) {
            takeRetentionEligible = false
            take?.excludeFromHistory()
            showPanelDisclosure(R.string.retention_field_off)
        }
        if (holding) {
            holding = false
            if (!interruptedView) {
                previewAllowed = true
                showPreview(latestPreview)
                if (!finishing) ring?.follow(feedback)
            }
        }
        settleAfterRotation()
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

    private fun rotationEvent(event: RotationEvent, flag: Boolean? = null, configDiff: Int? = null, handledConfig: Int? = null) {
        val trace = rotationTrace ?: return
        val rotation = observeRotation()
        trace.event(event, takeActive, recordingToken != null && session.matches(recordingToken),
            flag, rotationView.takeIf { it > 0 }, rotationTake.takeIf { it > 0 }, rotation, rotationChanges,
            configDiff, handledConfig)
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
        if (takeActive) return
        if (MicrophoneOwner.state().occupied) {
            interruptedView = true
            updateSharedMicrophone()
            return
        }
        MicrophoneOwner.acceptExplicitStart()
        interruptedView = false
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
            authority = null
            fun granted() = authority.takeIf { generation == takeGeneration }
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
                override fun commit(text: String) = commitForSession(granted(), text) { attempted = true }
                override fun commit(text: String, allowed: () -> Boolean) = commitForSession(granted(), text, allowed) { attempted = true }
                override fun commitAttempted() = attempted
                override fun returnToKeyboard(): Boolean {
                    if (!session.matches(granted())) return false
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
                    endHold()
                    forgetRotationChoice()
                    if (result.interrupted) {
                        take = null
                        finishing = false
                        showStatus(if (!inputViewVisible) R.string.status_recording_stopped else when {
                            result.interruptedSaved -> R.string.status_saved_history
                            result.failure != null -> R.string.status_transcription_incomplete
                            !result.retentionEligible -> R.string.status_stopped_recovery_unavailable
                            else -> R.string.status_recording_stopped
                        })
                        setMicrophone(true, R.string.start_recording)
                        return
                    }
                    showIdle()
                    if (result.outcome == TakeOutcome.FAILED || result.outcome == TakeOutcome.COMMIT_REJECTED) {
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
            endHold()
            forgetRotationChoice()
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
        // Stop grants authority to the input session the visible panel is attached to.
        authority = recordingToken?.takeIf { session.matches(it) }
        authorityMoves = false
        finishing = true
        showStatus(R.string.status_finishing)
        setMicrophone(false, R.string.status_finishing)
        feedback?.stop()
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
            { it.getTextBeforeCursor(1, 0) }, { ic, content -> onAttempt(); ic.commitText(content, 1) }, text)
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
        endHold()
        // A system interruption overrides any open or moved choice; the take goes to History.
        forgetRotationChoice()
        MicrophoneOwner.requireExplicitStart()
        revokeAuthority()
        if (interruptedView) return
        interruptedView = true
        val generation = takeGeneration
        session.invalidate()
        finishing = true
        feedback?.stop()
        ring?.follow(null)
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
                showStatus(if (!inputViewVisible) R.string.status_recording_stopped else
                    if (takeRetentionEligible == true) R.string.status_stopped_finishing_history
                    else R.string.status_stopped_recovery_unavailable)
                setMicrophone(true, R.string.start_recording)
            }
        }
        dismissRecordingPrompt()
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

    private fun dismissRecordingPrompt() {
        recordingPrompt?.dismiss()
        recordingPrompt = null
        openPrompt = null
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && takeActive) {
            if (finishing) showFinishingPrompt() else showRecordingPrompt()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    /**
     * [reopen]: shown again on the panel that returned after a rotation. If it cannot be attached
     * there, the safe default applies (Continue recording), never an interruption.
     */
    private fun showRecordingPrompt(reopen: Boolean = false) {
        if (recordingPrompt?.isShowing == true || !takeActive || finishing) return
        if (captureEnded()) { interruptTake(); return }
        val token = panel?.windowToken ?: return
        val generation = takeGeneration
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.discard_take_title)
            .setMessage(R.string.discard_take_recording)
            .setNegativeButton(R.string.continue_recording) { _, _ -> }
            .setNeutralButton(R.string.finish_transcription) { _, _ ->
                if (generation == takeGeneration && takeActive && !finishing) {
                    if (captureEnded()) interruptTake() else stopAndInsert()
                }
            }
            .setPositiveButton(R.string.discard_take) { _, _ ->
                if (generation == takeGeneration && takeActive && !finishing) {
                    if (captureEnded()) { interruptTake(); return@setPositiveButton }
                    val token = recordingToken
                    take?.cancel(onDiscarded = { deleted ->
                        if (generation == takeGeneration && deleted && session.matches(token)) returnToKeyboard()
                    })
                    showIdle()
                }
            }
            .create()
        dialog.setOnDismissListener { if (recordingPrompt === dialog) { recordingPrompt = null; openPrompt = null } }
        if (!attachPrompt(dialog, token, Prompt.RECORDING) && !reopen) interruptTake()
    }

    /** Shows [dialog] attached to the panel's window; false if that window is gone. */
    private fun attachPrompt(dialog: AlertDialog, token: android.os.IBinder, kind: Prompt): Boolean {
        recordingPrompt = dialog
        openPrompt = kind
        dialog.window?.apply {
            setType(WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG)
            attributes = attributes.apply { this.token = token }
            addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        }
        return try {
            dialog.show()
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).apply { isFocusableInTouchMode = true; requestFocus() }
            true
        } catch (_: WindowManager.BadTokenException) {
            recordingPrompt = null
            openPrompt = null
            false
        }
    }

    /**
     * Holds the result before dispatch while open. [reopen]: shown again after a rotation; if it
     * cannot be attached, the safe default applies (Keep finishing), never an interruption.
     */
    private fun showFinishingPrompt(reopen: Boolean = false) {
        if (recordingPrompt?.isShowing == true || !takeActive) return
        val current = take ?: return
        val token = panel?.windowToken ?: return
        if (!current.holdDelivery()) return
        val generation = takeGeneration
        var discarded = false
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.discard_transcript_title)
            .setNegativeButton(R.string.keep_finishing) { _, _ -> }
            .setPositiveButton(R.string.discard_take) { _, _ ->
                if (generation == takeGeneration && take === current) {
                    discarded = true
                    val editor = recordingToken
                    current.cancel(onDiscarded = { deleted ->
                        if (generation == takeGeneration && deleted && session.matches(editor)) returnToKeyboard()
                    })
                    showIdle()
                }
            }.create()
        dialog.setOnDismissListener {
            if (recordingPrompt === dialog) { recordingPrompt = null; openPrompt = null }
            // Closed for a rotation, or the rotation still holds the result: nothing is released.
            val moved = movedPrompt === dialog
            if (moved) movedPrompt = null
            if (!discarded && !moved && !(take === current && deliveryHeldForRotation)) current.resumeDelivery()
        }
        if (!attachPrompt(dialog, token, Prompt.FINISHING) && !reopen) interruptTake()
    }

    private fun cancelAndReturn() {
        if (takeActive) { if (finishing) showFinishingPrompt() else showRecordingPrompt(); return }
        // Idle (no take, or it already ended): Back belongs to the live editor session.
        val token = recordingToken ?: session.token()
        discardTake("by user"); showIdle(); if (session.matches(token)) returnToKeyboard()
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
        status?.setText(label)
    }

    private fun setMicrophone(enabled: Boolean, label: Int) {
        microphoneEnabled = enabled
        microphoneLabel = label
        microphone?.apply {
            isEnabled = enabled
            alpha = if (enabled) 1f else 0.55f
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
        previewText = text.takeLast(240)
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
