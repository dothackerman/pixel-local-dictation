package ch.og.dictation

import ch.og.dictation.audio.MicrophoneCapture
import ch.og.dictation.audio.MicrophoneOwner
import ch.og.dictation.audio.RecordingFeedback
import ch.og.dictation.diagnostics.BenchmarkStore
import ch.og.dictation.diagnostics.RotationEvent
import ch.og.dictation.diagnostics.RotationTrace
import ch.og.dictation.diagnostics.StartupTrace
import ch.og.dictation.history.HistoryIo
import ch.og.dictation.history.HistoryNotice
import ch.og.dictation.history.HistoryTakeOutput
import ch.og.dictation.history.copyTranscript
import ch.og.dictation.history.historyNotices
import ch.og.dictation.ime.PermissionActivity
import ch.og.dictation.ime.editorPort
import ch.og.dictation.ime.fieldIdentityOf
import ch.og.dictation.ime.retentionOf
import ch.og.dictation.panel.PanelFrame
import ch.og.dictation.panel.renderPanel
import ch.og.dictation.recognition.SAMPLE_RATE
import ch.og.dictation.take.EditorEvent
import ch.og.dictation.take.EditorRetention
import ch.og.dictation.take.RotationCounter
import ch.og.dictation.take.TakeCapture
import ch.og.dictation.take.TakeController
import ch.og.dictation.take.TakeDiagnostics
import ch.og.dictation.take.TakeHost
import ch.og.dictation.take.TakeLab
import ch.og.dictation.take.TakeLog
import ch.og.dictation.take.TakeOpener
import ch.og.dictation.take.TakeOutput
import ch.og.dictation.take.TakeResult
import ch.og.dictation.take.TakeScheduler
import ch.og.dictation.take.TakeSession
import ch.og.dictation.take.TimelineObserver
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
import java.util.concurrent.Executor
import org.json.JSONObject

/**
 * A voice-only input method FUTO hands off to. The take controller holds all take state and
 * decides; this service forwards Android's callbacks to it in their order, counts rotations,
 * builds the panel and carries out the effects the controller asks for (ADR-010).
 */
