package ch.og.dictation

import android.content.Context
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Benchmark mode (plan D4): the only code that writes audio to storage. Off by
 * default; when the user switches it on, each take is kept as <timestamp>.wav plus
 * <timestamp>.json under files/benchmark until he deletes it in the settings.
 */
class BenchmarkStore(private val dir: File) {
    companion object {
        private const val PREFS = "settings"
        private const val KEY_ENABLED = "benchmark"

        fun of(context: Context) = BenchmarkStore(File(context.filesDir, "benchmark"))

        fun isEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

        fun setEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
        }

        fun timestamp(now: Date = Date()): String =
            SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.ROOT).format(now)

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

    fun takeCount(): Int = dir.listFiles { f -> f.name.endsWith(".wav") }?.size ?: 0

    /** Removes everything under the benchmark directory; returns true when nothing is left. */
    fun deleteAll(): Boolean = !dir.exists() || dir.deleteRecursively()
}
