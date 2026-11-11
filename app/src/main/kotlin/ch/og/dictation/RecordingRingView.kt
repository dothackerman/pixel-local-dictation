package ch.og.dictation

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.google.android.material.R as MaterialR
import com.google.android.material.color.MaterialColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * The ring around the microphone disk (#36 Crate B2). At rest: a hairline in the outline role
 * around a pale field. Recording: a thick red-orange ribbon that follows the take's measured
 * loudness; with animations off it stays a static thick ring, so recording and idle differ in a
 * still frame. Colours are theme roles resolved from the panel's themed context.
 */
class RecordingRingView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG)
    private val field = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hairline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val ribbon = Path()
    private val inside = Path()
    private val outerX = FloatArray(128)
    private val outerY = FloatArray(128)
    private val innerX = FloatArray(128)
    private val innerY = FloatArray(128)
    private var source: RecordingFeedback? = null
    private var level = 0f
    private var motion = 0.0
    private var lastFrame = 0L
    private val density get() = resources.displayMetrics.density

    fun follow(feedback: RecordingFeedback?) {
        source = feedback
        lastFrame = 0
        if (feedback != null) { level = 0f; motion = 0.0 }
        invalidate()
    }

    init {
        ink.color = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary)
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
        if (animated && target > 0.01f) motion += dt * (0.5 + target * 2)
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
        // Loudness grows the ring evenly and thickens it; the contour only ripples by a few units,
        // so the outer edge stays within about 8% of a circle and reads crisp (#36 critique).
        // The motion shrinks to fit a short stage (landscape) so the loudest ring is never clipped;
        // the resting ring around the microphone keeps its size. Portrait has room for all of it.
        val room = minOf(width, height) / 2f / scale
        val reach = ((room - REST_OUTER) / (GROWTH + THICKENING / 2 + RIPPLE)).coerceIn(0f, 1f)
        val amount = if (animated) reach * level else 0f
        for (k in outerX.indices) {
            val a = 2 * PI * k / outerX.size - PI / 2
            val organic = 0.55 * sin(a * 3 + motion * 0.52) + 0.28 * sin(a * 5 - motion * 0.7) +
                0.17 * sin(a * 2 + motion * 0.26)
            val radius = (70 + amount * (GROWTH + RIPPLE * organic)) * scale
            val weight = (if (animated) 6.0 + amount * THICKENING else 6.0 + level * 2) * scale
            outerX[k] = (cx + cos(a) * (radius + weight / 2)).toFloat()
            outerY[k] = (cy + sin(a) * (radius + weight / 2)).toFloat()
            innerX[k] = (cx + cos(a) * (radius - weight / 2)).toFloat()
            innerY[k] = (cy + sin(a) * (radius - weight / 2)).toFloat()
        }
        inside.rewind()
        contour(inside, innerX, innerY)
        canvas.drawPath(inside, field)
        ribbon.rewind()
        // rewind resets the fill rule; keep the centre hollow on every frame.
        ribbon.fillType = Path.FillType.EVEN_ODD
        contour(ribbon, outerX, outerY)
        contour(ribbon, innerX, innerY)
        canvas.drawPath(ribbon, ink)
        if (isShown && (source?.isCapturing == true || level > 0f)) postInvalidateOnAnimation()
    }

    private fun contour(path: Path, x: FloatArray, y: FloatArray) {
        path.moveTo((x.last() + x[0]) / 2, (y.last() + y[0]) / 2)
        for (k in x.indices) {
            val next = (k + 1) % x.size
            path.quadTo(x[k], y[k], (x[k] + x[next]) / 2, (y[k] + y[next]) / 2)
        }
        path.close()
    }

    private companion object {
        /** Mock units (the 70dp disk is 94): the resting ribbon's outer edge, plus a hair. */
        const val REST_OUTER = 73.1f
        /** Even growth of the radius at full loudness. */
        const val GROWTH = 28f
        /** Extra ribbon thickness at full loudness. */
        const val THICKENING = 12f
        /** Largest ripple of the contour: under 8% of the loud outer radius (73 + 28 + 6 ≈ 107). */
        const val RIPPLE = 5.5f
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
