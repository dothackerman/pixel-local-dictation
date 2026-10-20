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
import android.widget.Button
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
    private var recordBtn: Button? = null
    private var stopBtn: Button? = null
    private var banner: View? = null
    /** Decided when the panel opens, so the banner always matches what happens to the take. */
    private var saveTake = false
    private val session = InputSession()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { mainHandler.post(it) }
    /** Set while a hand-back to the keyboard is pending, so the panel does not start a new take. */
    private var returning = false
    /** Session that started the current take; text goes there or nowhere. */
    private var recordingToken: Long? = null
    private var take: DictationSession? = null

    /** A take exists from the start of recording until its text is committed or dropped. */
    private val takeActive: Boolean get() = take?.active == true

    /** A locked phone must not keep listening. */
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (takeActive) discardAndReturn("screen off")
        }
    }

    override fun onCreate() {
        super.onCreate()
        registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), Context.RECEIVER_NOT_EXPORTED)
        Engine.warmUp(this)
    }

    override fun onCreateInputView(): View {
        val v = layoutInflater.inflate(R.layout.ime, null)
        status = v.findViewById(R.id.status)
        preview = v.findViewById(R.id.preview)
        banner = v.findViewById(R.id.benchmark_banner)
        recordBtn = v.findViewById<Button>(R.id.record).apply { setOnClickListener { startRecording() } }
        stopBtn = v.findViewById<Button>(R.id.stop).apply { setOnClickListener { stopAndInsert() } }
        v.findViewById<Button>(R.id.cancel).setOnClickListener { cancelAndReturn() }
        v.findViewById<Button>(R.id.back).setOnClickListener { cancelAndReturn() }
        return v
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
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
        saveTake = BenchmarkStore.isEnabled(this)
        banner?.visibility = if (saveTake) View.VISIBLE else View.GONE
        showIdle()
        if (hasMicPermission()) startRecording() else requestMicPermission()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        // Focus moved or keyboard hidden: never insert late, never keep the mic.
        session.invalidate()
        Log.i(tag, "input view finished: finishingInput=$finishingInput take=$takeActive")
        if (takeActive) discardAndReturn("input view finished")
        super.onFinishInputView(finishingInput)
    }

    override fun onFinishInput() {
        session.invalidate()
        if (takeActive) discardAndReturn("input finished")
        super.onFinishInput()
    }

    override fun onDestroy() { unregisterReceiver(screenOff); discardTake("service destroyed"); super.onDestroy() }

    private fun startRecording() {
        if (takeActive) return
        if (!hasMicPermission()) { status?.setText(R.string.status_no_permission); return }
        try {
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
                    showIdle()
                    if (result.outcome == TakeOutcome.FAILED || result.outcome == TakeOutcome.COMMIT_REJECTED) {
                        Log.e(tag, "take failed: ${result.outcome}", result.failure)
                        status?.setText(R.string.status_failed)
                    }
                }
            }
            status?.setText(R.string.status_recording)
            preview?.text = ""
            recordBtn?.isEnabled = false; stopBtn?.isEnabled = true
            take = Engine.runtime(this).open(MicrophoneCapture(), output, mainExecutor).also { it.start() }
            Log.i(tag, "recording started")
        } catch (e: Exception) {
            Log.e(tag, "start failed", e); status?.text = e.message
        }
    }

    private fun stopAndInsert() {
        if (!takeActive) return
        status?.setText(R.string.status_finishing)
        stopBtn?.isEnabled = false
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
        status?.setText(R.string.status_idle)
        preview?.text = ""
        recordBtn?.isEnabled = true; stopBtn?.isEnabled = false
    }

    /** The end of the text so far; the view is a few lines high. */
    private fun showPreview(text: String) { preview?.text = text.takeLast(240) }

    private fun hasMicPermission() =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private var permissionAsked = false

    /** Ask once per service lifetime; after "don't ask again" we only show the message. */
    private fun requestMicPermission() {
        status?.setText(R.string.status_no_permission)
        if (permissionAsked) return
        permissionAsked = true
        startActivity(Intent(this, PermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
