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
    private val post: Variant.Post = Variant.Post(),
    private val onPreview: (String) -> Unit,
) {
    private val texts = ArrayList<String>()   // decode thread only
    private var begun = false                  // decode thread only
    private var failure: Throwable? = null     // decode thread only
    @Volatile private var cancelled = false

    /** Recording thread. */
    fun feed(samples: FloatArray) {
        if (cancelled) return
        for (c in segmenter.accept(samples)) submit(c)
    }

    /** After the recording thread has ended. [onDone] gets the text, or the failure of any segment. */
    fun finish(onDone: (Result<String>) -> Unit) {
        for (c in segmenter.flush()) submit(c)
        decodeQueue.execute {
            if (cancelled) return@execute
            val result = failure?.let { Result.failure(it) } ?: Result.success(joinChunks(texts, post.seamCapitalize))
            deliver.execute { if (!cancelled) onDone(result) }
        }
    }

    fun cancel() { cancelled = true }

    private fun submit(chunk: Chunk) = decodeQueue.execute {
        if (cancelled || failure != null) return@execute
        // No-speech gate: a chunk without detected speech is not decoded (keeps models from writing on silence).
        if (post.skipSilentChunks && !chunk.speech) return@execute
        try {
            val rec = recognizer()
            if (!begun) { begun = true; rec.beginTake(chunk.samples) }
            val text = rec.transcribe(chunk.samples).trim()
            if (text.isEmpty()) return@execute
            texts.add(text)
            val joined = joinChunks(texts, post.seamCapitalize)
            deliver.execute { if (!cancelled) onPreview(joined) }
        } catch (e: Throwable) {
            failure = e
        }
    }
}
