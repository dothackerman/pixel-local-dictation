package ch.og.dictation.panel

import ch.og.dictation.R
import android.graphics.Rect
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Panel layout per state, rendered from frames (#65 step 5c). These cases moved here from
 * DictationProductionLifecycleTest, which drove a whole take to reach each state.
 */
@RunWith(RobolectricTestRunner::class)
class PanelLayoutTest {
    private val words = "old speech"
    private val recording = PanelState(PanelPhase.RECORDING, running = true, preview = words, ringLive = true)
    private val paused = PanelState(PanelPhase.PAUSED, PanelChoice.RECORDING, running = true, preview = words)
    private val finishingChoice = PanelState(PanelPhase.FINISHING_CHOICE, PanelChoice.FINISHING, running = true, finishing = true, preview = words)

    private lateinit var panel: View
    private var shown: PanelFrame? = null

    private fun open() {
        val app = RuntimeEnvironment.getApplication()
        panel = LayoutInflater.from(ContextThemeWrapper(app, R.style.Theme_Dictation)).inflate(R.layout.ime, null)
        shown = null
    }

    private fun show(state: PanelState, widthDp: Int) {
        shown = renderPanel(panel, panelFrame(state), shown, null)
        val width = (widthDp * panel.resources.displayMetrics.density).toInt()
        panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        panel.layout(0, 0, panel.measuredWidth, panel.measuredHeight)
    }

    private fun <T : View> view(id: Int): T = panel.findViewById(id)
    private fun bounds(target: View): Rect {
        val r = Rect(0, 0, target.width, target.height)
        (panel as ViewGroup).offsetDescendantRectToMyCoords(target, r)
        return r
    }

    /**
     * The choice at fontScale 2 in the recording panel: every action ≥ 48dp, inside the panel and
     * not truncated; the safe action first and focused; Discard destructive and apart. Portrait
     * keeps the panel height of the recording state.
     * Replaces DictationProductionLifecycleTest.choiceFitsAtLargeText*.
     */
    private fun choiceFitsAtLargeText(widthDp: Int) {
        RuntimeEnvironment.setFontScale(2f)
        open()
        show(recording, widthDp)
        val recordingHeight = panel.height
        show(paused, widthDp)
        val density = panel.resources.displayMetrics.density
        val safe = view<ImageButton>(R.id.choice_safe)
        val discard = view<ImageButton>(R.id.choice_discard)
        fun check(actions: List<ImageButton>) {
            val whole = Rect(0, 0, panel.width, panel.height)
            val tops = mutableSetOf<Int>()
            for (action in actions) {
                val name = action.contentDescription
                assertTrue("$name below 52dp", action.height >= 52 * density && action.width >= 52 * density)
                val box = bounds(action)
                assertTrue("$name outside the panel", whole.contains(box))
                assertFalse("icon-only buttons still carry their words", name.isNullOrBlank())
                assertEquals("$name tooltip", name.toString(), action.tooltipText?.toString())
                tops.add(box.top)
            }
            assertEquals("one row at one height", 1, tops.size)
            val safeBounds = bounds(safe)
            val discardBounds = bounds(discard)
            val apart = maxOf(discardBounds.left - safeBounds.right, discardBounds.top - safeBounds.bottom)
            assertTrue("Discard sits next to the safe action", apart >= 16 * density)
            assertTrue("Discard sits 40dp apart", apart >= 40 * density)
            assertNotSame("Discard has its own (dashed) edge", safe.background.constantState, discard.background.constantState)
            assertTrue(safe.isFocused)
        }
        check(listOf(safe, view(R.id.choice_stop), discard))
        assertEquals("Continue recording plays", R.drawable.ic_play_arrow, shadowOf(safe.drawable).createdFromResId)
        assertEquals("the choice keeps the panel height", recordingHeight, panel.height)
        show(finishingChoice, widthDp)
        check(listOf(safe, discard))
        assertEquals("Keep preparing text shows preparing, not play", R.drawable.ic_marker_preparing,
            shadowOf(safe.drawable).createdFromResId)
    }

    @Test @Config(qualifiers = "w411dp-h891dp-port") @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun choiceFitsAtLargeTextInPortrait() = choiceFitsAtLargeText(411)

    @Test @Config(qualifiers = "w411dp-h891dp-port-night") @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun choiceFitsAtLargeTextInPortraitDark() = choiceFitsAtLargeText(411)

    /** Pixel 10 Pro XL landscape window. */
    @Test @Config(qualifiers = "w960dp-h440dp-land") @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun choiceFitsAtLargeTextInLandscape() = choiceFitsAtLargeText(960)

    @Test @Config(qualifiers = "w960dp-h440dp-land-night") @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun choiceFitsAtLargeTextInLandscapeDark() = choiceFitsAtLargeText(960)

    private val longPreview = "zwei Hokkaido, eine Zwiebel, ein Stück Ingwer und Kokosmilch. And please bring fresh coriander on " +
        "Saturday, because the market closes early and we still need lime, garlic and a bunch of spring onions for the soup."

    /** Landscape preview (#36 critique P3): lines stop near 70 characters instead of running across the panel. */
    @Test @Config(qualifiers = "w960dp-h440dp-land") @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun landscapePreviewKeepsAReadableMeasure() {
        open()
        show(recording.copy(preview = longPreview), 960)
        val layout = view<TextView>(R.id.preview).layout
        val longest = (0 until layout.lineCount).maxOf { layout.getLineEnd(it) - layout.getLineStart(it) }
        assertTrue("longest preview line has $longest characters", longest <= 75)
    }

    /** Landscape at font 2× (#36 critique P3): the last preview line keeps clear room above the panel's bottom edge. */
    @Test @Config(qualifiers = "w960dp-h440dp-land") @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun landscapePreviewKeepsRoomBelowAtLargeText() {
        RuntimeEnvironment.setFontScale(2f)
        open()
        show(recording.copy(preview = longPreview), 960)
        val preview = view<TextView>(R.id.preview)
        val box = bounds(preview)
        val lastLine = box.top + preview.totalPaddingTop + preview.layout.getLineBottom(preview.layout.lineCount - 1)
        val density = panel.resources.displayMetrics.density
        assertTrue("last line ${(panel.height - lastLine) / density}dp above the bottom edge", panel.height - lastLine >= 16 * density)
    }

    /**
     * Landscape on the phone (#36 critique P1): the choice sits in the stage under the
     * right thumb, where the microphone was; the preview stays readable while Stop or Discard is
     * decided, and the panel keeps its height in every state.
     */
    @Test @Config(qualifiers = "w960dp-h440dp-land") @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun landscapeChoiceTakesTheStageAndKeepsThePreview() {
        open()
        show(recording, 960)
        val height = panel.height
        show(paused, 960)
        val stage = bounds(view(R.id.stage))
        val choice = bounds(view(R.id.choice))
        assertTrue("choice $choice not in the stage $stage", stage.contains(choice))
        assertEquals("the preview stays during the choice", View.VISIBLE, view<View>(R.id.preview).visibility)
        assertEquals(words, view<TextView>(R.id.preview).text.toString())
        assertEquals("the choice keeps the panel height", height, panel.height)
        show(PanelState(PanelPhase.IDLE), 960)
        assertEquals("the idle panel keeps the panel height", height, panel.height)
    }

    /** A narrow landscape window (320dp, fontScale 2): finishing still reads and both actions fit. */
    @Test @Config(qualifiers = "w320dp-h320dp-land") @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun narrowLandscapePanelKeepsFinishingStatusAndActions() {
        RuntimeEnvironment.setFontScale(2f)
        open()
        show(PanelState(PanelPhase.FINISHING, running = true, finishing = true), 320)
        val status = view<TextView>(R.id.status)
        assertEquals(panel.context.getString(R.string.status_finishing), status.text.toString())
        assertTrue("status squeezed to ${status.width}px", status.width >= status.paint.measureText("Finishing") / 2)
        assertTrue("status clipped", status.height >= status.layout.height + status.compoundPaddingTop + status.compoundPaddingBottom)
        val preview = view<TextView>(R.id.preview)
        assertTrue("preview squeezed", preview.width >= 64 * panel.resources.displayMetrics.density)
        val whole = Rect(0, 0, panel.width, panel.height)
        val mic = view<View>(R.id.microphone)
        for (target in listOf(view<View>(R.id.back), mic, view<View>(R.id.recording_ring))) {
            assertTrue("${target.id} outside the panel", whole.contains(bounds(target)))
        }
        assertTrue(mic.width >= 48 * panel.resources.displayMetrics.density)
    }
}
