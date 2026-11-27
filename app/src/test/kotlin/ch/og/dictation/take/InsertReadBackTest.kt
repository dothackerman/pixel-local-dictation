package ch.og.dictation.take

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InsertReadBackTest {
    /** An editor connection that answers reads with fixed values and counts them. */
    private class Connection(val before: () -> CharSequence?, val extracted: () -> Int?) : EditorConnection {
        var beforeReads = 0
        var extractedReads = 0
        override fun write(text: String) = true
        override fun textBeforeCursor(chars: Int): CharSequence? { beforeReads++; assertEquals(InsertReadBack.READ_CHARS, chars); return before() }
        override fun extractedLength(): Int? { extractedReads++; return extracted() }
    }

    @Test fun aLookThatSeesTheTextBeforeTheCursorDoesNotReadTheExtractedText() {
        val connection = Connection({ "Notes: hello there" }, { 99 })
        assertEquals(InsertCheck.BEFORE_CURSOR, InsertReadBack.look(connection, " hello there", 3))
        assertEquals(1, connection.beforeReads)
        assertEquals(0, connection.extractedReads)
    }

    @Test fun aLookFallsBackToTheExtractedTextGrowingByWhatWasWritten() {
        val grown = Connection({ "" }, { 16 })
        assertEquals(InsertCheck.EXTRACTED_GROWTH, InsertReadBack.look(grown, "hello there", 5))
        assertEquals(1, grown.extractedReads)
        assertEquals(InsertCheck.NONE, InsertReadBack.look(Connection({ "" }, { 5 }), "hello there", 5))
        assertEquals(InsertCheck.NONE, InsertReadBack.look(Connection({ "" }, { 16 }), "hello there", null))
        assertEquals(InsertCheck.NONE, InsertReadBack.look(Connection({ null }, { null }), "hello there", 5))
    }

    @Test fun anEditorThatFailsAReadShowsNothing() {
        val failing = Connection({ error("dead connection") }, { error("dead connection") })
        assertEquals(InsertCheck.NONE, InsertReadBack.look(failing, "hello there", 5))
        assertNull(InsertReadBack.extractedLength(failing))
        assertEquals(7, InsertReadBack.extractedLength(Connection({ null }, { 7 })))
        // The text before the cursor failing does not hide the extracted text's growth.
        assertEquals(InsertCheck.EXTRACTED_GROWTH, InsertReadBack.look(Connection({ error("dead") }, { 16 }), "hello there", 5))
    }

    private fun check(committed: String, before: CharSequence?, pre: Int? = null, post: Int? = null) =
        InsertReadBack.check(committed, before, pre, post)

    @Test fun textBeforeTheCursorEndingWithTheCommittedTextPasses() =
        assertEquals(InsertCheck.BEFORE_CURSOR, check(" hello there", "Notes: hello there"))

    @Test fun onlyTheLastSixtyFourCharactersAreCompared() {
        val long = "x".repeat(100) + "tail words"
        assertEquals(InsertCheck.BEFORE_CURSOR, check(long, "y".repeat(40) + long.takeLast(InsertReadBack.TAIL_CHARS)))
    }

    @Test fun whitespaceIsNormalised() {
        assertEquals(InsertCheck.BEFORE_CURSOR, check("one  two\nthree ", "one two three"))
        assertEquals(InsertCheck.BEFORE_CURSOR, check("one two three", "one two\n three  "))
    }

    @Test fun anEmptyReadAndAnUnchangedFieldFail() {
        assertEquals(InsertCheck.NONE, check("hello there", ""))
        assertEquals(InsertCheck.NONE, check("hello there", "hello", 5, 5))
        assertEquals(InsertCheck.NONE, check("hello there", null))
    }

    @Test fun differentTextBeforeTheCursorFails() =
        assertEquals(InsertCheck.NONE, check("hello there", "hello world"))

    @Test fun extractedTextGrowingByTheCommittedLengthPasses() =
        assertEquals(InsertCheck.EXTRACTED_GROWTH, check("hello there", "", 10, 21))

    @Test fun extractedTextGrowingByAnotherAmountFails() {
        assertEquals(InsertCheck.NONE, check("hello there", "", 10, 20))
        assertEquals(InsertCheck.NONE, check("hello there", "", null, 21))
        assertEquals(InsertCheck.NONE, check("hello there", "", 10, null))
    }
}
