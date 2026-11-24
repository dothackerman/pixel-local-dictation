package ch.og.dictation.panel

import ch.og.dictation.R
import ch.og.dictation.audio.RecordingFeedback
import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.TooltipCompat
import kotlin.math.roundToInt

private const val PREVIEW_LINES = 3

/**
 * Test seam, null in the app: sees each frame a panel is about to show (`fresh`: on a new panel).
 * The golden frame sequences record it (#77).
 */
@Volatile internal var onPanelFrame: ((frame: PanelFrame, fresh: Boolean) -> Unit)? = null

/**
 * Renders [frame] on [panel] (built from ime.xml, either orientation) and returns it as the frame
 * now [shown] there; null [shown] means a fresh panel. An identical frame writes nothing; a
 * change of the preview alone binds only the preview; any other change renders the whole panel
 * and moves focus to the safe action while a choice or held text is open.
 *
 * Portrait and the phone's landscape: the choice takes the stage's place, the preview stays and
 * the panel keeps its height. A narrow landscape window (under 600dp, `panel_choice_over_stage`
 * false): it takes the preview's place in the status column and the stage stays empty. The safe
 * action takes focus when a choice opens, so a hardware key or TalkBack lands on it; one tap
 * still chooses it.
 */
fun renderPanel(panel: View, frame: PanelFrame, shown: PanelFrame?, feedback: RecordingFeedback?): PanelFrame {
    val ring = panel.findViewById<RecordingRingView>(R.id.recording_ring)
    val ringTarget = if (frame.ringLive) feedback else null
    if (ring != null && ring.following !== ringTarget) ring.follow(ringTarget)
    if (frame == shown) return frame
    onPanelFrame?.invoke(frame, shown == null)
    val preview = panel.findViewById<TextView>(R.id.preview)
    if (shown != null && frame == shown.copy(preview = frame.preview)) {
        preview?.let { PanelPreview.bind(it, frame.preview) }
        return frame
    }
    val context = panel.context
    val resources = context.resources
    val open = frame.choice
    val held = frame.held
    val overStage = resources.getBoolean(R.bool.panel_choice_over_stage)

    panel.findViewById<TextView>(R.id.status)?.setText(frame.status)
    panel.findViewById<View>(R.id.benchmark_banner)?.visibility = if (frame.banner) View.VISIBLE else View.GONE
    val notice = panel.findViewById<TextView>(R.id.retention_notice)
    notice?.let { bindNotice(it, frame.notice) }
    preview?.let { PanelPreview.bind(it, frame.preview) }

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
    // One line-count rule. Landscape (`panel_preview_yields_to_notice`): the preview gives up a line
    // while the private-field line shows. A narrow window also gives one up for the held status,
    // which is longer (it says what to do), so the panel keeps its height.
    val yields = resources.getBoolean(R.bool.panel_preview_yields_to_notice)
    val lines = when {
        !overStage -> if (held || (frame.notice != null && yields)) PREVIEW_LINES - 1 else PREVIEW_LINES
        yields -> if (frame.notice != null) PREVIEW_LINES - 1 else PREVIEW_LINES
        else -> null
    }
    if (lines != null) preview?.let { it.minLines = lines; it.maxLines = lines }

    // The stage: in a choice (or holding text) there is no microphone to tap by mistake.
    val covered = open != null || held
    val disk = if (covered || frame.preparing) View.INVISIBLE else View.VISIBLE
    ring?.visibility = disk
    if (frame.ringLive) ring?.invalidate()
    panel.findViewById<ImageButton>(R.id.microphone)?.apply {
        visibility = disk
        setImageResource(if (frame.stopGlyph) R.drawable.ic_stop else R.drawable.ic_mic)
        // One disk colour in every state (#36): the glyph and the status say what it does.
        isEnabled = frame.microphoneEnabled
        contentDescription = context.getString(frame.microphoneLabel)
    }
    panel.findViewById<View>(R.id.processing)?.visibility = if (frame.preparing && !covered) View.VISIBLE else View.GONE
    preview?.visibility = when {
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
    return frame
}

/**
 * The secondary notice (the private-field line), independent of the recording status. The lock
 * glyph keeps the mock's 16dp-to-14sp proportion, so it grows with the font. It is parsed for
 * this view, not taken from the Resources cache: a cached vector copies the raster size of the
 * one cached first, which only grows, so after a larger font the lock would be drawn from a
 * larger raster, scaled down and soft. getXml on a drawable is how a vector is parsed uncached.
 */
@SuppressLint("ResourceType")
private fun bindNotice(view: TextView, message: Int?) {
    if (message == null) view.text = "" else view.setText(message)
    view.visibility = if (message == null) View.GONE else View.VISIBLE
    val lock = view.compoundDrawablesRelative[0]
        ?: Drawable.createFromXml(view.resources, view.resources.getXml(R.drawable.ic_lock), view.context.theme)
    val size = (view.textSize * 16 / 14).roundToInt()
    lock.setBounds(0, 0, size, size)
    view.setCompoundDrawablesRelative(lock, null, null, null)
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
