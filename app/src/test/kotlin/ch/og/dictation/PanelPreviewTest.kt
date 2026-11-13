package ch.og.dictation

import org.junit.Assert.assertEquals
import org.junit.Test

/** What the panel preview shows of the text so far: its end, from a whole word. */
class PanelPreviewTest {
    @Test fun shortTextIsShownWhole() {
        assertEquals("guten Morgen", PanelPreview.tail("guten Morgen"))
    }

    @Test fun aTailCutMidWordStartsAtTheNextWholeWordWithoutALeadingSpace() {
        val text = "x".repeat(10) + "abcdefghij klm nop"
        assertEquals("klm nop", PanelPreview.tail(text, limit = 12))
    }

    @Test fun aTailCutAtAWordBoundaryKeepsThatWord() {
        assertEquals("klm nop", PanelPreview.tail("abcdefghij klm nop", limit = 8))
    }

    @Test fun oneOverlongWordIsKeptRatherThanShowingNothing() {
        assertEquals("cdefghij", PanelPreview.tail("abcdefghij", limit = 8))
    }

    @Test fun theDefaultLimitIs240Characters() {
        val words = List(100) { "wort$it" }.joinToString(" ")
        val shown = PanelPreview.tail(words)
        assertEquals(true, shown.length <= 240)
        assertEquals(true, words.endsWith(shown))
        assertEquals(true, shown.startsWith("wort"))
    }
}
