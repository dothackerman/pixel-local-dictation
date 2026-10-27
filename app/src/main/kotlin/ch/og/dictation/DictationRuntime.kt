package ch.og.dictation

import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

/**
 * Process lifetime of one setup. The injected decode executor must be serial.
 * Model loading, takes and release share that executor; cancelling a take does not release
 * a model still used by native work. Production and replay share this take lifecycle;
 * recognizer construction and ownership may differ (for example, dictionary leases).
 */
class DictationRuntime(
    private val variant: Variant,
    private val decode: Executor,
    private val load: () -> Recognizer,
    private val segments: () -> Segmenter,
    private val release: (Recognizer) -> Unit = {},
) {
    private var recognizer: Recognizer? = null // decode executor only
    @Volatile private var closed = false
    private var retiring = false
    private val sessions = mutableSetOf<TakeSession>()

    private fun recognizer(): Recognizer = recognizer ?: load().also { recognizer = it }

    fun warmUp(silenceSamples: Int = 0): CompletableFuture<Unit> {
        check(!closed) { "runtime closed" }
        val done = CompletableFuture<Unit>()
        decode.execute {
            try {
                val rec = recognizer()
                if (silenceSamples > 0) rec.transcribe(FloatArray(silenceSamples))
                done.complete(Unit)
            } catch (failure: Throwable) { done.completeExceptionally(failure) }
        }
        return done
    }

    fun open(
        capture: TakeCapture, output: TakeOutput, deliver: Executor,
        timeline: TimelineObserver? = null, clock: () -> Long = System::nanoTime,
    ): TakeSession = synchronized(sessions) {
        check(!closed && !retiring) { "runtime closed" }
        val segmenter = segments()
        lateinit var session: TakeSession
        session = TakeSession(capture, output, deliver, { delivery, observer, preview ->
            check(!closed) { "runtime closed" }
            TakeTranscriber(::recognizer, segmenter, decode, delivery, variant.post, observer, preview)
        }, timeline, clock, {
            try { segmenter.reset() } finally { synchronized(sessions) {
                sessions.remove(session)
                if (retiring && sessions.isEmpty() && !closed) close()
            } }
        })
        synchronized(sessions) { sessions.add(session) }
        return session
    }

    /** Existing takes finish with their original model; release queues behind their native work. */
    fun retire() = synchronized(sessions) {
        retiring = true
        if (sessions.isEmpty() && !closed) close()
    }

    /** Call only after all captures have stopped. Completion acknowledges all queued native work. */
    fun close(): CompletableFuture<Unit> {
        check(!closed) { "runtime closed" }
        check(synchronized(sessions) { sessions.isEmpty() }) { "takes or captures remain active" }
        closed = true
        val done = CompletableFuture<Unit>()
        decode.execute {
            try {
                recognizer?.let(release)
                recognizer = null
                done.complete(Unit)
            } catch (failure: Throwable) { done.completeExceptionally(failure) }
        }
        return done
    }
}
