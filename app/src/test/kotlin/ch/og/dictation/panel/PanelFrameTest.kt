package ch.og.dictation.panel

import ch.og.dictation.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure panel: one frame per take state (#65 step 5). */
class PanelFrameTest {
    @Test fun eachPhaseIsOneStatusAndOneMicrophoneState() {
        val table = mapOf(
            PanelPhase.OPENING to Triple(R.string.status_opening, false, R.string.status_opening),
            PanelPhase.RECORDING to Triple(R.string.status_recording, true, R.string.stop_recording),
            PanelPhase.PAUSED to Triple(R.string.status_paused, false, R.string.status_paused),
            PanelPhase.FINISHING to Triple(R.string.status_finishing, false, R.string.status_finishing),
            PanelPhase.FINISHING_CHOICE to Triple(R.string.status_finishing_choice, false, R.string.status_finishing),
            PanelPhase.STOPPING to Triple(R.string.status_stopping_recording, false, R.string.status_stopping_recording),
            PanelPhase.RELEASE_FAILED to Triple(R.string.status_release_failed, true, R.string.retry_release),
            PanelPhase.IDLE to Triple(R.string.status_idle, true, R.string.start_recording),
            PanelPhase.NO_PERMISSION to Triple(R.string.status_no_permission, false, R.string.status_no_permission),
            PanelPhase.FAILED to Triple(R.string.status_failed, true, R.string.retry_recording),
            PanelPhase.NOT_INSERTED to Triple(R.string.status_unable_insert, true, R.string.start_recording),
            PanelPhase.NOT_INSERTED_WAITING to Triple(R.string.status_unable_insert, false, R.string.status_stopping_recording),
            PanelPhase.IDLE_WAITING to Triple(R.string.status_idle, false, R.string.status_stopping_recording),
        )
        assertEquals(PanelPhase.entries.toSet(), table.keys)
        for ((phase, expected) in table) {
            val frame = panelFrame(PanelState(phase = phase))
            assertEquals(phase.name, expected, Triple(frame.status, frame.microphoneEnabled, frame.microphoneLabel))
            assertEquals(phase.name, phase.status == R.string.status_unable_insert, frame.unableToInsert)
        }
    }

    @Test fun recordingPreparingAndChoicesSetGlyphStageAndBack() {
        val recording = panelFrame(PanelState(PanelPhase.RECORDING, running = true))
        assertTrue(recording.stopGlyph); assertFalse(recording.preparing)
        assertEquals(R.string.discard_recording, recording.backLabel)
        val preparing = panelFrame(PanelState(PanelPhase.FINISHING, running = true, finishing = true))
        assertTrue(preparing.preparing); assertFalse(preparing.stopGlyph)
        assertEquals(R.string.back_hold_and_choose, preparing.backLabel)
        val paused = panelFrame(PanelState(PanelPhase.PAUSED, PanelChoice.RECORDING, running = true))
        assertEquals(R.string.continue_recording, paused.backLabel)
        assertTrue("a paused take keeps the Stop glyph under its choice", paused.stopGlyph)
        val finishingChoice = panelFrame(PanelState(PanelPhase.FINISHING_CHOICE, PanelChoice.FINISHING, running = true, finishing = true))
        assertEquals(R.string.keep_finishing, finishingChoice.backLabel)
        assertFalse("the open choice replaces Preparing text", finishingChoice.preparing)
        val interrupted = panelFrame(PanelState(PanelPhase.STOPPING, running = false, finishing = true))
        assertFalse(interrupted.preparing); assertFalse(interrupted.stopGlyph)
        assertEquals(R.string.back_to_keyboard, interrupted.backLabel)
    }

    @Test fun heldTextOwnsStatusAndBack() {
        val cases = listOf(
            HeldPanel(inHistory = true) to R.string.status_unable_history,
            HeldPanel(inHistory = false) to R.string.status_unable_unsaved,
            HeldPanel(inHistory = true, unconfirmed = true) to R.string.status_unconfirmed,
            HeldPanel(inHistory = true, warned = true, unconfirmed = true) to R.string.status_back_warning,
        )
        for ((held, status) in cases) {
            val frame = panelFrame(PanelState(PanelPhase.NOT_INSERTED, held = held, preview = "held words"))
            assertTrue(frame.held)
            assertEquals(status, frame.status)
            assertEquals(held.unconfirmed, frame.uncertain)
            assertEquals(if (held.warned) R.string.back_discard_text else R.string.back_to_keyboard, frame.backLabel)
            assertEquals("held words", frame.preview)
        }
    }

    @Test fun privateFieldBannerAndRingCarryOver() {
        val frame = panelFrame(PanelState(PanelPhase.RECORDING, running = true, privateField = true, banner = true, ringLive = true))
        assertEquals(R.string.retention_field_off, frame.notice)
        assertTrue(frame.banner); assertTrue(frame.ringLive)
        assertNull(panelFrame(PanelState()).notice)
    }
}
