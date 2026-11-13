package ch.og.dictation

import org.junit.Assert.assertEquals
import org.junit.Test

class InsertReadBackTest {
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
