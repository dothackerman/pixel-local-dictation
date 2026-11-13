package ch.og.dictation

import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
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
    /** The take ended without insertion and no text is held: the status carries its marker. */
    val unableToInsert: Boolean = false,
    /**
     * The finished text is held in the panel (Unable to insert text, plan B): the preview shows it
     * and the stage offers Copy and Discard text instead of the microphone. Back label is the
     * caller's.
     */
    val held: Boolean = false,
    /** The held text is a write that was not confirmed: the status carries the "Insertion uncertain" mark, not "not inserted". */
    val uncertain: Boolean = false,
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
    safe?.let {
        it.setBackgroundResource(R.drawable.choice_continue)
        it.imageTintList = ColorStateList.valueOf(com.google.android.material.color.MaterialColors.getColor(
            it, com.google.android.material.R.attr.colorOnPrimary))
    }
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

    // A narrow landscape window has no room beside the preview: held text stacks its two buttons in the stage.
    if (!overStage && choice is LinearLayout) placeChoice(panel, choice, inStage = held)
    // The held status is longer (it says what to do): two preview lines keep the narrow panel's height.
    if (!overStage) panel.findViewById<android.widget.TextView>(R.id.preview)?.let {
        val notice = panel.findViewById<View>(R.id.retention_notice)?.visibility == View.VISIBLE &&
            context.resources.getBoolean(R.bool.panel_preview_yields_to_notice)
        val lines = if (held || notice) 2 else 3
        it.minLines = lines
        it.maxLines = lines
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
        visibility = if (held || (frame.unableToInsert && open == null)) View.VISIBLE else View.GONE
        // One mark per state (DESIGN.md): an unconfirmed write is uncertain, not "not inserted".
        if (frame.unableToInsert || held) setImageResource(if (frame.uncertain) R.drawable.ic_marker_uncertain else R.drawable.ic_marker_not_inserted)
    }
    panel.findViewById<View>(R.id.back)?.contentDescription = context.getString(frame.backLabel)
    if ((open != null || held) && safe != null) {
        safe.isFocusableInTouchMode = true
        safe.requestFocus()
        safe.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED)
    }
}

/** Where the choice row sits in a narrow landscape panel: its place in the status column, or the stage. */
private class ChoiceHome(val parent: ViewGroup, val index: Int, val params: ViewGroup.LayoutParams)

private fun placeChoice(panel: View, choice: LinearLayout, inStage: Boolean) {
    val stage = panel.findViewById<ViewGroup>(R.id.stage) ?: return
    val home = (choice.getTag(R.id.choice) as? ChoiceHome)
        ?: ChoiceHome(choice.parent as ViewGroup, (choice.parent as ViewGroup).indexOfChild(choice), choice.layoutParams)
            .also { choice.setTag(R.id.choice, it) }
    val target = if (inStage) stage else home.parent
    if (choice.parent !== target) {
        (choice.parent as ViewGroup).removeView(choice)
        if (inStage) stage.addView(choice, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        else home.parent.addView(choice, home.index, home.params)
    }
    choice.orientation = if (inStage) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
    choice.findViewById<View>(R.id.choice_gap)?.visibility = if (inStage) View.GONE else View.VISIBLE
}
