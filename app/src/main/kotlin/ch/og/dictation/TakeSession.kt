package ch.og.dictation

import java.util.concurrent.Executor

/** Both acknowledgements mean capture has stopped and no audio callback is still in flight. */
interface TakeCapture {
    fun start(accept: (FloatArray) -> Unit)
    fun stop(done: (Result<ByteArray>) -> Unit)
    fun cancel(done: () -> Unit)
}

/** Called on the delivery executor. commit must check the live editor immediately before writing. */
interface TakeOutput {
    fun preview(text: String)
    fun commit(text: String): Boolean
    fun returnToKeyboard(): Boolean
    /** PCM is borrowed for this callback only and wiped immediately afterwards. */
    fun finished(result: TakeResult, pcm: ByteArray)
}

enum class TakeOutcome { COMPLETED, CANCELLED, INPUT_CHANGED, FAILED, COMMIT_REJECTED }

/** Monotonic timestamps describe receipts, not observed editor text or keyboard readiness. */
data class TakeResult(
    val outcome: TakeOutcome,
    val text: String?,
    val eventsNs: Map<String, Long>,
    val commitAccepted: Boolean? = null,
    val returnAccepted: Boolean? = null,
    val failure: Throwable? = null,
)

/**
 * One take. start/stop/cancel and delivery run on one serial executor (the IME main thread).
 * Capture may deliver audio on its own thread, through its stop acknowledgement.
 * A cancelled take cannot publish or commit, even when native work completes later.
 */
class TakeSession internal constructor(
    private val capture: TakeCapture,
    private val output: TakeOutput,
    private val deliver: Executor,
    private val transcribe: (Executor, TimelineObserver?, (String) -> Unit) -> TakeTranscriber,
    private val timeline: TimelineObserver?,
    private val clock: () -> Long,
    private val releaseSegmenter: () -> Unit,
) {
    private enum class State { NEW, RECORDING, DRAINING, FINISHING, COMPLETING, ENDED }
    @Volatile private var state = State.NEW
    private var take: TakeTranscriber? = null
    private var pcm = ByteArray(0)
    private var captureReleased = true
    private var cleaned = false
    private var terminal: TakeResult? = null
    private val events = linkedMapOf<String, Long>()
    /** Remains active while cancellation drains capture, so a new microphone take must wait. */
    val active: Boolean get() = state != State.NEW && (state != State.ENDED || !captureReleased)

    private fun mark(phase: String) {
        val now = clock()
        events[phase] = now
        timeline?.emit(TimelineEvent(phase, ns = now))
    }

    fun start() {
        check(state == State.NEW) { "a session can start only once" }
        state = State.RECORDING
        captureReleased = false
        try {
            take = transcribe(deliver, timeline) { text ->
                if (active) {
                    if ("previewReady" !in events) mark("previewReady")
                    output.preview(text)
                }
            }
            mark("captureStart")
            capture.start { samples ->
                if (state == State.RECORDING || state == State.DRAINING) take?.feed(samples)
            }
        } catch (failure: Throwable) {
            cancelCapture()
            finish(TakeOutcome.FAILED, failure = failure)
        }
    }

    fun stop() {
        if (state != State.RECORDING) return
        state = State.DRAINING
        mark("stopRequested")
        take?.timelineSnapshot("producerStop")
        try {
            capture.stop { result ->
                deliver.execute {
                    if (state != State.DRAINING) {
                        result.getOrNull()?.fill(0)
                        captureReleased = true
                        cleanup()
                    } else {
                        captureReleased = true
                        result.fold({ audio ->
                            pcm = audio
                            mark("captureStopped")
                            state = State.FINISHING
                            try { take!!.finish(::decoded) }
                            catch (failure: Throwable) { finish(TakeOutcome.FAILED, failure = failure) }
                        }, { failure -> finish(TakeOutcome.FAILED, failure = failure) })
                    }
                }
            }
        } catch (failure: Throwable) {
            cancelCapture()
            finish(TakeOutcome.FAILED, failure = failure)
        }
    }

    fun cancel(inputChanged: Boolean = false) {
        if (state == State.ENDED) return
        take?.cancel()
        cancelCapture()
        finish(if (inputChanged) TakeOutcome.INPUT_CHANGED else TakeOutcome.CANCELLED)
    }

    private fun decoded(result: Result<String>) {
        if (state != State.FINISHING) return
        mark("finalReady")
        val text = result.getOrElse { finish(TakeOutcome.FAILED, failure = it); return }
        state = State.COMPLETING
        try {
            val accepted = if (text.isEmpty()) null else output.commit(text)
            if (state != State.COMPLETING) return // An editor callback invalidated this take.
            if (accepted == false) {
                mark("commitRejected")
                finish(TakeOutcome.COMMIT_REJECTED, text, accepted)
                return
            }
            if (accepted == true) mark("commitAccepted")
            val returned = output.returnToKeyboard()
            if (state != State.COMPLETING) return
            mark("returnRequested")
            finish(TakeOutcome.COMPLETED, text, accepted, returned)
        } catch (failure: Throwable) {
            finish(TakeOutcome.FAILED, text, failure = failure)
        }
    }

    private fun finish(
        outcome: TakeOutcome, text: String? = null, committed: Boolean? = null,
        returned: Boolean? = null, failure: Throwable? = null,
    ) {
        if (state == State.ENDED) return
        state = State.ENDED
        take?.cancel()
        terminal = TakeResult(outcome, text, emptyMap(), committed, returned, failure)
        cleanup()
    }

    private fun cancelCapture() {
        if (captureReleased) return
        capture.cancel {
            deliver.execute {
                captureReleased = true
                cleanup()
            }
        }
    }

    private fun cleanup() {
        if (state == State.ENDED && captureReleased && !cleaned) {
            cleaned = true
            mark("takeEnded")
            try {
                output.finished(requireNotNull(terminal).copy(eventsNs = events.toMap()), pcm)
            } finally {
                pcm.fill(0)
                pcm = ByteArray(0)
                releaseSegmenter()
            }
        }
    }
}
