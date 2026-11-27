package ch.og.dictation.panel

import ch.og.dictation.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** Golden files hold frames as text (#77): a line names everything the frame holds and reads back as the same frame. */
@RunWith(RobolectricTestRunner::class)
class FrameTextTest {
    private val resources = RuntimeEnvironment.getApplication().resources

    @Test fun aRecordingFrameIsOneReadableLine() {
        val frame = PanelFrame(stopGlyph = true, backLabel = R.string.discard_recording, status = R.string.status_recording,
            microphoneEnabled = true, microphoneLabel = R.string.stop_recording, preview = "first words", ringLive = true)
        assertEquals("[new panel] status_recording | microphone on: stop_recording | back: discard_recording | " +
            "stopGlyph ringLive | preview: \"first words\"", FrameText.line(frame, fresh = true, resources))
    }

    @Test fun everyFieldReadsBackAsWritten() {
        val frames = listOf(
            PanelFrame(),
            PanelFrame(choice = PanelChoice.RECORDING, stopGlyph = true, backLabel = R.string.continue_recording, status = R.string.status_paused,
                microphoneLabel = R.string.status_paused, preview = "a | preview: \"quoted\" line\nand another", notice = R.string.retention_field_off),
            PanelFrame(choice = PanelChoice.FINISHING, backLabel = R.string.keep_finishing, status = R.string.status_finishing_choice, banner = true),
            PanelFrame(preparing = true, backLabel = R.string.back_hold_and_choose, status = R.string.status_finishing),
            PanelFrame(unableToInsert = true, status = R.string.status_unable_insert, microphoneEnabled = true, microphoneLabel = R.string.start_recording),
            PanelFrame(held = true, uncertain = true, backLabel = R.string.back_discard_text, status = R.string.status_back_warning, preview = "held"),
        )
        for (frame in frames) for (fresh in listOf(true, false)) {
            val line = FrameText.line(frame, fresh, resources)
            assertFalse(line, line.contains('\n'))
            assertTrue(FrameText.isFrame(line))
            assertEquals(line, frame, FrameText.frame(line, resources))
            assertEquals(fresh, line.startsWith("[new panel] "))
        }
        assertEquals(frames.size, frames.map { FrameText.line(it, false, resources) }.toSet().size)
    }

    @Test fun aStepIsNoFrame() {
        assertFalse(FrameText.isFrame("> Stop"))
    }
}
