package ch.og.dictation

/** Each take owns its recorder. Draining never blocks the IME main thread. */
class MicrophoneCapture : TakeCapture {
    private val recorder = Recorder()
    private val lock = Any()
    override fun start(accept: (FloatArray) -> Unit) = synchronized(lock) { recorder.start(accept) }
    override fun stop(done: (Result<ByteArray>) -> Unit) {
        Thread({
            val result = runCatching { synchronized(lock) { recorder.stop() } }
            done(result)
        }, "capture-stop").start()
    }
    override fun cancel(done: () -> Unit) {
        Thread({
            try { synchronized(lock) { recorder.cancel() } } finally { done() }
        }, "capture-cancel").start()
    }
}
