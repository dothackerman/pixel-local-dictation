package ch.og.dictation

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.ContextThemeWrapper
import android.view.View
import com.google.android.material.color.MaterialColors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** #36 critique: the recording ribbon follows loudness by growing evenly; it stays a concentric ring of even thickness. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-port-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RecordingRingViewTest {
    @Test fun loudestRibbonStaysAConcentricRingOfEvenThickness() {
        val context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_Dictation)
        val ring = RecordingRingView(context)
        val size = (200 * context.resources.displayMetrics.density).toInt()
        ring.measure(View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY))
        ring.layout(0, 0, size, size)
        val feedback = RecordingFeedback().apply { start(); accept(FloatArray(1600) { if (it % 2 == 0) 0.9f else -0.9f }) }
        ring.follow(feedback)
        val ink = MaterialColors.getColor(ring, androidx.appcompat.R.attr.colorPrimary)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        var worst = 0.0
        var widest = 0.0
        var largestMean = 0.0
        repeat(40) { frame ->
            Thread.sleep(25)
            bitmap.eraseColor(0)
            ring.draw(Canvas(bitmap))
            if (frame < 10) return@repeat // let the level reach the loudness
            fun isInk(a: Double, r: Double): Boolean {
                val pixel = bitmap.getPixel((size / 2 + cos(a) * r).toInt(), (size / 2 + sin(a) * r).toInt())
                return abs(android.graphics.Color.red(pixel) - android.graphics.Color.red(ink)) < 8 &&
                    abs(android.graphics.Color.green(pixel) - android.graphics.Color.green(ink)) < 8 && android.graphics.Color.alpha(pixel) > 250
            }
            val edges = (0 until 72).map { step ->
                val a = 2 * PI * step / 72
                var outer = size / 2.0 - 1
                while (outer > 0 && !isInk(a, outer)) outer -= 0.5
                var inner = outer
                while (inner > 0 && isInk(a, inner)) inner -= 0.5
                outer to inner
            }
            val radii = edges.map { it.first }
            val thickness = edges.map { it.first - it.second }
            val mean = radii.average()
            largestMean = maxOf(largestMean, mean)
            worst = maxOf(worst, radii.maxOf { abs(it - mean) } / mean)
            widest = maxOf(widest, thickness.max() - thickness.min())
        }
        val resting = 70 * context.resources.displayMetrics.density * 70f / 94f
        assertTrue("the ribbon still grows with loudness: $largestMean vs $resting", largestMean > resting * 1.08)
        assertTrue("radial deviation ${"%.3f".format(worst)} of the radius", worst <= 0.015)
        assertTrue("thickness varies by $widest px around the ring", widest <= 2.5)
    }
}
