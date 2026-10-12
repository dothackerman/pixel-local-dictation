package ch.og.dictation

import android.content.Context
import android.content.res.AssetManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.SpokenLanguageIdentification
import com.k2fsa.sherpa.onnx.SpokenLanguageIdentificationConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private const val TAG = "Recognizer"
private const val ASSET_MODEL = "parakeet-v3"
const val SAMPLE_RATE = 16_000

/**
 * Where a model's files are: the APK's assets (daily app) or a directory on disk (laboratory,
 * pushed by tools/phone). sherpa-onnx takes a null AssetManager to mean file paths.
 */
class ModelFiles(val assets: AssetManager?, private val dir: String, private val names: List<String>) {
    companion object {
        fun asset(context: Context, dir: String) = ModelFiles(context.assets, dir, context.assets.list(dir).orEmpty().toList())
        fun dir(dir: File) = ModelFiles(null, dir.absolutePath, dir.list().orEmpty().toList())
    }

    /** The single .onnx file whose name contains [stem]; the int8 one when both precisions exist. */
    fun onnx(stem: String): String {
        val hits = names.filter { it.contains(stem) && it.endsWith(".onnx") }
        val pick = hits.singleOrNull() ?: hits.singleOrNull { it.contains(".int8.") }
        return "$dir/" + requireNotNull(pick) { "no unique *$stem*.onnx in $dir: $names" }
    }

    fun tokens(): String = "$dir/" + requireNotNull(names.singleOrNull { it.endsWith("tokens.txt") }) { "no tokens.txt in $dir" }
    fun suffixed(suffix: String): String = "$dir/" + requireNotNull(names.singleOrNull { it.endsWith(suffix) }) { "no unique *$suffix in $dir" }
    fun file(name: String): String = "$dir/$name"
    /** The directory itself (Qwen3's tokenizer files live next to the ONNX files). */
    fun root(): String = dir
}

