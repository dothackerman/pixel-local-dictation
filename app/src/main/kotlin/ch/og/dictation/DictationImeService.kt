package ch.og.dictation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView

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

    override fun onCreateInputView(): View {
        val v = layoutInflater.inflate(R.layout.ime, null)
        status = v.findViewById(R.id.status)
        recordBtn = v.findViewById<Button>(R.id.record).apply { setOnClickListener { startRecording() } }
        stopBtn = v.findViewById<Button>(R.id.stop).apply { setOnClickListener { stopAndInsert() } }
        v.findViewById<Button>(R.id.cancel).setOnClickListener { cancelAndReturn() }
        v.findViewById<Button>(R.id.back).setOnClickListener { returnToKeyboard() }
        return v
    }

    override fun onStartInputView(info: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // Apps call restartInput on text changes; keep a live recording untouched.
        if (recorder.isRunning) return
        showIdle()
        if (hasMicPermission()) startRecording() else requestMicPermission()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        // Focus moved or keyboard hidden: never insert late, never keep the mic.
        if (recorder.isRunning) { recorder.cancel(); Log.i(tag, "cancelled: input view finished") }
        super.onFinishInputView(finishingInput)
    }

    override fun onDestroy() { recorder.cancel(); super.onDestroy() }

    private fun startRecording() {
        if (recorder.isRunning) return
        if (!hasMicPermission()) { status?.setText(R.string.status_no_permission); return }
        try {
            recorder.start()
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
        pcm.fill(0)
        val ic = currentInputConnection
        if (ic == null) { Log.w(tag, "no input connection; dropping result"); showIdle(); return }
        ic.commitText("[recorded %.1f s] ".format(seconds), 1)
        showIdle()
        returnToKeyboard()
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
