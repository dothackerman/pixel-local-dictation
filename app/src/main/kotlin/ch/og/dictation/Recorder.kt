package ch.og.dictation

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream

/** Mono 16 kHz PCM capture, in memory only, released on stop/cancel. */
class Recorder internal constructor(private val createInput: () -> AudioInput) {
    constructor() : this({ androidInput() })

    internal interface AudioInput {
        val bufferSize: Int
        fun start()
        fun read(buffer: ShortArray): Int
        fun stop()
        fun release()
    }

    companion object {
        @SuppressLint("MissingPermission")
        private fun androidInput(): AudioInput {
            val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf * 4, SAMPLE_RATE * 2))
            return object : AudioInput {
                override val bufferSize = minBuf
                override fun start() {
                    check(rec.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord failed to initialise" }
                    rec.startRecording()
                }
                override fun read(buffer: ShortArray) = rec.read(buffer, 0, buffer.size)
                override fun stop() { if (rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) rec.stop() }
                override fun release() = rec.release()
            }
        }
    }

    @Volatile private var capturing = false
    private var thread: Thread? = null
    private var record: AudioInput? = null
    private val buffer = ByteArrayOutputStream()
    val isRunning: Boolean get() = record != null

    fun start(onSamples: (FloatArray) -> Unit = {}, onStarted: (() -> Unit)? = null, onEnded: (() -> Unit)? = null) {
        check(record == null) { "capture still owned" }
        val rec = createInput()
        record = rec
        buffer.reset()
        try { rec.start() } catch (failure: Throwable) {
            try { rec.release(); record = null } catch (_: Throwable) { }
            throw failure
        }
        onStarted?.let { runCatching(it) }
        capturing = true
        thread = Thread {
            val chunk = ShortArray(rec.bufferSize / 2)
            val bytes = ByteArray(rec.bufferSize)
            try {
                while (capturing) {
                    val n = try { rec.read(chunk) } catch (failure: Throwable) {
                        if (!capturing) break else throw failure
                    }
                    if (n < 0) break
                    if (n == 0) continue
                    for (i in 0 until n) {
                        bytes[2 * i] = chunk[i].toByte(); bytes[2 * i + 1] = (chunk[i].toInt() shr 8).toByte()
                    }
                    synchronized(buffer) { buffer.write(bytes, 0, 2 * n) }
                    onSamples(FloatArray(n) { chunk[it] / 32768f })
                }
            } catch (_: Throwable) {
                // Capture ended unexpectedly; the owner still releases the device.
            } finally { onEnded?.let { runCatching(it) } }
        }.also { it.start() }
    }

    /** Stop the platform read before joining; return only after callbacks have drained. */
    fun stop(): ByteArray {
        capturing = false
        val rec = record
        var failure: Throwable? = null
        try { rec?.stop() } catch (e: Throwable) { failure = e }
        var released = false
        if (failure != null) try { rec?.release(); released = true } catch (_: Throwable) {}
        // A failed stop and failed release cannot guarantee the read will unblock.
        // Keep the thread and device owned so a later release can be retried.
        if (failure != null && !released) throw failure
        thread?.join()
        thread = null
        if (!released) try { rec?.release(); released = true } catch (e: Throwable) { if (failure == null) failure = e }
        if (released) record = null
        if (failure != null) {
            synchronized(buffer) { buffer.reset() }
            throw failure
        }
        return synchronized(buffer) { buffer.toByteArray().also { buffer.reset() } }
    }

    fun cancel() { stop().fill(0) }
}
