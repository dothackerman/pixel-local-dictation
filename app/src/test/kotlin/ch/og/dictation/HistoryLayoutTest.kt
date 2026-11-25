package ch.og.dictation

import ch.og.dictation.history.HistoryEntry
import ch.og.dictation.history.HistoryPageCase
import ch.og.dictation.settings.SettingsScreenTest
import android.content.res.Configuration
import android.graphics.Rect
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import java.util.UUID
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * History and Settings at large type, on a 320dp-wide screen and at landscape height, on the laptop
 * (#79; the phone suite of this name is gone, where the page was only measured at 320dp).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HistoryLayoutTest : HistoryPageCase() {
    @Test @Config(qualifiers = "w320dp-h569dp-port-notnight-xhdpi")
    fun historyListAndDetailControlsFitLargeTypeInTheOneScheme() {
        RuntimeEnvironment.setFontScale(2f)
        val entry = HistoryEntry(UUID.randomUUID().toString(), 1, "Synthetic layout transcript")
        save(entry)
        val page = open().get()
        val width = (320 * density).toInt()
        assertEquals(2f, page.resources.configuration.fontScale, 0.01f)
        assertEquals(Configuration.UI_MODE_NIGHT_NO, page.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
        assertEquals(width, page.window.decorView.width)
        val preview = awaitText(page, entry.text)
        val body = target(preview)
        assertTrue(body.width + rowTrash(body).width <= width)
        assertTrue(body.height >= (64 * density).toInt())
        // Preview stays within two lines in the primary text role.
        assertTrue(preview.maxLines <= 2 && preview.lineCount <= 2)
        // The first row is on screen without scrolling even at 2x: the explanation scrolls away with the list.
        val list = page.findViewById<View>(R.id.history_list)
        val top = IntArray(2).also(body::getLocationOnScreen)[1]
        val listTop = IntArray(2).also(list::getLocationOnScreen)[1]
        assertTrue("first row below the viewport: $top", top - listTop < list.height)
        val trash = rowTrash(body)
        assertTrue(trash.width >= (48 * density).toInt() && trash.height >= (48 * density).toInt())
        click(body)
        // Detail: Copy and Delete are toolbar icons, 48dp, at the same height, inside 320dp.
        val copy = awaitText(page, app.getString(R.string.history_copy_transcript), exact = true)
        val delete = awaitText(page, app.getString(R.string.history_delete_transcript), exact = true)
        val copyAt = IntArray(2).also(copy::getLocationOnScreen)
        val deleteAt = IntArray(2).also(delete::getLocationOnScreen)
        for ((control, at) in listOf(copy to copyAt, delete to deleteAt)) {
            assertTrue(control.width >= (48 * density).toInt() && control.height >= (48 * density).toInt())
            assertTrue("inside the 320dp screen: ${at[0]}+${control.width}", at[0] >= 0 && at[0] + control.width <= width)
        }
        // Same height in the bar (centres aligned), Copy first, Delete at least 16dp after it.
        assertTrue(abs((copyAt[1] + copy.height / 2) - (deleteAt[1] + delete.height / 2)) <= 2)
        assertTrue(copyAt[0] < deleteAt[0])
        val iconGap = (deleteAt[0] + (delete.width - (24 * density).toInt()) / 2) - (copyAt[0] + (copy.width + (24 * density).toInt()) / 2)
        assertTrue("Copy and Delete glyphs ${iconGap}px apart", iconGap >= (16 * density).toInt())
        // The transcript wraps inside the screen.
        val text = page.findViewById<TextView>(R.id.history_detail_text)
        assertTrue(text.isShown && text.width <= width)
    }

    @Test @Config(qualifiers = "w569dp-h320dp-land-notnight-xhdpi")
    fun settingsControlsRemainReachableAt320dpLargeTypeAndLandscapeHeight() {
        RuntimeEnvironment.setFontScale(2f)
        val controller = Robolectric.buildActivity(SettingsScreenTest.Page::class.java).create().start().resume().visible()
        try {
            val page = controller.get()
            assertEquals(2f, page.resources.configuration.fontScale, 0.01f)
            val scroll = page.findViewById<ScrollView>(R.id.settings_scroll)
            val width = (320 * density).toInt()
            val height = (320 * density).toInt()
            scroll.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            scroll.layout(0, 0, width, height)
            for (id in listOf(R.id.open_dictionary, R.id.open_history, R.id.benchmark_switch, R.id.benchmark_delete)) {
                val control = page.findViewById<View>(id)
                assertTrue(control.measuredHeight >= (48 * density).toInt())
                assertTrue(control.measuredWidth <= width)
                val rect = Rect()
                control.getDrawingRect(rect)
                scroll.offsetDescendantRectToMyCoords(control, rect)
                scroll.scrollTo(0, rect.top)
                assertTrue(rect.bottom > scroll.scrollY && rect.top < scroll.scrollY + scroll.height)
            }
            // Dictionary and History are navigation rows of equal weight.
            val dictionary = page.findViewById<View>(R.id.open_dictionary)
            val history = page.findViewById<View>(R.id.open_history)
            assertFalse(dictionary is Button || history is Button)
            assertEquals(dictionary.measuredHeight, history.measuredHeight)
            assertEquals(dictionary.measuredWidth, history.measuredWidth)
            assertTrue(scroll.getChildAt(0).measuredHeight > height)
        } finally { controller.pause().stop().destroy() }
    }
}
