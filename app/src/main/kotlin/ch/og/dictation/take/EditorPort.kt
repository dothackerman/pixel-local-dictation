package ch.og.dictation.take

/**
 * One connection to an editor. Two values are equal exactly when they are the same connection, so
 * the connection a take started on, the one a write went to and the live one can be compared.
 * No decision behind it: every call goes to the editor as it is and answers what the editor
 * answered; a failing editor throws.
 */
interface EditorConnection {
    /** Writes [text] at the cursor. The editor's answer; it proves nothing by itself (ADR-010). */
    fun write(text: String): Boolean
    /** Up to [chars] characters before the cursor; null when the editor gives none. */
    fun textBeforeCursor(chars: Int): CharSequence?
    /** The length of the editor's whole text as it extracts it; null when it gives none. */
    fun extractedLength(): Int?
}

/** The editor port: the editor as a take sees it. Main thread only. */
fun interface EditorPort {
    /** The live connection, or null without one. */
    fun live(): EditorConnection?
}
