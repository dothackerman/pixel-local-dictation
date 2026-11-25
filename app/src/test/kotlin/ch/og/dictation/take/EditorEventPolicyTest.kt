package ch.og.dictation.take

import ch.og.dictation.take.EditorDecision.INTERRUPT
import ch.og.dictation.take.EditorDecision.NOTHING
import org.junit.Assert.assertEquals
import org.junit.Test

/** The one place that decides what an editor event does to the current take. */
class EditorEventPolicyTest {
    private val ending = listOf(EditorEvent.INPUT_START, EditorEvent.INPUT_VIEW_FINISH, EditorEvent.INPUT_FINISH,
        EditorEvent.WINDOW_HIDDEN, EditorEvent.SCREEN_OFF)
    /** A recording take with no rotation; tests vary one fact at a time with copy(). */
    private fun take(sameSession: Boolean = true, returning: Boolean = false) = TakeFacts(
        active = true, returning = returning, sameSession = sameSession, capturing = true, finishing = false, rotation = false, otherApp = false)

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

    /** Stop wait: the result Stop authorised is held and its authority moves with the panel (#53). */
    @Test fun identifiedRotationDuringTheStopWaitRevokesAuthorityButKeepsFinishing() {
        val stopWait = take(sameSession = false).copy(rotation = true, capturing = false, finishing = true)
        for (event in ending - EditorEvent.SCREEN_OFF) {
            assertEquals(event.name, EditorDecision.REVOKE_AUTHORITY, EditorEventPolicy.decide(event, stopWait))
        }
        assertEquals(NOTHING, EditorEventPolicy.decide(EditorEvent.INPUT_VIEW_START, stopWait))
        assertEquals(INTERRUPT, EditorEventPolicy.decide(EditorEvent.SCREEN_OFF, stopWait))
    }

    @Test fun stopWaitWithoutRotationStillInterrupts() {
        val stopWait = take().copy(capturing = false, finishing = true)
        for (event in ending) assertEquals(event.name, INTERRUPT, EditorEventPolicy.decide(event, stopWait))
        assertEquals(INTERRUPT, EditorEventPolicy.decide(EditorEvent.INPUT_VIEW_START, stopWait.copy(sameSession = false)))
    }

    /** An interrupted take already goes to History; a rotation cannot bring insertion back. */
    @Test fun rotationAfterAnInterruptionStillInterrupts() {
        for (event in ending) {
            assertEquals(event.name, INTERRUPT, EditorEventPolicy.decide(event, take().copy(rotation = true, capturing = false)))
        }
        assertEquals(INTERRUPT, EditorEventPolicy.decide(EditorEvent.INPUT_VIEW_START,
            take(sameSession = false).copy(rotation = true, capturing = false)))
    }

    @Test fun withoutATakeNothingHappens() {
        for (event in EditorEvent.entries) {
            assertEquals(event.name, NOTHING, EditorEventPolicy.decide(event, take(sameSession = false).copy(active = false, capturing = false)))
        }
    }

    /** A rotation never moves a take to another app: its input start ends the take, whatever the rotation says. */
    @Test fun anotherAppsInputStartInterruptsEvenDuringARotation() {
        val capturing = take(sameSession = false).copy(rotation = true, otherApp = true)
        val stopWait = capturing.copy(capturing = false, finishing = true)
        for (facts in listOf(capturing, stopWait)) {
            assertEquals(INTERRUPT, EditorEventPolicy.decide(EditorEvent.INPUT_START, facts))
        }
    }

    /** Only the input start tells whose editor it is: the hides before it are still the rotation's. */
    @Test fun theOtherAppFactChangesNothingButTheInputStart() {
        val facts = take(sameSession = false).copy(rotation = true, otherApp = true)
        for (event in ending - EditorEvent.SCREEN_OFF - EditorEvent.INPUT_START) {
            assertEquals(event.name, EditorDecision.REVOKE_AUTHORITY, EditorEventPolicy.decide(event, facts))
        }
        assertEquals(NOTHING, EditorEventPolicy.decide(EditorEvent.INPUT_VIEW_START, facts))
        assertEquals("a hand-back is not an interruption there either",
            NOTHING, EditorEventPolicy.decide(EditorEvent.INPUT_START, facts.copy(returning = true)))
        for (event in EditorEvent.entries) {
            assertEquals(event.name, EditorEventPolicy.decide(event, take()), EditorEventPolicy.decide(event, take().copy(otherApp = true)))
        }
    }
}
