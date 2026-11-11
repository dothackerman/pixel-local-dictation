package ch.og.dictation

import ch.og.dictation.EditorDecision.INTERRUPT
import ch.og.dictation.EditorDecision.NOTHING
import org.junit.Assert.assertEquals
import org.junit.Test

/** The one place that decides what an editor event does to the current take. */
class EditorEventPolicyTest {
    private val ending = listOf(EditorEvent.INPUT_START, EditorEvent.INPUT_VIEW_FINISH, EditorEvent.INPUT_FINISH,
        EditorEvent.WINDOW_HIDDEN, EditorEvent.SCREEN_OFF)
    private fun take(sameSession: Boolean = true, returning: Boolean = false) =
        TakeFacts(active = true, returning = returning, sameSession = sameSession)

    @Test fun everyEndingEventInterruptsATake() {
        for (event in ending) assertEquals(event.name, INTERRUPT, EditorEventPolicy.decide(event, take()))
    }

    @Test fun viewStartKeepsTheTakeOnlyOnItsOwnSession() {
        assertEquals(NOTHING, EditorEventPolicy.decide(EditorEvent.INPUT_VIEW_START, take(sameSession = true)))
        assertEquals(INTERRUPT, EditorEventPolicy.decide(EditorEvent.INPUT_VIEW_START, take(sameSession = false)))
    }

    @Test fun handBackToTheKeyboardIsNotAnInterruptionButScreenOffIs() {
        for (event in ending + EditorEvent.INPUT_VIEW_START) {
            val expected = if (event == EditorEvent.SCREEN_OFF) INTERRUPT else NOTHING
            assertEquals(event.name, expected, EditorEventPolicy.decide(event, take(sameSession = false, returning = true)))
        }
    }

    @Test fun identifiedRotationDuringCaptureRevokesAuthorityButKeepsCapture() {
        for (event in ending - EditorEvent.SCREEN_OFF) {
            assertEquals(event.name, EditorDecision.REVOKE_AUTHORITY,
                EditorEventPolicy.decide(event, take(sameSession = false).copy(rotation = true)))
        }
    }

    @Test fun panelReturningOnANewSessionAfterARotationKeepsTheTake() {
        assertEquals(NOTHING, EditorEventPolicy.decide(EditorEvent.INPUT_VIEW_START, take(sameSession = false).copy(rotation = true)))
    }

    @Test fun screenOffInterruptsEvenDuringARotation() {
        assertEquals(INTERRUPT, EditorEventPolicy.decide(EditorEvent.SCREEN_OFF, take().copy(rotation = true)))
    }

    /** Stop wait and an interrupted take have no capture to keep; rotation there is #53. */
    @Test fun rotationWithoutCaptureStillInterrupts() {
        for (event in ending) {
            assertEquals(event.name, INTERRUPT, EditorEventPolicy.decide(event, take().copy(rotation = true, capturing = false)))
        }
        assertEquals(INTERRUPT, EditorEventPolicy.decide(EditorEvent.INPUT_VIEW_START,
            take(sameSession = false).copy(rotation = true, capturing = false)))
    }

    @Test fun withoutATakeNothingHappens() {
        for (event in EditorEvent.entries) {
            assertEquals(event.name, NOTHING, EditorEventPolicy.decide(event, TakeFacts(active = false, returning = false, sameSession = false)))
        }
    }
}
