package ch.og.dictation.audio

import ch.og.dictation.take.CaptureReleaseUnconfirmed
import ch.og.dictation.take.TakeCapture
/** Each take owns its recorder. Draining never blocks the IME main thread. */
class MicrophoneCapture(
    private val onStarted: (() -> Unit)? = null,
    private val feedback: RecordingFeedback? = null,
    private val onInterrupted: (() -> Unit)? = null,
    private val recorder: Recorder = Recorder(),
) : TakeCapture {
    private val lock = Any()
    override fun start(accept: (FloatArray) -> Unit) = synchronized(lock) {
        check(MicrophoneOwner.acquire(this)) { "microphone already owned" }
        try { recorder.start({ samples ->
            feedback?.accept(samples)
            accept(samples)
        }, {
            feedback?.start()
            onStarted?.invoke()
        }, {
            feedback?.stop()
            onInterrupted?.invoke()
        }) } catch (failure: Throwable) {
            if (!recorder.isRunning) MicrophoneOwner.released(this)
            throw failure
        }
    }
    fun setReleaseRetry(action: () -> Unit) = MicrophoneOwner.setRetry(this, action)
    /**
     * Pause and resume run on the caller's thread (the IME main thread): stopping a platform
     * recording and joining its read returns within milliseconds. The microphone stays owned by
     * this capture throughout, so no other capture can start while paused (ADR-010).
     */
    override fun pause() {
        synchronized(lock) {
            feedback?.pause()
            recorder.pause()
        }
    }
    override fun resume() {
        synchronized(lock) {
            recorder.resume()
            feedback?.resume()
        }
    }
    override fun stop(done: (Result<ByteArray>) -> Unit) {
        feedback?.stop()
        Thread({
            val result = synchronized(lock) {
                val stopped = runCatching { recorder.stop() }
                if (recorder.isRunning) Result.failure(CaptureReleaseUnconfirmed()) else stopped
            }
            if (result.exceptionOrNull() !is CaptureReleaseUnconfirmed) MicrophoneOwner.released(this)
            runCatching { done(result) }
            if (result.exceptionOrNull() is CaptureReleaseUnconfirmed) MicrophoneOwner.failure(this)
        }, "capture-stop").start()
    }
    override fun cancel(done: () -> Unit) {
        cancelWithResult { if (it.isSuccess) done() }
    }
    override fun cancelWithResult(done: (Result<Unit>) -> Unit) {
        feedback?.stop()
        Thread({
            val result = synchronized(lock) {
                val stopped = runCatching { recorder.cancel() }
                if (recorder.isRunning) Result.failure(CaptureReleaseUnconfirmed()) else stopped
            }
            // A stop error with confirmed release still drains ownership.
            if (result.exceptionOrNull() !is CaptureReleaseUnconfirmed) MicrophoneOwner.released(this)
            runCatching { done(if (result.exceptionOrNull() is CaptureReleaseUnconfirmed) result else Result.success(Unit)) }
            if (result.exceptionOrNull() is CaptureReleaseUnconfirmed) MicrophoneOwner.failure(this)
        }, "capture-cancel").start()
    }
}
