package ch.og.dictation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InputSessionTest {
    @Test fun noSessionBeforeStart() {
        val s = InputSession()
        assertNull(s.token())
        assertFalse(s.matches(null))
    }

    @Test fun restartKeepsSession() {
        val s = InputSession()
        s.start(restarting = false)
        val t = s.token()
        s.start(restarting = true)
        assertTrue(s.matches(t))
    }

    @Test fun newInputIsNewSession() {
        val s = InputSession()
        s.start(restarting = false)
        val t = s.token()
        s.start(restarting = false)
        assertFalse(s.matches(t))
    }

    @Test fun finishInvalidatesEvenIfSameFieldReturns() {
        val s = InputSession()
        s.start(restarting = false)
        val t = s.token()
        s.invalidate()
        assertFalse(s.matches(t))
        assertNull(s.token())
        s.start(restarting = true)
        assertFalse(s.matches(t))
        s.invalidate()
        s.resume()
        assertFalse(s.matches(t))
        assertTrue(s.matches(s.token()))
    }

    @Test fun resumeKeepsLiveSession() {
        val s = InputSession()
        s.start(restarting = false)
        val t = s.token()
        s.resume()
        assertTrue(s.matches(t))
    }
}
