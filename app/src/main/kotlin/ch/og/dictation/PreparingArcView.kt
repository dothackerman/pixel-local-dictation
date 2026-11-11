package ch.og.dictation

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.google.android.material.R as MaterialR
import com.google.android.material.color.MaterialColors

/**
 * Preparing text… (#35/#36): a small red-orange arc turning on an outline-variant track, in place
 * of the microphone disk. With animations off it is a static segmented ring, so a still frame
 * never reads as a stuck spinner.
 */
class PreparingArcView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 4 * density; strokeCap = Paint.Cap.BUTT
        color = MaterialColors.getColor(this@PreparingArcView, MaterialR.attr.colorOutlineVariant)
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 4 * density; strokeCap = Paint.Cap.BUTT
        color = MaterialColors.getColor(this@PreparingArcView, androidx.appcompat.R.attr.colorPrimary)
    }
    private val box = RectF()
    private var startNs = 0L

    /** True when the last frame drew the static segmented ring (animations off). */
    var segmented = false
        private set

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val inset = arc.strokeWidth / 2 + density
        box.set(inset, inset, width - inset, height - inset)
        segmented = !ValueAnimator.areAnimatorsEnabled()
        if (segmented) {
            // Eight segments with gaps, two of them in the arc colour: reads as "in progress".
            for (k in 0 until SEGMENTS) {
                canvas.drawArc(box, -90f + k * 45f + 4f, 37f, false, if (k < 2) arc else track)
            }
            return
        }
        canvas.drawOval(box, track)
        if (startNs == 0L) startNs = System.nanoTime()
        val turn = ((System.nanoTime() - startNs) / 1e9 * 300.0 % 360.0).toFloat()
        canvas.drawArc(box, turn - 90f, 90f, false, arc)
        if (isShown) postInvalidateOnAnimation()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) { startNs = 0L; invalidate() }
    }

    private companion object { const val SEGMENTS = 8 }
}
