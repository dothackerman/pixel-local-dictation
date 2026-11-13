package ch.og.dictation

import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import androidx.appcompat.widget.TooltipCompat

/** The inline Cancel choice a take can have open. RECORDING: the take is paused. FINISHING: delivery held. */
enum class PanelChoice { RECORDING, FINISHING }

/**
 * What the recording panel shows, derived from the take's state in DictationImeService. Every
 * panel (also one recreated for a rotation) renders the same frame, so state lives in the
 * service and the views only draw it.
 */
data class PanelFrame(
    /** The open choice, or null. */
    val choice: PanelChoice? = null,
    /** Preparing text…: the arc replaces the microphone disk. */
    val preparing: Boolean = false,
    /** The disk's glyph is the Stop square (a take is open and recording); otherwise the mic. */
    val stopGlyph: Boolean = false,
    /** The take ended without insertion while the panel showed it: the status carries its marker. */
    val unableToInsert: Boolean = false,
    /**
     * The finished text is held in the panel (Unable to insert text, plan B): the preview shows it
     * and the stage offers Copy and Discard text instead of the microphone. Back label is the
     * caller's.
     */
    val held: Boolean = false,
    /** What Back does now. */
    val backLabel: Int = R.string.back_to_keyboard,
)

/**
 * Renders [frame] on [panel] (built from ime.xml, either orientation). Portrait and the phone's
 * landscape: the choice takes the stage's place, the preview stays and the panel keeps its
 * height. A narrow landscape window (under 600dp, `panel_choice_over_stage` false): it takes the
 * preview's place in the status column and the stage stays empty. The safe action takes focus when a choice
 * opens, so a hardware key or TalkBack lands on it; one tap still chooses it.
 */
fun renderPanel(panel: View, frame: PanelFrame) {
    val context = panel.context
    val open = frame.choice
    val overStage = context.resources.getBoolean(R.bool.panel_choice_over_stage)
    val held = frame.held
    val choice = panel.findViewById<View>(R.id.choice)
    choice?.visibility = if (open != null || held) View.VISIBLE else View.GONE
    val safe = panel.findViewById<ImageButton>(R.id.choice_safe)
    val safeLabel = context.getString(when {
        held -> R.string.copy_text
        open == PanelChoice.FINISHING -> R.string.keep_finishing
        else -> R.string.continue_recording
    })
    safe?.contentDescription = safeLabel
    // Keep preparing text is not "play": it shows the preparing glyph (hourglass), as History does.
    safe?.setImageResource(when {
        held -> R.drawable.ic_content_copy
        open == PanelChoice.FINISHING -> R.drawable.ic_marker_preparing
        else -> R.drawable.ic_play_arrow
    })
    // No tooltip on Copy: no touch-and-hold hint (owner decision).
    safe?.let { TooltipCompat.setTooltipText(it, if (held) null else safeLabel) }
    panel.findViewById<View>(R.id.choice_stop)?.let {
        it.visibility = if (open == PanelChoice.RECORDING) View.VISIBLE else View.GONE
        TooltipCompat.setTooltipText(it, it.contentDescription)
    }
    panel.findViewById<View>(R.id.choice_discard)?.let {
        it.contentDescription = context.getString(if (held) R.string.discard_text else R.string.discard_take)
        TooltipCompat.setTooltipText(it, it.contentDescription)
    }

    // The stage: in a choice (or holding text) there is no microphone to tap by mistake.
    val covered = open != null || held
    val disk = when {
        covered || frame.preparing -> View.INVISIBLE
        else -> View.VISIBLE
    }
    panel.findViewById<View>(R.id.recording_ring)?.visibility = disk
    panel.findViewById<ImageButton>(R.id.microphone)?.apply {
        visibility = disk
        setImageResource(if (frame.stopGlyph) R.drawable.ic_stop else R.drawable.ic_mic)
    }
    panel.findViewById<View>(R.id.processing)?.visibility = if (frame.preparing && !covered) View.VISIBLE else View.GONE
    panel.findViewById<View>(R.id.preview)?.visibility = when {
        !covered || held -> View.VISIBLE
        overStage -> View.VISIBLE
        else -> View.GONE
    }
    panel.findViewById<ImageView>(R.id.status_marker)?.apply {
        visibility = if (frame.unableToInsert && open == null) View.VISIBLE else View.GONE
        if (frame.unableToInsert) setImageResource(R.drawable.ic_marker_not_inserted)
    }
    panel.findViewById<View>(R.id.back)?.contentDescription = context.getString(frame.backLabel)
    if ((open != null || held) && safe != null) {
        safe.isFocusableInTouchMode = true
        safe.requestFocus()
        safe.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED)
    }
}
