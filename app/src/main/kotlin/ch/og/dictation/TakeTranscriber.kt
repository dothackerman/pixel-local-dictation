package ch.og.dictation

import java.util.concurrent.Executor

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
    private val seamCapitalize: Boolean = false,
    private val onPreview: (String) -> Unit,
) {
    private val texts = ArrayList<String>()   // decode thread only
    private var begun = false                  // decode thread only
    private var failure: Throwable? = null     // decode thread only
    @Volatile private var cancelled = false

    /** Recording thread. */
    fun feed(samples: FloatArray) {
        if (cancelled) return
        for (s in segmenter.accept(samples)) submit(s)
    }

    /** After the recording thread has ended. [onDone] gets the text, or the failure of any segment. */
    fun finish(onDone: (Result<String>) -> Unit) {
        for (s in segmenter.flush()) submit(s)
        decodeQueue.execute {
            if (cancelled) return@execute
            val result = failure?.let { Result.failure(it) } ?: Result.success(joinChunks(texts, seamCapitalize))
            deliver.execute { if (!cancelled) onDone(result) }
        }
    }

    fun cancel() { cancelled = true }

    private fun submit(segment: FloatArray) = decodeQueue.execute {
        if (cancelled || failure != null) return@execute
        try {
            val rec = recognizer()
            if (!begun) { begun = true; rec.beginTake(segment) }
            val text = rec.transcribe(segment).trim()
            if (text.isEmpty()) return@execute
            texts.add(text)
            val joined = joinChunks(texts, seamCapitalize)
            deliver.execute { if (!cancelled) onPreview(joined) }
        } catch (e: Throwable) {
            failure = e
        }
    }
}
