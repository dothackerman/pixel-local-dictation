package ch.og.dictation

import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private const val TAG = "Recognizer"
private const val MODEL = "parakeet-v3"
const val SAMPLE_RATE = 16_000

/**
 * Parakeet TDT 0.6B v3 through sherpa-onnx (ADR-003). Model files are read from the
 * APK's assets; nothing is downloaded. Use from [Engine.decodeQueue] only.
 */
class ParakeetRecognizer(context: Context, threads: Int = 4) : Recognizer {
    private val recognizer: OfflineRecognizer

    init {
        val started = SystemClock.elapsedRealtime()
        val files = context.assets.list(MODEL).orEmpty()
        fun part(stem: String) = "$MODEL/" + files.single { it.startsWith(stem) && it.endsWith(".onnx") }
        val config = OfflineRecognizerConfig()
        config.modelConfig.transducer.encoder = part("encoder")
        config.modelConfig.transducer.decoder = part("decoder")
        config.modelConfig.transducer.joiner = part("joiner")
        config.modelConfig.tokens = "$MODEL/tokens.txt"
        config.modelConfig.modelType = "nemo_transducer"
        config.modelConfig.numThreads = threads
        recognizer = OfflineRecognizer(context.assets, config)
        Log.i(TAG, "model loaded in ${SystemClock.elapsedRealtime() - started} ms, pid ${Process.myPid()}")
    }

    override fun transcribe(samples: FloatArray): String {
        val started = SystemClock.elapsedRealtime()
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            recognizer.decode(stream)
            val text = recognizer.getResult(stream).text
            Log.i(TAG, "decoded %.1f s in %d ms, pid %d".format(
                samples.size / SAMPLE_RATE.toDouble(), SystemClock.elapsedRealtime() - started, Process.myPid()))
            return text
        } finally {
            stream.release()
        }
    }
}

/**
 * Silero VAD on the live stream. Starting parameters from docs/plan.md: 0.8 s of silence
 * ends a segment, 20 s is the longest segment, segments under 2 s join the next one.
 * One thread at a time: the recording thread during a take, the main thread around it.
 */
class VadSegmenter(private val context: Context) : Segmenter {
    private var vad: Vad? = null
    private val merger = ShortSegmentMerger(2 * SAMPLE_RATE)

    private fun vad(): Vad = vad ?: run {
        val config = VadModelConfig()
        config.sileroVadModelConfig.model = "$MODEL/silero_vad.onnx"
        config.sileroVadModelConfig.minSilenceDuration = 0.8f
        config.sileroVadModelConfig.minSpeechDuration = 0.25f
        config.sileroVadModelConfig.maxSpeechDuration = 20f
        config.sampleRate = SAMPLE_RATE
        Vad(context.assets, config).also { vad = it }
    }

    override fun accept(samples: FloatArray): List<FloatArray> {
        vad().acceptWaveform(samples)
        return drain()
    }

    override fun flush(): List<FloatArray> {
        vad().flush()
        return drain() + listOfNotNull(merger.rest())
    }

    override fun reset() {
        vad?.reset()
        merger.rest()
    }

    private fun drain(): List<FloatArray> {
        val v = vad()
        val out = ArrayList<FloatArray>()
        while (!v.empty()) { val s = v.front(); v.pop(); merger.add(s.samples)?.let(out::add) }
        return out
    }
}

/** Process-wide: the model is loaded once and reused for every take while the process lives. */
object Engine {
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
        recognizer ?: SwissSpelling(ParakeetRecognizer(context.applicationContext)).also { recognizer = it }

    @Synchronized fun segmenter(context: Context): Segmenter =
        segmenter ?: VadSegmenter(context.applicationContext).also { segmenter = it }
}
