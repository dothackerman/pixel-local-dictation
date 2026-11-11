package ch.og.dictation

import java.util.concurrent.Executor

/** Both acknowledgements mean capture has stopped and no audio callback is still in flight. */
interface TakeCapture {
    fun start(accept: (FloatArray) -> Unit)
    fun stop(done: (Result<ByteArray>) -> Unit)
    fun cancel(done: () -> Unit)
    fun cancelWithResult(done: (Result<Unit>) -> Unit) = cancel { done(Result.success(Unit)) }
}

/** The platform did not confirm release; the owner must retain the capture. */
class CaptureReleaseUnconfirmed : Exception()

/** Called on the delivery executor. commit must check the live editor immediately before writing. */
interface TakeOutput {
    fun preview(text: String)
    fun captureReleaseFailed() {}
    fun startHistory(eligible: Boolean) {}
    fun finishingHistory() {}
    fun commit(text: String): Boolean
    fun commit(text: String, allowed: () -> Boolean): Boolean = if (allowed()) commit(text) else false
    /** False if commit was skipped because the originating editor was unavailable. */
    fun commitAttempted(): Boolean = true
    fun stage(text: String, retentionEligible: Boolean, ready: () -> Unit) = ready()
    fun saveInterrupted(text: String, retentionEligible: Boolean, done: (Boolean) -> Unit) = done(false)
    fun receipt(status: HistoryDelivery) {}
    fun discardStaged(done: (Boolean) -> Unit = {}) { done(true) }
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
    /** Latched from the editor that opened this take; false forbids every content writer. */
    val retentionEligible: Boolean = true,
    val interrupted: Boolean = false,
    val interruptedSaved: Boolean = false,
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
    private val retentionEligible: Boolean,
    private val releaseSegmenter: () -> Unit,
) {
    private enum class State { NEW, RECORDING, DRAINING, FINISHING, COMPLETING, ENDED }
    @Volatile private var state = State.NEW
    private var take: TakeTranscriber? = null
    private var pcm = ByteArray(0)
    private var captureReleased = true
    private var cleaned = false
    private var terminal: TakeResult? = null
    private var deliveryHeld = false
    private var pendingDelivery: String? = null
    private var dispatching = false
    private var invalidatedDuringDispatch = false
    private var deliberatelyCancelledDuringDispatch = false
    private var deferredDiscard: ((Boolean) -> Unit)? = null
    private val audioLock = Any()
    private var interrupted = false
    private var releaseNotice: (() -> Unit)? = null
    private var interruptedSaved = false
    private var interruptionFailure: Throwable? = null
    private var staged = false
    private val events = linkedMapOf<String, Long>()
    /** Remains active while cancellation drains capture, so a new microphone take must wait. */
    val active: Boolean get() = state != State.NEW && (state != State.ENDED || !captureReleased)

    private fun mark(phase: String) {
        val now = clock()
        synchronized(events) { events[phase] = now }
        timeline?.emit(TimelineEvent(phase, ns = now))
    }

    fun start() {
        check(state == State.NEW) { "a session can start only once" }
        state = State.RECORDING
        output.startHistory(retentionEligible)
        captureReleased = false
        try {
            take = transcribe(deliver, timeline) { text ->
                if (active) {
                    synchronized(events) { if ("previewReady" !in events) mark("previewReady") }
                    output.preview(text)
                }
            }
            mark("captureStart")
            capture.start { samples ->
                synchronized(audioLock) { if (!interrupted && (state == State.RECORDING || state == State.DRAINING)) {
                    if (timeline != null && samples.isNotEmpty()) synchronized(events) {
                        if ("firstAudio" !in events) mark("firstAudio")
                    }
                    take?.feed(samples)
                } }
            }
        } catch (failure: Throwable) {
            cancelCapture()
            finish(TakeOutcome.FAILED, failure = failure)
        }
    }

    fun stop() {
        if (state != State.RECORDING) return
        state = State.DRAINING
        output.finishingHistory()
        mark("stopRequested")
        take?.timelineSnapshot("producerStop")
        try {
            capture.stop { result ->
                deliver.execute {
                    if (result.exceptionOrNull() is CaptureReleaseUnconfirmed) {
                        if (!captureReleased) {
                            if (state != State.ENDED) finish(TakeOutcome.FAILED, failure = CaptureReleaseUnconfirmed())
                            output.captureReleaseFailed()
                        }
                        return@execute
                    }
                    if (state != State.DRAINING) {
                        result.getOrNull()?.fill(0)
                        captureReleased = true
                        releaseNotice?.also { releaseNotice = null; it() }
                        cleanup()
                    } else {
                        captureReleased = true
                        releaseNotice?.also { releaseNotice = null; it() }
                        result.fold({ audio ->
                            pcm = audio
                            mark("captureStopped")
                            state = State.FINISHING
                            try { take!!.finish(::decoded) }
                            catch (failure: Throwable) { finish(TakeOutcome.FAILED, failure = failure) }
                        }, { failure ->
                            if (interrupted) {
                                interruptionFailure = failure
                                state = State.FINISHING
                                try { take!!.finish(::decoded) }
                                catch (decodeFailure: Throwable) { finish(TakeOutcome.FAILED, failure = decodeFailure) }
                            } else finish(TakeOutcome.FAILED, failure = failure)
                        })
                    }
                }
            }
        } catch (failure: Throwable) {
            cancelCapture()
            finish(TakeOutcome.FAILED, failure = failure)
        }
    }

    /** Hold only before dispatch; once a commit starts it cannot be retracted. */
    fun holdDelivery(): Boolean {
        if ((dispatching && output.commitAttempted()) || interrupted || state !in setOf(State.DRAINING, State.FINISHING, State.COMPLETING)) return false
        deliveryHeld = true
        return true
    }

    fun resumeDelivery() {
        deliveryHeld = false
        pendingDelivery?.also { pendingDelivery = null; complete(it) }
    }

    /** Revoke audio acceptance before stopping capture; retain accepted audio for History only. */
    fun interrupt(onCaptureReleased: () -> Unit = {}) {
        if (state == State.NEW || state == State.ENDED || interrupted) return
        synchronized(audioLock) { interrupted = true }
        if (captureReleased) onCaptureReleased() else releaseNotice = onCaptureReleased
        if (dispatching) { invalidatedDuringDispatch = true; return }
        resumeDelivery()
        if (state == State.RECORDING) stop()
    }

    fun cancel(inputChanged: Boolean = false, onDiscarded: (Boolean) -> Unit = {}) {
        if (state == State.ENDED) return
        if (dispatching) {
            invalidatedDuringDispatch = true
            if (!inputChanged) { deliberatelyCancelledDuringDispatch = true; deferredDiscard = onDiscarded }
            return
        }
        var cancellationApplied = false
        var immediateDeletion: Boolean? = null
        if (inputChanged) {
            if (staged) output.receipt(HistoryDelivery.NOT_ATTEMPTED)
        } else output.discardStaged { deleted ->
            if (cancellationApplied) onDiscarded(deleted) else immediateDeletion = deleted
        }
        take?.cancel()
        cancelCapture()
        finish(if (inputChanged) TakeOutcome.INPUT_CHANGED else TakeOutcome.CANCELLED)
        cancellationApplied = true
        immediateDeletion?.let(onDiscarded)
    }

    private fun decoded(result: Result<String>) {
        if (state != State.FINISHING) return
        mark("finalReady")
        val text = result.getOrElse { finish(TakeOutcome.FAILED, failure = it); return }
        state = State.COMPLETING
        if (interrupted) { saveInterrupted(text); return }
        if (text.isEmpty()) { complete(text); return }
        staged = true
        output.stage(text, retentionEligible) {
            if (state == State.COMPLETING) complete(text)
        }
    }

    private fun saveInterrupted(text: String) {
        output.saveInterrupted(text, retentionEligible) { saved ->
            interruptedSaved = saved
            if (state == State.COMPLETING) finish(
                if (interruptionFailure == null) TakeOutcome.INPUT_CHANGED else TakeOutcome.FAILED,
                text, failure = interruptionFailure)
        }
    }

    private fun complete(text: String) {
        if (deliveryHeld && !interrupted) { pendingDelivery = text; return }
        if (interrupted) { saveInterrupted(text); return }
        var accepted: Boolean? = null
        try {
            dispatching = true
            accepted = try { if (text.isEmpty()) null else output.commit(text) { !deliveryHeld && !invalidatedDuringDispatch && state == State.COMPLETING } }
                finally { dispatching = false }
            if (deliberatelyCancelledDuringDispatch && accepted == false && !output.commitAttempted()) {
                output.discardStaged { deferredDiscard?.invoke(it); deferredDiscard = null }
                finish(TakeOutcome.CANCELLED)
                return
            }
            if (deliveryHeld && !interrupted && accepted == false && !output.commitAttempted()) { pendingDelivery = text; return }
            if (accepted != null) output.receipt(if (accepted) HistoryDelivery.ACCEPTED else
                if (output.commitAttempted()) HistoryDelivery.REJECTED else HistoryDelivery.NOT_ATTEMPTED)
            if (invalidatedDuringDispatch) {
                finish(if (accepted == true) TakeOutcome.COMPLETED else TakeOutcome.INPUT_CHANGED, text, accepted)
                return
            }
            if (state != State.COMPLETING) return // An editor callback invalidated this take.
            if (accepted == false) {
                if (!output.commitAttempted()) {
                    finish(TakeOutcome.INPUT_CHANGED)
                    return
                }
                mark("commitRejected")
                finish(TakeOutcome.COMMIT_REJECTED, text, accepted)
                return
            }
            if (accepted == true) mark("commitAccepted")
            dispatching = true
            val returned = try { output.returnToKeyboard() } finally { dispatching = false }
            if (invalidatedDuringDispatch) {
                finish(TakeOutcome.COMPLETED, text, accepted, returned)
                return
            }
            if (state != State.COMPLETING) return
            mark("returnRequested")
            finish(TakeOutcome.COMPLETED, text, accepted, returned)
        } catch (failure: Throwable) {
            if (text.isNotEmpty() && accepted == null && deliberatelyCancelledDuringDispatch && !output.commitAttempted()) {
                output.discardStaged()
                finish(TakeOutcome.CANCELLED)
                return
            }
            finish(TakeOutcome.FAILED, text, committed = accepted, failure = failure)
        }
    }

    private fun finish(
        outcome: TakeOutcome, text: String? = null, committed: Boolean? = null,
        returned: Boolean? = null, failure: Throwable? = null,
    ) {
        if (state == State.ENDED) return
        state = State.ENDED
        take?.cancel()
        terminal = TakeResult(outcome, text, emptyMap(), committed, returned, failure, retentionEligible, interrupted, interruptedSaved)
        cleanup()
    }

    private fun cancelCapture() {
        if (captureReleased) return
        capture.cancelWithResult { result ->
            deliver.execute {
                if (result.isFailure) {
                    output.captureReleaseFailed()
                    return@execute
                }
                captureReleased = true
                releaseNotice?.also { releaseNotice = null; it() }
                cleanup()
            }
        }
    }

    fun retryCaptureRelease() { if (!captureReleased && state == State.ENDED) cancelCapture() }

    private fun cleanup() {
        if (state == State.ENDED && captureReleased && !cleaned) {
            cleaned = true
            mark("takeEnded")
            try {
                output.finished(requireNotNull(terminal).copy(eventsNs = synchronized(events) { events.toMap() }), pcm)
            } finally {
                pcm.fill(0)
                pcm = ByteArray(0)
                releaseSegmenter()
            }
        }
    }
}
