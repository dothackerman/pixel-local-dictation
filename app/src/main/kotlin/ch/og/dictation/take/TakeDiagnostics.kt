package ch.og.dictation.take

import ch.og.dictation.diagnostics.RotationEvent
import ch.og.dictation.diagnostics.RotationTrace
import ch.og.dictation.diagnostics.StartupTrace

/**
 * What the rotation trace records about the take at an event. The caller states them at the event;
 * diagnostics never read take state back.
 */
data class TakeTraceFacts(
    /** A take is active. */
    val active: Boolean,
    /** The live input session is the one the take's panel is attached to (the trace's `authority` column). */
    val liveSession: Boolean,
    /** The number of the panel view created last; null before the first. */
    val view: Long?,
    /** The number of the take started last; null before the first. */
    val take: Long?,
    /** The display rotation read for this event; null when it cannot be read. */
    val rotation: Int?,
    /** How many rotation changes were counted up to this event. */
    val rotations: Long,
)

/**
 * Take diagnostics: everything take code traces. One per take controller (today: per service). Every
 * event is written on the thread that reports it, in the order reported; without a trace nothing is.
 */
class TakeDiagnostics {
    @Volatile private var rotation: RotationTrace? = null

    /** A rotation trace was attached (also one closed since). */
    val tracing: Boolean get() = rotation != null

    /** No trace is attached, or the attached one was closed: a new probe's trace may replace it. */
    val attachable: Boolean get() = rotation?.closed != false

    fun attach(trace: RotationTrace) { rotation = trace }

    /** An event with the take's [facts] at it. */
    fun event(
        event: RotationEvent, facts: TakeTraceFacts, flag: Boolean? = null, configDiff: Int? = null,
        handledConfig: Int? = null, retention: EditorRetention? = null, detail: Map<String, Any>? = null,
    ) {
        rotation?.event(event, facts.active, facts.liveSession, flag, facts.view, facts.take, facts.rotation, facts.rotations,
            configDiff, handledConfig, retention, detail)
    }

    /** An event of take number [take] alone, on the trace attached now. */
    fun takeEvent(event: RotationEvent, take: Long) { rotation?.event(event, take = take) }

    /** The terminal event of take number [take]. */
    fun terminal(outcome: TakeOutcome, take: Long) = takeEvent(when (outcome) {
        TakeOutcome.COMPLETED -> RotationEvent.TERMINAL_COMPLETED
        TakeOutcome.CANCELLED -> RotationEvent.TERMINAL_CANCELLED
        TakeOutcome.INPUT_CHANGED -> RotationEvent.TERMINAL_INPUT_CHANGED
        TakeOutcome.FAILED -> RotationEvent.TERMINAL_FAILED
        TakeOutcome.COMMIT_REJECTED -> RotationEvent.TERMINAL_COMMIT_REJECTED
    }, take)

    /**
     * The trace of one take's capture and timeline: it keeps the rotation trace attached now and
     * [startup], whatever is attached later. [startupTake] is the take's id in the startup trace.
     */
    fun forTake(take: Long, startup: StartupTrace?, startupTake: String?) = TakeTrace(rotation, startup, take, startupTake)
}

/** One take's capture and timeline events; safe from any thread. */
class TakeTrace internal constructor(
    private val rotation: RotationTrace?, private val startup: StartupTrace?,
    private val take: Long, private val startupTake: String?,
) {
    fun event(event: RotationEvent) { rotation?.event(event, take = take) }

    /** The platform recording started. */
    fun captureStarted() {
        event(RotationEvent.CAPTURE_STARTED)
        startup?.event("recordingStarted", startupTake)
    }

    /** [capture] with its release and failures traced before the take hears of them. */
    fun traced(capture: TakeCapture): TakeCapture = object : TakeCapture {
        override fun start(accept: (FloatArray) -> Unit) {
            try { capture.start(accept) }
            catch (failure: Throwable) {
                event(RotationEvent.CAPTURE_FAILED)
                throw failure
            }
        }
        override fun stop(done: (Result<ByteArray>) -> Unit) {
            capture.stop { result ->
                event(if (result.isFailure) RotationEvent.CAPTURE_FAILED else RotationEvent.CAPTURE_RELEASED)
                done(result)
            }
        }
        override fun cancel(done: () -> Unit) {
            capture.cancel {
                event(RotationEvent.CAPTURE_RELEASED)
                done()
            }
        }
        override fun pause() = capture.pause()
        override fun resume() = capture.resume()
        override fun cancelWithResult(done: (Result<Unit>) -> Unit) {
            capture.cancelWithResult { result ->
                event(if (result.isSuccess) RotationEvent.CAPTURE_RELEASED else RotationEvent.CAPTURE_FAILED)
                done(result)
            }
        }
    }

    /** The take's timeline as the traces record it; null when nothing is traced. */
    val observer: TimelineObserver? = if (startup == null && rotation == null) null else TimelineObserver { timed ->
        val rotationEvent = when (timed.phase) {
            "stopRequested" -> RotationEvent.STOP_REQUESTED
            "captureStopped" -> RotationEvent.CAPTURE_STOPPED
            "finalReady" -> RotationEvent.FINAL_READY
            "takeEnded" -> RotationEvent.TAKE_ENDED
            else -> null
        }
        if (rotationEvent != null) event(rotationEvent)
        if (timed.phase in startupPhases) startup?.event(timed.phase, startupTake, timed.ns)
        if (timed.phase in timingPhases) startup?.timing(startupTake, timed)
    }

    private companion object {
        val startupPhases = setOf("captureStart", "firstAudio", "takeEnded")
        val timingPhases = setOf("scenario", "captureStart", "captureSampleAnchor", "captureStopAnchor", "chunkReady", "chunkEnqueue",
            "chunkWorkerStart", "chunkWorkerEnd", "checkpointQueued", "checkpointDurable", "checkpointFailed",
            "stopRequested", "producerStop", "captureStopped", "finishCall", "finishEnqueue",
            "finishWorkerStart", "finishWorkerEnd", "finalReady", "commitAccepted", "commitRejected",
            "returnRequested", "takeEnded")
    }
}
