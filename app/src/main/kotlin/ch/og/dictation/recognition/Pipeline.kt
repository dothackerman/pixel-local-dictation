package ch.og.dictation.recognition

import ch.og.dictation.take.TimelineEvent
import ch.og.dictation.take.TimelineObserver
import ch.og.dictation.take.emit
/** Builds the pipeline a [Variant] describes; shared by the daily app (assets) and LabTest (files). */
object Pipeline {
    fun runtime(
        v: Variant, model: ModelFiles, lid: ModelFiles?, vad: ModelFiles,
        queue: java.util.concurrent.Executor, hotwordsPath: String = "", timeline: TimelineObserver? = null,
        written: (String) -> String = { it },
    ) = DictationRuntime(v, queue, { recognizer(v, model, lid, hotwordsPath, timeline) },
        { VadSegmenter(v, vad) }, written, ::release)

    fun recognizer(v: Variant, model: ModelFiles, lid: ModelFiles?, hotwordsPath: String = "", timeline: TimelineObserver? = null): Recognizer {
        fun buildAsr(build: () -> SherpaRecognizer): SherpaRecognizer {
            timeline?.emit(TimelineEvent("asrLoadStart"))
            val recognizer = try { build() }
                finally { timeline?.emit(TimelineEvent("asrLoadEnd")) }
            timeline?.emit(TimelineEvent("asrReady"))
            return recognizer
        }
        val inner: Recognizer = when (v.family) {
            "parakeet" -> buildAsr { SherpaRecognizer.parakeet(v, model, hotwordsPath) }
            "whisper" -> SherpaRecognizer.whisper(v, model)
            "canary" -> LanguageSwitchedRecognizer(v, requireNotNull(lid) { "canary needs the language identifier's files" }, { lang -> buildAsr { SherpaRecognizer.canary(v, model, lang) } }, timeline = timeline)
            "qwen3" -> SherpaRecognizer.qwen3(v, model, hotwordsPath)
            "cohere" -> if (lid != null && v.cohere.language.isEmpty()) LanguageSwitchedRecognizer(v, lid, { lang -> buildAsr { SherpaRecognizer.cohere(v, model, lang) } }, perStream = true, timeline = timeline)
                        else buildAsr { SherpaRecognizer.cohere(v, model) }
            else -> throw IllegalArgumentException(v.family)
        }
        return if (v.post.swissSs) SwissSpelling(inner) else inner
    }

    fun release(r: Recognizer) {
        when (r) {
            is SwissSpelling -> release(r.inner)
            is SherpaRecognizer -> r.release()
            is LanguageSwitchedRecognizer -> r.release()
            is DictionaryModelLeases.Lease -> r.release()
        }
    }
}
