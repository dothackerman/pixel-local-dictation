package ch.og.dictation.panel

import ch.og.dictation.R
import ch.og.dictation.audio.RecordingFeedback
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** [renderPanel] is the only writer of the recording panel's views (#65 step 5). */
@RunWith(RobolectricTestRunner::class)
class RenderPanelTest {
    private fun inflate(): View {
        val app = RuntimeEnvironment.getApplication()
        return LayoutInflater.from(ContextThemeWrapper(app, R.style.Theme_Dictation)).inflate(R.layout.ime, null)
    }

    private fun layOut(panel: View, widthDp: Int) {
        val width = (widthDp * panel.resources.displayMetrics.density).toInt()
        panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        panel.layout(0, 0, panel.measuredWidth, panel.measuredHeight)
    }

    private val recording = PanelFrame(stopGlyph = true, backLabel = R.string.discard_recording, status = R.string.status_recording,
        microphoneEnabled = true, microphoneLabel = R.string.stop_recording, ringLive = true)
    private val heldText = PanelFrame(held = true, status = R.string.status_unable_history, microphoneEnabled = true,
        microphoneLabel = R.string.start_recording, preview = "held words")

    @Test fun anIdenticalFrameWritesNothing() {
        val panel = inflate()
        val shown = renderPanel(panel, recording, null, null)
        val status = panel.findViewById<TextView>(R.id.status)
        status.text = "untouched"
        assertSame(shown, renderPanel(panel, recording, shown, null))
        assertEquals("untouched", status.text.toString())
    }

    @Test fun aPreviewChangeBindsOnlyThePreviewAndNeverMovesFocus() {
        val panel = inflate()
        val shown = renderPanel(panel, heldText, null, null)
        val back = panel.findViewById<View>(R.id.back).apply { isFocusableInTouchMode = true; requestFocus() }
        val status = panel.findViewById<TextView>(R.id.status).apply { text = "untouched" }
        renderPanel(panel, heldText.copy(preview = "newer words"), shown, null)
        assertEquals("newer words", panel.findViewById<TextView>(R.id.preview).text.toString())
        assertEquals("untouched", status.text.toString())
        assertTrue("focus stays where the user put it", back.isFocused)
    }

    @Test fun anyOtherChangeRendersTheWholePanelAndMovesFocusToTheSafeAction() {
        val panel = inflate()
        val shown = renderPanel(panel, heldText, null, null)
        panel.findViewById<View>(R.id.back).apply { isFocusableInTouchMode = true; requestFocus() }
        renderPanel(panel, heldText.copy(status = R.string.status_back_warning, backLabel = R.string.back_discard_text), shown, null)
        assertEquals(panel.context.getString(R.string.status_back_warning), panel.findViewById<TextView>(R.id.status).text.toString())
        assertTrue(panel.findViewById<View>(R.id.choice_safe).isFocused)
    }

    @Test fun theRingFollowsTheTakesFeedbackOnlyWhileLive() {
        val panel = inflate()
        val ring = panel.findViewById<RecordingRingView>(R.id.recording_ring)
        val feedback = RecordingFeedback()
        val shown = renderPanel(panel, recording, null, feedback)
        assertSame(feedback, ring.following)
        renderPanel(panel, recording.copy(ringLive = false), shown, feedback)
        assertNull(ring.following)
        val next = RecordingFeedback()
        renderPanel(panel, recording, null, next)
        assertSame("a new take's feedback is followed even when the frame is equal", next, ring.following)
    }

    /** The seam of the golden frame sequences (#77): each frame a panel is about to show, once. */
    @Test fun everyFrameAPanelIsAboutToShowReachesTheObserverOnce() {
        val seen = mutableListOf<Pair<PanelFrame, Boolean>>()
        onPanelFrame = { frame, fresh -> seen += frame to fresh }
        try {
            val panel = inflate()
            var shown = renderPanel(panel, recording, null, null)
            shown = renderPanel(panel, recording, shown, null)
            val newer = recording.copy(preview = "newer words")
            shown = renderPanel(panel, newer, shown, null)
            renderPanel(inflate(), newer, null, null)
            assertEquals(listOf(recording to true, newer to false, newer to true), seen)
        } finally { onPanelFrame = null }
    }

