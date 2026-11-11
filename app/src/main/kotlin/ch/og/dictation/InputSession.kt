package ch.og.dictation

/**
 * Identifies the input session a recording belongs to, so text is only ever
 * committed to the field that started it. Main thread only.
 */
class InputSession {
    private var current = 0L
    private var live = false

    /**
     * onStartInput, restart or not: a browser reports a move between two fields of one page as
     * a restart with identical attributes (2026-10-10), so every input start is a new session.
     */
    fun start() = open()

    /** onStartInputView: the panel may reappear without a new onStartInput after it was hidden. */
    fun resume() {
        if (!live) open()
    }

    /** onFinishInputView / onFinishInput: nothing captured before this may be committed. */
    fun invalidate() { live = false }

    /** Token for work starting now, or null when no session is live. */
    fun token(): Long? = if (live) current else null

    fun matches(token: Long?): Boolean = live && token == current

    private fun open() { current++; live = true }
}

/** Null means no editor call was made; callbacks may synchronously invalidate the input. */
fun <C : Any> commitToInput(
    session: InputSession, token: Long?, connection: C?, current: () -> C?,
    allowed: () -> Boolean, before: (C) -> CharSequence?, write: (C, String) -> Boolean,
    text: String,
): Boolean? {
    if (!allowed() || !session.matches(token) || connection == null) return null
    val preceding = before(connection)
    val currentConnection = current()
    if (!allowed() || !session.matches(token) || currentConnection !== connection) return null
    val lead = if (!preceding.isNullOrEmpty() && !preceding[0].isWhitespace()) " " else ""
    return write(connection, lead + text)
}
