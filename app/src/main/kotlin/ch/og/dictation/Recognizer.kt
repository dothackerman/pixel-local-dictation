package ch.og.dictation

/** Speech to text for one piece of audio: 16 kHz mono samples in [-1, 1]. */
interface Recognizer {
    fun transcribe(samples: FloatArray): String
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
class SwissSpelling(private val inner: Recognizer) : Recognizer {
    override fun transcribe(samples: FloatArray): String =
        inner.transcribe(samples).replace("ß", "ss").replace("ẞ", "SS")
}

/**
 * Cuts a take into contiguous chunks at pauses, so no audio is dropped between chunks.
 * Measured on Oriol's takes of 2026-10-10: decoding only the speech found by the VAD lost
 * words at the start of segments, and chunks of at least 20 s gave fewer errors than
 * shorter ones (docs/plan.md). A chunk ends [padSamples] after speech ended, once it is
 * at least [minSamples] long. Positions are sample counts since the start of the take.
 */
class PauseChunker(private val minSamples: Int, private val padSamples: Int) {
    private var pending = FloatArray(1 shl 16)
    private var size = 0
    private var cutAt = 0L   // position of pending[0]

    fun append(samples: FloatArray) {
        if (size + samples.size > pending.size) pending = pending.copyOf(maxOf(pending.size * 2, size + samples.size))
        samples.copyInto(pending, size)
        size += samples.size
    }

    /** A pause was found: speech ended at [speechEnd]. Returns a chunk if one is due. */
    fun onSpeechEnd(speechEnd: Long): FloatArray? {
        val n = (minOf(speechEnd + padSamples, cutAt + size) - cutAt).toInt()
        if (n < minSamples) return null
        val chunk = pending.copyOfRange(0, n)
        pending.copyInto(pending, 0, n, size)
        size -= n; cutAt += n
        return chunk
    }

    /** End of the take: everything not yet returned. */
    fun rest(): FloatArray? = (if (size == 0) null else pending.copyOfRange(0, size)).also { cutAt += size; size = 0 }
}
