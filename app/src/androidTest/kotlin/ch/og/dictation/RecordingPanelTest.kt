package ch.og.dictation

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

/** Inflates only our panel: no microphone, keyboard handoff, settings changes or stored audio. */
@RunWith(AndroidJUnit4::class)
class RecordingPanelTest {
    @Test fun quietRingKeepsItsCentreTransparent() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val density = context.resources.displayMetrics.density
            val width = (300 * density).roundToInt()
            val height = (255 * density).roundToInt()
            val ring = RecordingRingView(context)
            ring.layout(0, 0, width, height)
            ring.follow(RecordingFeedback().apply { start() })
            val image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                ring.draw(Canvas(image))
                val gap = image.getPixel(width / 2 + (43 * density).roundToInt(), height / 2)
                assertTrue("ring filled the gap around the microphone", Color.alpha(gap) < 32)
                val row = IntArray(width)
                image.getPixels(row, 0, width, 0, height / 2, width, 1)
                assertTrue("ring outline was not drawn", row.any { Color.alpha(it) > 200 })
            } finally { image.recycle() }
        }
    }

    @Test fun compactPanelKeepsControlsAndPreviewAtLargeFontSizes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        instrumentation.runOnMainSync {
            for (fontScale in listOf(1f, 2f)) {
                val config = Configuration(app.resources.configuration).apply { this.fontScale = fontScale }
                val context = app.createConfigurationContext(config)
                val density = context.resources.displayMetrics.density
                val panel = LayoutInflater.from(context).inflate(R.layout.ime, null)
                val status = panel.findViewById<TextView>(R.id.status)
                val preview = panel.findViewById<TextView>(R.id.preview)
                status.setText(R.string.status_capture_ended)
                preview.text = "An invented preview sentence for checking larger text. ".repeat(8)
                val width = (320 * density).toInt()
                panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                panel.layout(0, 0, panel.measuredWidth, panel.measuredHeight)
                val mic = panel.findViewById<ImageButton>(R.id.microphone)
                val back = panel.findViewById<ImageButton>(R.id.back)
                assertEquals((70 * density).roundToInt(), mic.width)
                assertEquals((48 * density).roundToInt(), back.width)
                assertTrue(preview.textSize <= status.textSize)
                assertTrue("status clipped at fontScale=$fontScale",
                    status.height >= status.layout.height + status.compoundPaddingTop + status.compoundPaddingBottom)
                // The gap after the last visible line is not clipped text.
                assertTrue("preview clipped at fontScale=$fontScale",
                    preview.height >= preview.layout.getLineBottom(2, false) + preview.compoundPaddingTop + preview.compoundPaddingBottom)
                val stage = mic.parent as ViewGroup
                assertTrue(mic.left >= 0 && mic.right <= stage.width && mic.bottom <= stage.height)
                assertEquals(2, actions(panel))
            }
            val metrics = app.resources.displayMetrics
            instrumentation.sendStatus(0, Bundle().apply {
                putString("stream", "Panel metrics: density=${metrics.density}, screenWidthDp=${app.resources.configuration.screenWidthDp}, micPx=${(70 * metrics.density).roundToInt()}\n")
            })
        }
    }

    private fun actions(view: View): Int = when (view) {
        is ImageButton -> 1
        is ViewGroup -> (0 until view.childCount).sumOf { actions(view.getChildAt(it)) }
        else -> 0
    }
}
