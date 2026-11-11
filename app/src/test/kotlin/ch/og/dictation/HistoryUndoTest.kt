package ch.og.dictation

import org.junit.Assert.*
import org.junit.Test

class HistoryUndoTest {
    @Test fun deletedRowIsHiddenAtOnceAndUndoRestoresIt() {
        val undo = HistoryUndo()
        assertNull(undo.delete("a"))
        assertTrue(undo.hidden("a"))
        assertFalse(undo.hidden("b"))
        assertEquals("a", undo.undo())
        assertFalse(undo.hidden("a"))
        assertNull(undo.settle())
    }

    @Test fun settleHandsBackTheIdOnceAndKeepsItHiddenUntilStorageFinishes() {
        val undo = HistoryUndo()
        undo.delete("a")
        assertEquals("a", undo.settle())
        assertNull(undo.settle())
        assertTrue(undo.hidden("a"))
        assertNull(undo.undo())
        undo.finished("a")
        assertFalse(undo.hidden("a"))
    }

    @Test fun secondDeleteCommitsTheFirstImmediately() {
        val undo = HistoryUndo()
        undo.delete("a")
        assertEquals("a", undo.delete("b"))
        assertTrue(undo.hidden("a"))
        assertTrue(undo.hidden("b"))
        assertEquals("b", undo.undo())
        assertFalse(undo.hidden("b"))
    }
}
