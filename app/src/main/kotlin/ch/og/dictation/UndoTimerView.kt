package ch.og.dictation

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.google.android.material.R as MaterialR
import com.google.android.material.color.MaterialColors

/**
 * Take discarded · Undo (#36): what is left of the Undo window as a 2dp rule, onSurface on an
 * outlineVariant track, square ends. A plain view (not LinearProgressIndicator) so it draws the
 * same in an offscreen capture as on the panel, and never animates on its own.
 */
class UndoTimerView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val track = Paint().apply { color = MaterialColors.getColor(this@UndoTimerView, MaterialR.attr.colorOutlineVariant) }
    private val left = Paint().apply { color = MaterialColors.getColor(this@UndoTimerView, MaterialR.attr.colorOnSurface) }

    /** Share of the window still open, 1 at Discard and 0 when it ends. */
    var fraction = 1f
        set(value) {
            val next = value.coerceIn(0f, 1f)
            if (next != field) { field = next; invalidate() }
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = (width - paddingLeft - paddingRight).toFloat()
        val top = paddingTop.toFloat()
        val bottom = (height - paddingBottom).toFloat()
        val start = paddingLeft.toFloat()
        canvas.drawRect(start, top, start + w, bottom, track)
        val end = w * fraction
        if (layoutDirection == LAYOUT_DIRECTION_RTL) canvas.drawRect(start + w - end, top, start + w, bottom, left)
        else canvas.drawRect(start, top, start + end, bottom, left)
    }
}
