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
        val rowDelete = firstRow(page).findViewById<View>(R.id.history_row_delete)
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
        firstRow(page).findViewById<View>(R.id.history_row_delete).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(File(root, "$a.history").exists())
        controller.pause().stop()
        settle { !File(root, "$a.history").exists() }
        assertTrue(File(root, "$b.history").exists())
    }

    /** Item 0 of the list is the explanation header; rows follow it. */
    private fun rowAt(page: Page, n: Int) = page.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.history_list).getChildAt(n + 1)
    private fun firstRow(page: Page) = rowAt(page, 0)
    private fun clipboard() = app.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    private fun clipText() = clipboard().primaryClip?.getItemAt(0)?.text?.toString()
    private fun toolbar(page: Page) = page.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.history_toolbar)

    @Test fun detailHasItsOwnTitleIconActionsInOrderAndNoClearHistoryOrTextButtons() {
        val page = open()
        firstRow(page).findViewById<View>(R.id.history_row_body).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val toolbar = toolbar(page)
        assertEquals(HistoryRows.date(200), toolbar.title.toString())
        assertFalse(toolbar.menu.findItem(R.id.history_clear_item).isVisible)
        assertEquals("First synthetic transcript", page.findViewById<TextView>(R.id.history_detail_text).text.toString())
        val copy = toolbar.menu.findItem(R.id.history_copy_item)
        val delete = toolbar.menu.findItem(R.id.history_delete_item)
        assertTrue(copy.isVisible && delete.isVisible)
        // Same toolbar, same height; Copy leads by order only.
        val order = (0 until toolbar.menu.size()).map { toolbar.menu.getItem(it).itemId }
        assertTrue(order.indexOf(R.id.history_copy_item) < order.indexOf(R.id.history_delete_item))
        assertNotNull(copy.icon); assertNotNull(delete.icon)
        assertEquals(app.getString(R.string.history_copy_transcript), copy.contentDescription.toString())
        assertEquals(app.getString(R.string.history_delete_transcript), delete.contentDescription.toString())
        assertEquals(copy.contentDescription.toString(), copy.tooltipText.toString())
        assertEquals(delete.contentDescription.toString(), delete.tooltipText.toString())
        // No text buttons in the detail.
        assertFalse(texts(page.window.decorView).any { it is android.widget.Button })
        assertEquals(View.GONE, page.findViewById<View>(R.id.history_list_pane).visibility)
    }

    @Test fun listHidesTheDetailActionsAndShowsClear() {
        val page = open()
        val toolbar = toolbar(page)
        assertFalse(toolbar.menu.findItem(R.id.history_copy_item).isVisible)
        assertFalse(toolbar.menu.findItem(R.id.history_delete_item).isVisible)
        assertTrue(toolbar.menu.findItem(R.id.history_clear_item).isVisible)
    }

    @Test fun detailCopyIconCopiesWithTheSensitiveFlagAndConfirms() {
        val page = open()
        firstRow(page).findViewById<View>(R.id.history_row_body).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        toolbar(page).menu.performIdentifierAction(R.id.history_copy_item, 0)
        settle { clipText() == "First synthetic transcript" }
        assertTrue(clipboard().primaryClip!!.description.extras!!.getBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE))
        settle { shown(page, app.getString(R.string.history_copied)) }
    }

    @Test fun detailDeleteIconReturnsToTheListWithUndo() {
        val page = open()
        firstRow(page).findViewById<View>(R.id.history_row_body).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        toolbar(page).menu.performIdentifierAction(R.id.history_delete_item, 0)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(View.VISIBLE, page.findViewById<View>(R.id.history_list_pane).visibility)
        assertNull(page.findViewById<View>(R.id.history_detail_text))
        assertFalse(shown(page, "First synthetic transcript"))
        assertTrue(shown(page, app.getString(R.string.dictionary_undo)))
        assertTrue(File(root, "$a.history").exists())
    }

    @Test fun longPressOnARowCopiesThatTranscriptAndConfirmsOnTheList() {
        val page = open()
        // The second row (entry b), not the first: the right transcript is copied.
        val row = rowAt(page, 1).findViewById<View>(R.id.history_row_body)
        assertTrue(row.isLongClickable)
        assertTrue(row.performLongClick())
        settle { clipText() == "Second synthetic transcript" }
        assertTrue(clipboard().primaryClip!!.description.extras!!.getBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE))
        settle { shown(page, app.getString(R.string.history_copied)) }
        // Still the list: long-press does not open the detail.
        assertEquals(View.VISIBLE, page.findViewById<View>(R.id.history_list_pane).visibility)
        assertNull(page.findViewById<View>(R.id.history_detail_text))
    }

    @Test fun longPressCopiesTheFullSavedTextNotTheTruncatedPreview() {
        val long = (1..120).joinToString(" ") { "word$it" }
        assertTrue(long.length > 400)
        HistoryStore(root).save(HistoryEntry("0c1f2a3b-4c5d-4e6f-8a9b-0c1d2e3f4a5b", 300, long, HistoryDelivery.ACCEPTED))
        val page = open()
        settle { shown(page, "word1 ") }
        assertTrue(firstRow(page).findViewById<TextView>(R.id.history_row_preview).text.length <= 200)
        assertTrue(firstRow(page).findViewById<View>(R.id.history_row_body).performLongClick())
        settle { clipText() == long }
    }

    @Test fun rowExposesCopyAndDeleteAsAccessibilityActionsThatWork() {
        val page = open()
        val row = firstRow(page).findViewById<View>(R.id.history_row_body)
        val actions = row.createAccessibilityNodeInfo().actionList.associate { it.label?.toString() to it.id }
        val copyId = actions[app.getString(R.string.history_copy_transcript)]
        val deleteId = actions[app.getString(R.string.history_delete_transcript)]
        assertNotNull(copyId); assertNotNull(deleteId)
        assertTrue(row.performAccessibilityAction(copyId!!, null))
        settle { clipText() == "First synthetic transcript" }
        assertTrue(row.performAccessibilityAction(deleteId!!, null))
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(shown(page, "First synthetic transcript"))
        assertTrue(File(root, "$a.history").exists())
    }

    @Test fun rowTrashIsAFortyEightDpButtonLabelledWithTheDate() {
        val page = open()
        val trash = firstRow(page).findViewById<View>(R.id.history_row_delete)
        val density = app.resources.displayMetrics.density
        assertTrue(trash.layoutParams.width >= (48 * density).toInt() && trash.layoutParams.height >= (48 * density).toInt())
        // Date plus the first words, so two takes in one minute are told apart.
        assertEquals(app.getString(R.string.history_delete_row, HistoryRows.date(200), "First synthetic transcript"), trash.contentDescription.toString())
        // One node per row: the body carries tap and long-press, the trash is the only other focus.
        val body = firstRow(page).findViewById<View>(R.id.history_row_body)
        assertTrue(body.isClickable && body.isLongClickable && trash.isClickable)
    }

    @Test fun explanationIsOneShortLineAndScrollsAwayAsTheFirstListItem() {
        val page = open()
        assertTrue(app.getString(R.string.history_explanation).length <= 80)
        val list = page.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.history_list)
        val header = list.getChildAt(0)
        assertSame(page.findViewById<View>(R.id.history_explanation).parent, header)
        assertEquals(0, list.getChildAdapterPosition(header))
        assertEquals(1, list.getChildAdapterPosition(firstRow(page)))
    }

    @Test fun oneTimeHintNamesTheLongPressAndStaysDismissed() {
        val prefs = app.getSharedPreferences("history_ui", android.content.Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val hint = app.getString(R.string.history_hint)
        assertTrue(hint.contains("hold", ignoreCase = true) && hint.contains("copy", ignoreCase = true))
        val page = open()
        assertTrue(shown(page, hint))
        page.findViewById<View>(R.id.history_hint_dismiss).performClick()
        assertFalse(shown(page, hint))
        val again = open()
        assertFalse(shown(again, hint))
    }

    @Test fun detailMetadataIsTheSameLineAsTheListRowAndTextIsSelectable() {
        val page = open()
        val rowMeta = firstRow(page).findViewById<TextView>(R.id.history_row_meta).text.toString()
        val secondMeta = rowAt(page, 1).findViewById<TextView>(R.id.history_row_meta).text.toString()
        rowAt(page, 1).findViewById<View>(R.id.history_row_body).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(secondMeta, page.findViewById<TextView>(R.id.history_detail_meta).text.toString())
        assertEquals(HistoryRows.date(100) + " · Not inserted", secondMeta)
        assertNotEquals(rowMeta, secondMeta)
        assertTrue(page.findViewById<TextView>(R.id.history_detail_text).isTextSelectable)
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
