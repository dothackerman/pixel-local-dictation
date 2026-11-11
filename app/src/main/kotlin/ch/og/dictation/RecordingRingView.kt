package ch.og.dictation

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.google.android.material.R as MaterialR
import com.google.android.material.color.MaterialColors
import kotlin.math.exp

/**
 * The ring around the microphone disk (#36 Crate B2). At rest: a hairline in the outline role
 * around a pale field. Recording: a thick red-orange concentric band whose radius and thickness
 * follow the take's measured loudness; with animations off it stays a static thick ring, so recording and idle differ in a
 * still frame. Colours are theme roles resolved from the panel's themed context.
 */
class RecordingRingView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val field = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hairline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val band = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var source: RecordingFeedback? = null
    private var level = 0f
    private var lastFrame = 0L
    private val density get() = resources.displayMetrics.density

    fun follow(feedback: RecordingFeedback?) {
        source = feedback
        lastFrame = 0
        if (feedback != null) level = 0f
        invalidate()
    }

    init {
        band.color = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary)
        field.color = MaterialColors.getColor(this, MaterialR.attr.colorSurface)
        hairline.color = MaterialColors.getColor(this, MaterialR.attr.colorOutline)
    }

    /** True when the last frame drew the recording ribbon rather than the resting hairline. */
    var recording = false
        private set

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = System.nanoTime()
        val dt = if (lastFrame == 0L) 0.016 else ((now - lastFrame) / 1e9).coerceIn(0.0, 0.08)
        lastFrame = now
        val target = source?.level ?: 0f
        val animated = ValueAnimator.areAnimatorsEnabled()
        level += ((target - level) * (1 - exp(-dt / if (target > level) 0.03 else 0.13))).toFloat()
        if (level < 0.001f && target == 0f) level = 0f
        val scale = density * 70f / 94f
        val cx = width / 2f
        val cy = height / 2f
        recording = source?.isCapturing == true
        if (!recording && level == 0f) {
            // At rest: a pale field inside a 1dp hairline.
            canvas.drawCircle(cx, cy, 70 * scale, field)
            hairline.strokeWidth = density
            canvas.drawCircle(cx, cy, 70 * scale, hairline)
            return
        }
        // Loudness grows the ring evenly and thickens it; the band stays a concentric circle of
        // even thickness, so only its size answers the voice, never its shape (#36 critique).
        // The growth shrinks to fit a short stage (landscape) so the loudest ring is never clipped;
        // the resting ring around the microphone keeps its size. Portrait has room for all of it.
        val room = minOf(width, height) / 2f / scale
        val reach = ((room - REST_OUTER) / (GROWTH + THICKENING / 2)).coerceIn(0f, 1f)
        val amount = if (animated) reach * level else 0f
        val radius = (70 + amount * GROWTH) * scale
        val weight = (if (animated) 6f + amount * THICKENING else 6f + level * 2) * scale
        canvas.drawCircle(cx, cy, radius - weight / 2, field)
        band.strokeWidth = weight
        canvas.drawCircle(cx, cy, radius, band)
        if (isShown && (source?.isCapturing == true || level > 0f)) postInvalidateOnAnimation()
    }

    private companion object {
        /** Mock units (the 70dp disk is 94): the resting ribbon's outer edge, plus a hair. */
        const val REST_OUTER = 73.1f
        /** Even growth of the radius at full loudness. */
        const val GROWTH = 28f
        /** Extra ribbon thickness at full loudness. */
        const val THICKENING = 12f
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        lastFrame = 0
        if (visibility == VISIBLE) invalidate()
    }

    override fun onDetachedFromWindow() {
        source = null
        level = 0f
        lastFrame = 0
        super.onDetachedFromWindow()
    }
}
