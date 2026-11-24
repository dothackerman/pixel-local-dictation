package ch.og.dictation

import ch.og.dictation.audio.RecordingFeedback
import ch.og.dictation.panel.PanelChoice
import ch.og.dictation.panel.PanelFrame
import ch.og.dictation.panel.PanelPreview
import ch.og.dictation.panel.RecordingRingView
import ch.og.dictation.panel.renderPanel
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt

/**
 * The recording panel device suite on the laptop (#77): only our panel, through the service's own
 * renderer, at the Pixel's portrait and landscape window sizes. Its captures are the panel gallery
 * (PanelGalleryTest).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RecordingPanelTest {
    private companion object {
        /** The Pixel 10 Pro XL's window: 1344 px at density 3 in portrait. */
        const val PORTRAIT_DP = 448
        const val LANDSCAPE_DP = 960
        /** The short side of the phone held sideways. */
        const val LANDSCAPE_HEIGHT_DP = 440
    }

    /** The themed context of a panel in this window (#36: inflated from Theme.Dictation). */
    private fun themed(landscape: Boolean, fontScale: Float = 1f, narrowWidthDp: Int? = null): Context {
        RuntimeEnvironment.setQualifiers(when {
            narrowWidthDp != null -> "w${narrowWidthDp}dp-h360dp-land-xxhdpi"
            landscape -> "w${LANDSCAPE_DP}dp-h${LANDSCAPE_HEIGHT_DP}dp-land-xxhdpi"
            else -> "w${PORTRAIT_DP}dp-h997dp-port-xxhdpi"
        })
        RuntimeEnvironment.setFontScale(fontScale)
        return ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_Dictation)
    }

    private fun panelOf(context: Context): View = LayoutInflater.from(context).inflate(R.layout.ime, null)

    private fun layOut(panel: View, widthDp: Int) {
        val width = (widthDp * panel.resources.displayMetrics.density).roundToInt()
        panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        panel.layout(0, 0, panel.measuredWidth, panel.measuredHeight)
    }

    private val recording = PanelFrame(stopGlyph = true, backLabel = R.string.discard_recording, status = R.string.status_recording,
        microphoneEnabled = true, microphoneLabel = R.string.stop_recording)
    private val privateField = R.string.retention_field_off

    /** A recording panel on the phone, in either orientation: the last 240 characters from a whole word, newest line in view. */
    private fun phonePanel(landscape: Boolean, fontScale: Float, narrowWidthDp: Int? = null, notice: Int? = null): Pair<View, Int> {
        val panel = panelOf(themed(landscape, fontScale, narrowWidthDp))
        val widthDp = narrowWidthDp ?: if (landscape) LANDSCAPE_DP else PORTRAIT_DP
        renderPanel(panel, recording.copy(notice = notice,
            preview = PanelPreview.tail("An invented preview sentence for checking the panel. ".repeat(6).trim())), null, null)
        layOut(panel, widthDp)
        return panel to widthDp
    }

    /** The view's box in [root]'s coordinates; its own scroll (the preview shows its newest lines) does not move it. */
    private fun bounds(view: View, root: View): Rect {
        val r = Rect(view.scrollX, view.scrollY, view.scrollX + view.width, view.scrollY + view.height)
        (root as ViewGroup).offsetDescendantRectToMyCoords(view, r)
        return r
    }

    private fun unclipped(text: TextView) = text.height >= text.layout.height + text.compoundPaddingTop + text.compoundPaddingBottom

    /** Recording: a thick red-orange ribbon around a pale field; at rest a hairline in the outline role. */
    @Test fun recordingRingIsAnInkRibbonAroundAPaleFieldAndRestIsAHairline() {
        val context = themed(landscape = false)
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

    @Test fun privacyDisclosureIsSecondaryAndOrdinaryHistoryDisclosureStartsHidden() {
        val context = themed(landscape = false)
        val finishing = PanelFrame(preparing = true, status = R.string.status_finishing)
        val panel = panelOf(context)
        renderPanel(panel, finishing, null, null)
        val disclosure = panel.findViewById<TextView>(R.id.retention_notice)
        assertEquals(View.GONE, disclosure.visibility)

        // The same latched decision is rendered again after the system recreates the panel.
        renderPanel(panel, finishing.copy(notice = privateField), null, null)
        val recreated = panelOf(context)
        renderPanel(recreated, finishing.copy(notice = privateField), null, null)
        val recreatedNotice = recreated.findViewById<TextView>(R.id.retention_notice)
        assertEquals(View.VISIBLE, recreatedNotice.visibility)
        assertEquals(disclosure.text.toString(), recreatedNotice.text.toString())
        assertEquals(context.getString(R.string.status_finishing), recreated.findViewById<TextView>(R.id.status).text.toString())
        assertEquals(context.getString(privateField), recreatedNotice.text.toString())
        val status = recreated.findViewById<TextView>(R.id.status)
        assertTrue("the disclosure is secondary to the status", recreatedNotice.textSize < status.textSize)
    }

    @Test fun compactPanelKeepsControlsAndPreviewAtLargeFontSizes() {
        for (fontScale in listOf(1f, 2f)) {
            val context = themed(landscape = false, fontScale)
            val density = context.resources.displayMetrics.density
            val panel = panelOf(context)
            val ended = PanelFrame(status = R.string.status_capture_ended, notice = privateField,
                preview = "An invented preview sentence for checking larger text. ".repeat(8))
            renderPanel(panel, ended, null, null)
            layOut(panel, 320)
            val status = panel.findViewById<TextView>(R.id.status)
            val preview = panel.findViewById<TextView>(R.id.preview)
            val notice = panel.findViewById<TextView>(R.id.retention_notice)
            val mic = panel.findViewById<ImageButton>(R.id.microphone)
            val back = panel.findViewById<ImageButton>(R.id.back)
            assertEquals((70 * density).roundToInt(), mic.width)
            assertEquals((48 * density).roundToInt(), back.width)
            assertTrue(preview.textSize <= status.textSize)
            assertEquals(View.VISIBLE, notice.visibility)
            assertTrue("notice clipped at fontScale=$fontScale", unclipped(notice))
            assertTrue("status clipped at fontScale=$fontScale", unclipped(status))
            // The gap after the last visible line is not clipped text.
            assertTrue("preview clipped at fontScale=$fontScale",
                preview.height >= preview.layout.getLineBottom(2, false) + preview.compoundPaddingTop + preview.compoundPaddingBottom)
            val stage = mic.parent as ViewGroup
            assertTrue(mic.left >= 0 && mic.right <= stage.width && mic.bottom <= stage.height)
            assertEquals(2, actions(panel))
            for (message in listOf(R.string.status_stopping_recording, R.string.status_unable_insert,
                R.string.status_finishing_choice, R.string.status_paused)) {
                renderPanel(panel, ended.copy(status = message), null, null)
                layOut(panel, 320)
                assertTrue("interruption status clipped at fontScale=$fontScale", unclipped(status))
            }
        }
    }

    @Test fun landscapePanelIsCompactAndKeepsEveryControl() {
        for (fontScale in listOf(1f, 2f)) {
            val (panel, _) = phonePanel(landscape = true, fontScale, notice = privateField)
            val density = panel.resources.displayMetrics.density
            // The editor stays visible above: the panel takes well under half the sideways height.
            assertTrue("panel ${panel.height / density}dp too tall at fontScale=$fontScale for ${LANDSCAPE_HEIGHT_DP}dp",
                panel.height <= LANDSCAPE_HEIGHT_DP * density * 0.45f)
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
            assertTrue("status clipped at fontScale=$fontScale", unclipped(status))
            assertTrue("notice clipped at fontScale=$fontScale", unclipped(notice))
            assertTrue("preview clipped at fontScale=$fontScale",
                preview.height >= preview.layout.getLineBottom(preview.maxLines - 1, false) + preview.compoundPaddingTop + preview.compoundPaddingBottom)
            assertEquals(2, actions(panel))
        }
    }

    @Test fun overflowingPreviewShowsTheNewestWords() {
        for (landscape in listOf(false, true)) for (fontScale in listOf(1f, 2f)) {
            val panel = panelOf(themed(landscape, fontScale))
            val preview = panel.findViewById<TextView>(R.id.preview)
            renderPanel(panel, recording.copy(preview = PanelPreview.tail((1..60).joinToString(" ") { "word$it" })), null, null)
            layOut(panel, if (landscape) LANDSCAPE_DP else PORTRAIT_DP)
            val layout = preview.layout
            val box = preview.height - preview.compoundPaddingTop - preview.compoundPaddingBottom
            val lastLine = layout.lineCount - 1
            assertTrue("the preview does not overflow (landscape=$landscape fontScale=$fontScale)", layout.lineCount > preview.maxLines)
            assertTrue("newest words hidden (landscape=$landscape fontScale=$fontScale)",
                layout.getLineBottom(lastLine) - preview.scrollY <= box + 1)
            assertTrue("newest words above the box (landscape=$landscape fontScale=$fontScale)",
                layout.getLineTop(lastLine) - preview.scrollY >= 0)
            assertEquals("a clipped older line shows above (landscape=$landscape fontScale=$fontScale)",
                layout.getLineTop(layout.getLineForVertical(preview.scrollY)), preview.scrollY)
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
        val (panel, _) = phonePanel(landscape = true, fontScale = 1f)
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

    /** The held-text states (Unable to insert text) as the service renders them; labels are the status the service shows. */
    private class HeldState(val name: String, val status: Int, val privateField: Boolean = false,
                            val uncertain: Boolean = false)
    private val heldStates = listOf(
        HeldState("unable-to-insert", R.string.status_unable_history),
        HeldState("private-unable-to-insert", R.string.status_unable_unsaved, privateField = true),
        HeldState("unable-to-insert-back-warning", R.string.status_back_warning),
        HeldState("unable-to-insert-unconfirmed", R.string.status_unconfirmed, uncertain = true),
    )
    private val words = PanelPreview.tail("… zwei Hokkaido, eine Zwiebel, ein Stück Ingwer und Kokosmilch. " +
        "And please bring fresh coriander on Saturday. ".repeat(3).trim())

    /**
     * Every panel state the service renders (idle, recording, paused choice, preparing text,
     * preparing choice, unable to insert, private field), portrait and landscape, font 1× and 2×,
     * through the service's own renderer. Every target ≥ 48dp (≥ 52dp for the paused buttons),
     * inside the panel and on one row; status never clipped; the preview keeps three lines (two
     * beside the private-field line in landscape); portrait keeps one panel height in every state.
     * The images are in the panel gallery.
     */
    @Test fun everyPanelStateKeepsItsTargetsStatusAndHeightAtBothFontScales() {
        for (landscape in listOf(false, true)) for (fontScale in listOf(1f, 2f)) {
            val (panel, widthDp) = phonePanel(landscape, fontScale)
            val context = panel.context
            val density = context.resources.displayMetrics.density
            val heights = mutableMapOf<String, Int>()
            val suffix = "${if (landscape) "land" else "port"}-font${if (fontScale > 1f) "2" else "1"}"
            val status = panel.findViewById<TextView>(R.id.status)
            val preview = panel.findViewById<TextView>(R.id.preview)
            val mic = panel.findViewById<ImageButton>(R.id.microphone)
            fun state(name: String, label: Int, frame: PanelFrame, text: String = words, private: Boolean = false) {
                renderPanel(panel, frame.copy(status = label, preview = text, microphoneEnabled = true,
                    microphoneLabel = R.string.start_recording, notice = if (private) privateField else null), null, null)
                layOut(panel, widthDp)
                heights[name] = panel.height
                val whole = Rect(0, 0, panel.width, panel.height)
                assertTrue("status clipped ($name $suffix)", unclipped(status))
                if (preview.visibility == View.VISIBLE) assertTrue("preview lost its lines ($name $suffix)",
                    preview.height >= preview.layout.getLineBottom(minOf(preview.maxLines - 1, preview.layout.lineCount - 1), false))
                val back = panel.findViewById<View>(R.id.back)
                val safeAction = panel.findViewById<View>(R.id.choice_safe)
                val discardAction = panel.findViewById<View>(R.id.choice_discard)
                for (target in listOf(back, mic, safeAction, discardAction).filter { it.visibility == View.VISIBLE && (it.parent as View).visibility == View.VISIBLE }) {
                    assertTrue("target below 48dp ($name $suffix)", target.width >= 48 * density - 1 && target.height >= 48 * density - 1)
                    assertTrue("target outside the panel ($name $suffix)", whole.contains(bounds(target, panel)))
                }
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
            state("idle", R.string.status_idle, PanelFrame(), text = "")
            state("private-field", R.string.status_idle, PanelFrame(), text = "", private = true)
            state("recording", R.string.status_recording, PanelFrame(stopGlyph = true, backLabel = R.string.discard_recording))
            state("private-recording", R.string.status_recording, PanelFrame(stopGlyph = true, backLabel = R.string.discard_recording), private = true)
            assertEquals(View.VISIBLE, mic.visibility)
            state("paused-choice", R.string.status_paused, PanelFrame(choice = PanelChoice.RECORDING, backLabel = R.string.continue_recording))
            oneRow("paused-choice", listOf(R.id.choice_safe, R.id.choice_stop, R.id.choice_discard))
            val apart = bounds(panel.findViewById(R.id.choice_discard), panel).left - bounds(panel.findViewById(R.id.choice_stop), panel).right
            assertTrue("Discard set apart ($suffix)", apart >= 40 * density - 1)
            state("preparing", R.string.status_finishing, PanelFrame(preparing = true, backLabel = R.string.back_hold_and_choose))
            assertEquals(View.VISIBLE, panel.findViewById<View>(R.id.processing).visibility)
            assertNotEquals("no microphone disk while preparing", View.VISIBLE, mic.visibility)
            state("preparing-choice", R.string.status_finishing_choice, PanelFrame(choice = PanelChoice.FINISHING, backLabel = R.string.keep_finishing))
            oneRow("preparing-choice", listOf(R.id.choice_safe, R.id.choice_discard))
            for (held in heldStates) {
                state(held.name, held.status, PanelFrame(held = true, uncertain = held.uncertain), private = held.privateField)
                assertEquals(View.VISIBLE, panel.findViewById<View>(R.id.status_marker).visibility)
                assertNotEquals("no microphone disk with held text", View.VISIBLE, mic.visibility)
                assertNotEquals("no ring with held text", View.VISIBLE, panel.findViewById<View>(R.id.recording_ring).visibility)
                assertEquals(context.getString(R.string.copy_text), panel.findViewById<View>(R.id.choice_safe).contentDescription.toString())
                assertEquals(context.getString(R.string.discard_text), panel.findViewById<View>(R.id.choice_discard).contentDescription.toString())
                assertEquals(View.VISIBLE, preview.visibility)
                assertTrue(preview.text.isNotBlank())
                oneRow(held.name, listOf(R.id.choice_safe, R.id.choice_discard))
            }
            // Held text says what to do: at font 2 its status may take one more line than the others.
            val held = heldStates.map { it.name }.toSet()
            if (!landscape) {
                assertEquals("portrait keeps one panel height ($suffix): $heights", 1,
                    heights.filterKeys { !it.startsWith("private-") && it !in held }.values.toSet().size)
                for (name in listOf("unable-to-insert", "unable-to-insert-back-warning")) {
                    assertTrue("$name grows by more than one status line ($suffix): $heights",
                        heights.getValue(name) - heights.getValue("idle") <= status.lineHeight)
                }
            }
        }
    }

    /**
     * Narrow landscape (under 600dp wide, layout-land): Copy and Discard text stack in the stage, so
     * the panel keeps the height of the other states; the preview stays in the status column.
     */
    @Test fun heldTextInNarrowLandscapeStacksInTheStageAndKeepsThePanelHeight() {
        for (fontScale in listOf(1f, 2f)) {
            val (panel, widthDp) = phonePanel(landscape = true, fontScale, narrowWidthDp = 560)
            assertFalse("not the narrow layout", panel.resources.getBoolean(R.bool.panel_choice_over_stage))
            val density = panel.resources.displayMetrics.density
            val suffix = "narrow-font${if (fontScale > 1f) "2" else "1"}"
            val status = panel.findViewById<TextView>(R.id.status)
            fun draw(label: Int, frame: PanelFrame, private: Boolean): Int {
                renderPanel(panel, frame.copy(status = label, preview = if (frame.held) words else "", microphoneEnabled = true,
                    microphoneLabel = R.string.start_recording, notice = if (private) privateField else null), null, null)
                layOut(panel, widthDp)
                return panel.height
            }
            val pausedChoice = PanelFrame(choice = PanelChoice.RECORDING, backLabel = R.string.continue_recording)
            val idle = draw(R.string.status_idle, PanelFrame(), false)
            val paused = draw(R.string.status_paused, pausedChoice, false)
            // A private field adds the notice line to every state: compare with the same field's other states.
            val privateIdle = draw(R.string.status_idle, PanelFrame(), true)
            val privatePaused = draw(R.string.status_paused, pausedChoice, true)
            for (held in heldStates) {
                val reference = if (held.privateField) maxOf(privateIdle, privatePaused) else maxOf(idle, paused)
                val height = draw(held.status, PanelFrame(held = true, uncertain = held.uncertain), held.privateField)
                val safe = panel.findViewById<View>(R.id.choice_safe)
                val discard = panel.findViewById<View>(R.id.choice_discard)
                val stage = panel.findViewById<View>(R.id.stage)
                val whole = Rect(0, 0, panel.width, panel.height)
                // The status says what to do: at font 2 it may be a few dp taller than the other states (a private field,
                // with its notice line, up to 40dp: measured 34dp on the Pixel), no more.
                assertTrue("${held.name} ($suffix): panel $height px vs $reference px", height <= reference + (if (held.privateField && fontScale > 1f) 40 else 16) * density)
                for (button in listOf(safe, discard)) {
                    assertTrue("${held.name} ($suffix): button outside panel", whole.contains(bounds(button, panel)))
                    assertTrue("${held.name} ($suffix): button outside the stage", bounds(stage, panel).contains(bounds(button, panel)))
                    assertTrue("${held.name} ($suffix): target below 48dp", button.width >= 48 * density - 1 && button.height >= 48 * density - 1)
                }
                assertTrue("${held.name} ($suffix): not stacked", bounds(discard, panel).top >= bounds(safe, panel).bottom - 1)
                assertTrue("${held.name} ($suffix): status clipped", unclipped(status))
                assertEquals(View.VISIBLE, panel.findViewById<View>(R.id.status_marker).visibility)
            }
            // Leaving the held state puts the choice row back in the status column for the paused choice.
            draw(R.string.status_paused, pausedChoice, false)
            assertEquals(paused, panel.height)
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
