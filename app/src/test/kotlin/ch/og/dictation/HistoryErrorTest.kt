package ch.og.dictation

import ch.og.dictation.history.HistoryEntry
import ch.og.dictation.history.HistoryPageCase
import ch.og.dictation.storage.lastCopy
import android.view.View
import android.widget.TextView
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** History read and removal errors on the page, on the laptop (#79; the phone suite of this name is gone). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-port-xxhdpi")
class HistoryErrorTest : HistoryPageCase() {
    @Test fun malformedHistoryShowsRetryAndPreservesGoodRecord() {
        val good = HistoryEntry(UUID.randomUUID().toString(), 123, "Synthetic preserved record")
        save(good)
        val bad = File(root, "${UUID.randomUUID()}.history").apply { writeText("bad synthetic record") }
        val page = open().get()
        val retry = awaitText(page, app.getString(R.string.history_retry))
        assertTrue(shows(page, app.getString(R.string.history_read_error)))
        assertFalse(shows(page, "Synthetic preserved record"))
        assertTrue(bad.exists())
        assertTrue(bad.delete())
        retry.performClick()
        awaitText(page, "Synthetic preserved record")
        assertFalse(shows(page, app.getString(R.string.history_read_error)))
        assertEquals(good, onHistoryThread { store.get(good.id) })
    }

    @Test fun emptyHistoryIsNotAReadError() {
        val page = open().get()
        awaitText(page, app.getString(R.string.history_empty))
        assertFalse(shows(page, app.getString(R.string.history_read_error)))
        // Empty state is its own block, distinct from the explanation above the list.
        val explanation = page.findViewById<View>(R.id.history_explanation)
        val emptyState = page.findViewById<View>(R.id.history_empty)
        assertEquals(View.VISIBLE, emptyState.visibility)
        assertNotSame(explanation, emptyState)
        assertEquals(View.VISIBLE, explanation.visibility)
    }

    @Test fun failedRemovalKeepsRecordAndWarnsOnTheHistoryPage() {
        val id = UUID.randomUUID().toString()
        save(HistoryEntry(id, 123, "Synthetic deletion test"))
        undoWindow = 60_000
        val page = open().get()
        click(awaitRow(page, "Synthetic deletion test"))
        awaitText(page, app.getString(R.string.history_copy_transcript))
        assertEquals(app.getString(R.string.marker_not_inserted_line), page.findViewById<TextView>(R.id.history_detail_marker_text).text.toString())
        val temp = File(root, "$id.tmp")
        assertTrue(temp.mkdir())
        File(temp, "block").writeText("synthetic")
        awaitText(page, app.getString(R.string.history_delete_transcript), exact = true).performClick()
        idle()
        assertFalse(shows(page, "Synthetic deletion test"))
        advance(60_000)
        awaitText(page, app.getString(R.string.history_cancel_delete_error))
        awaitText(page, "Synthetic deletion test")
        assertTrue(stored(id))
        assertNotNull(onHistoryThread { store.get(id) })
    }

    @Test fun staleDetailCannotCopyAfterTheTranscriptIsGone() {
        val id = UUID.randomUUID().toString()
        save(HistoryEntry(id, 123, "Synthetic deletion test"))
        val page = open().get()
        click(awaitRow(page, "Synthetic deletion test"))
        val copy = awaitText(page, app.getString(R.string.history_copy_transcript), exact = true)
        onHistoryThread { store.delete(id) }
        app.wiring.storage.clipboard.copy("copied earlier")
        copy.performClick()
        awaitText(page, app.getString(R.string.history_missing))
        assertFalse(shows(page, app.getString(R.string.history_copy_transcript)))
        assertEquals("nothing was copied", "copied earlier", lastCopy(app))
    }
}
