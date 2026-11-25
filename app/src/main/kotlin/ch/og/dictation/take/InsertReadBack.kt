package ch.og.dictation.take

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

    /** The extracted text's length, read before a write for a later [look]; an editor that fails the read gives none. */
    fun extractedLength(connection: EditorConnection): Int? = runCatching { connection.extractedLength() }.getOrNull()

    /**
     * Reads the field back after [committed] was written to [connection]. The extracted text is only
     * read when the text before the cursor did not show the write.
     */
    fun look(connection: EditorConnection, committed: String, extractedBefore: Int?): InsertCheck {
        val before = runCatching { connection.textBeforeCursor(READ_CHARS) }.getOrNull()
        val first = check(committed, before, extractedBefore, null)
        if (first != InsertCheck.NONE) return first
        return check(committed, before, extractedBefore, extractedLength(connection))
    }

    private fun normalize(text: CharSequence): String = text.replace(Regex("\\s+"), " ").trimEnd()
}
