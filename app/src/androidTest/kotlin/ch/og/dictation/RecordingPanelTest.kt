package ch.og.dictation

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import java.io.File
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

    /** Inflates the panel as the input method would on this phone held sideways. */
    private fun landscapePanel(fontScale: Float, night: Boolean): Pair<View, Context> {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val base = app.resources.configuration
        val config = Configuration(base).apply {
            orientation = Configuration.ORIENTATION_LANDSCAPE
            screenWidthDp = maxOf(base.screenWidthDp, base.screenHeightDp)
            screenHeightDp = minOf(base.screenWidthDp, base.screenHeightDp)
            this.fontScale = fontScale
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
        val context = app.createConfigurationContext(config)
        val panel = LayoutInflater.from(context).inflate(R.layout.ime, null)
        val density = context.resources.displayMetrics.density
        val metrics = app.resources.displayMetrics
        val width = maxOf(metrics.widthPixels, metrics.heightPixels)
        panel.findViewById<TextView>(R.id.status).setText(R.string.status_recording)
        // As the service shows it: the last 240 characters from a whole word, newest line in view.
        PanelPreview.bind(panel.findViewById(R.id.preview),
            PanelPreview.tail("An invented preview sentence for checking the landscape panel. ".repeat(6).trim()))
        panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        panel.layout(0, 0, panel.measuredWidth, panel.measuredHeight)
        assertTrue(density > 0)
        return panel to context
    }

    private fun bounds(view: View, root: View): Rect {
        val r = Rect(0, 0, view.width, view.height)
        (root as ViewGroup).offsetDescendantRectToMyCoords(view, r)
        return r
    }

    @Test fun landscapePanelIsCompactAndKeepsEveryControl() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        instrumentation.runOnMainSync {
            for (fontScale in listOf(1f, 2f)) {
                val (panel, context) = landscapePanel(fontScale, night = false)
                val density = context.resources.displayMetrics.density
                PanelDisclosure.bind(panel.findViewById(R.id.retention_notice), R.string.retention_field_off)
                panel.measure(View.MeasureSpec.makeMeasureSpec(panel.width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                panel.layout(0, 0, panel.measuredWidth, panel.measuredHeight)
                val shortSide = minOf(app.resources.displayMetrics.widthPixels, app.resources.displayMetrics.heightPixels)
                // The editor stays visible above: the panel takes well under half the sideways height.
                assertTrue("panel ${panel.height}px too tall at fontScale=$fontScale for $shortSide px",
                    panel.height <= shortSide * 0.45f)
                val back = panel.findViewById<ImageButton>(R.id.back)
                val mic = panel.findViewById<ImageButton>(R.id.microphone)
                val status = panel.findViewById<TextView>(R.id.status)
                val preview = panel.findViewById<TextView>(R.id.preview)
                val notice = panel.findViewById<TextView>(R.id.retention_notice)
                val ring = panel.findViewById<View>(R.id.recording_ring)
                val whole = Rect(0, 0, panel.width, panel.height)
                for (view in listOf(back, mic, status, preview, notice, ring)) {
                    assertEquals(View.VISIBLE, view.visibility)
                    assertTrue("${view.id} empty", view.width > 0 && view.height > 0)
                    assertTrue("${view.id} outside panel", whole.contains(bounds(view, panel)))
                }
                for (target in listOf(back, mic)) {
                    assertTrue(target.width >= (48 * density).roundToInt() && target.height >= (48 * density).roundToInt())
                }
                assertTrue("ring does not surround microphone", bounds(ring, panel).contains(bounds(mic, panel)))
                assertTrue("status clipped at fontScale=$fontScale",
                    status.height >= status.layout.height + status.compoundPaddingTop + status.compoundPaddingBottom)
                assertTrue("notice clipped at fontScale=$fontScale",
                    notice.height >= notice.layout.height + notice.compoundPaddingTop + notice.compoundPaddingBottom)
                assertTrue("preview clipped at fontScale=$fontScale",
                    preview.height >= preview.layout.getLineBottom(preview.maxLines - 1, false) + preview.compoundPaddingTop + preview.compoundPaddingBottom)
                assertEquals(2, actions(panel))
            }
        }
    }

    @Test fun overflowingPreviewShowsTheNewestWords() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            for (landscape in listOf(false, true)) for (fontScale in listOf(1f, 2f)) {
                val app = instrumentation.targetContext
                val base = app.resources.configuration
                val config = Configuration(base).apply {
                    this.fontScale = fontScale
                    if (landscape) {
                        orientation = Configuration.ORIENTATION_LANDSCAPE
                        screenWidthDp = maxOf(base.screenWidthDp, base.screenHeightDp)
                        screenHeightDp = minOf(base.screenWidthDp, base.screenHeightDp)
                    }
                }
                val context = app.createConfigurationContext(config)
                val panel = LayoutInflater.from(context).inflate(R.layout.ime, null)
                val preview = panel.findViewById<TextView>(R.id.preview)
                val words = (1..60).joinToString(" ") { "word$it" }
                PanelPreview.bind(preview, PanelPreview.tail(words))
                val metrics = app.resources.displayMetrics
                val width = if (landscape) maxOf(metrics.widthPixels, metrics.heightPixels) else minOf(metrics.widthPixels, metrics.heightPixels)
                panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                panel.layout(0, 0, panel.measuredWidth, panel.measuredHeight)
                val layout = preview.layout
                val box = preview.height - preview.compoundPaddingTop - preview.compoundPaddingBottom
                val lastLine = layout.lineCount - 1
                assertTrue("newest words hidden (landscape=$landscape fontScale=$fontScale)",
                    layout.getLineBottom(lastLine) - preview.scrollY <= box + 1)
                assertTrue("newest words above the box (landscape=$landscape fontScale=$fontScale)",
                    layout.getLineTop(lastLine) - preview.scrollY >= 0)
                assertEquals("a clipped older line shows above (landscape=$landscape fontScale=$fontScale)",
                    layout.getLineTop(layout.getLineForVertical(preview.scrollY)), preview.scrollY)
            }
        }
    }

    /** Draws the ring until its smoothed loudness has settled at the given synthetic sample amplitude. */
    private fun settledRing(ring: RecordingRingView, amplitude: Float): Bitmap {
        val feedback = RecordingFeedback().apply { start() }
        ring.follow(feedback)
        val image = Bitmap.createBitmap(ring.width, ring.height, Bitmap.Config.ARGB_8888)
        repeat(12) {
            feedback.accept(FloatArray(256) { amplitude })
            image.eraseColor(Color.TRANSPARENT)
            ring.draw(Canvas(image))
            Thread.sleep(40)
        }
        return image
    }

    @Test fun loudRingStaysInsideTheLandscapeStage() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val (panel, _) = landscapePanel(1f, night = false)
            val ring = panel.findViewById<RecordingRingView>(R.id.recording_ring)
            val image = settledRing(ring, 1f)
            try {
                val w = image.width; val h = image.height
                val edge = (0 until w).flatMap { listOf(image.getPixel(it, 0), image.getPixel(it, h - 1)) } +
                    (0 until h).flatMap { listOf(image.getPixel(0, it), image.getPixel(w - 1, it)) }
                assertTrue("loud ring clipped at the stage edge", edge.all { Color.alpha(it) < 16 })
                val row = IntArray(w)
                image.getPixels(row, 0, w, 0, h / 2, w, 1)
                assertTrue("ring outline was not drawn", row.any { Color.alpha(it) > 200 })
            } finally { image.recycle() }
        }
    }

    /** Pixel captures for review; synthetic text only. Kept in files/lab/issue56, pulled into corpus/. */
    @Test fun captureLandscapePanel() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val out = File(instrumentation.targetContext.filesDir, "lab/issue56").apply { mkdirs() }
        instrumentation.runOnMainSync {
            for (night in listOf(false, true)) for (fontScale in listOf(1f, 2f)) for (privateField in listOf(false, true)) {
                val (panel, _) = landscapePanel(fontScale, night)
                PanelDisclosure.bind(panel.findViewById(R.id.retention_notice), if (privateField) R.string.retention_field_off else null)
                panel.measure(View.MeasureSpec.makeMeasureSpec(panel.width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                panel.layout(0, 0, panel.measuredWidth, panel.measuredHeight)
                // Mid-loudness speech, so the capture shows the ring as it looks while talking.
                settledRing(panel.findViewById(R.id.recording_ring), 0.05f).recycle()
                val image = Bitmap.createBitmap(panel.width, panel.height, Bitmap.Config.ARGB_8888)
                try {
                    panel.draw(Canvas(image))
                    val name = "panel-land-${if (night) "dark" else "light"}-font${if (fontScale > 1f) "large" else "default"}" +
                        (if (privateField) "-private" else "") + ".png"
                    File(out, name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                } finally { image.recycle() }
            }
        }
    }

    /**
     * #53 review captures (files/lab/issue53-ui, pulled into corpus/): the landscape finishing panel
     * as Stop leaves it, and each Cancel choice over it, light/dark × default/large font. Offscreen
     * composites (the dialog as the input method builds it, centred over a dimmed sideways screen);
     * synthetic text only, no window, microphone or setting is touched.
     */
    @Test fun captureLandscapeFinishingAndChoices() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        val out = File(app.filesDir, "lab/issue53-ui").apply { mkdirs() }
        val metrics = app.resources.displayMetrics
        val screenW = maxOf(metrics.widthPixels, metrics.heightPixels)
        val screenH = minOf(metrics.widthPixels, metrics.heightPixels)
        instrumentation.runOnMainSync {
            for (night in listOf(false, true)) for (fontScale in listOf(1f, 2f)) {
                val (panel, context) = landscapePanel(fontScale, night)
                panel.findViewById<TextView>(R.id.status).setText(R.string.status_finishing)
                panel.findViewById<ImageButton>(R.id.microphone).apply {
                    isEnabled = false; alpha = 0.55f; contentDescription = context.getString(R.string.status_finishing)
                }
                PanelDisclosure.bind(panel.findViewById(R.id.retention_notice), null)
                panel.measure(View.MeasureSpec.makeMeasureSpec(panel.width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                panel.layout(0, 0, panel.measuredWidth, panel.measuredHeight)
                val suffix = "${if (night) "dark" else "light"}-font${if (fontScale > 1f) "large" else "default"}.png"
                fun save(name: String, image: Bitmap) = try {
                    File(out, "$name-$suffix").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                } finally { image.recycle() }
                save("finishing-panel", Bitmap.createBitmap(panel.width, panel.height, Bitmap.Config.ARGB_8888).also { panel.draw(Canvas(it)) })
                // The input method's own theme (the service sets none): what its AlertDialog.Builder(this) resolves.
                val themed = android.view.ContextThemeWrapper(context, android.R.style.Theme_DeviceDefault_InputMethod)
                val recording = android.app.AlertDialog.Builder(themed)
                    .setTitle(R.string.discard_take_title).setMessage(R.string.discard_take_recording)
                    .setNegativeButton(R.string.continue_recording, null).setNeutralButton(R.string.finish_transcription, null)
                    .setPositiveButton(R.string.discard_take, null).create()
                val finishingChoice = android.app.AlertDialog.Builder(themed)
                    .setTitle(R.string.discard_transcript_title)
                    .setNegativeButton(R.string.keep_finishing, null).setPositiveButton(R.string.discard_take, null).create()
                for ((name, dialog, status) in listOf(Triple("recording-choice", recording, R.string.status_recording),
                        Triple("finishing-choice", finishingChoice, R.string.status_finishing))) {
                    panel.findViewById<TextView>(R.id.status).setText(status)
                    panel.findViewById<ImageButton>(R.id.microphone).apply {
                        val on = status == R.string.status_recording
                        isEnabled = on; alpha = if (on) 1f else 0.55f
                    }
                    dialog.create()
                    val decor = dialog.window!!.decorView
                    decor.measure(View.MeasureSpec.makeMeasureSpec(screenW, View.MeasureSpec.AT_MOST),
                        View.MeasureSpec.makeMeasureSpec(screenH, View.MeasureSpec.AT_MOST))
                    decor.layout(0, 0, decor.measuredWidth, decor.measuredHeight)
                    val image = Bitmap.createBitmap(screenW, screenH, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(image)
                    canvas.drawColor(if (night) Color.rgb(16, 16, 16) else Color.rgb(240, 240, 240))
                    canvas.save(); canvas.translate(0f, (screenH - panel.height).toFloat()); panel.draw(canvas); canvas.restore()
                    val dim = themed.obtainStyledAttributes(intArrayOf(android.R.attr.backgroundDimAmount)).let { a ->
                        a.getFloat(0, 0.6f).also { a.recycle() }
                    }
                    canvas.drawColor(Color.argb((dim * 255).roundToInt(), 0, 0, 0))
                    canvas.save()
                    canvas.translate(((screenW - decor.width) / 2).toFloat(), ((screenH - decor.height) / 2).toFloat())
                    decor.draw(canvas); canvas.restore()
                    save(name, image)
                    for (button in listOf(android.app.AlertDialog.BUTTON_NEGATIVE, android.app.AlertDialog.BUTTON_NEUTRAL, android.app.AlertDialog.BUTTON_POSITIVE)) {
                        val b = dialog.getButton(button) ?: continue
                        if (b.visibility != View.VISIBLE) continue
                        val r = Rect(); b.getDrawingRect(r); (decor as ViewGroup).offsetDescendantRectToMyCoords(b, r)
                        assertTrue("${b.text} outside its prompt ($name $suffix)", Rect(0, 0, decor.width, decor.height).contains(r) && b.width > 0)
                    }
                }
            }
        }
    }

    private fun actions(view: View): Int = when (view) {
        is ImageButton -> 1
        is ViewGroup -> (0 until view.childCount).sumOf { actions(view.getChildAt(it)) }
        else -> 0
    }
}
