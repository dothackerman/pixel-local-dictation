package ch.og.dictation.panel

import ch.og.dictation.R
import ch.og.dictation.audio.RecordingFeedback
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
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
}
