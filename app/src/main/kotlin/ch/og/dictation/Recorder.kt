package ch.og.dictation

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream

/**
 * Mono 16 kHz PCM capture, in memory only, released on stop/cancel.
 * Audio never touches storage (requirements: privacy handling).
 */
class Recorder {
    companion object {
        const val SAMPLE_RATE = 16_000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }

    @Volatile private var capturing = false
    private var thread: Thread? = null
    private var record: AudioRecord? = null
    private val buffer = ByteArrayOutputStream()

    /** True from start until stop or cancel, also when the device ended the capture early. Main thread. */
    val isRunning: Boolean get() = thread != null

    /**
     * [onSamples] is called on the recording thread with each chunk as floats in [-1, 1].
     * @throws SecurityException when RECORD_AUDIO is not granted.
     */
    fun start(onSamples: (FloatArray) -> Unit = {}) {
        if (thread != null) return
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        // One second of headroom: the first chunk of a process also pays for setting up the segmenter.
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, CHANNEL, ENCODING,
            maxOf(minBuf * 4, SAMPLE_RATE * 2))
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IllegalStateException("AudioRecord failed to initialise")
        }
        record = rec
        buffer.reset()
        rec.startRecording()
        capturing = true
        thread = Thread {
            val chunk = ShortArray(minBuf / 2)
            val bytes = ByteArray(minBuf)
            while (capturing) {
                val n = rec.read(chunk, 0, chunk.size)
                if (n < 0) break  // device error or interruption; stop() still returns what was captured
                if (n == 0) continue
                for (i in 0 until n) {
                    bytes[2 * i] = chunk[i].toByte(); bytes[2 * i + 1] = (chunk[i].toInt() shr 8).toByte()
                }
                synchronized(buffer) { buffer.write(bytes, 0, 2 * n) }
                onSamples(FloatArray(n) { chunk[it] / 32768f })
            }
        }.also { it.start() }
    }

    /** Stops capture and returns the PCM bytes (16-bit little endian); the internal copy is discarded. */
    fun stop(): ByteArray {
        val t = thread ?: return ByteArray(0)
        capturing = false
        t.join(); thread = null
        record?.run { stop(); release() }; record = null
        val pcm = synchronized(buffer) { buffer.toByteArray().also { buffer.reset() } }
        return pcm
    }

    fun cancel() { stop().fill(0) }
}
