package ch.og.dictation

/** Each take owns its recorder. Draining never blocks the IME main thread. */
class MicrophoneCapture(
    private val onStarted: (() -> Unit)? = null,
    private val feedback: RecordingFeedback? = null,
    private val onInterrupted: (() -> Unit)? = null,
) : TakeCapture {
    private val recorder = Recorder()
    private val lock = Any()
    override fun start(accept: (FloatArray) -> Unit) = synchronized(lock) {
        recorder.start({ samples ->
            feedback?.accept(samples)
            accept(samples)
        }, {
            feedback?.start()
            onStarted?.invoke()
        }, {
            val interrupted = feedback?.isCapturing == true
            feedback?.stop()
            if (interrupted) onInterrupted?.invoke()
        })
    }
    override fun stop(done: (Result<ByteArray>) -> Unit) {
        feedback?.stop()
        Thread({
            val result = runCatching { synchronized(lock) { recorder.stop() } }
            done(result)
        }, "capture-stop").start()
    }
    override fun cancel(done: () -> Unit) {
        feedback?.stop()
        Thread({
            try { synchronized(lock) { recorder.cancel() } } finally { done() }
        }, "capture-cancel").start()
    }
}
