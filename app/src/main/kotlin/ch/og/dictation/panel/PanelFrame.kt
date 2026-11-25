package ch.og.dictation.panel

import ch.og.dictation.R

/** The inline Cancel choice a take can have open. RECORDING: the take is paused. FINISHING: delivery held. */
enum class PanelChoice { RECORDING, FINISHING }

/**
 * What the panel says and what its microphone disk does, stored by the take's code at each
 * change (never derived from flags). Each phase is one status line and one microphone state.
 */
enum class PanelPhase(val status: Int, val microphoneEnabled: Boolean, val microphoneLabel: Int) {
    OPENING(R.string.status_opening, false, R.string.status_opening),
    RECORDING(R.string.status_recording, true, R.string.stop_recording),
    PAUSED(R.string.status_paused, false, R.string.status_paused),
    FINISHING(R.string.status_finishing, false, R.string.status_finishing),
    FINISHING_CHOICE(R.string.status_finishing_choice, false, R.string.status_finishing),
    STOPPING(R.string.status_stopping_recording, false, R.string.status_stopping_recording),
    RELEASE_FAILED(R.string.status_release_failed, true, R.string.retry_release),
    IDLE(R.string.status_idle, true, R.string.start_recording),
    NO_PERMISSION(R.string.status_no_permission, false, R.string.status_no_permission),
    FAILED(R.string.status_failed, true, R.string.retry_recording),
    NOT_INSERTED(R.string.status_unable_insert, true, R.string.start_recording),
    /** Not inserted, and the take's outcome is still pending: the microphone stays off until it is in. */
    NOT_INSERTED_WAITING(R.string.status_unable_insert, false, R.string.status_stopping_recording),
    /** Idle while a write is looked at once more (ADR-010): the microphone stays off until that look. */
    IDLE_WAITING(R.string.status_idle, false, R.string.status_stopping_recording);

    /** The take ended without insertion; without held text the status carries the "not inserted" marker. */
    val notInserted: Boolean get() = this == NOT_INSERTED || this == NOT_INSERTED_WAITING
}

/** Text a take could not insert, held in the panel for Copy (plan B). */
data class HeldPanel(
    /** History keeps the take (the editor is retention-eligible); the status says where to find it. */
    val inHistory: Boolean,
    /** The first Back warned that a second one discards the uncopied text. */
    val warned: Boolean = false,
    /** A write was made but not seen in the field (rotation safety net): the status admits the uncertainty. */
    val unconfirmed: Boolean = false,
) {
    val status: Int get() = when {
        warned -> R.string.status_back_warning
        unconfirmed -> R.string.status_unconfirmed
        inHistory -> R.string.status_unable_history
        else -> R.string.status_unable_unsaved
    }
}

/** The take's state as far as the panel shows it. */
data class PanelState(
    val phase: PanelPhase = PanelPhase.OPENING,
    val choice: PanelChoice? = null,
    /** A take is open and not interrupted. */
    val running: Boolean = false,
    /** The take is in the Stop wait: Stop was pressed and its text is awaited. Read only while [running]. */
    val finishing: Boolean = false,
    val held: HeldPanel? = null,
    /** The end of the text so far, or the held text. */
    val preview: String = "",
    /** The take is excluded from History (a private field): the private-field line shows. */
    val privateField: Boolean = false,
    val banner: Boolean = false,
    val ringLive: Boolean = false,
)

/**
 * Everything the recording panel shows, derived from the take's state. Every panel (also one
 * recreated for a rotation) renders the same frame, so state lives with the take and the views
 * only draw it; renderPanel is the only code that writes the panel's views.
 */
data class PanelFrame(
    /** The open choice, or null. */
    val choice: PanelChoice? = null,
    /** Preparing text…: the arc replaces the microphone disk. */
    val preparing: Boolean = false,
    /** The disk's glyph is the Stop square (a take is open and recording); otherwise the mic. */
    val stopGlyph: Boolean = false,
    /** The take ended without insertion and no text is held: the status carries its marker. */
    val unableToInsert: Boolean = false,
    /**
     * The finished text is held in the panel (Unable to insert text, plan B): the preview shows it
     * and the stage offers Copy and Discard text instead of the microphone.
     */
    val held: Boolean = false,
    /** The held text is a write that was not confirmed: the status carries the "Insertion uncertain" mark, not "not inserted". */
    val uncertain: Boolean = false,
    /** What Back does now. */
    val backLabel: Int = R.string.back_to_keyboard,
    /** The status line. */
    val status: Int = R.string.status_opening,
    /** The microphone disk answers a tap, and what it says it does. */
    val microphoneEnabled: Boolean = false,
    val microphoneLabel: Int = R.string.status_opening,
    /** The end of the text so far (or the held text), already cut to the preview's length. */
    val preview: String = "",
    /** The secondary notice line (the private-field line), or null. */
    val notice: Int? = null,
    /** This take's audio is saved for benchmarks: the banner shows. */
    val banner: Boolean = false,
    /** The ring follows the take's live loudness; otherwise it rests. */
    val ringLive: Boolean = false,
)

/** The whole recording panel for [state]. */
fun panelFrame(state: PanelState): PanelFrame {
    val open = state.choice
    val running = state.running
    val held = state.held
    return PanelFrame(
        choice = open,
        preparing = running && state.finishing && open == null,
        stopGlyph = running && !state.finishing,
        unableToInsert = state.phase.notInserted,
        held = held != null,
        uncertain = held?.unconfirmed == true,
        backLabel = when {
            open == PanelChoice.RECORDING -> R.string.continue_recording
            open == PanelChoice.FINISHING -> R.string.keep_finishing
            held?.warned == true -> R.string.back_discard_text
            running && state.finishing -> R.string.back_hold_and_choose
            running -> R.string.discard_recording
            else -> R.string.back_to_keyboard
        },
        // Held text owns the status line (what to do, and whether History keeps it); otherwise the phase's.
        status = held?.status ?: state.phase.status,
        microphoneEnabled = state.phase.microphoneEnabled,
        microphoneLabel = state.phase.microphoneLabel,
        preview = state.preview,
        notice = if (state.privateField) R.string.retention_field_off else null,
        banner = state.banner,
        ringLive = state.ringLive,
    )
}