    @Test fun theMicrophoneBannerAndNoticeComeFromTheFrame() {
        val panel = inflate()
        renderPanel(panel, recording.copy(banner = true, notice = R.string.retention_field_off), null, null)
        val mic = panel.findViewById<ImageButton>(R.id.microphone)
        assertTrue(mic.isEnabled)
        assertEquals(panel.context.getString(R.string.stop_recording), mic.contentDescription.toString())
        assertEquals(View.VISIBLE, panel.findViewById<View>(R.id.benchmark_banner).visibility)
        val notice = panel.findViewById<TextView>(R.id.retention_notice)
        assertEquals(View.VISIBLE, notice.visibility)
        assertEquals(panel.context.getString(R.string.retention_field_off), notice.text.toString())
        renderPanel(panel, recording, null, null)
        assertEquals(View.GONE, panel.findViewById<View>(R.id.benchmark_banner).visibility)
        assertEquals(View.GONE, notice.visibility)
    }

    private val states = PanelStates.all(words = "words so far", heldWords = "held words")

    private fun everyState(widthDp: Int) {
        val panel = inflate()
        val context = panel.context
        val density = context.resources.displayMetrics.density
        val overStage = context.resources.getBoolean(R.bool.panel_choice_over_stage)
        val yields = context.resources.getBoolean(R.bool.panel_preview_yields_to_notice)
        var shown: PanelFrame? = null
        for ((name, state) in states) {
            val frame = panelFrame(state)
            // Each state rendered on a fresh panel and as a change of the previous one look the same.
            for (fresh in listOf(true, false)) {
                val view = if (fresh) inflate() else panel
                val drawn = renderPanel(view, frame, if (fresh) null else shown, null)
                layOut(view, widthDp)
                val covered = frame.choice != null || frame.held
                assertEquals(name, context.getString(frame.status), view.findViewById<TextView>(R.id.status).text.toString())
                assertEquals(name, if (covered) View.VISIBLE else View.GONE, view.findViewById<View>(R.id.choice).visibility)
                val mic = view.findViewById<ImageButton>(R.id.microphone)
                assertEquals(name, if (covered || frame.preparing) View.INVISIBLE else View.VISIBLE, mic.visibility)
                assertEquals(name, frame.microphoneEnabled, mic.isEnabled)
                assertEquals(name, if (frame.preparing && !covered) View.VISIBLE else View.GONE, view.findViewById<View>(R.id.processing).visibility)
                assertEquals(name, if (frame.notice != null) View.VISIBLE else View.GONE, view.findViewById<View>(R.id.retention_notice).visibility)
                val marker = frame.held || (frame.unableToInsert && frame.choice == null)
                assertEquals(name, if (marker) View.VISIBLE else View.GONE, view.findViewById<View>(R.id.status_marker).visibility)
                val preview = view.findViewById<TextView>(R.id.preview)
                val lines = when {
                    !overStage -> if (frame.held || (frame.notice != null && yields)) 2 else 3
                    yields -> if (frame.notice != null) 2 else 3
                    else -> null
                }
                if (lines != null) assertEquals("$name preview lines", lines, preview.maxLines)
                if (covered) assertTrue("$name: the safe action takes focus", view.findViewById<View>(R.id.choice_safe).isFocused)
                val whole = android.graphics.Rect(0, 0, view.width, view.height)
                val targets = listOf(R.id.back, R.id.microphone, R.id.choice_safe, R.id.choice_discard).map { view.findViewById<View>(it) }
                    .filter { it.visibility == View.VISIBLE && (it.parent as View).visibility == View.VISIBLE }
                for (target in targets) {
                    assertTrue("$name: target below 48dp", target.width >= 48 * density - 1 && target.height >= 48 * density - 1)
                    val bounds = android.graphics.Rect(0, 0, target.width, target.height)
                    (view as android.view.ViewGroup).offsetDescendantRectToMyCoords(target, bounds)
                    assertTrue("$name: target outside the panel", whole.contains(bounds))
                }
                if (!fresh) shown = drawn
            }
        }
    }

