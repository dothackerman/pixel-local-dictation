package ch.og.dictation.recognition

import ch.og.dictation.take.TimelineEvent
import ch.og.dictation.take.TimelineObserver
import ch.og.dictation.take.emit
import android.util.Log
import com.k2fsa.sherpa.onnx.SpokenLanguageIdentification
import com.k2fsa.sherpa.onnx.SpokenLanguageIdentificationConfig

private const val TAG = "Recognizer"

/**
 * Canary and Cohere need the language as input: a Whisper-based spoken-language identifier looks at
 * the first chunk of each take (or its first probe seconds) and picks one of two recognizers.
 * Twin of class LanguageSwitched in tools/experiments/pipeline.py.
 */
class LanguageSwitchedRecognizer(
    private val v: Variant, lid: ModelFiles, build: (String) -> SherpaRecognizer,
    /** Cohere reads the language from each stream, so one instance serves both languages (2026-10-12). */
    private val perStream: Boolean = false,
    private val timeline: TimelineObserver? = null,
) : Recognizer {
    private val recognizers = if (perStream) build("de").let { mapOf("de" to it, "en" to it) } else mapOf("de" to build("de"), "en" to build("en"))
    private val identifier: SpokenLanguageIdentification
    @Volatile private var language = v.lid.fallback

    init {
        timeline?.emit(TimelineEvent("lidLoadStart"))
        try {
            val config = SpokenLanguageIdentificationConfig()
            config.whisper.encoder = lid.onnx("encoder")
            config.whisper.decoder = lid.onnx("decoder")
            config.numThreads = v.threads
            config.provider = RuntimeConfig.provider(v, lid, lid = true)
            identifier = SpokenLanguageIdentification(lid.assets, config)
            timeline?.emit(TimelineEvent("lidReady"))
        } catch (failure: Throwable) {
            recognizers.values.toSet().forEach { runCatching { it.release() } }
            throw failure
        }
    }

    override fun beginTake(firstChunk: FloatArray): String {
        val probe = (v.lid.probeSeconds * SAMPLE_RATE).toInt()
        val x = if (probe > 0 && probe < firstChunk.size) firstChunk.copyOfRange(0, probe) else firstChunk
        timeline?.emit(TimelineEvent("lidStart", samples = x.size))
        val detected = try {
            val stream = identifier.createStream()
            try {
                stream.acceptWaveform(x, SAMPLE_RATE)
                identifier.compute(stream)
            } finally { stream.release() }
        } finally {
            timeline?.emit(TimelineEvent("lidEnd", samples = x.size))
        }
        language = if (detected in recognizers) detected else v.lid.fallback
        timeline?.emit(TimelineEvent("lidDecision", samples = x.size, language = language, fallback = detected !in recognizers))
        Log.i(TAG, "language detected: '$detected', using $language")
        return detected
    }

    override fun transcribe(samples: FloatArray): String =
        recognizers.getValue(language).transcribe(samples, if (perStream) mapOf("language" to language) else emptyMap())

    fun release() { try { recognizers.values.toSet().forEach { it.release() } } finally { identifier.release() } }
}
