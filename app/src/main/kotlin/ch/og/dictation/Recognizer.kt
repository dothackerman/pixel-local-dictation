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

/** Segments shorter than [minSamples] are held back and prepended to the next one (plan: under 2 s). */
class ShortSegmentMerger(private val minSamples: Int) {
    private var carry: FloatArray? = null

    fun add(segment: FloatArray): FloatArray? {
        val whole = carry?.plus(segment) ?: segment
        return if (whole.size < minSamples) { carry = whole; null } else { carry = null; whole }
    }

    fun rest(): FloatArray? = carry.also { carry = null }
}
