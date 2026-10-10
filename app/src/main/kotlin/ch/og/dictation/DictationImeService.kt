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
import org.json.JSONObject

/**
 * Milestone 1 shell: a voice-only input method FUTO can hand off to.
 * Record -> Stop inserts text into the focused field through the InputConnection,
 * then returns to the previous keyboard. No recognizer yet: inserted text is a
 * placeholder describing the capture, so insertion/cancel/return can be verified.
 */
class DictationImeService : InputMethodService() {
    private val tag = "DictationIme"
    private val recorder = Recorder()
    private var status: TextView? = null
    private var recordBtn: Button? = null
    private var stopBtn: Button? = null
    private var startedAt = 0L
    private var banner: View? = null
    /** Decided when the panel opens, so the banner always matches what happens to the take. */
    private var saveTake = false
    private val session = InputSession()
    private val mainHandler = Handler(Looper.getMainLooper())
    /** Set while a hand-back to the keyboard is pending, so the panel does not start a new take. */
    private var returning = false
    /** Session that started the current recording; text goes there or nowhere. */
    private var recordingToken: Long? = null

    /** A locked phone must not keep listening. */
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (recorder.isRunning) discardAndReturn("screen off")
        }
    }

    override fun onCreate() {
        super.onCreate()
        registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), Context.RECEIVER_NOT_EXPORTED)
    }

    override fun onCreateInputView(): View {
        val v = layoutInflater.inflate(R.layout.ime, null)
        status = v.findViewById(R.id.status)
        banner = v.findViewById(R.id.benchmark_banner)
        recordBtn = v.findViewById<Button>(R.id.record).apply { setOnClickListener { startRecording() } }
        stopBtn = v.findViewById<Button>(R.id.stop).apply { setOnClickListener { stopAndInsert() } }
        v.findViewById<Button>(R.id.cancel).setOnClickListener { cancelAndReturn() }
        v.findViewById<Button>(R.id.back).setOnClickListener { returnToKeyboard() }
        return v
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        Log.i(tag, "input start: restarting=$restarting recording=${recorder.isRunning} " +
            "type=0x%x field=%d".format(attribute?.inputType ?: 0, attribute?.fieldId ?: 0))
        // Observed 2026-10-10: a browser reports a move between two fields of one page as a
        // restart with identical field attributes, so a restart cannot be trusted to be the
        // same field. Any input start during a recording ends that recording's session.
        session.start(restarting = false)
        if (recorder.isRunning) discardAndReturn("input restarted")
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        session.resume()
        if (returning) return
        if (recorder.isRunning) {
            // The view can be restarted without a new input start; keep that recording.
            if (session.matches(recordingToken)) return
            recorder.cancel(); Log.i(tag, "cancelled: input session changed")
        }
        saveTake = BenchmarkStore.isEnabled(this)
        banner?.visibility = if (saveTake) View.VISIBLE else View.GONE
        showIdle()
        if (hasMicPermission()) startRecording() else requestMicPermission()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        // Focus moved or keyboard hidden: never insert late, never keep the mic.
        session.invalidate()
        Log.i(tag, "input view finished: finishingInput=$finishingInput recording=${recorder.isRunning}")
        if (recorder.isRunning) discardAndReturn("input view finished")
        super.onFinishInputView(finishingInput)
    }

    override fun onFinishInput() {
        session.invalidate()
        super.onFinishInput()
    }

    override fun onDestroy() { unregisterReceiver(screenOff); recorder.cancel(); super.onDestroy() }

    private fun startRecording() {
        if (recorder.isRunning) return
        if (!hasMicPermission()) { status?.setText(R.string.status_no_permission); return }
        try {
            recorder.start()
            recordingToken = session.token()
            startedAt = SystemClock.elapsedRealtime()
            status?.setText(R.string.status_recording)
            recordBtn?.isEnabled = false; stopBtn?.isEnabled = true
            Log.i(tag, "recording started")
        } catch (e: Exception) {
            Log.e(tag, "start failed", e); status?.text = e.message
        }
    }

    private fun stopAndInsert() {
        if (!recorder.isRunning) return
        val pcm = recorder.stop()
        val ms = SystemClock.elapsedRealtime() - startedAt
        val seconds = pcm.size / 2.0 / Recorder.SAMPLE_RATE
        Log.i(tag, "stopped after ${ms} ms, ${pcm.size} bytes (%.2f s)".format(seconds))
        val text = "[recorded %.1f s] ".format(seconds)
        if (saveTake) saveBenchmarkTake(pcm, text, seconds, ms)
        pcm.fill(0)
        val committed = commitForSession(recordingToken, text)
        showIdle()
        if (committed) returnToKeyboard()
    }

    /**
     * The only path that writes to a field. Commits on the main thread and only
     * while the session that started the recording is still the live one.
     */
    private fun commitForSession(token: Long?, text: String): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper()) { "commit off the main thread" }
        if (!session.matches(token)) { Log.w(tag, "input session changed; dropping result"); return false }
        val ic = currentInputConnection
        if (ic == null) { Log.w(tag, "no input connection; dropping result"); return false }
        ic.commitText(text, 1)
        return true
    }

    private fun saveBenchmarkTake(pcm: ByteArray, text: String, audioSeconds: Double, recordingMs: Long) {
        try {
            val json = JSONObject()
                .put("app", packageManager.getPackageInfo(packageName, 0).versionName)
                .put("recognizer", "none")
                .put("text", text)
                .put("audioSeconds", audioSeconds)
                .put("recordingMs", recordingMs)
            val name = BenchmarkStore.of(this).save(pcm, Recorder.SAMPLE_RATE, json.toString(2))
            Log.i(tag, "benchmark take saved: $name")
        } catch (e: Exception) {
            Log.e(tag, "benchmark save failed", e)
        }
    }

    /**
     * The system took the field away mid-recording. Drop the audio and hand back to
     * the keyboard, so the panel never reappears recording without the microphone key.
     */
    private fun discardAndReturn(reason: String) {
        recorder.cancel(); recordingToken = null
        Log.i(tag, "cancelled: $reason")
        showIdle()
        returning = true
        mainHandler.post { returnToKeyboard(); returning = false }
    }

    private fun cancelAndReturn() {
        recorder.cancel(); Log.i(tag, "cancelled by user"); showIdle(); returnToKeyboard()
    }

    private fun returnToKeyboard() {
        if (!switchToPreviousInputMethod()) {
            Log.w(tag, "no previous IME to return to"); requestHideSelf(0)
        }
    }

    private fun showIdle() {
        status?.setText(R.string.status_idle)
        recordBtn?.isEnabled = true; stopBtn?.isEnabled = false
    }

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
