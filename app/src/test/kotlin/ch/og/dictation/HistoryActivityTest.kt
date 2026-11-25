package ch.og.dictation

import ch.og.dictation.history.HistoryCompletion
import ch.og.dictation.history.HistoryDelivery
import ch.og.dictation.history.HistoryEntry
import ch.og.dictation.history.HistoryPageCase
import ch.og.dictation.history.HistoryProcess
import ch.og.dictation.history.HistoryRows
import ch.og.dictation.storage.lastCopy
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * History list and detail statuses, on the laptop (#79; the phone suite of this name is gone).
 * Marks on plain rows and long-press copy: HistoryScreenTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-port-xxhdpi")
class HistoryActivityTest : HistoryPageCase() {
    /** A row read as one node: its spoken description names the preview and, if any, ends with its mark's sentence. */
    private fun awaitMarkedRow(page: Page, text: String, sentence: Int?): View {
        val row = awaitRow(page, text)
        val spoken = row.contentDescription.toString()
        assertTrue(spoken, spoken.contains(text))
        for (line in listOf(R.string.marker_uncertain_line, R.string.marker_not_inserted_line, R.string.marker_recording_line,
            R.string.marker_preparing_line, R.string.marker_cut_short_line)) {
            assertEquals("$spoken / ${app.getString(line)}", line == sentence, spoken.endsWith(app.getString(line)))
        }
        return row
    }
    private fun detailLine(page: Page): String? = page.findViewById<View>(R.id.history_detail_marker)?.takeIf { it.visibility == View.VISIBLE }
        ?.findViewById<TextView>(R.id.history_detail_marker_text)?.text?.toString()
    private fun awaitDetailLine(page: Page, sentence: Int?) {
        val expected = sentence?.let(app::getString)
        await("detail line $expected") { page.findViewById<View>(R.id.history_detail_text)?.isShown == true && detailLine(page) == expected }
    }

    @Test fun completionAndDeliveryAppearSeparatelyInDetails() {
        val entries = HistoryDelivery.entries.mapIndexed { index, status ->
            HistoryEntry(UUID.randomUUID().toString(), 100L + index, "Synthetic status $index", status)
        }
        assertEquals(listOf(HistoryDelivery.UNKNOWN, HistoryDelivery.NOT_ATTEMPTED, HistoryDelivery.REJECTED, HistoryDelivery.ACCEPTED), entries.map { it.delivery })
        save(*entries.toTypedArray())
        val page = open().get()
        // List and detail say the same sentence; a complete, inserted take says nothing.
        val lines = listOf(R.string.marker_uncertain_line, R.string.marker_not_inserted_line, R.string.marker_not_inserted_line, null)
        entries.forEachIndexed { index, _ ->
            val row = awaitMarkedRow(page, "Synthetic status $index", lines[index])
            assertEquals(if (lines[index] == null) View.GONE else View.VISIBLE, (row.parent as View).findViewById<View>(R.id.history_row_marker).visibility)
            click(row)
            awaitDetailLine(page, lines[index])
            assertTrue(page.findViewById<TextView>(R.id.history_detail_meta).text.toString().contains(HistoryRows.time(100L + index)))
            page.onBackPressedDispatcher.onBackPressed()
            idle()
        }
    }

    @Test fun liveAndAbandonedCheckpointsShowTheirCompletionTruth() {
        val id = UUID.randomUUID().toString()
        HistoryProcess.owner.register(id)
        onHistoryThread {
            store.checkpoint(HistoryEntry(id, 123, "Synthetic live checkpoint", HistoryDelivery.UNKNOWN, HistoryCompletion.RECORDING, 1))
        }
        val controller = open()
        awaitMarkedRow(controller.get(), "Synthetic live checkpoint", R.string.marker_recording_line)
        controller.recreate()
        awaitMarkedRow(controller.get(), "Synthetic live checkpoint", R.string.marker_recording_line)
        HistoryProcess.owner.finishing(id)
        controller.recreate()
        awaitMarkedRow(controller.get(), "Synthetic live checkpoint", R.string.marker_preparing_line)
        HistoryProcess.owner.remove(id)
        controller.recreate()
        val page = controller.get()
        // An abandoned checkpoint is cut short; that one marker wins over its delivery truth.
        val row = awaitMarkedRow(page, "Synthetic live checkpoint", R.string.marker_cut_short_line)
        assertTrue(shows(page, app.getString(R.string.history_explanation)))
        click(row)
        awaitDetailLine(page, R.string.marker_cut_short_line)
    }

    @Test fun recreatedDetailRetainsTakeAndAccessibleBackReturnsToList() {
        val full = "Synthetic row\nFull detail after recreation"
        save(HistoryEntry(UUID.randomUUID().toString(), 123, full))
        val controller = open()
        click(awaitRow(controller.get(), "Synthetic row"))
        fun detailText() = controller.get().findViewById<TextView>(R.id.history_detail_text)?.takeIf { it.isShown }?.text?.toString()
        await("the full text in the detail") { detailText() == full }
        assertEquals(HistoryRows.dayText(123), toolbar(controller.get()).title.toString())
        controller.recreate()
        val page = controller.get()
        await("the full text after recreation") { detailText() == full }
        val back = find(page.window.decorView) { it.contentDescription == app.getString(R.string.history_back) }!!
        back.performClick()
        awaitText(page, "Synthetic row")
        assertTrue(shows(page, app.getString(R.string.history_explanation)))
        assertEquals(View.VISIBLE, page.findViewById<View>(R.id.history_list_pane).visibility)
        assertNull(page.findViewById<View>(R.id.history_detail_text))
        assertEquals(app.getString(R.string.history_title), toolbar(page).title.toString())
    }

    @Test fun rejectedTranscriptCanBeReadCopiedAndDeleted() {
        val id = UUID.randomUUID().toString()
        val text = "Synthetic History test\nFull second line"
        save(HistoryEntry(id, 123, text))
        undoWindow = 60_000
        val page = open().get()
        assertTrue(page.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        click(awaitRow(page, "Synthetic History test"))
        awaitText(page, "Full second line")
        // Copy and Delete are icon actions in the toolbar, side by side at the same height.
        val copy = awaitText(page, app.getString(R.string.history_copy_transcript), exact = true)
        val delete = awaitText(page, app.getString(R.string.history_delete_transcript), exact = true)
        assertTrue(copy.height >= (48 * density).toInt() && delete.height >= (48 * density).toInt())
        assertEquals(IntArray(2).also(copy::getLocationOnScreen)[1], IntArray(2).also(delete::getLocationOnScreen)[1])
        assertTrue(copy.text.isNullOrEmpty() && delete.text.isNullOrEmpty())
        app.wiring.storage.clipboard.copy("copied earlier")
        copy.performClick()
        await("the transcript on the build's clipboard") { lastCopy(app) == text }
        // minSdk 36: the system confirms the copy; the page shows no "Copied" Snackbar.
        advance(600)
        assertFalse(shows(page, "Copied", exact = true))
        delete.performClick()
        idle()
        assertTrue("stored while Undo is open", stored(id))
        advance(60_000)
        await("the transcript removed when the window ends") { !stored(id) }
        assertNull(onHistoryThread { store.get(id) })
    }
}
