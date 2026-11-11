package ch.og.dictation

import android.app.Application
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The History page through its real layouts: list, detail, Undo delete, warning row. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HistoryScreenTest {
    class Page : HistoryActivity() {
        override val undoWindowMs: Long get() = 60_000
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_Dictation_Settings)
            super.onCreate(savedInstanceState)
        }
    }
    private lateinit var app: Application
    private lateinit var root: File
    private val a = "b3de7a72-84ce-4721-8412-497b90c09315"
    private val b = "9a67d378-1c8c-405e-bdb1-c113df823988"

    @Before fun seed() {
        app = RuntimeEnvironment.getApplication()
        root = historyRoot(app)
        root.deleteRecursively()
        HistoryNotice.entries.forEach(historyNotices(app)::dismiss)
        HistoryStore(root).save(HistoryEntry(a, 200, "First synthetic transcript", HistoryDelivery.ACCEPTED))
        HistoryStore(root).save(HistoryEntry(b, 100, "Second synthetic transcript", HistoryDelivery.REJECTED))
    }

    @After fun clean() { root.deleteRecursively() }

    private fun settle(done: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000
        do {
            shadowOf(Looper.getMainLooper()).idle()
            if (done()) return
            Thread.sleep(10)
        } while (System.nanoTime() < deadline)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(done())
    }

    private fun texts(view: View, out: MutableList<TextView> = mutableListOf()): List<TextView> {
        if (view is TextView && view.isShown) out.add(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) texts(view.getChildAt(i), out)
        return out
    }
    private fun shown(page: Page, value: String) = texts(page.window.decorView).any { it.text.toString().contains(value) }

    private fun open(): Page {
        val page = Robolectric.buildActivity(Page::class.java).create().start().resume().visible().get()
        settle { shown(page, "First synthetic transcript") }
        return page
    }

    @Test fun listShowsStatusOnlyForTakesThatAreNotCompleteAndInserted() {
        val page = open()
        val metas = texts(page.window.decorView).filter { it.id == R.id.history_row_meta }.map { it.text.toString() }
        assertEquals(listOf(HistoryRows.date(200), HistoryRows.date(100) + " · Not inserted"), metas)
        assertEquals(View.VISIBLE, page.findViewById<View>(R.id.history_explanation).visibility)
        assertEquals(View.GONE, page.findViewById<View>(R.id.history_empty).visibility)
    }

    @Test fun deleteNeedsNoDialogHidesTheRowAtOnceAndUndoRestoresIt() {
        val page = open()
        val rowDelete = page.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.history_list).getChildAt(0).findViewById<View>(R.id.history_row_delete)
        rowDelete.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(shown(page, "First synthetic transcript"))
        assertTrue(shown(page, "Second synthetic transcript"))
        assertNull(org.robolectric.shadows.ShadowDialog.getLatestDialog())
        assertTrue("transcript stays stored while Undo is open", File(root, "$a.history").exists())
        val undo = texts(page.window.decorView).first { it.text.toString() == app.getString(R.string.dictionary_undo) }
        undo.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(shown(page, "First synthetic transcript"))
        assertTrue(File(root, "$a.history").exists())
    }

    @Test fun leavingThePageRemovesAPendingDelete() {
        val controller = Robolectric.buildActivity(Page::class.java).create().start().resume().visible()
        val page = controller.get()
        settle { shown(page, "First synthetic transcript") }
        page.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.history_list).getChildAt(0).findViewById<View>(R.id.history_row_delete).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(File(root, "$a.history").exists())
        controller.pause().stop()
        settle { !File(root, "$a.history").exists() }
        assertTrue(File(root, "$b.history").exists())
    }

    @Test fun detailHasItsOwnTitleOneCopyActionAndNoClearHistory() {
        val page = open()
        val body = page.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.history_list).getChildAt(0).findViewById<View>(R.id.history_row_body)
        body.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val toolbar = page.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.history_toolbar)
        assertEquals(HistoryRows.date(200), toolbar.title.toString())
        assertFalse(toolbar.menu.findItem(R.id.history_clear_item).isVisible)
        assertEquals("First synthetic transcript", page.findViewById<TextView>(R.id.history_detail_text).text.toString())
        assertEquals(app.getString(R.string.history_copy), page.findViewById<TextView>(R.id.history_copy_button).text.toString())
        assertEquals(View.GONE, page.findViewById<View>(R.id.history_list_pane).visibility)
    }

    private fun slot(page: Page) = page.findViewById<View>(R.id.history_status)
    private fun slotText(page: Page) = page.findViewById<TextView>(R.id.history_status_text).text.toString()
    private fun slotAction(page: Page) = page.findViewById<TextView>(R.id.history_status_action)
    private fun liveRegions(view: View): Int =
        (if (view.isShown && view.accessibilityLiveRegion != View.ACCESSIBILITY_LIVE_REGION_NONE) 1 else 0) +
            if (view is ViewGroup) (0 until view.childCount).sumOf { liveRegions(view.getChildAt(it)) } else 0

    @Test fun pendingWarningIsOneInlineRowWithADismissAction() {
        historyNotices(app).record(HistoryNotice.SAVE)
        historyNotices(app).record(HistoryNotice.DELETE)
        val page = open()
        assertEquals(View.VISIBLE, slot(page).visibility)
        assertEquals(app.getString(R.string.history_save_error), slotText(page))
        val dismiss = slotAction(page)
        assertEquals(app.getString(R.string.history_notice_dismiss), dismiss.text.toString())
        assertTrue(dismiss.minimumHeight >= (48 * app.resources.displayMetrics.density).toInt())
        assertTrue(dismiss.minimumWidth >= (48 * app.resources.displayMetrics.density).toInt())
        dismiss.performClick()
        assertEquals(app.getString(R.string.history_cancel_delete_error), slotText(page))
        dismiss.performClick()
        assertEquals(View.GONE, slot(page).visibility)
        assertTrue(historyNotices(app).pending().isEmpty())
    }

    /** The page's own state comes first; a pending warning waits for the same slot, never a second row. */
    @Test fun pageStateAndPendingWarningShareOneStatusSlot() {
        historyNotices(app).record(HistoryNotice.SAVE)
        root.deleteRecursively()
        root.writeText("not a directory")
        val page = Robolectric.buildActivity(Page::class.java).create().start().resume().visible().get()
        settle { slotText(page) == app.getString(R.string.history_read_error) }
        assertEquals(app.getString(R.string.history_retry), slotAction(page).text.toString())
        assertFalse(shown(page, app.getString(R.string.history_save_error)))
        assertEquals(1, liveRegions(page.window.decorView))

        root.delete()
        HistoryStore(root).save(HistoryEntry(a, 200, "First synthetic transcript", HistoryDelivery.ACCEPTED))
        slotAction(page).performClick()
        settle { shown(page, "First synthetic transcript") }
        assertEquals(app.getString(R.string.history_save_error), slotText(page))
        assertEquals(app.getString(R.string.history_notice_dismiss), slotAction(page).text.toString())
        assertFalse(shown(page, app.getString(R.string.history_read_error)))
        assertEquals(1, liveRegions(page.window.decorView))
        slotAction(page).performClick()
        assertEquals(View.GONE, slot(page).visibility)
        assertTrue(historyNotices(app).pending().isEmpty())
    }
}
