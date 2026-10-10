package ch.og.dictation

/** Speech to text for one piece of audio: 16 kHz mono samples in [-1, 1]. */
interface Recognizer {
    fun transcribe(samples: FloatArray): String

    /**
     * Called on the decode thread with the first chunk of a take, before that chunk is transcribed.
     * Recognizers that need the language as input decide it here. Returns what was detected, or "".
     */
    fun beginTake(firstChunk: FloatArray): String = ""
}

/** Cuts a live stream into pieces that can be decoded while recording continues. */
interface Segmenter {
    /** Feeds more audio; returns the segments that became complete. */
    fun accept(samples: FloatArray): List<FloatArray>
    /** End of the take: returns whatever audio is still pending. */
    fun flush(): List<FloatArray>
    /** Forgets all state before a new take. */
    fun reset()
}

/** Stand-in for tests and for exercising the panel without a model. */
class FakeRecognizer(private val text: (FloatArray) -> String = { "[%.1f s]".format(it.size / 16_000.0) }) : Recognizer {
    override fun transcribe(samples: FloatArray): String = text(samples)
}

/** Swiss spelling (R7): the recognizer writes Standard German "ß", Oriol writes "ss". */
class SwissSpelling(val inner: Recognizer) : Recognizer {
    override fun transcribe(samples: FloatArray): String =
        inner.transcribe(samples).replace("ß", "ss").replace("ẞ", "SS")
    override fun beginTake(firstChunk: FloatArray): String = inner.beginTake(firstChunk)
}

/**
 * Joins chunk texts with one space (twin of post_join in tools/pipeline.py). With [seamCapitalize]
 * a chunk that follows a sentence-final mark starts with a capital letter.
 */
fun joinChunks(texts: List<String>, seamCapitalize: Boolean): String {
    val out = ArrayList<String>()
    for (raw in texts) {
        var t = raw.trim()
        if (t.isEmpty()) continue
        if (seamCapitalize && out.isNotEmpty() && out.last().last() in ".!?") t = t[0].uppercaseChar() + t.substring(1)
        out.add(t)
    }
    return out.joinToString(" ")
}

/**
 * Cuts a take into contiguous chunks at pauses, so no audio is dropped between chunks.
 * Measured on Oriol's takes of 2026-10-10: decoding only the speech found by the VAD lost
 * words at the start of segments, and chunks of at least 20 s gave fewer errors than
 * shorter ones (docs/plan.md). A chunk ends [padSamples] after speech ended, once it is
 * at least [minSamples] long; with [maxSamples] > 0 a chunk is also cut as soon as the
 * pending audio reaches that length (Whisper models drop audio beyond 30 s).
 * Positions are sample counts since the start of the take.
 */
class PauseChunker(private val minSamples: Int, private val padSamples: Int, private val maxSamples: Int = 0) {
    private var pending = FloatArray(1 shl 16)
    private var size = 0
    private var cutAt = 0L   // position of pending[0]

    /** Appends audio; returns a chunk when the hard length limit is reached. */
    fun append(samples: FloatArray): FloatArray? {
        if (size + samples.size > pending.size) pending = pending.copyOf(maxOf(pending.size * 2, size + samples.size))
        samples.copyInto(pending, size)
        size += samples.size
        return if (maxSamples > 0 && size >= maxSamples) take(maxSamples) else null
    }

    /** A pause was found: speech ended at [speechEnd]. Returns a chunk if one is due. */
    fun onSpeechEnd(speechEnd: Long): FloatArray? {
        val n = (minOf(speechEnd + padSamples, cutAt + size) - cutAt).toInt()
        if (n < minSamples) return null
        return take(n)
    }

    /** End of the take: everything not yet returned. */
    fun rest(): FloatArray? = if (size == 0) null else take(size)

    private fun take(n: Int): FloatArray {
        val chunk = pending.copyOfRange(0, n)
        pending.copyInto(pending, 0, n, size)
        size -= n; cutAt += n
        return chunk
    }
}