/** One sherpa-onnx offline recognizer (Parakeet transducer or Whisper), configured from a [Variant]. */
class SherpaRecognizer(
    private val recognizer: OfflineRecognizer, private val label: String,
    private val qwenVariant: Variant? = null,
) : Recognizer {
    companion object {
        fun parakeet(v: Variant, m: ModelFiles, hotwordsPath: String = ""): SherpaRecognizer {
            val config = OfflineRecognizerConfig()
            config.modelConfig.transducer.encoder = m.onnx("encoder")
            config.modelConfig.transducer.decoder = m.onnx("decoder")
            config.modelConfig.transducer.joiner = m.onnx("joiner")
            config.modelConfig.tokens = m.tokens()
            config.modelConfig.modelType = "nemo_transducer"
            config.modelConfig.numThreads = v.threads
            config.decodingMethod = v.decoding.method
            config.maxActivePaths = v.decoding.maxActivePaths
            config.blankPenalty = v.decoding.blankPenalty
            if (hotwordsPath.isNotEmpty()) {
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
            return load(config, m, "canary-$lang")
        }

        private fun load(config: OfflineRecognizerConfig, m: ModelFiles, label: String, qwenVariant: Variant? = null): SherpaRecognizer {
            val started = SystemClock.elapsedRealtime()
            val rec = OfflineRecognizer(m.assets, config)
            Log.i(TAG, "$label loaded in ${SystemClock.elapsedRealtime() - started} ms, pid ${Process.myPid()}")
            return SherpaRecognizer(rec, label, qwenVariant)
        }
    }

    override fun transcribe(samples: FloatArray): String {
        qwenVariant?.let {
            require(samples.size <= (it.cutting.maxChunkS * SAMPLE_RATE).toInt()) { "qwen3: input exceeds validated chunk duration" }
        }
        val started = SystemClock.elapsedRealtime()
        val stream = recognizer.createStream()
        try {
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

/**
 * Canary and Cohere need the language as input: a Whisper-based spoken-language identifier looks at
 * the first chunk of each take (or its first probe seconds) and picks one of two recognizers.
 * Twin of class LanguageSwitched in tools/pipeline.py.
 */
class LanguageSwitchedRecognizer(private val v: Variant, lid: ModelFiles, build: (String) -> SherpaRecognizer) : Recognizer {
    private val recognizers = mapOf("de" to build("de"), "en" to build("en"))
    private val identifier: SpokenLanguageIdentification
    @Volatile private var language = v.lid.fallback

    init {
        val config = SpokenLanguageIdentificationConfig()
        config.whisper.encoder = lid.onnx("encoder")
        config.whisper.decoder = lid.onnx("decoder")
        config.numThreads = v.threads
        identifier = SpokenLanguageIdentification(lid.assets, config)
    }

    override fun beginTake(firstChunk: FloatArray): String {
        val probe = (v.lid.probeSeconds * SAMPLE_RATE).toInt()
        val x = if (probe > 0 && probe < firstChunk.size) firstChunk.copyOfRange(0, probe) else firstChunk
        val stream = identifier.createStream()
        val detected = try {
            stream.acceptWaveform(x, SAMPLE_RATE)
            identifier.compute(stream)
        } finally {
            stream.release()
        }
        language = if (detected in recognizers) detected else v.lid.fallback
        Log.i(TAG, "language detected: '$detected', using $language")
        return detected
    }

    override fun transcribe(samples: FloatArray): String = recognizers.getValue(language).transcribe(samples)

    fun release() { recognizers.values.forEach { it.release() }; identifier.release() }
}

/**
 * Silero VAD on the live stream finds the pauses; [PauseChunker] cuts there (parameters from the
 * [Variant], defaults from docs/plan.md). One thread at a time: the recording thread during a
 * take, the main thread around it.
 */
class VadSegmenter(private val v: Variant, private val vadModel: ModelFiles) : Segmenter {
    private var vad: Vad? = null
    private var chunker = newChunker()

    private fun newChunker() = PauseChunker(
        minSamples = (v.cutting.minChunkS * SAMPLE_RATE).toInt(), padSamples = (v.cutting.padS * SAMPLE_RATE).toInt(),
        maxSamples = (v.cutting.maxChunkS * SAMPLE_RATE).toInt())

    private fun vad(): Vad = vad ?: run {
        val config = VadModelConfig()
        config.sileroVadModelConfig.model = vadModel.file("silero_vad.onnx")
        config.sileroVadModelConfig.minSilenceDuration = v.cutting.minSilenceS
        config.sileroVadModelConfig.minSpeechDuration = v.cutting.minSpeechS
        config.sileroVadModelConfig.maxSpeechDuration = v.cutting.vadMaxSpeechS
        config.sampleRate = SAMPLE_RATE
        Vad(vadModel.assets, config).also { vad = it }
    }

    override fun accept(samples: FloatArray): List<Chunk> {
        val out = ArrayList<Chunk>()
        val d = vad()
        d.acceptWaveform(samples)
        chunker.append(samples, d.isSpeechDetected())?.let(out::add)
        while (!d.empty()) {
            val s = d.front(); d.pop()
            chunker.onSpeechEnd(s.start.toLong() + s.samples.size)?.let(out::add)
        }
        return out
    }

    override fun flush(): List<Chunk> = listOfNotNull(chunker.rest(vad?.isSpeechDetected() ?: false))

    /** A fresh detector per take, so its sample positions start at zero again. */
    override fun reset() {
        vad?.release(); vad = null
        chunker = newChunker()
    }
}

/** Builds the pipeline a [Variant] describes; shared by the daily app (assets) and LabTest (files). */
object Pipeline {
    fun recognizer(v: Variant, model: ModelFiles, lid: ModelFiles?, hotwordsPath: String = ""): Recognizer {
        val inner: Recognizer = when (v.family) {
            "parakeet" -> SherpaRecognizer.parakeet(v, model, hotwordsPath)
            "whisper" -> SherpaRecognizer.whisper(v, model)
            "canary" -> LanguageSwitchedRecognizer(v, requireNotNull(lid) { "canary needs the language identifier's files" }) { SherpaRecognizer.canary(v, model, it) }
            "qwen3" -> SherpaRecognizer.qwen3(v, model, hotwordsPath)
            "cohere" -> if (lid != null && v.cohere.language.isEmpty()) LanguageSwitchedRecognizer(v, lid) { SherpaRecognizer.cohere(v, model, it) }
                        else SherpaRecognizer.cohere(v, model)
            else -> throw IllegalArgumentException(v.family)
        }
        return if (v.post.swissSs) SwissSpelling(inner) else inner
    }

    fun release(r: Recognizer) {
        when (r) {
            is SwissSpelling -> release(r.inner)
            is SherpaRecognizer -> r.release()
            is LanguageSwitchedRecognizer -> r.release()
        }
    }
}

/** Process-wide: the model is loaded once and reused for every take while the process lives. */
object Engine {
    /** What the daily app runs (ADR-003); the laboratory overrides it per pushed variant. */
    val variant: Variant = Variant.DEFAULT

    /** Serial: model load first, then segments in order. */
    val decodeQueue: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "decode") }
    private var recognizer: Recognizer? = null   // decode thread only
    private var segmenter: VadSegmenter? = null

    /** Starts loading the model in the background; safe to call repeatedly. */
    fun warmUp(context: Context) {
        val app = context.applicationContext
        decodeQueue.execute { runCatching { recognizer(app) }.onFailure { Log.e(TAG, "model load failed", it) } }
    }

    /** Decode thread only. */
    fun recognizer(context: Context): Recognizer =
        recognizer ?: Pipeline.recognizer(variant, ModelFiles.asset(context.applicationContext, ASSET_MODEL), null).also { recognizer = it }

    @Synchronized fun segmenter(context: Context): Segmenter =
        segmenter ?: VadSegmenter(variant, ModelFiles.asset(context.applicationContext, ASSET_MODEL)).also { segmenter = it }
}
