package ch.og.dictation

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
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
import android.widget.ImageButton
import android.widget.TextView
import java.util.concurrent.Executor
import org.json.JSONObject

/**
 * A voice-only input method FUTO hands off to. Recording starts when the panel opens;
 * speech is decoded on the phone while recording continues and shown in the panel only.
 * Stop commits the whole text once into the field that started the take and returns to
 * the previous keyboard. Nothing is written to a field before Stop.
 */
class DictationImeService : InputMethodService() {
    private val tag = "DictationIme"
    private var status: TextView? = null
    private var preview: TextView? = null
    private var microphone: ImageButton? = null
    private var ring: RecordingRingView? = null
    private var feedback: RecordingFeedback? = null
    private var finishing = false
    private var statusText = R.string.status_opening
    private var previewText = ""
    private var microphoneEnabled = false
    private var microphoneLabel = R.string.status_opening
    private var banner: View? = null
    /** Only saving audio needs a banner; experiment receipts remain out of the normal panel. */
    private var saveTake = false
    private val session = InputSession()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { mainHandler.post(it) }
    /** Set while a hand-back to the keyboard is pending, so the panel does not start a new take. */
    private var returning = false
    /** Session that started the current take; text goes there or nowhere. */
    private var recordingToken: Long? = null
    private var take: TakeSession? = null
    private var startup: StartupTrace? = null
    private var startupTake: String? = null
    private var panel: View? = null
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
            if (takeActive) discardAndReturn("screen off")
        }
    }

    override fun onCreate() {
        val createdNs = System.nanoTime()
        val createdUptimeMs = SystemClock.uptimeMillis()
        super.onCreate()
        val superReturnedNs = System.nanoTime()
        val superReturnedUptimeMs = SystemClock.uptimeMillis()
        if (resources.getBoolean(R.bool.startup_timing)) {
            startup = StartupTrace(applicationContext).also {
                it.event("serviceCreate", ns = createdNs, uptimeMs = createdUptimeMs)
                it.event("serviceCreateSuperReturned", ns = superReturnedNs, uptimeMs = superReturnedUptimeMs)
            }
            observeGeometry()
        }
        registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), Context.RECEIVER_NOT_EXPORTED)
        val ready = Engine.warmUp(this)
        startup?.event("warmUpReturned")
        ready.whenComplete { _, failure ->
            startup?.event(if (failure == null) "modelReady" else "modelFailed",
                ns = Engine.modelReadyNs ?: System.nanoTime())
        }
        startup?.event("serviceCreateEnd")
    }

    override fun onCreateInputView(): View {
        startup?.event("inputViewCreateBegin")
        val v = layoutInflater.inflate(R.layout.ime, null)
        startup?.event("inputViewInflated")
        panel = v
        observeGeometry(startupTake)
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
        preview = v.findViewById<TextView>(R.id.preview).apply { text = previewText }
        showStatus(statusText)
        banner = v.findViewById(R.id.benchmark_banner)
        ring = v.findViewById<RecordingRingView>(R.id.recording_ring).also { it.follow(feedback) }
        microphone = v.findViewById<ImageButton>(R.id.microphone).apply {
            setOnClickListener { if (takeActive) stopAndInsert() else startRecording() }
        }
        setMicrophone(microphoneEnabled, microphoneLabel)
        v.findViewById<ImageButton>(R.id.back).setOnClickListener { cancelAndReturn() }
        startup?.event("inputViewCreateEnd")
        return v
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        observeGeometry()
        Log.i(tag, "input start: restarting=$restarting take=$takeActive " +
            "type=0x%x field=%d".format(attribute?.inputType ?: 0, attribute?.fieldId ?: 0))
        // Observed 2026-10-10: a browser reports a move between two fields of one page as a
        // restart with identical field attributes, so a restart cannot be trusted to be the
        // same field. Any input start during a take ends that take's session.
        session.start(restarting = false)
        if (takeActive) discardAndReturn("input restarted")
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        session.resume()
        if (returning) return
        if (takeActive) {
            // The view can be restarted without a new input start; keep that take.
            if (session.matches(recordingToken)) return
            discardTake("input session changed")
        }
        val liveCheck = Engine.liveReceipt() != null
        // The supervised live check stores timing/identity receipts only, never new recordings.
        saveTake = !liveCheck && BenchmarkStore.isEnabled(this)
        startupTake = startup?.beginTake(Engine.modelReadyNs != null)
        observeGeometry(startupTake)
        banner?.visibility = if (saveTake) View.VISIBLE else View.GONE
        showIdle()
        if (hasMicPermission()) startRecording() else requestMicPermission()
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
        ring?.follow(null)
        geometryTake = null
        geometryUntilNs = 0L
        // Focus moved or keyboard hidden: never insert late, never keep the mic.
        session.invalidate()
        Log.i(tag, "input view finished: finishingInput=$finishingInput take=$takeActive")
        if (takeActive) discardAndReturn("input view finished")
        super.onFinishInputView(finishingInput)
    }

    override fun onFinishInput() {
        geometryTake = null
        geometryUntilNs = 0L
        session.invalidate()
        if (takeActive) discardAndReturn("input finished")
        super.onFinishInput()
    }

    override fun onDestroy() {
        unregisterReceiver(screenOff)
        discardTake("service destroyed")
        feedback?.stop()
        ring?.follow(null)
        super.onDestroy()
    }

    private fun startRecording() {
        if (takeActive) return
        if (!hasMicPermission()) {
            showStatus(R.string.status_no_permission)
            setMicrophone(false, R.string.status_no_permission)
            return
        }
        if (startupTake == null) startupTake = startup?.beginTake(Engine.modelReadyNs != null)
        try {
            val liveTakeId = startupTake
            val token = session.token()
            recordingToken = token
            val save = saveTake
            val output = object : TakeOutput {
                override fun preview(text: String) = showPreview(text)
                override fun commit(text: String) = commitForSession(token, text)
                override fun returnToKeyboard(): Boolean {
                    // Switching the IME can synchronously invalidate input. The result has
                    // already been submitted; do not turn that hand-back into cancellation.
                    returning = true
                    return try { this@DictationImeService.returnToKeyboard() }
                    finally { mainHandler.post { returning = false } }
                }
                override fun finished(result: TakeResult, pcm: ByteArray) {
                    Engine.liveReceipt()?.let { startup?.liveResult(liveTakeId, it, result) }
                    if (save && result.outcome !in setOf(TakeOutcome.CANCELLED, TakeOutcome.INPUT_CHANGED)) {
                        val stopped = result.eventsNs["stopRequested"]
                        val final = result.eventsNs["finalReady"]
                        val wait = if (stopped != null && final != null) (final - stopped) / 1_000_000 else 0
                        val started = result.eventsNs["captureStart"]
                        val recordingMs = if (started != null && stopped != null) (stopped - started) / 1_000_000 else 0
                        saveBenchmarkTake(pcm, result.text, pcm.size / 2.0 / Recorder.SAMPLE_RATE,
                            recordingMs, wait, result)
                    }
                    recordingToken = null
                    startupTake = null
                    showIdle()
                    if (result.outcome == TakeOutcome.FAILED || result.outcome == TakeOutcome.COMMIT_REJECTED) {
                        Log.e(tag, "take failed: ${result.outcome}", result.failure)
                        showStatus(R.string.status_failed)
                        setMicrophone(true, R.string.retry_recording)
                    }
                }
            }
            showStatus(R.string.status_opening)
            showPreview("")
            finishing = false
            setMicrophone(false, R.string.status_opening)
            val currentFeedback = RecordingFeedback()
            feedback?.stop()
            feedback = currentFeedback
            ring?.follow(currentFeedback)
            val trace = startup
            val traceTake = startupTake
            val capture = MicrophoneCapture(onStarted = {
                trace?.event("recordingStarted", traceTake)
                mainHandler.post {
                    if (feedback === currentFeedback && takeActive && !finishing) {
                        showStatus(R.string.status_recording)
                        setMicrophone(true, R.string.stop_recording)
                        ring?.invalidate()
                    }
                }
            }, feedback = currentFeedback, onInterrupted = {
                mainHandler.post {
                    if (feedback === currentFeedback && takeActive && !finishing) {
                        // Preserve the existing ability to finish the audio captured before interruption.
                        showStatus(R.string.status_capture_ended)
                        setMicrophone(true, R.string.finish_transcription)
                        ring?.follow(null)
                    }
                }
            })
            val observer = if (trace == null) null else TimelineObserver { event ->
                if (event.phase in setOf("captureStart", "firstAudio", "takeEnded")) {
                    trace.event(event.phase, traceTake, event.ns)
                }
            }
            take = Engine.runtime(this).open(capture, output, mainExecutor, observer).also { it.start() }
            Log.i(tag, "recording started")
        } catch (e: Exception) {
            startup?.event("recordingFailed", startupTake)
            startupTake = null
            feedback?.stop()
            showIdle()
            Log.e(tag, "start failed", e)
            showStatus(R.string.status_failed)
            setMicrophone(true, R.string.retry_recording)
        }
    }

    private fun stopAndInsert() {
        if (!takeActive || finishing) return
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
    private fun commitForSession(token: Long?, text: String): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper()) { "commit off the main thread" }
        if (!session.matches(token)) { Log.w(tag, "input session changed; dropping result"); return false }
        val ic = currentInputConnection
        if (ic == null) { Log.w(tag, "no input connection; dropping result"); return false }
        // Dictating after existing text: separate with a space unless there is one already.
        val before = ic.getTextBeforeCursor(1, 0)
        val lead = if (!before.isNullOrEmpty() && !before[0].isWhitespace()) " " else ""
        return ic.commitText(lead + text, 1)
    }

    private fun saveBenchmarkTake(pcm: ByteArray, text: String?, audioSeconds: Double, recordingMs: Long, stopToFinalMs: Long, result: TakeResult) {
        try {
            val json = JSONObject()
                .put("app", packageManager.getPackageInfo(packageName, 0).versionName)
                .put("recognizer", "parakeet-v3-matmul8 sherpa-onnx-1.13.8")
                .put("text", text ?: JSONObject.NULL)
                .put("audioSeconds", audioSeconds)
                .put("recordingMs", recordingMs)
                .put("stopToFinalMs", stopToFinalMs)
                .put("timingProtocol", "dictation-session-v1")
                .put("eventsNs", JSONObject(result.eventsNs))
                .put("outcome", result.outcome.name)
                .put("commitAccepted", result.commitAccepted ?: JSONObject.NULL)
                .put("returnAccepted", result.returnAccepted ?: JSONObject.NULL)
            val name = BenchmarkStore.of(this).save(pcm, Recorder.SAMPLE_RATE, json.toString(2))
            Log.i(tag, "benchmark take saved: $name")
        } catch (e: Exception) {
            Log.e(tag, "benchmark save failed", e)
        }
    }

    private fun discardTake(reason: String) {
        if (!takeActive) return
        take?.cancel(inputChanged = reason != "by user")
        Log.i(tag, "cancelled: $reason")
    }

    /**
     * The system took the field away mid-take. Drop it and hand back to the keyboard,
     * so the panel never reappears recording without the microphone key.
     */
    private fun discardAndReturn(reason: String) {
        if (returning) return
        discardTake(reason)
        showIdle()
        returning = true
        mainHandler.post { returnToKeyboard(); returning = false }
    }

    private fun cancelAndReturn() {
        discardTake("by user"); showIdle(); returnToKeyboard()
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
        showStatus(R.string.status_idle)
        showPreview("")
        setMicrophone(true, R.string.start_recording)
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
    private fun showPreview(text: String) {
        previewText = text.takeLast(240)
        preview?.text = previewText
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
