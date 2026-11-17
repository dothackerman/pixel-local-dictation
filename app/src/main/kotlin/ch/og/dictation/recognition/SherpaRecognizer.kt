package ch.og.dictation.recognition

import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import java.io.File

private const val TAG = "Recognizer"

/** One sherpa-onnx offline recognizer (Parakeet transducer or Whisper), configured from a [Variant]. */
class SherpaRecognizer(
    private val recognizer: OfflineRecognizer, private val label: String,
    private val qwenVariant: Variant? = null,
) : Recognizer {
    companion object {
        fun parakeet(v: Variant, m: ModelFiles, hotwordsPath: String = "", perStreamDictionary: Boolean = false): SherpaRecognizer {
            val config = OfflineRecognizerConfig()
            config.modelConfig.transducer.encoder = m.onnx("encoder")
            config.modelConfig.transducer.decoder = m.onnx("decoder")
            config.modelConfig.transducer.joiner = m.onnx("joiner")
            config.modelConfig.tokens = m.tokens()
            config.modelConfig.modelType = "nemo_transducer"
            config.modelConfig.numThreads = v.threads
            config.modelConfig.provider = RuntimeConfig.provider(v, m)
            config.decodingMethod = v.decoding.method
            config.maxActivePaths = v.decoding.maxActivePaths
            config.blankPenalty = v.decoding.blankPenalty
            if (hotwordsPath.isNotEmpty() || perStreamDictionary) {
                // Dictionary (ADR-005): hotwords need the model's BPE vocabulary next to the ONNX files.
                config.hotwordsFile = hotwordsPath
                config.hotwordsScore = v.hotwords.score
                config.modelConfig.modelingUnit = "bpe"
                config.modelConfig.bpeVocab = m.suffixed(".vocab")
            }
            return load(config, m, "parakeet")
        }

        fun qwen3(v: Variant, m: ModelFiles, hotwordsPath: String = ""): SherpaRecognizer {
            val config = OfflineRecognizerConfig()
            config.modelConfig.qwen3Asr.convFrontend = m.onnx("conv_frontend")
            config.modelConfig.qwen3Asr.encoder = m.onnx("encoder")
            config.modelConfig.qwen3Asr.decoder = m.onnx("decoder")
            config.modelConfig.qwen3Asr.tokenizer = m.root()
            if (hotwordsPath.isNotEmpty()) {
                config.modelConfig.qwen3Asr.hotwords = File(hotwordsPath).readLines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(",")
            }
            v.qwen3.checkBudget(v.cutting.maxChunkS, config.modelConfig.qwen3Asr.hotwords)
            // Dynamic KV dimensions are fixed from these constructor settings by sherpa.
            config.modelConfig.qwen3Asr.maxTotalLen = v.qwen3.maxTotalLen
            config.modelConfig.qwen3Asr.maxNewTokens = v.qwen3.maxNewTokens
            config.modelConfig.numThreads = v.threads
            config.modelConfig.provider = RuntimeConfig.provider(v, m)
            return load(config, m, "qwen3", v)
        }

        fun cohere(v: Variant, m: ModelFiles, language: String = v.cohere.language): SherpaRecognizer {
            val config = OfflineRecognizerConfig()
            config.modelConfig.cohereTranscribe.encoder = m.onnx("encoder")
            config.modelConfig.cohereTranscribe.decoder = m.onnx("decoder")
            config.modelConfig.cohereTranscribe.language = language
            config.modelConfig.cohereTranscribe.usePunct = v.cohere.usePunct
            config.modelConfig.cohereTranscribe.useItn = v.cohere.useItn
            config.modelConfig.tokens = m.tokens()
            config.modelConfig.numThreads = v.threads
            config.modelConfig.provider = RuntimeConfig.provider(v, m)
            return load(config, m, "cohere")
        }

        fun whisper(v: Variant, m: ModelFiles): SherpaRecognizer {
            val config = OfflineRecognizerConfig()
            config.modelConfig.whisper.encoder = m.onnx("encoder")
            config.modelConfig.whisper.decoder = m.onnx("decoder")
            config.modelConfig.whisper.language = v.whisper.language
            config.modelConfig.whisper.task = "transcribe"
            config.modelConfig.whisper.tailPaddings = v.whisper.tailPaddings
            config.modelConfig.tokens = m.tokens()
            config.modelConfig.numThreads = v.threads
            config.modelConfig.provider = RuntimeConfig.provider(v, m)
            return load(config, m, "whisper")
        }

        fun canary(v: Variant, m: ModelFiles, lang: String): SherpaRecognizer {
            val config = OfflineRecognizerConfig()
            config.modelConfig.canary.encoder = m.onnx("encoder")
            config.modelConfig.canary.decoder = m.onnx("decoder")
            config.modelConfig.canary.srcLang = lang
            config.modelConfig.canary.tgtLang = lang
            config.modelConfig.tokens = m.tokens()
            config.modelConfig.numThreads = v.threads
            config.modelConfig.provider = RuntimeConfig.provider(v, m)
            return load(config, m, "canary-$lang")
        }

        private fun load(config: OfflineRecognizerConfig, m: ModelFiles, label: String, qwenVariant: Variant? = null): SherpaRecognizer {
            val started = SystemClock.elapsedRealtime()
            val rec = OfflineRecognizer(m.assets, config)
            Log.i(TAG, "$label loaded in ${SystemClock.elapsedRealtime() - started} ms, pid ${Process.myPid()}")
            return SherpaRecognizer(rec, label, qwenVariant)
        }
    }

    override fun transcribe(samples: FloatArray): String = transcribe(samples, emptyMap())

    /** [options] are set on the stream before decoding (Cohere: "language"). */
    fun transcribe(samples: FloatArray, options: Map<String, String>, dictionary: String? = null): String {
        qwenVariant?.let {
            require(samples.size <= (it.cutting.maxChunkS * SAMPLE_RATE).toInt()) { "qwen3: input exceeds validated chunk duration" }
        }
        val started = SystemClock.elapsedRealtime()
        val stream = if (dictionary == null) recognizer.createStream() else recognizer.createStream(dictionary)
        try {
            for ((k, value) in options) stream.setOption(k, value)
            stream.acceptWaveform(samples, SAMPLE_RATE)
            recognizer.decode(stream)
            val result = recognizer.getResult(stream)
            qwenVariant?.let {
                // sherpa removes up to 16 generated language/scaffold tokens from the result.
                require(result.tokens.size < it.qwen3.maxNewTokens - 16) {
                    "qwen3: result approaches generation limit; increase budget or shorten chunks"
                }
            }
            val text = result.text
            Log.i(TAG, "%s decoded %.1f s in %d ms, pid %d".format(
                label, samples.size / SAMPLE_RATE.toDouble(), SystemClock.elapsedRealtime() - started, Process.myPid()))
            return text
        } finally {
            stream.release()
        }
    }

    fun release() = recognizer.release()
}
