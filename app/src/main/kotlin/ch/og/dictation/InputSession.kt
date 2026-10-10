package ch.og.dictation

/**
 * Identifies the input session a recording belongs to, so text is only ever
 * committed to the field that started it. Main thread only.
 */
class InputSession {
    private var current = 0L
    private var live = false

    /** onStartInput: a restart is the same session (the app changed its text), anything else is a new one. */
    fun start(restarting: Boolean) {
        if (!restarting || !live) open()
    }

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
