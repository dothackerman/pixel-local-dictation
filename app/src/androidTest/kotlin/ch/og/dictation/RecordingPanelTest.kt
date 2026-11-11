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

    @Test fun privacyDisclosureIsSecondaryAndOrdinaryHistoryDisclosureStartsHidden() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val panel = LayoutInflater.from(instrumentation.targetContext).inflate(R.layout.ime, null)
            val status = panel.findViewById<TextView>(R.id.status)
            val disclosure = panel.findViewById<TextView>(R.id.retention_notice)
            status.setText(R.string.status_finishing)
            assertEquals(View.GONE, disclosure.visibility)

            // Rebind the same latched decision after the system recreates the panel.
            val latchedEligible = false
            PanelDisclosure.bind(disclosure, if (latchedEligible) null else R.string.retention_field_off)
            val recreated = LayoutInflater.from(instrumentation.targetContext).inflate(R.layout.ime, null)
            val recreatedNotice = recreated.findViewById<TextView>(R.id.retention_notice)
            PanelDisclosure.bind(recreatedNotice, if (latchedEligible) null else R.string.retention_field_off)
            assertEquals(View.VISIBLE, recreatedNotice.visibility)
            assertEquals(disclosure.text.toString(), recreatedNotice.text.toString())
            assertEquals(instrumentation.targetContext.getString(R.string.status_finishing), status.text.toString())
            assertEquals(instrumentation.targetContext.getString(R.string.retention_field_off), disclosure.text.toString())
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
                val notice = panel.findViewById<TextView>(R.id.retention_notice)
                PanelDisclosure.bind(notice, R.string.retention_field_off)
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
                assertEquals(View.VISIBLE, notice.visibility)
                assertTrue("notice clipped at fontScale=$fontScale",
                    notice.height >= notice.layout.height + notice.compoundPaddingTop + notice.compoundPaddingBottom)
                assertTrue("status clipped at fontScale=$fontScale",
                    status.height >= status.layout.height + status.compoundPaddingTop + status.compoundPaddingBottom)
                // The gap after the last visible line is not clipped text.
                assertTrue("preview clipped at fontScale=$fontScale",
                    preview.height >= preview.layout.getLineBottom(2, false) + preview.compoundPaddingTop + preview.compoundPaddingBottom)
                val stage = mic.parent as ViewGroup
                assertTrue(mic.left >= 0 && mic.right <= stage.width && mic.bottom <= stage.height)
                assertEquals(2, actions(panel))
                for (message in listOf(R.string.status_stopping_recording,
                    R.string.status_stopped_finishing_history, R.string.status_stopped_recovery_unavailable, R.string.status_transcription_incomplete, R.string.status_saved_history)) {
                    status.setText(message)
                    panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                    panel.layout(0, 0, panel.measuredWidth, panel.measuredHeight)
                    assertTrue("interruption status clipped at fontScale=$fontScale",
                        status.height >= status.layout.height + status.compoundPaddingTop + status.compoundPaddingBottom)
                }
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
