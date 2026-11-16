package ch.og.dictation

import org.junit.Assert.assertEquals
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

    @Test fun everyInputStartIsNewSession() {
        val s = InputSession()
        s.start()
        val t = s.token()
        s.start()
        assertFalse(s.matches(t))
    }

    @Test fun finishInvalidatesEvenIfSameFieldReturns() {
        val s = InputSession()
        s.start()
        val t = s.token()
        s.invalidate()
        assertFalse(s.matches(t))
        assertNull(s.token())
        s.start()
        assertFalse(s.matches(t))
        s.invalidate()
        s.resume()
        assertFalse(s.matches(t))
        assertTrue(s.matches(s.token()))
    }

    @Test fun resumeKeepsLiveSession() {
        val s = InputSession()
        s.start()
        val t = s.token()
        s.resume()
        assertTrue(s.matches(t))
    }

    private fun commit(read: CharSequence?, requireLive: Boolean, writes: MutableList<String>, refused: MutableList<Unit>): Boolean? {
        val s = InputSession().also { it.start() }
        val c = Any()
        return commitToInput(s, s.token(), c, { c }, { true }, { read }, { _, t -> writes.add(t); true }, "hi",
            requireLive = requireLive, refused = { refused.add(Unit) })
    }

    @Test fun nullReadAfterRotationWritesNothingAndReportsRejected() {
        val writes = mutableListOf<String>(); val refused = mutableListOf<Unit>()
        assertEquals(false, commit(null, true, writes, refused))
        assertTrue(writes.isEmpty())
        assertEquals(1, refused.size)
    }

    @Test fun nullReadWithoutRotationStillWrites() {
        val writes = mutableListOf<String>(); val refused = mutableListOf<Unit>()
        assertEquals(true, commit(null, false, writes, refused))
        assertEquals(listOf("hi"), writes)
        assertTrue(refused.isEmpty())
    }

    @Test fun emptyReadAfterRotationIsAliveAndWrites() {
        val writes = mutableListOf<String>(); val refused = mutableListOf<Unit>()
        assertEquals(true, commit("", true, writes, refused))
        assertEquals(listOf("hi"), writes)
    }
}
