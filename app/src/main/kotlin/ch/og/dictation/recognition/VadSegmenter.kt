package ch.og.dictation.recognition

import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

/**
 * Silero VAD on the live stream finds the pauses; [PauseChunker] cuts there (parameters from the
 * [Variant], which defines the defaults). One thread at a time: the recording thread during a
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
