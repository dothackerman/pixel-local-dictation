package ch.og.dictation.diagnostics

import ch.og.dictation.recognition.SAMPLE_RATE
import ch.og.dictation.take.TakeOutcome
import ch.og.dictation.wiring
import ch.og.dictation.take.TakeResult
import android.content.Context
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject

/**
 * Benchmark mode (plan D4): the only code that writes audio to storage, and only in the lab
 * build (ADR-029). Off by default; switched on, each take is kept as <timestamp>.wav plus
 * <timestamp>.json in Storage's recordings folder until it is deleted in the settings. Release
 * never saves; it counts and deletes what earlier builds left.
 */
class BenchmarkStore(private val dir: File) {
    companion object {
        private const val KEY_ENABLED = "benchmark"

        /** Shared retention gate for benchmark now and future content writers. */
        fun shouldSave(enabled: Boolean, result: TakeResult): Boolean =
            enabled && result.retentionEligible && !result.interrupted && result.outcome !in setOf(TakeOutcome.CANCELLED, TakeOutcome.INPUT_CHANGED)

        fun recordingEnabled(optIn: Boolean, liveReceipt: Boolean): Boolean = optIn && !liveReceipt

        fun of(context: Context) = BenchmarkStore(context.wiring.storage.recordingsRoot)

        fun isEnabled(context: Context): Boolean =
            context.wiring.storage.let { it.savesRecordings && it.settings.flag(KEY_ENABLED) }

        fun setEnabled(context: Context, enabled: Boolean) = context.wiring.storage.settings.setFlag(KEY_ENABLED, enabled)

        fun timestamp(now: Date = Date()): String =
            SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.ROOT).format(now)

        /** The .json beside a saved take: [app] version, recognizer, the take's timing, outcome and acknowledgements. */
        fun receipt(app: String?, pcm: ByteArray, result: TakeResult): String {
            fun ms(from: String, to: String): Long {
                val start = result.eventsNs[from]
                val end = result.eventsNs[to]
                return if (start != null && end != null) (end - start) / 1_000_000 else 0
            }
            return JSONObject()
                .put("app", app)
                .put("recognizer", "parakeet-v3-matmul8 sherpa-onnx-1.13.8")
                .put("text", result.text ?: JSONObject.NULL)
                .put("audioSeconds", pcm.size / 2.0 / SAMPLE_RATE)
                .put("recordingMs", ms("captureStart", "stopRequested"))
                .put("stopToFinalMs", ms("stopRequested", "finalReady"))
                .put("timingProtocol", "dictation-session-v1")
                .put("eventsNs", JSONObject(result.eventsNs))
                .put("outcome", result.outcome.name)
                .put("commitAccepted", result.commitAccepted ?: JSONObject.NULL)
                .put("returnAccepted", result.returnAccepted ?: JSONObject.NULL)
                .toString(2)
        }

        /** 16-bit mono PCM wrapped in a canonical 44-byte WAV header. */
        fun wav(pcm: ByteArray, sampleRate: Int): ByteArray {
            val b = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN)
            b.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36 + pcm.size)
            b.put("WAVE".toByteArray(Charsets.US_ASCII))
            b.put("fmt ".toByteArray(Charsets.US_ASCII)).putInt(16)
            b.putShort(1).putShort(1).putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16)
            b.put("data".toByteArray(Charsets.US_ASCII)).putInt(pcm.size)
            b.put(pcm)
            return b.array()
        }
    }

    /** Writes one take; returns the base name shared by the .wav and .json files. */
    fun save(pcm: ByteArray, sampleRate: Int, json: String, name: String = timestamp()): String {
        dir.mkdirs()
        File(dir, "$name.wav").writeBytes(wav(pcm, sampleRate))
        File(dir, "$name.json").writeText(json)
        return name
    }

    /** Applies the take-latched privacy decision at the file-writing boundary. */
    fun saveIfEligible(enabled: Boolean, result: TakeResult, pcm: ByteArray, sampleRate: Int, json: String): String? =
        if (shouldSave(enabled, result)) save(pcm, sampleRate, json) else null

    fun takeCount(): Int = dir.listFiles { f -> f.name.endsWith(".wav") }?.size ?: 0

    /** Removes everything under the benchmark directory; returns true when nothing is left. */
    fun deleteAll(): Boolean = !dir.exists() || dir.deleteRecursively()
}
