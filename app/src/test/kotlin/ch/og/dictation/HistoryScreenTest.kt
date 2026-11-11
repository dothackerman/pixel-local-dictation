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
            setTheme(R.style.Theme_Dictation)
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

    @Test fun listMarksOnlyTakesThatAreNotCompleteAndInsertedWithOneGlyph() {
        val page = open()
        val markers = (0..1).map { rowAt(page, it).findViewById<View>(R.id.history_row_marker).visibility }
        assertEquals("a clean row shows nothing; at most one marker", listOf(View.GONE, View.VISIBLE), markers)
        // No text chips: the words are the row's spoken description, the same sentence as the detail.
        assertFalse(shown(page, app.getString(R.string.history_delivery_rejected)))
        assertEquals(app.getString(R.string.history_row_description, HistoryRows.time(100), "Second synthetic transcript") + ", " +
            app.getString(R.string.marker_not_inserted_line), rowAt(page, 1).findViewById<View>(R.id.history_row_body).contentDescription.toString())
        assertEquals(HistoryRows.time(200), rowAt(page, 0).findViewById<TextView>(R.id.history_row_time).text.toString())
        // One day header above both rows.
        val days = texts(page.window.decorView).filter { it.id == R.id.history_day }.map { it.text.toString() }
        assertEquals(listOf(HistoryRows.dayText(200)), days)
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

    /** Item 0 of the list is the explanation header; rows follow under their day headers. */
    private fun rowAt(page: Page, n: Int): View {
        val list = page.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.history_list)
        return (0 until list.childCount).map(list::getChildAt).filter { it.findViewById<View>(R.id.history_row_body) != null }[n]
    }
    private fun firstRow(page: Page) = rowAt(page, 0)
    private fun clipboard() = app.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    private fun clipText() = clipboard().primaryClip?.getItemAt(0)?.text?.toString()
    private fun toolbar(page: Page) = page.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.history_toolbar)

    @Test fun detailHasItsOwnTitleIconActionsInOrderAndNoClearHistoryOrTextButtons() {
        val page = open()
        firstRow(page).findViewById<View>(R.id.history_row_body).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val toolbar = toolbar(page)
        assertEquals(HistoryRows.dayText(200), toolbar.title.toString())
        assertFalse(toolbar.menu.findItem(R.id.history_clear_item).isVisible)
        assertFalse(toolbar.menu.findItem(R.id.history_marks_item).isVisible)
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
        assertFalse(texts(page.window.decorView).any { it is android.widget.Button && it.text.isNotEmpty() })
        assertEquals(View.GONE, page.findViewById<View>(R.id.history_list_pane).visibility)
        // Delete keeps a 16dp gap after Copy and is a 48dp target with its words.
        val action = delete.actionView!!
        val density = app.resources.displayMetrics.density
        assertTrue(action.paddingStart >= (16 * density).toInt())
        val button = action.findViewById<View>(R.id.history_delete_action)
        assertTrue(button.layoutParams.width >= (48 * density).toInt() && button.layoutParams.height >= (48 * density).toInt())
        assertEquals(app.getString(R.string.history_delete_transcript), button.contentDescription.toString())
        button.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(shown(page, "First synthetic transcript"))
    }

    @Test fun marksSheetListsEveryMarkWithItsSentenceFromTheMenuAndTheDetailLine() {
        val page = open()
        val toolbar = toolbar(page)
        val items = (0 until toolbar.menu.size()).map { toolbar.menu.getItem(it) }.filter { it.isVisible }.map { it.itemId }
        assertEquals("marks first, then Clear History", listOf(R.id.history_marks_item, R.id.history_clear_item), items)
        toolbar.menu.performIdentifierAction(R.id.history_marks_item, 0)
        shadowOf(Looper.getMainLooper()).idle()
        val sheet = org.robolectric.shadows.ShadowDialog.getLatestDialog() as com.google.android.material.bottomsheet.BottomSheetDialog
        val rows = sheet.findViewById<ViewGroup>(R.id.history_marks_rows)!!
        assertEquals(HistoryMarker.entries.size + 1, rows.childCount)
        for (mark in HistoryMarker.entries) assertTrue(texts(rows).any { it.text.toString() == app.getString(mark.sentence) })
        sheet.dismiss()
        rowAt(page, 1).findViewById<View>(R.id.history_row_body).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val line = page.findViewById<View>(R.id.history_detail_marker)
        assertTrue(line.minimumHeight >= (48 * app.resources.displayMetrics.density).toInt())
        line.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val opened = org.robolectric.shadows.ShadowDialog.getLatestDialog()
        val marks = opened.findViewById<ViewGroup>(R.id.history_marks_rows)
        val selected = (0 until marks.childCount).map { marks.getChildAt(it).findViewById<View>(R.id.history_mark_body) }.filter { it.isSelected }
        assertEquals("the sheet opens at that mark", 1, selected.size)
        assertTrue(texts(selected.single()).any { it.text.toString() == app.getString(R.string.marker_not_inserted_sentence) })
    }

    private fun pixels(view: View): android.graphics.Bitmap =
        android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888).also { view.draw(android.graphics.Canvas(it)) }
    private fun role(view: View, attr: Int) = Integer.toHexString(com.google.android.material.color.MaterialColors.getColor(view, attr))

    /** #36 critique: sheets sit on the kraft surface (no elevation tint); the named mark carries an ink start bar. */
    @Test @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun marksSheetIsOnTheSurfaceAndTheNamedMarkHasAStartBar() {
        val page = open()
        rowAt(page, 1).findViewById<View>(R.id.history_row_body).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        page.findViewById<View>(R.id.history_detail_marker).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val sheet = org.robolectric.shadows.ShadowDialog.getLatestDialog() as com.google.android.material.bottomsheet.BottomSheetDialog
        val ground = sheet.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)!!
        val surface = role(ground, com.google.android.material.R.attr.colorSurface)
        assertEquals("sheet ground", surface, Integer.toHexString(pixels(ground).getPixel(ground.width / 2, 2)))
        val marks = sheet.findViewById<ViewGroup>(R.id.history_marks_rows)!!
        val body = (0 until marks.childCount).map { marks.getChildAt(it).findViewById<View>(R.id.history_mark_body) }.single { it.isSelected }
        val drawn = pixels(body)
        val dp = app.resources.displayMetrics.density
        assertEquals("start bar", role(body, com.google.android.material.R.attr.colorOnSurface), Integer.toHexString(drawn.getPixel((2 * dp).toInt(), body.height / 2)))
    }

    /** The overflow menu draws on the kraft surface, not Material's baseline (device capture, #36). */
    @Test fun overflowMenuUsesTheAppSurface() {
        val page = open()
        val toolbar = toolbar(page)
        val popup = android.view.ContextThemeWrapper(toolbar.context, toolbar.popupTheme)
        assertEquals(role(toolbar, com.google.android.material.R.attr.colorSurface),
            Integer.toHexString(com.google.android.material.color.MaterialColors.getColor(popup, com.google.android.material.R.attr.colorSurface, 0)))
        assertEquals(role(toolbar, com.google.android.material.R.attr.colorOnSurface),
            Integer.toHexString(com.google.android.material.color.MaterialColors.getColor(popup, com.google.android.material.R.attr.colorOnSurface, 0)))
    }

    /** #36 critique: the Clear confirmation is on the surface; Clear is error-coloured, Cancel stays neutral. */
    @Test @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun clearDialogMarksOnlyTheDestructiveButton() {
        val page = open()
        toolbar(page).menu.performIdentifierAction(R.id.history_clear_item, 0)
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        val clear = dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
        val cancel = dialog.getButton(android.content.DialogInterface.BUTTON_NEGATIVE)
        val error = role(clear, androidx.appcompat.R.attr.colorError)
        assertEquals(error, Integer.toHexString(clear.currentTextColor))
        assertNotEquals(error, Integer.toHexString(cancel.currentTextColor))
        val decor = dialog.window!!.decorView
        val content = decor.findViewById<View>(android.R.id.content)
        val at = IntArray(2).also(content::getLocationInWindow)
        assertEquals("dialog ground", role(clear, com.google.android.material.R.attr.colorSurface),
            Integer.toHexString(pixels(decor).getPixel(at[0] + content.width / 2, at[1] + 4)))
    }

    @Test fun listHidesTheDetailActionsAndShowsClear() {
        val page = open()
        val toolbar = toolbar(page)
        assertFalse(toolbar.menu.findItem(R.id.history_copy_item).isVisible)
        assertFalse(toolbar.menu.findItem(R.id.history_delete_item).isVisible)
        assertTrue(toolbar.menu.findItem(R.id.history_clear_item).isVisible)
    }

    @Test fun detailCopyIconCopiesWithoutTheSensitiveFlagAndLeavesTheConfirmationToTheSystem() {
        val page = open()
        firstRow(page).findViewById<View>(R.id.history_row_body).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        toolbar(page).menu.performIdentifierAction(R.id.history_copy_item, 0)
        settle { clipText() == "First synthetic transcript" }
        assertNotSensitive()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(shown(page, "Copied"))
    }

    private fun assertNotSensitive() {
        val extras = clipboard().primaryClip!!.description.extras
        assertFalse(extras?.getBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE) ?: false)
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

    @Test fun longPressOnARowCopiesThatTranscriptWithoutOurSnackbarOnAndroid13Plus() {
        val page = open()
        // The second row (entry b), not the first: the right transcript is copied.
        val row = rowAt(page, 1).findViewById<View>(R.id.history_row_body)
        assertTrue(row.isLongClickable)
        assertTrue(row.performLongClick())
        settle { clipText() == "Second synthetic transcript" }
        assertNotSensitive()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(shown(page, "Copied"))
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
        assertEquals("Audio is not stored.", app.getString(R.string.history_explanation))
        val list = page.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.history_list)
        val header = list.getChildAt(0)
        assertSame(page.findViewById<View>(R.id.history_explanation).parent, header)
        assertEquals(0, list.getChildAdapterPosition(header))
        assertEquals("a day header, then the first row", 2, list.getChildAdapterPosition(firstRow(page)))
    }

    @Test fun detailSaysTheSameSentenceAsTheRowAndTextIsSelectable() {
        val page = open()
        val spoken = rowAt(page, 1).findViewById<View>(R.id.history_row_body).contentDescription.toString()
        rowAt(page, 1).findViewById<View>(R.id.history_row_body).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(HistoryRows.time(100) + " · 3 words", page.findViewById<TextView>(R.id.history_detail_meta).text.toString())
        val sentence = page.findViewById<TextView>(R.id.history_detail_marker_text).text.toString()
        assertEquals(app.getString(R.string.marker_not_inserted_line), sentence)
        assertTrue("row and detail share one sentence", spoken.endsWith(sentence))
        assertTrue(page.findViewById<TextView>(R.id.history_detail_text).isTextSelectable)
        // A clean take has no exception line.
        page.onBackPressedDispatcher.onBackPressed()
        shadowOf(Looper.getMainLooper()).idle()
        firstRow(page).findViewById<View>(R.id.history_row_body).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(View.GONE, page.findViewById<View>(R.id.history_detail_marker).visibility)
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
        // Plain words: what happened, then what may be missing (#36 critique).
        assertEquals("Couldn’t save the last change to History. Some text may be missing.", slotText(page))
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
