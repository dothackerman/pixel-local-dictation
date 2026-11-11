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

    @Test fun withoutATakeNothingHappens() {
        for (event in EditorEvent.entries) {
            assertEquals(event.name, NOTHING, EditorEventPolicy.decide(event, TakeFacts(active = false, returning = false, sameSession = false)))
        }
    }
}
