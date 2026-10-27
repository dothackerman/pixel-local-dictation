package ch.og.dictation

import java.io.File

/** Decode-executor only. One model, immutable per-revision stream dictionaries, reference-counted leases. */
class DictionaryModels {
    private var model: SherpaRecognizer? = null
    private var acceptance: ProvisionalBaseline? = null
    private var references = 0
    var loads = 0
        private set

    fun acquire(baseline: ProvisionalBaseline, dictionary: DictionarySnapshot, apk: File, load: () -> SherpaRecognizer): Lease {
        val verified = acceptance
        if (verified == null) baseline.verify(apk) else baseline.verifyWithLoadedModel(verified)
        val current = model ?: load().also { model = it; acceptance = baseline; loads++ }
        references++
        return Lease(current, dictionary.entries.joinToString("\n"))
    }

    inner class Lease(private val current: SherpaRecognizer, private val terms: String) : Recognizer {
        private var released = false
        override fun transcribe(samples: FloatArray): String {
            check(!released)
            return current.transcribe(samples, emptyMap(), terms)
        }
        fun release() {
            check(!released)
            released = true
            references--
            if (references == 0) {
                model = null
                acceptance = null
                current.release()
            }
        }
    }
}
