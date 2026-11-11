package ch.og.dictation

import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Delete one transcript with no dialog: it goes at once, Undo is offered, storage follows when the window ends or the page is left. */
@RunWith(AndroidJUnit4::class)
class HistoryUndoDeleteTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val suffix = UUID.randomUUID().toString()
    private val root get() = File(context.noBackupFilesDir, "history-ui-test-$suffix")
    private val store get() = HistoryStore(root)
    private val id = UUID.randomUUID().toString()
    private val other = UUID.randomUUID().toString()

    @Before fun seed() {
        root.deleteRecursively()
        store.save(HistoryEntry(id, 100, "Synthetic row to delete"))
        store.save(HistoryEntry(other, 90, "Synthetic row to keep"))
        HistoryTestActivity.historyRootSuffix = suffix
    }

    @After fun clean() {
        HistoryTestActivity.undoWindow = 8000
        HistoryTestActivity.historyRootSuffix = null
        root.deleteRecursively()
    }

    private fun launch() = ActivityScenario.launch(HistoryTestActivity::class.java)
    private fun ActivityScenario<HistoryTestActivity>.visible(text: String): Boolean {
        var found = false
        onActivity { found = HistoryUi.find(it.window.decorView) { v -> v is TextView && v.isShown && v.text.toString().contains(text) } != null }
        return found
    }
    private fun ActivityScenario<HistoryTestActivity>.await(text: String, shown: Boolean = true) {
        repeat(100) { if (visible(text) == shown) return; Thread.sleep(50) }
        error("${if (shown) "Missing" else "Still showing"}: $text")
    }
    private fun ActivityScenario<HistoryTestActivity>.click(text: String, exact: Boolean = false) {
        await(text)
        onActivity { activity ->
            HistoryUi.click(HistoryUi.findText(activity.window.decorView, text, exact)!!)
        }
    }
    private fun ActivityScenario<HistoryTestActivity>.clickRowDelete(preview: String) {
        await(preview)
        onActivity { activity ->
            val body = HistoryUi.target(HistoryUi.findText(activity.window.decorView, preview)!!)
            (body.parent as View).findViewById<View>(R.id.history_row_delete).performClick()
        }
    }
    /**
     * The row's file is gone. Not store.get: this test thread reads outside HistoryIo's serial
     * executor; a read racing the delete fails its length check (Pixel run 20261111T133751).
     */
    private fun gone(id: String) = !File(root, "$id.history").exists()

    private fun waitFor(condition: () -> Boolean) {
        repeat(100) { if (condition()) return; Thread.sleep(50) }
        error("Condition not met")
    }

    @Test fun deleteFromListHidesAtOnceAndKeepsTheFileUntilTheWindowEnds() {
        HistoryTestActivity.undoWindow = 1500
        val scenario = launch()
        try {
            scenario.clickRowDelete("Synthetic row to delete")
            scenario.await("Synthetic row to delete", shown = false)
            scenario.await(context.getString(R.string.history_deleted))
            scenario.await(context.getString(R.string.dictionary_undo))
            assertNotNull("not removed before the window ends", store.get(id))
            scenario.await("Synthetic row to keep")
            waitFor { gone(id) }
            assertNotNull(store.get(other))
            scenario.await("Synthetic row to delete", shown = false)
        } finally { scenario.close() }
    }

    @Test fun undoBringsTheRowBackAndNeverDeletesIt() {
        HistoryTestActivity.undoWindow = 900
        val scenario = launch()
        try {
            scenario.clickRowDelete("Synthetic row to delete")
            scenario.await("Synthetic row to delete", shown = false)
            scenario.click(context.getString(R.string.dictionary_undo), exact = true)
            scenario.await("Synthetic row to delete")
            Thread.sleep(1500)
            assertNotNull(store.get(id))
            scenario.await("Synthetic row to delete")
        } finally { scenario.close() }
    }

    @Test fun deleteFromDetailReturnsToTheListWithUndo() {
        HistoryTestActivity.undoWindow = 1500
        val scenario = launch()
        try {
            scenario.click("Synthetic row to delete")
            scenario.await(context.getString(R.string.history_copy))
            scenario.click(context.getString(R.string.history_delete), exact = true)
            scenario.await(context.getString(R.string.history_deleted))
            scenario.await("Synthetic row to delete", shown = false)
            scenario.await("Synthetic row to keep")
            scenario.onActivity { assertNull(it.findViewById<View>(R.id.history_detail_text)) }
            assertNotNull(store.get(id))
            waitFor { gone(id) }
        } finally { scenario.close() }
    }

    @Test fun leavingThePageRemovesTheTranscriptWithoutWaitingForTheWindow() {
        HistoryTestActivity.undoWindow = 60_000
        val scenario = launch()
        try {
            scenario.clickRowDelete("Synthetic row to delete")
            scenario.await("Synthetic row to delete", shown = false)
            assertNotNull(store.get(id))
        } finally { scenario.close() }
        waitFor { gone(id) }
        assertNotNull(store.get(other))
    }

    @Test fun relaunchInsideTheWindowAfterProcessDeathWouldFindTheRowInPlace() {
        // The window only ever lives in memory: nothing is written before it ends, so a process
        // death inside it leaves the transcript and a fresh page lists it again.
        HistoryTestActivity.undoWindow = 60_000
        val scenario = launch()
        try {
            scenario.clickRowDelete("Synthetic row to delete")
            scenario.await("Synthetic row to delete", shown = false)
            assertNotNull(store.get(id))
            assertFalse(File(root, "$id.deleted").exists())
        } finally { scenario.close() }
    }

    @Test fun failedRemovalRestoresTheRowAndShowsAWarningOnThePage() {
        val notices = historyNotices(context)
        val original = notices.pending()
        HistoryNotice.entries.forEach(notices::dismiss)
        HistoryTestActivity.undoWindow = 500
        val blocker = File(root, "$id.tmp")
        val scenario = launch()
        try {
            scenario.await("Synthetic row to delete")
            assertTrue(blocker.mkdir())
            File(blocker, "block").writeText("synthetic")
            scenario.clickRowDelete("Synthetic row to delete")
            scenario.await(context.getString(R.string.history_cancel_delete_error))
            scenario.await("Synthetic row to delete")
            assertNotNull(store.get(id))
            assertTrue(HistoryNotice.DELETE in notices.pending())
        } finally {
            blocker.deleteRecursively()
            scenario.close()
            HistoryNotice.entries.forEach { if (it in original) notices.record(it) else notices.dismiss(it) }
        }
    }

    @Test fun listKeepsItsPlaceAndShowsNoLoadingStateWhenReturningFromDetail() {
        val extra = (1..40).map { UUID.randomUUID().toString() to "Synthetic scroll row $it" }
        extra.forEachIndexed { index, (rowId, text) -> store.save(HistoryEntry(rowId, 1000L + index, text)) }
        val scenario = launch()
        try {
            scenario.await("Synthetic scroll row 40")
            scenario.onActivity { it.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.history_list).scrollToPosition(30) }
            Thread.sleep(300)
            var first = -1
            scenario.onActivity { first = (it.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.history_list).layoutManager as androidx.recyclerview.widget.LinearLayoutManager).findFirstVisibleItemPosition() }
            assertTrue(first > 5)
            scenario.onActivity { activity ->
                val row = HistoryUi.find(activity.window.decorView) { v -> v is TextView && v.isShown && v.id == R.id.history_row_preview }!!
                HistoryUi.click(row)
            }
            scenario.await(context.getString(R.string.history_copy))
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            var loadingShown = false
            var after = -1
            scenario.onActivity {
                loadingShown = it.findViewById<View>(R.id.history_status).visibility == View.VISIBLE &&
                    it.findViewById<TextView>(R.id.history_status_text).text.toString() == context.getString(R.string.history_loading)
                after = (it.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.history_list).layoutManager as androidx.recyclerview.widget.LinearLayoutManager).findFirstVisibleItemPosition()
            }
            assertFalse(loadingShown)
            assertEquals(first, after)
        } finally { scenario.close() }
    }
}
