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
    /** The panel as the service builds it: inflated from Theme.Dictation (#36). */
    private fun panelOf(context: Context): View =
        LayoutInflater.from(android.view.ContextThemeWrapper(context, R.style.Theme_Dictation)).inflate(R.layout.ime, null)

    /** Recording: a thick red-orange ribbon around a pale field; at rest a hairline in the outline role. */
    @Test fun recordingRingIsAnInkRibbonAroundAPaleFieldAndRestIsAHairline() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = android.view.ContextThemeWrapper(instrumentation.targetContext, R.style.Theme_Dictation)
            val density = context.resources.displayMetrics.density
            val width = (300 * density).roundToInt()
            val height = (200 * density).roundToInt()
            val ring = RecordingRingView(context)
            ring.layout(0, 0, width, height)
            val primary = context.getColor(R.color.b2_primary)
            val field = context.getColor(R.color.b2_surface)
            fun draw(): IntArray {
                val image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try {
                    ring.draw(Canvas(image))
                    assertEquals("the field between disk and ring is the pale surface", field, image.getPixel(width / 2 + (43 * density).roundToInt(), height / 2))
                    return IntArray(width).also { image.getPixels(it, 0, width, 0, height / 2, width, 1) }
                } finally { image.recycle() }
            }
            ring.follow(RecordingFeedback().apply { start() })
            assertTrue("recording ribbon not drawn in the brand ink", draw().count { it == primary } >= 2 * (4 * density).toInt())
            assertTrue(ring.recording)
            ring.follow(null)
            assertEquals("the resting ring is not red-orange", 0, draw().count { it == primary })
            assertFalse(ring.recording)
        }
    }

    @Test fun privacyDisclosureIsSecondaryAndOrdinaryHistoryDisclosureStartsHidden() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val panel = panelOf(instrumentation.targetContext)
            val status = panel.findViewById<TextView>(R.id.status)
            val disclosure = panel.findViewById<TextView>(R.id.retention_notice)
            status.setText(R.string.status_finishing)
            assertEquals(View.GONE, disclosure.visibility)

            // Rebind the same latched decision after the system recreates the panel.
            val latchedEligible = false
            PanelDisclosure.bind(disclosure, if (latchedEligible) null else R.string.retention_field_off)
            val recreated = panelOf(instrumentation.targetContext)
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
                val panel = panelOf(context)
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
                for (message in listOf(R.string.status_stopping_recording, R.string.status_unable_insert,
                    R.string.status_discarded, R.string.status_finishing_choice, R.string.status_paused)) {
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
        val panel = panelOf(context)
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

    /** The view's box in [root]'s coordinates; its own scroll (the preview shows its newest lines) does not move it. */
    private fun bounds(view: View, root: View): Rect {
        val r = Rect(view.scrollX, view.scrollY, view.scrollX + view.width, view.scrollY + view.height)
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
                val panel = panelOf(context)
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

    /** Detaches the feedback and draws until the ribbon has decayed to the resting hairline, as on the panel. */
    private fun restedRing(ring: RecordingRingView) {
        ring.follow(null)
        if (ring.width == 0 || ring.height == 0) return
        val image = Bitmap.createBitmap(ring.width, ring.height, Bitmap.Config.ARGB_8888)
        try {
            repeat(25) { ring.draw(Canvas(image)); Thread.sleep(40) }
        } finally { image.recycle() }
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
            for (night in listOf(false)) for (fontScale in listOf(1f, 2f)) for (privateField in listOf(false, true)) {
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

    /** Inflates the panel as the input method would on this phone, in either orientation. */
    private fun phonePanel(landscape: Boolean, fontScale: Float, night: Boolean): Pair<View, Context> {
        if (landscape) return landscapePanel(fontScale, night)
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val base = app.resources.configuration
        val config = Configuration(base).apply {
            orientation = Configuration.ORIENTATION_PORTRAIT
            screenWidthDp = minOf(base.screenWidthDp, base.screenHeightDp)
            screenHeightDp = maxOf(base.screenWidthDp, base.screenHeightDp)
            this.fontScale = fontScale
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
        val context = app.createConfigurationContext(config)
        val panel = panelOf(context)
        panel.findViewById<TextView>(R.id.status).setText(R.string.status_recording)
        PanelPreview.bind(panel.findViewById(R.id.preview),
            PanelPreview.tail("An invented preview sentence for checking the portrait panel. ".repeat(6).trim()))
        val metrics = app.resources.displayMetrics
        layOut(panel, minOf(metrics.widthPixels, metrics.heightPixels))
        return panel to context
    }

    private fun layOut(panel: View, width: Int) {
        panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        panel.layout(0, 0, panel.measuredWidth, panel.measuredHeight)
    }

    /**
     * #36 Crate B2 review captures (files/lab/issue36/b2-panel, pulled into corpus/): every panel
     * state the service renders (idle, recording, paused choice, preparing text, preparing choice,
     * take discarded with Undo, unable to insert, private field), portrait and landscape, font 1×
     * and 2×, through the service's own renderer. Offscreen; synthetic text only; no window,
     * microphone or setting is touched. Every target ≥ 48dp (≥ 52dp for the paused buttons, ≥ 56dp
     * for Undo), inside the panel and on one row; status never clipped; the preview keeps three
     * lines (two beside the private-field line in landscape); portrait keeps one panel height in
     * every state. The recording frame draws the ribbon from synthetic loudness.
     */
    @Test fun captureEveryPanelStateAtBothFontScales() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        val out = File(app.filesDir, "lab/issue36/b2-panel").apply { mkdirs() }
        instrumentation.runOnMainSync {
            for (landscape in listOf(false, true)) for (fontScale in listOf(1f, 2f)) {
                val (panel, context) = phonePanel(landscape, fontScale, night = false)
                val width = panel.width
                val density = context.resources.displayMetrics.density
                val heights = mutableMapOf<String, Int>()
                val suffix = "${if (landscape) "land" else "port"}-font${if (fontScale > 1f) "2" else "1"}.png"
                val status = panel.findViewById<TextView>(R.id.status)
                val preview = panel.findViewById<TextView>(R.id.preview)
                val mic = panel.findViewById<ImageButton>(R.id.microphone)
                val text = PanelPreview.tail("… zwei Hokkaido, eine Zwiebel, ein Stück Ingwer und Kokosmilch. And please bring fresh coriander on Saturday. ".repeat(3).trim())
                val ring = panel.findViewById<RecordingRingView>(R.id.recording_ring)
                fun state(name: String, label: Int, frame: PanelFrame, words: String = text, privateField: Boolean = false) {
                    status.setText(label)
                    PanelPreview.bind(preview, words)
                    PanelDisclosure.bind(panel.findViewById(R.id.retention_notice), if (privateField) R.string.retention_field_off else null)
                    renderPanel(panel, frame)
                    layOut(panel, width)
                    // Recording draws the real ribbon: the service attaches the take's feedback;
                    // here mid-loudness speech, as it looks while talking. Every other frame rests.
                    if (frame.stopGlyph) settledRing(ring, 0.05f).recycle() else restedRing(ring)
                    heights[name] = panel.height
                    val whole = Rect(0, 0, panel.width, panel.height)
                    assertTrue("status clipped ($name $suffix)", status.height >= status.layout.height + status.compoundPaddingTop + status.compoundPaddingBottom)
                    if (preview.visibility == View.VISIBLE) assertTrue("preview lost its lines ($name $suffix)",
                        preview.height >= preview.layout.getLineBottom(minOf(preview.maxLines - 1, preview.layout.lineCount - 1), false))
                    val back = panel.findViewById<View>(R.id.back)
                    for (target in listOf(back, mic).filter { it.visibility == View.VISIBLE }) {
                        assertTrue("target below 48dp ($name $suffix)", target.width >= 48 * density - 1 && target.height >= 48 * density - 1)
                        assertTrue("target outside the panel ($name $suffix)", whole.contains(bounds(target, panel)))
                    }
                    val image = Bitmap.createBitmap(panel.width, panel.height, Bitmap.Config.ARGB_8888)
                    try {
                        panel.draw(Canvas(image))
                        File(out, "$name-$suffix").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    } finally { image.recycle() }
                }
                fun oneRow(state: String, ids: List<Int>) {
                    val tops = ids.map { bounds(panel.findViewById(it), panel).top }.toSet()
                    assertEquals("one row at one height ($state $suffix)", 1, tops.size)
                    for (id in ids) {
                        val button = panel.findViewById<ImageButton>(id)
                        assertTrue("${button.contentDescription} below 52dp ($state $suffix)", button.height >= 52 * density - 1 && button.width >= 52 * density - 1)
                        assertTrue("${button.contentDescription} outside the panel ($state $suffix)", Rect(0, 0, panel.width, panel.height).contains(bounds(button, panel)))
                        assertFalse(button.contentDescription.isNullOrBlank())
                    }
                }
                mic.isEnabled = true
                state("idle", R.string.status_idle, PanelFrame(), words = "")
                state("private-field", R.string.status_idle, PanelFrame(), words = "", privateField = true)
                state("recording", R.string.status_recording, PanelFrame(stopGlyph = true, backLabel = R.string.discard_recording))
                state("private-recording", R.string.status_recording, PanelFrame(stopGlyph = true, backLabel = R.string.discard_recording), privateField = true)
                state("paused-choice", R.string.status_paused, PanelFrame(choice = PanelChoice.RECORDING, backLabel = R.string.continue_recording))
                oneRow("paused-choice", listOf(R.id.choice_safe, R.id.choice_stop, R.id.choice_discard))
                val apart = bounds(panel.findViewById(R.id.choice_discard), panel).left - bounds(panel.findViewById(R.id.choice_stop), panel).right
                assertTrue("Discard set apart ($suffix)", apart >= 40 * density - 1)
                state("preparing", R.string.status_finishing, PanelFrame(preparing = true, backLabel = R.string.back_hold_and_choose))
                assertEquals(View.VISIBLE, panel.findViewById<View>(R.id.processing).visibility)
                assertNotEquals("no microphone disk while preparing", View.VISIBLE, mic.visibility)
                state("preparing-choice", R.string.status_finishing_choice, PanelFrame(choice = PanelChoice.FINISHING, backLabel = R.string.keep_finishing))
                oneRow("preparing-choice", listOf(R.id.choice_safe, R.id.choice_discard))
                // As the service draws it 3 s into the 8 s window.
                panel.findViewById<UndoTimerView>(R.id.undo_timer).fraction = 5f / 8f
                state("discarded-undo", R.string.status_discarded, PanelFrame(undo = true))
                assertEquals(View.VISIBLE, panel.findViewById<View>(R.id.undo_timer).visibility)
                val undo = panel.findViewById<View>(R.id.undo_discard)
                assertTrue("Undo below 56dp ($suffix)", undo.height >= 56 * density - 1)
                assertEquals("no microphone during the Undo window", false, mic.visibility == View.VISIBLE)
                state("unable-to-insert", R.string.status_unable_insert, PanelFrame(unableToInsert = true), words = "")
                assertEquals(View.VISIBLE, panel.findViewById<View>(R.id.status_marker).visibility)
                assertEquals(View.VISIBLE, mic.visibility)
                if (!landscape) assertEquals("portrait keeps one panel height ($suffix): $heights", 1,
                    heights.filterKeys { !it.startsWith("private-") }.values.toSet().size)
            }
        }
    }

    /** Visible tap targets on the panel (Back and the microphone at rest). */
    private fun actions(view: View): Int = when {
        view.visibility != View.VISIBLE -> 0
        view is ImageButton || view is android.widget.Button -> 1
        view is ViewGroup -> (0 until view.childCount).sumOf { actions(view.getChildAt(it)) }
        else -> 0
    }
}
