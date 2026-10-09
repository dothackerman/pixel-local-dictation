package ch.og.dictation

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

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

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private var record: AudioRecord? = null
    private val buffer = ByteArrayOutputStream()

    val isRunning: Boolean get() = running.get()

    /** @throws SecurityException when RECORD_AUDIO is not granted. */
    fun start() {
        if (!running.compareAndSet(false, true)) return
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, CHANNEL, ENCODING, minBuf * 4)
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release(); running.set(false)
            throw IllegalStateException("AudioRecord failed to initialise")
        }
        record = rec
        buffer.reset()
        rec.startRecording()
        thread = Thread {
            val chunk = ByteArray(minBuf)
            while (running.get()) {
                val n = rec.read(chunk, 0, chunk.size)
                if (n < 0) { running.set(false); break }  // device error or interruption
                if (n > 0) synchronized(buffer) { buffer.write(chunk, 0, n) }
            }
        }.also { it.start() }
    }

    /** Stops capture and returns the PCM bytes; the internal copy is discarded. */
    fun stop(): ByteArray {
        if (!running.compareAndSet(true, false)) return ByteArray(0)
        thread?.join(); thread = null
        record?.run { stop(); release() }; record = null
        val pcm = synchronized(buffer) { buffer.toByteArray().also { buffer.reset() } }
        return pcm
    }

    fun cancel() { stop().fill(0) }
}
