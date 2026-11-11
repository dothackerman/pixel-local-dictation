package ch.og.dictation

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout

/**
 * The inline Cancel choice's actions: one row when every action fits at its natural width with
 * the gap before Discard, otherwise stacked full width (large text, narrow panel). Never
 * truncates a label. Children: safe action, Stop, gap ([R.id.choice_gap]), Discard.
 */
class PanelChoiceLayout @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {
    private val density = resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).toInt()

    /** True when the last measure stacked the actions. */
    val stacked: Boolean get() = orientation == VERTICAL

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        val unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        var needed = dp(GAP_DP)
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE || child.id == R.id.choice_gap) continue
            child.measure(unspecified, unspecified)
            needed += child.measuredWidth + (if (i > 0) dp(8) else 0)
        }
        val row = MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED && needed <= available
        orientation = if (row) HORIZONTAL else VERTICAL
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val params = child.layoutParams as LayoutParams
            if (child.id == R.id.choice_gap) {
                params.width = if (row) 0 else ViewGroup.LayoutParams.MATCH_PARENT
                params.height = dp(GAP_DP)
                params.weight = if (row) 1f else 0f
            } else {
                params.width = if (row) ViewGroup.LayoutParams.WRAP_CONTENT else ViewGroup.LayoutParams.MATCH_PARENT
                params.weight = 0f
                val first = i == 0
                params.marginStart = if (row && !first && getChildAt(i - 1).id != R.id.choice_gap) dp(8) else 0
                params.topMargin = if (!row && !first && getChildAt(i - 1).id != R.id.choice_gap) dp(8) else 0
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private companion object { const val GAP_DP = 16 }
}

/** The inline Cancel choice a take can have open. RECORDING: the take is paused. FINISHING: delivery held. */
enum class PanelChoice { RECORDING, FINISHING }

/**
 * Renders [open] (or no choice) and the state-correct Back label on [panel]; the panel is built
 * from ime.xml (either orientation). [ended]: a take ended without insertion, show the way back.
 * [backLabel]: what Back does now. The safe action takes focus when a choice opens.
 */
fun renderPanelChoice(panel: View, open: PanelChoice?, ended: Boolean, backLabel: Int) {
    val context = panel.context
    panel.findViewById<View>(R.id.choice)?.visibility = if (open != null) View.VISIBLE else View.GONE
    val safe = panel.findViewById<android.widget.TextView>(R.id.choice_safe)
    safe?.setText(if (open == PanelChoice.FINISHING) R.string.keep_finishing else R.string.continue_recording)
    panel.findViewById<View>(R.id.choice_stop)?.visibility = if (open == PanelChoice.RECORDING) View.VISIBLE else View.GONE
    val overStage = context.resources.getBoolean(R.bool.panel_choice_over_stage)
    val stage = if (open != null && overStage) View.INVISIBLE else View.VISIBLE
    panel.findViewById<View>(R.id.recording_ring)?.visibility = stage
    panel.findViewById<View>(R.id.microphone)?.visibility = stage
    panel.findViewById<View>(R.id.preview)?.visibility = if (open != null && !overStage) View.GONE else View.VISIBLE
    panel.findViewById<View>(R.id.back_to_keyboard)?.visibility = if (ended && open == null) View.VISIBLE else View.GONE
    panel.findViewById<View>(R.id.back)?.contentDescription = context.getString(backLabel)
    if (open != null && safe != null) {
        // Focused in touch mode too, so a hardware key or TalkBack lands on the safe action;
        // it is already focused, so one tap still chooses it.
        safe.isFocusableInTouchMode = true
        safe.requestFocus()
        safe.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED)
    }
}
