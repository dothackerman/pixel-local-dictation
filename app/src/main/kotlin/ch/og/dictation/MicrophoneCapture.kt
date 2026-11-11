package ch.og.dictation

/** Real microphone ownership survives IME service replacement. */
object MicrophoneOwner {
    data class State(val occupied: Boolean, val releaseFailed: Boolean, val explicitStartRequired: Boolean)
    private var owner: MicrophoneCapture? = null
    private var failed = false
    private var retry: (() -> Unit)? = null
    private var explicitStart = false
    private val listeners = mutableSetOf<(State) -> Unit>()

    fun state(): State = synchronized(this) { State(owner != null, failed, explicitStart) }
    fun observe(listener: (State) -> Unit) { synchronized(this) { listeners.add(listener) }; listener(state()) }
    fun remove(listener: (State) -> Unit) { synchronized(this) { listeners.remove(listener) } }
    private fun notifyChanged() {
        val (state, targets) = synchronized(this) { State(owner != null, failed, explicitStart) to listeners.toList() }
        targets.forEach { runCatching { it(state) } }
    }
    internal fun acquire(capture: MicrophoneCapture): Boolean {
        val acquired = synchronized(this) {
            if (owner != null) false else { owner = capture; failed = false; retry = null; true }
        }
        if (acquired) notifyChanged()
        return acquired
    }
    internal fun failure(capture: MicrophoneCapture) {
        val changed = synchronized(this) { if (owner !== capture) false else { failed = true; true } }
        if (changed) notifyChanged()
    }
    internal fun released(capture: MicrophoneCapture) {
        val changed = synchronized(this) {
            if (owner !== capture) false else { owner = null; failed = false; retry = null; true }
        }
        if (changed) notifyChanged()
    }
    internal fun setRetry(capture: MicrophoneCapture, action: () -> Unit) {
        synchronized(this) { if (owner === capture) retry = action }
        notifyChanged()
    }
    fun retryRelease() {
        val pending = synchronized(this) {
            if (!failed || retry == null || owner == null) null else (owner!! to retry!!).also { failed = false }
        }
        if (pending != null) {
            notifyChanged()
            if (runCatching { pending.second() }.isFailure) failure(pending.first)
        }
    }
    fun requireExplicitStart() { synchronized(this) { explicitStart = true } }
    fun acceptExplicitStart() { synchronized(this) { explicitStart = false } }
}

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
            val interrupted = feedback?.isCapturing == true
            feedback?.stop()
            if (interrupted) onInterrupted?.invoke()
        }) } catch (failure: Throwable) {
            if (!recorder.isRunning) MicrophoneOwner.released(this)
            throw failure
        }
    }
    fun setReleaseRetry(action: () -> Unit) = MicrophoneOwner.setRetry(this, action)
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
