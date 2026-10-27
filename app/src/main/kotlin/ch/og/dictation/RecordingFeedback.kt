package ch.og.dictation

import kotlin.math.sqrt

/** A take's latest loudness only. Never retains samples or queues work on the UI thread. */
class RecordingFeedback {
    @Volatile private var ended = false
    @Volatile var isCapturing = false
        private set
    @Volatile private var measured = 0f

    val level: Float get() = if (isCapturing) measured else 0f

    fun start() {
        if (!ended) isCapturing = true
    }

    /** Called alongside the existing transcription feed on the recording thread. */
    fun accept(samples: FloatArray) {
        if (!isCapturing || samples.isEmpty()) return
        var energy = 0.0
        for (sample in samples) energy += sample.toDouble() * sample
        val rms = sqrt(energy / samples.size)
        val value = (sqrt((rms - 0.0008).coerceAtLeast(0.0)) * 2.6).coerceIn(0.0, 1.0).toFloat()
        if (!ended) measured = value
    }

    fun stop() {
        ended = true
        isCapturing = false
        measured = 0f
    }
}