class DictationImeService : InputMethodService() {
    companion object {
        /** The second read-back after a write that was not seen in the field at once (ADR-010). */
        internal const val CONFIRM_DELAY_MS = TakeController.CONFIRM_DELAY_MS
    }
    private val tag = "DictationIme"
    /** The frame the current panel shows; null for a panel not rendered yet. */
    private var shownFrame: PanelFrame? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    /** Take diagnostics: the controller traces through it; this service attaches the trace. */
    private val diagnostics = TakeDiagnostics()
    private var rotationView = 0L
    /** Last observed display rotation and the number of rotation changes this service saw. */
    private var displayRotation: Int? = null
    private var rotationChanges = 0L
    /** Orientation of the last configuration this service saw; a change counts as a rotation. */
    private var configurationOrientation: Int? = null
    private var rotationsAtConfiguration = 0L
    /** Android may silence a client whose window is hidden; a silenced hold cannot keep listening. */
    private val recordingCallback = object : android.media.AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<android.media.AudioRecordingConfiguration>?) {
            controller.recordingConfigChanged { configs.orEmpty().any { it.isClientSilenced } }
        }
    }
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

    /** The take host: the effects the take controller asks for, carried out here. */
    private val host = object : TakeHost {
        /** The only way this service changes panel views. */
        override fun show(frame: PanelFrame, feedback: RecordingFeedback?) {
            val view = panel ?: return
            shownFrame = renderPanel(view, frame, shownFrame, feedback)
        }
        override fun returnToKeyboard(): Boolean {
            val switched = switchToPreviousInputMethod()
            if (!switched) {
                Log.w(tag, "no previous IME to return to"); requestHideSelf(0)
            }
            return switched
        }
        override fun requestPanel() { requestShowSelf(0) }
        override fun copy(text: String) { copyTranscript(this@DictationImeService, text) }
        override fun askPermission() {
            startActivity(Intent(this@DictationImeService, PermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        override fun historyNotice(notice: HistoryNotice) { historyNotices(this@DictationImeService).record(notice) }
        override fun hasMicPermission() =
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        override fun captureSilenced() = runCatching {
            getSystemService(android.media.AudioManager::class.java)?.activeRecordingConfigurations.orEmpty().any { it.isClientSilenced }
        }.getOrDefault(false)
        override fun currentField() = fieldIdentityOf(currentInputEditorInfo)
    }

    /** The take scheduler on the main thread's queue. */
    private val scheduler = object : TakeScheduler {
        override fun post(action: Runnable) { mainHandler.post(action) }
        override fun postDelayed(delayMs: Long, action: Runnable): TakeScheduler.Cancellable {
            mainHandler.postDelayed(action, delayMs)
            return TakeScheduler.Cancellable { mainHandler.removeCallbacks(action) }
        }
        override val executor = Executor { mainHandler.post(it) }
        override fun checkThread(what: String) { check(Looper.myLooper() == Looper.getMainLooper()) { "$what off the main thread" } }
    }

    /** The take opener: one take's parts from the Wiring read at its start (its History root above all). */
    private val opener = TakeOpener {
        val wiring = this.wiring
        object : TakeOpener.Opening {
            override fun liveCheck() = wiring.engine.liveReceipt() != null
            override fun capture(onStarted: () -> Unit, feedback: RecordingFeedback, onInterrupted: () -> Unit) =
                MicrophoneCapture(onStarted = onStarted, feedback = feedback, onInterrupted = onInterrupted, recorder = wiring.recorderFactory())
            override fun open(
                output: TakeOutput, capture: TakeCapture, executor: Executor, observer: TimelineObserver?, retentionEligible: Boolean,
                notice: (HistoryNotice) -> Unit, attached: (history: HistoryTakeOutput, receipt: () -> JSONObject?) -> Unit,
            ): TakeSession {
                val historyRoot = wiring.storage.historyRoot
                return wiring.engine.openTake { runtime, receipt ->
                    val history = HistoryTakeOutput(output,
                        { HistoryIo.store(historyRoot) }, HistoryIo.executor,
                        { notice(HistoryNotice.SAVE) }, delivery = executor,
                        onDeleteFailure = { notice(HistoryNotice.DELETE) },
                        onReceiptFailure = { notice(HistoryNotice.RECEIPT) },
                        onTakeFailure = { hasPrefix -> if (hasPrefix) notice(HistoryNotice.INCOMPLETE) },
                        timeline = observer)
                    attached(history, receipt)
                    runtime.open(capture, history, executor, observer,
                        retentionEligible = retentionEligible).also { it.start() }
                }
            }
        }
    }

    /** Rotation counting stays here; the controller reads it at its two points and for each traced event. */
    private val rotations = object : RotationCounter {
        override fun observe() = observeRotation()
        override val changes: Long get() = rotationChanges
        override val view: Long? get() = rotationView.takeIf { it > 0 }
    }

    /** The lab build's side of a take: the startup trace and the recording save. */
    private val lab = object : TakeLab {
        override val startup: StartupTrace? get() = this@DictationImeService.startup
        override var startupTake: String?
            get() = this@DictationImeService.startupTake
            set(value) { this@DictationImeService.startupTake = value }
        override fun beginStartupTake() = this@DictationImeService.startup?.beginTake(wiring.engine.modelReadyNs != null)
        override fun observeGeometry(take: String?) { this@DictationImeService.observeGeometry(take) }
        override fun recordingEnabled(liveCheck: Boolean) =
            BenchmarkStore.recordingEnabled(BenchmarkStore.isEnabled(this@DictationImeService), liveCheck)
        override fun saveRecording(enabled: Boolean, pcm: ByteArray, result: TakeResult) { saveBenchmarkTake(enabled, pcm, result) }
    }

    private val log = object : TakeLog {
        override fun info(message: String) { Log.i(tag, message) }
        override fun warn(message: String) { Log.w(tag, message) }
        override fun error(message: String) { Log.e(tag, message) }
    }

    /** All take state and decisions; this service forwards its events and carries out the effects. */
    private val controller = TakeController(host, scheduler, opener, editorPort { currentInputConnection }, diagnostics, rotations, lab, log)

    /** A locked phone must not keep listening. */
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            rotationEvent(RotationEvent.SCREEN_OFF)
            controller.screenOff()
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
        MicrophoneOwner.observe(controller.microphoneOwnerListener)
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
        controller.panelCreated() // A rotation cancels the Back warning.
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
        v.findViewById<ImageButton>(R.id.microphone).setOnClickListener { controller.microphonePressed() }
        v.findViewById<ImageButton>(R.id.back).setOnClickListener { controller.cancelAndReturn() }
        v.findViewById<View>(R.id.choice_safe).setOnClickListener { controller.onSafeAction() }
        v.findViewById<View>(R.id.choice_stop).setOnClickListener { controller.chooseStop() }
        v.findViewById<View>(R.id.choice_discard).setOnClickListener { controller.onDiscardAction() }
        controller.render()
        startup?.event("inputViewCreateEnd")
        return v
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        attachRotationTrace()
        rotationEvent(RotationEvent.INPUT_START, restarting)
        super.onStartInput(attribute, restarting)
        observeGeometry()
        Log.i(tag, "input start: restarting=$restarting take=${controller.takeActive}")
        val editorRetention = retentionOf(attribute)
        controller.inputStart(editorRetention, attribute?.packageName, fieldIdentityOf(attribute))
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
        controller.inputViewStart()
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
        geometryTake = null
        geometryUntilNs = 0L
        controller.inputViewHidden()
        Log.i(tag, "input view finished: finishingInput=$finishingInput take=${controller.takeActive}")
        controller.editorEvent(EditorEvent.INPUT_VIEW_FINISH)
        super.onFinishInputView(finishingInput)
        rotationEvent(RotationEvent.INPUT_VIEW_FINISH_DONE, finishingInput)
    }

    override fun onFinishInput() {
        rotationEvent(RotationEvent.INPUT_FINISH)
        geometryTake = null
        geometryUntilNs = 0L
        controller.inputFinish()
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
        controller.settleAfterRotation()
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
        controller.windowHidden()
        super.onWindowHidden()
    }

    private fun rotationEvent(event: RotationEvent, flag: Boolean? = null, configDiff: Int? = null, handledConfig: Int? = null,
                              retention: EditorRetention? = null) =
        controller.rotationEvent(event, flag, configDiff, handledConfig, retention)

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
            controller.settleAfterRotation()
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
        controller.beginDestroy()
        getSystemService(android.media.AudioManager::class.java)?.unregisterAudioRecordingCallback(recordingCallback)
        MicrophoneOwner.remove(controller.microphoneOwnerListener)
        unregisterReceiver(screenOff)
        controller.finishDestroy()
        super.onDestroy()
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

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && controller.backKey()) return true
        return super.onKeyDown(keyCode, event)
    }
}
