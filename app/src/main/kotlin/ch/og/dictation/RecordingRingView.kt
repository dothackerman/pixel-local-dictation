package ch.og.dictation

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/** Soft ink driven by the current take's measured loudness, independently of model readiness. */
class RecordingRingView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wash = Paint(Paint.ANTI_ALIAS_FLAG)
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

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        ink.shader = LinearGradient(w / 2f - 87.5f * density, h / 2f - 87.5f * density,
            w / 2f + 87.5f * density, h / 2f + 87.5f * density,
            intArrayOf(0xff789855.toInt(), 0xff506e3c.toInt(), 0xff6f8d50.toInt()), null, Shader.TileMode.CLAMP)
    }

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
        for (k in outerX.indices) {
            val a = 2 * PI * k / outerX.size - PI / 2
            val organic = 0.55 * sin(a * 3 + motion * 0.52) + 0.28 * sin(a * 5 - motion * 0.7) +
                0.17 * sin(a * 2 + motion * 0.26)
            val radius = (70 + if (animated) level * 2.4 * (12 + 13 * organic) else 0.0) * scale
            val weight = (if (animated) 2.2 + level * 2.4 * (4.3 + 2.7 * sin(a * 2 - motion * 0.48) +
                1.1 * sin(a * 3 + motion * 0.31)) else 2.0 + level * 2) * scale
            outerX[k] = (cx + cos(a) * (radius + weight / 2)).toFloat()
            outerY[k] = (cy + sin(a) * (radius + weight / 2)).toFloat()
            innerX[k] = (cx + cos(a) * (radius - weight / 2)).toFloat()
            innerY[k] = (cy + sin(a) * (radius - weight / 2)).toFloat()
        }
        ribbon.rewind()
        // rewind resets the fill rule; keep the centre hollow on every frame.
        ribbon.fillType = Path.FillType.EVEN_ODD
        contour(ribbon, outerX, outerY)
        contour(ribbon, innerX, innerY)
        ink.alpha = if (source?.isCapturing == true) 255 else 115
        canvas.drawPath(ribbon, ink)
        inside.rewind()
        contour(inside, innerX, innerY)
        wash.color = 0xff5f7f3e.toInt()
        wash.alpha = ((0.015 + level * 0.025) * 255).toInt()
        canvas.drawPath(inside, wash)
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
