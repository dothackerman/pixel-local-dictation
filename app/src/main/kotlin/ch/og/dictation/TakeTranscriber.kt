package ch.og.dictation

import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/** Timing metadata only; never audio or recognition text. Clock: CLOCK_MONOTONIC. */
data class TimelineEvent(
    val phase: String, val ns: Long = System.nanoTime(), val chunkId: Int? = null,
    val samples: Int? = null, val active: Boolean? = null, val queued: Int? = null,
    val scheduledNs: Long? = null, val language: String? = null, val fallback: Boolean? = null,
)

fun interface TimelineObserver { fun record(event: TimelineEvent) }

/** Diagnostics must not change recognition or cleanup when an observer fails. */
internal fun TimelineObserver?.emit(event: TimelineEvent) {
    if (this != null) runCatching { record(event) }
}

/**
 * One take: segments are decoded one after another on [decodeQueue] while recording
 * continues, the joined text so far goes to [onPreview], and [finish] delivers the whole
 * text once. Nothing here touches a text field; callbacks arrive on [deliver].
 * After [cancel] no callback is delivered.
 */
class TakeTranscriber(
    private val recognizer: () -> Recognizer,
    private val segmenter: Segmenter,
    private val decodeQueue: Executor,
    private val deliver: Executor,
    private val post: Variant.Post = Variant.Post(),
    private val timeline: TimelineObserver? = null,
    private val onPreview: (String) -> Unit,
) {
    private val texts = ArrayList<String>()   // decode thread only
    private var begun = false                  // decode thread only
    private var failure: Throwable? = null     // decode thread only
    @Volatile private var cancelled = false
    private val stateLock = Any()
    private var active = false
    private var queued = 0
    private var nextChunk = 0
    private var usablePreview = false // delivery thread only

    fun timelineSnapshot(phase: String, scheduledNs: Long? = null) = synchronized(stateLock) {
        timeline?.emit(TimelineEvent(phase, active = active, queued = queued, scheduledNs = scheduledNs))
    }

    /** Recording thread. */
    fun feed(samples: FloatArray) {
        if (cancelled) return
        for (c in segmenter.accept(samples)) submit(c)
    }

    /** After the recording thread has ended. [onDone] gets the text, or the failure of any segment. */
    fun finish(onDone: (Result<String>) -> Unit) {
        timelineSnapshot("finishCall")
        timeline?.emit(TimelineEvent("flushStart"))
        for (c in segmenter.flush()) submit(c)
        timeline?.emit(TimelineEvent("flushEnd"))
        enqueue("finish", null) {
            timelineSnapshot("finishDrain")
            if (cancelled) return@enqueue
            val result = failure?.let { Result.failure(it) } ?: Result.success(joinChunks(texts, post.seamCapitalize))
            deliver.execute { if (!cancelled) { timeline?.emit(TimelineEvent("finalCallback")); onDone(result) } }
        }
    }

    fun cancel() { cancelled = true }

    private fun enqueue(kind: String, id: Int?, work: () -> Unit) {
        if (timeline == null) { decodeQueue.execute(work); return }
        synchronized(stateLock) {
            queued++
            timeline?.emit(TimelineEvent("${kind}Enqueue", chunkId = id, active = active, queued = queued))
        }
        val started = AtomicBoolean(false)
        try {
            decodeQueue.execute {
                started.set(true)
                synchronized(stateLock) { queued--; active = true }
                timeline?.emit(TimelineEvent("${kind}WorkerStart", chunkId = id))
                try { work() } finally {
                    synchronized(stateLock) { active = false }
                    timeline?.emit(TimelineEvent("${kind}WorkerEnd", chunkId = id))
                }
            }
        } catch (e: Throwable) {
            if (!started.get()) synchronized(stateLock) { queued-- }
            throw e
        }
    }

    private fun submit(chunk: Chunk) {
        val id = nextChunk++
        timeline?.emit(TimelineEvent("chunkReady", chunkId = id, samples = chunk.samples.size))
        enqueue("chunk", id) {
            if (cancelled || failure != null) return@enqueue
            // No-speech gate: a chunk without detected speech is not decoded (keeps models from writing on silence).
            if (post.skipSilentChunks && !chunk.speech) return@enqueue
            try {
                val rec = recognizer()
                if (!begun) { begun = true; timeline?.emit(TimelineEvent("firstClosedChunkGate", chunkId = id, samples = chunk.samples.size)); rec.beginTake(chunk.samples) }
                timeline?.emit(TimelineEvent("recognitionStart", chunkId = id, samples = chunk.samples.size))
                val text = try { rec.transcribe(chunk.samples).trim() }
                    finally { timeline?.emit(TimelineEvent("recognitionEnd", chunkId = id, samples = chunk.samples.size)) }
                if (text.isEmpty()) return@enqueue
                texts.add(text)
                val joined = joinChunks(texts, post.seamCapitalize)
                deliver.execute { if (!cancelled) {
                    if (!usablePreview) { usablePreview = true; timeline?.emit(TimelineEvent("firstUsablePreview", chunkId = id)) }
                    onPreview(joined)
                } }
            } catch (e: Throwable) {
                failure = e
            }
        }
    }
}
