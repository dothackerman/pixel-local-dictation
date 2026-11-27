package ch.og.dictation

import ch.og.dictation.history.HistoryEntry
import ch.og.dictation.history.HistoryNotice
import ch.og.dictation.history.HistoryPageCase
import ch.og.dictation.history.historyNotices
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Delete one transcript with no dialog, on the laptop (#79; the phone suite of this name is gone):
 * what happens when the Undo window ends. Hide at once, Undo, delete from the detail and leaving
 * the page: HistoryScreenTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-port-xxhdpi")
class HistoryUndoDeleteTest : HistoryPageCase() {
    private val id = UUID.randomUUID().toString()
    private val other = UUID.randomUUID().toString()

    @Before fun seed() = save(HistoryEntry(id, 100, "Synthetic row to delete"), HistoryEntry(other, 90, "Synthetic row to keep"))

    // The windows are long: idling also runs the Snackbar's animation, which moves the test clock.
    @Test fun deleteFromListHidesAtOnceAndKeepsTheFileUntilTheWindowEnds() {
        undoWindow = 60_000
        val page = open().get()
        rowTrash(awaitRow(page, "Synthetic row to delete")).performClick()
        idle()
        assertFalse(shows(page, "Synthetic row to delete"))
        assertTrue(shows(page, app.getString(R.string.history_deleted)))
        assertTrue(shows(page, app.getString(R.string.dictionary_undo)))
        assertTrue(shows(page, "Synthetic row to keep"))
        advance(50_000)
        assertTrue("not removed before the window ends", stored(id))
        assertFalse(File(root, "$id.deleted").exists())
        advance(10_000)
        await("the transcript removed when the window ends") { !stored(id) }
        assertTrue(stored(other))
        idle()
        assertFalse(shows(page, "Synthetic row to delete"))
        assertTrue(shows(page, "Synthetic row to keep"))
    }

    @Test fun failedRemovalRestoresTheRowAndShowsAWarningOnThePage() {
        undoWindow = 60_000
        val page = open().get()
        val row = awaitRow(page, "Synthetic row to delete")
        val blocker = File(root, "$id.tmp")
        assertTrue(blocker.mkdir())
        File(blocker, "block").writeText("synthetic")
        rowTrash(row).performClick()
        idle()
        assertFalse(shows(page, "Synthetic row to delete"))
        advance(60_000)
        awaitText(page, app.getString(R.string.history_cancel_delete_error))
        awaitText(page, "Synthetic row to delete")
        assertTrue(stored(id))
        assertTrue(HistoryNotice.DELETE in historyNotices(app).pending())
    }

    @Test fun listKeepsItsPlaceAndShowsNoLoadingStateWhenReturningFromDetail() {
        save(*(1..40).map { HistoryEntry(UUID.randomUUID().toString(), 1000L + it, "Synthetic scroll row $it") }.toTypedArray())
        val page = open().get()
        awaitText(page, "Synthetic scroll row 40")
        val list = page.findViewById<RecyclerView>(R.id.history_list)
        fun first() = (list.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition()
        list.scrollToPosition(30)
        idle()
        val before = first()
        assertTrue("scrolled: $before", before > 5)
        click(find(list) { it is TextView && it.isShown && it.id == R.id.history_row_preview }!!)
        awaitText(page, app.getString(R.string.history_copy_transcript))
        page.onBackPressedDispatcher.onBackPressed()
        // Before the reload answers: the rows are still there and nothing says Loading.
        assertFalse(page.findViewById<View>(R.id.history_status).visibility == View.VISIBLE &&
            page.findViewById<TextView>(R.id.history_status_text).text.toString() == app.getString(R.string.history_loading))
        assertEquals(before, first())
        onHistoryThread {}
        idle()
        assertEquals(before, first())
        assertFalse(shows(page, app.getString(R.string.history_loading)))
    }
}
