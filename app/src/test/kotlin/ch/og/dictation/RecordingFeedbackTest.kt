package ch.og.dictation

import org.junit.Assert.*
import org.junit.Test

class RecordingFeedbackTest {
    @Test fun `only actual capture produces a level and samples are unchanged`() {
        val feedback = RecordingFeedback()
        val samples = floatArrayOf(0.1f, -0.1f)
        feedback.accept(samples)
        assertEquals(0f, feedback.level, 0f)
        feedback.start()
        feedback.accept(samples)
        assertTrue(feedback.level > 0f)
        assertArrayEquals(floatArrayOf(0.1f, -0.1f), samples, 0f)
    }

    @Test fun `silence settles to zero and louder audio is stronger but bounded`() {
        val feedback = RecordingFeedback().apply { start() }
        feedback.accept(floatArrayOf(0.0003f, -0.0003f))
        assertEquals(0f, feedback.level, 0f)
        feedback.accept(floatArrayOf(0.01f, -0.01f))
        val quiet = feedback.level
        feedback.accept(floatArrayOf(0.1f, -0.1f))
        assertTrue(feedback.level > quiet)
        feedback.accept(floatArrayOf(1f, -1f))
        assertEquals(1f, feedback.level, 0f)
    }

    @Test fun `late audio and start callbacks cannot revive an ended take`() {
        val old = RecordingFeedback().apply { start(); accept(floatArrayOf(0.2f)) }
        old.stop()
        old.start()
        old.accept(floatArrayOf(1f))
        assertFalse(old.isCapturing)
        assertEquals(0f, old.level, 0f)
        val next = RecordingFeedback().apply { start() }
        assertTrue(next.isCapturing)
        assertEquals(0f, next.level, 0f)
    }
}
