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
    /** The microphone is still recording this take (not the Stop wait, not interrupted). */
    val capturing: Boolean = active,
    /** Stop was pressed and the take waits for its result (not interrupted). */
    val finishing: Boolean = false,
    /**
     * The app itself observed a display-rotation change since the panel was last visible, at or
     * before this event. Never inferred from package, field ID or editor attributes.
     */
    val rotation: Boolean = false,
)

/**
 * Longest time the microphone may stay on without a visible panel, and only after an identified
 * rotation. Start value from #36; to be replaced by the #56 Pixel measurement. Never extended.
 */
const val ROTATION_HOLD_GAP_MS = 3_000L

/**
 * How long the panel must stay visible after a rotation before the rotation is no longer
 * identified. On the Pixel (#56, recreated editor) Android reset and showed the panel inside the
 * configuration change and the editor hid it ~95 ms later; this is that delay with a wide margin.
 * Runs only while the panel is visible; a hide inside it is held at most until the gap ends.
 */
const val ROTATION_SETTLE_MS = 1_000L

object EditorEventPolicy {
    fun decide(event: EditorEvent, take: TakeFacts): EditorDecision {
        if (!take.active) return EditorDecision.NOTHING
        // A locked phone must not keep listening, whatever else is going on.
        if (event == EditorEvent.SCREEN_OFF) return EditorDecision.INTERRUPT
        // Switching back to the keyboard can synchronously end input; that is not an interruption.
        if (take.returning) return EditorDecision.NOTHING
        // Rotation hold: the editor reacts to a rotation the app saw; keep capture or the Stop
        // wait, never insert until the panel is back.
        val hold = take.rotation && (take.capturing || take.finishing)
        return when (event) {
            // The view can be restarted without a new input start; keep that take. After a rotation
            // the panel returning on a live session ends the hold with the take continuing.
            EditorEvent.INPUT_VIEW_START -> if (take.sameSession || hold) EditorDecision.NOTHING else EditorDecision.INTERRUPT
            // Every input start ends the session (InputSession.start); hide and input loss end it too.
            else -> if (hold) EditorDecision.REVOKE_AUTHORITY else EditorDecision.INTERRUPT
        }
    }
}
