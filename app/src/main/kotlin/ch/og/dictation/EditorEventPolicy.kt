package ch.og.dictation

/** Editor and system events the input method receives during a take. */
enum class EditorEvent { INPUT_START, INPUT_VIEW_START, INPUT_VIEW_FINISH, INPUT_FINISH, WINDOW_HIDDEN, SCREEN_OFF }

/**
 * What an editor event does to the current take. Revoking authority to insert never stops
 * capture by itself; interrupting revokes authority and stops capture.
 */
enum class EditorDecision { NOTHING, REVOKE_AUTHORITY, INTERRUPT }

/** What the input method knows about the take when an editor event arrives. */
data class TakeFacts(
    val active: Boolean,
    /** A hand-back to the previous keyboard is in progress. */
    val returning: Boolean,
    /** The live input session is the one the take's panel is attached to. */
    val sameSession: Boolean,
)

object EditorEventPolicy {
    fun decide(event: EditorEvent, take: TakeFacts): EditorDecision {
        if (!take.active) return EditorDecision.NOTHING
        // A locked phone must not keep listening, whatever else is going on.
        if (event == EditorEvent.SCREEN_OFF) return EditorDecision.INTERRUPT
        // Switching back to the keyboard can synchronously end input; that is not an interruption.
        if (take.returning) return EditorDecision.NOTHING
        return when (event) {
            // The view can be restarted without a new input start; keep that take.
            EditorEvent.INPUT_VIEW_START -> if (take.sameSession) EditorDecision.NOTHING else EditorDecision.INTERRUPT
            // Every input start ends the session (InputSession.start); hide and input loss end it too.
            else -> EditorDecision.INTERRUPT
        }
    }
}
