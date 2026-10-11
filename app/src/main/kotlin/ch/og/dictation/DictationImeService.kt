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
    private val recorder = Recorder()
    private var status: TextView? = null
    private var preview: TextView? = null
    private var recordBtn: Button? = null
    private var stopBtn: Button? = null
    private var startedAt = 0L
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
    private var transcriber: TakeTranscriber? = null
    /** Audio of a take that is being finished after Stop; wiped when the take ends either way. */
    private var finishingPcm: ByteArray? = null

    /** A take exists from the start of recording until its text is committed or dropped. */
    private val takeActive: Boolean get() = recorder.isRunning || finishingPcm != null

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
        super.onFinishInput()
    }

    override fun onDestroy() { unregisterReceiver(screenOff); discardTake("service destroyed"); super.onDestroy() }

    private fun startRecording() {
        if (takeActive) return
        if (!hasMicPermission()) { status?.setText(R.string.status_no_permission); return }
        try {
            val segmenter = Engine.segmenter(this).also { it.reset() }
            val take = TakeTranscriber({ Engine.recognizer(this) }, segmenter, Engine.decodeQueue, mainExecutor, Engine.variant.post) { text ->
                showPreview(text)
            }
            recorder.start(take::feed)
            transcriber = take
            recordingToken = session.token()
            startedAt = SystemClock.elapsedRealtime()
            status?.setText(R.string.status_recording)
            preview?.text = ""
            recordBtn?.isEnabled = false; stopBtn?.isEnabled = true
            Log.i(tag, "recording started")
        } catch (e: Exception) {
            Log.e(tag, "start failed", e); status?.text = e.message
        }
    }

    private fun stopAndInsert() {
        if (!recorder.isRunning) return
        val take = transcriber ?: return
        val pcm = recorder.stop()
        val stoppedAt = SystemClock.elapsedRealtime()
        val recordingMs = stoppedAt - startedAt
        val seconds = pcm.size / 2.0 / Recorder.SAMPLE_RATE
        Log.i(tag, "stopped after $recordingMs ms, %.2f s of audio".format(seconds))
        val token = recordingToken
        finishingPcm = pcm
        status?.setText(R.string.status_finishing)
        stopBtn?.isEnabled = false
        take.finish { result ->
            val waitMs = SystemClock.elapsedRealtime() - stoppedAt
            val text = result.getOrNull()
            Log.i(tag, "final after $waitMs ms: ${text?.length ?: -1} characters")
            if (saveTake) saveBenchmarkTake(pcm, text, seconds, recordingMs, waitMs)
            endTake()
            if (text == null) {
                Log.e(tag, "recognition failed", result.exceptionOrNull())
                showIdle(); status?.setText(R.string.status_failed)
                return@finish
            }
            val done = text.isEmpty() || commitForSession(token, text)
            showIdle()
            if (done) returnToKeyboard()
        }
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
        ic.commitText(lead + text, 1)
        return true
    }

    private fun saveBenchmarkTake(pcm: ByteArray, text: String?, audioSeconds: Double, recordingMs: Long, stopToFinalMs: Long) {
        try {
            val json = JSONObject()
                .put("app", packageManager.getPackageInfo(packageName, 0).versionName)
                .put("recognizer", "parakeet-v3-matmul8 sherpa-onnx-1.13.8")
                .put("text", text ?: JSONObject.NULL)
                .put("audioSeconds", audioSeconds)
                .put("recordingMs", recordingMs)
                .put("stopToFinalMs", stopToFinalMs)
            val name = BenchmarkStore.of(this).save(pcm, Recorder.SAMPLE_RATE, json.toString(2))
            Log.i(tag, "benchmark take saved: $name")
        } catch (e: Exception) {
            Log.e(tag, "benchmark save failed", e)
        }
    }

    /** Stops the microphone, drops queued decoding and wipes the audio. Nothing is committed afterwards. */
    private fun endTake() {
        recorder.cancel()
        transcriber?.cancel(); transcriber = null
        finishingPcm?.fill(0); finishingPcm = null
        recordingToken = null
    }

    private fun discardTake(reason: String) {
        if (!takeActive) return
        endTake()
        Log.i(tag, "cancelled: $reason")
    }

    /**
     * The system took the field away mid-take. Drop it and hand back to the keyboard,
     * so the panel never reappears recording without the microphone key.
     */
    private fun discardAndReturn(reason: String) {
        discardTake(reason)
        showIdle()
        returning = true
        mainHandler.post { returnToKeyboard(); returning = false }
    }

    private fun cancelAndReturn() {
        discardTake("by user"); showIdle(); returnToKeyboard()
    }

    private fun returnToKeyboard() {
        if (!switchToPreviousInputMethod()) {
            Log.w(tag, "no previous IME to return to"); requestHideSelf(0)
        }
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
