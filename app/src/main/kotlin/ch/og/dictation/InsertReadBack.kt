package ch.og.dictation

/** Which read-back proved the text is in the field. NONE: neither did. */
enum class InsertCheck { NONE, BEFORE_CURSOR, EXTRACTED_GROWTH }

/**
 * The safety net after a write on a take that went through a rotation hold (ADR-010). A write's
 * return value and a non-null read prove nothing: the platform's placeholder connection for a view
 * that is not an editor answers `true` and "" . So the text must show up in the field: the text
 * before the cursor ends with the last committed characters, or the extracted text grew by exactly
 * what was committed. Pure; never logs or keeps text.
 */
object InsertReadBack {
    /** How many of the committed characters must be seen before the cursor. */
    const val TAIL_CHARS = 64
    /** How much text before the cursor to read: the tail plus room for whitespace the editor reshapes. */
    const val READ_CHARS = 256

    fun check(committed: String, beforeCursor: CharSequence?, extractedBefore: Int?, extractedAfter: Int?): InsertCheck {
        val tail = normalize(committed.takeLast(TAIL_CHARS))
        if (tail.isNotEmpty() && beforeCursor != null && normalize(beforeCursor).endsWith(tail)) return InsertCheck.BEFORE_CURSOR
        if (committed.isNotEmpty() && extractedBefore != null && extractedAfter != null &&
            extractedAfter - extractedBefore == committed.length) return InsertCheck.EXTRACTED_GROWTH
        return InsertCheck.NONE
    }

    private fun normalize(text: CharSequence): String = text.replace(Regex("\\s+"), " ").trimEnd()
}