    @Test @Config(qualifiers = "w411dp-h891dp-port") @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun everyStateInPortraitLight() = everyState(411)

    @Test @Config(qualifiers = "w411dp-h891dp-port-night") @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun everyStateInPortraitDark() = everyState(411)

    @Test @Config(qualifiers = "w540dp-h360dp-land") @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun everyStateInNarrowLandscapeLight() = everyState(540)

    @Test @Config(qualifiers = "w540dp-h360dp-land-night") @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun everyStateInNarrowLandscapeDark() = everyState(540)

    @Test @Config(qualifiers = "w960dp-h440dp-land") @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun everyStateInWideLandscapeLight() = everyState(960)

    @Test @Config(qualifiers = "w960dp-h440dp-land-night") @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun everyStateInWideLandscapeDark() = everyState(960)

    /**
     * Private field, landscape, font 2× (#36 critique): the lock line costs the editor almost no
     * height (the preview yields a line to it), and the lock glyph grows with the text.
     * Replaces DictationProductionLifecycleTest.privateFieldLineStaysCompactInLandscapeAtLargeText.
     */
    @Test @Config(qualifiers = "w960dp-h440dp-land") @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun privateFieldLineStaysCompactInLandscapeAtLargeText() {
        RuntimeEnvironment.setFontScale(2f)
        val panel = inflate()
        val private = recording.copy(notice = R.string.retention_field_off)
        val shown = renderPanel(panel, private, null, null)
        val density = panel.resources.displayMetrics.density
        val notice = panel.findViewById<TextView>(R.id.retention_notice)
        layOut(panel, 960)
        assertEquals(View.VISIBLE, notice.visibility)
        val withLine = panel.height
        val lock = notice.compoundDrawablesRelative[0]
        assertTrue("lock ${lock.bounds.height() / density}dp does not scale with the text", lock.bounds.height() >= 24 * density)
        assertEquals("lock keeps the label's proportion", notice.textSize * 16 / 14, lock.bounds.height().toFloat(), 1.5f)
        renderPanel(panel, recording, shown, null)
        layOut(panel, 960)
        assertTrue("private line adds ${(withLine - panel.height) / density}dp", withLine - panel.height <= 4 * density)
    }

    /**
     * The lock is as sharp as a freshly parsed one whatever was drawn before (#77). A vector
     * drawable from the Resources cache copies the raster size of the cached one, and that size
     * only grows: after a panel at font 2×, a cached lock at font 1× is drawn from the larger
     * raster, scaled down. The cached one lives until a garbage collection, so the panel gallery
     * differed from run to run.
     */
    @Test @Config(qualifiers = "w411dp-h891dp-port-xxhdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun privateFieldLockIsSharpAfterAPanelAtLargerText() {
        val private = recording.copy(notice = R.string.retention_field_off)
        fun lock(fontScale: Float): Drawable {
            RuntimeEnvironment.setFontScale(fontScale)
            val panel = inflate()
            renderPanel(panel, private, null, null)
            return panel.findViewById<TextView>(R.id.retention_notice).compoundDrawablesRelative[0]
        }
        fun pixels(drawable: Drawable): IntArray {
            val image = Bitmap.createBitmap(drawable.bounds.width(), drawable.bounds.height(), Bitmap.Config.ARGB_8888)
            drawable.draw(Canvas(image))
            return IntArray(image.width * image.height).also { image.getPixels(it, 0, image.width, 0, 0, image.width, image.height) }
        }
        val large = lock(2f)
        val largePixels = pixels(large)
        val small = lock(1f)
        val context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_Dictation)
        val fresh = Drawable.createFromXml(context.resources, context.resources.getXml(R.drawable.ic_lock), context.theme)
        fresh.bounds = small.bounds
        assertArrayEquals(pixels(fresh), pixels(small))
        // The larger lock is still referenced here, so the cache kept it throughout.
        assertTrue(large.bounds.width() > small.bounds.width() && largePixels.size > small.bounds.width() * small.bounds.height())
    }
}
