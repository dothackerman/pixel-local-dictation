package ch.og.dictation

import android.os.SystemClock
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Shared benchmark-only WAV reader and real-time feeder; input samples are never written. */
object SyntheticFixtureAudio {
    fun file(filesDir: File, fixtureId: String): File {
        val name = when (fixtureId) {
            "continuous" -> "recovery_continuous_60s.wav"
            "paused" -> "recovery_paused_60s.wav"
            else -> error("unknown synthetic fixture")
        }
        return File(filesDir, "lab/issue36-inputs/$name")
    }

    fun read(file: File): FloatArray {
        val bytes = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        require(bytes.getInt(0) == 0x46464952 && bytes.getInt(8) == 0x45564157)
        require(bytes.getInt(12) == 0x20746d66 && bytes.getInt(16) == 16)
        require(bytes.getShort(20).toInt() == 1 && bytes.getShort(22).toInt() == 1)
        require(bytes.getInt(24) == SAMPLE_RATE && bytes.getShort(34).toInt() == 16)
        require(bytes.getInt(36) == 0x61746164 && bytes.getInt(40) == bytes.limit() - 44)
        return FloatArray((bytes.limit() - 44) / 2) { bytes.getShort(44 + it * 2) / 32768f }
    }

    fun feedPaced(audio: FloatArray, accept: (FloatArray) -> Unit, beforeBlock: () -> Unit = {}) {
        val started = System.nanoTime()
        var offset = 0
        while (offset < audio.size) {
            beforeBlock()
            val end = minOf(offset + 640, audio.size)
            accept(audio.copyOfRange(offset, end))
            val waitMs = (started + end * 1_000_000_000L / SAMPLE_RATE - System.nanoTime()) / 1_000_000L
            if (waitMs > 0) SystemClock.sleep(waitMs)
            offset = end
        }
    }
}
