package ch.og.dictation

import android.content.Intent
import android.view.View
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoryErrorTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun find(root: View, text: String) = HistoryUi.findText(root, text)
    private fun await(activity: HistoryActivity, text: String): TextView {
        repeat(100) {
            instrumentation.waitForIdleSync()
            var found: TextView? = null
            instrumentation.runOnMainSync { found = find(activity.window.decorView, text) }
            found?.let { return it }
            Thread.sleep(50)
        }
        error("Missing view: $text")
    }
    private fun launch(): HistoryActivity {
        val context = instrumentation.targetContext
        return instrumentation.startActivitySync(Intent(context, HistoryTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as HistoryActivity
    }
    @Test fun malformedHistoryShowsRetryAndPreservesGoodRecord() {
        val context = instrumentation.targetContext
        val root = File(context.noBackupFilesDir, "history-ui-test").apply { mkdirs() }
        val id = UUID.randomUUID().toString()
        val goodId = UUID.randomUUID().toString()
        val good = HistoryEntry(goodId, 123, "Synthetic preserved record")
        val store = HistoryStore(root)
        store.save(good)
        val bad = File(root, "$id.history").apply { writeText("bad synthetic record") }
        val activity = launch()
        try {
            val retry = await(activity, context.getString(R.string.history_retry))
            assertTrue(bad.exists())
            assertTrue(bad.delete())
            instrumentation.runOnMainSync { retry.performClick() }
            await(activity, "Synthetic preserved record")
            assertEquals(good, store.get(goodId))
        } finally { instrumentation.runOnMainSync { activity.finish() }; bad.delete(); store.delete(goodId) }
    }
    @Test fun emptyHistoryIsNotAReadError() {
        val context = instrumentation.targetContext
        val root = File(context.noBackupFilesDir, "history-ui-test")
        root.deleteRecursively()
        val activity = launch()
        try {
            await(activity, context.getString(R.string.history_empty))
            instrumentation.runOnMainSync {
                assertNull(find(activity.window.decorView, context.getString(R.string.history_read_error)))
                // Empty state is its own block, distinct from the explanation above the list.
                val explanation = activity.findViewById<View>(R.id.history_explanation)
                val emptyState = activity.findViewById<View>(R.id.history_empty)
                assertEquals(View.VISIBLE, emptyState.visibility)
                assertNotSame(explanation, emptyState)
                assertEquals(View.VISIBLE, explanation.visibility)
            }
        } finally { instrumentation.runOnMainSync { activity.finish() }; root.deleteRecursively() }
    }
    @Test fun failedRemovalKeepsRecordAndWarnsOnTheHistoryPage() {
        val context = instrumentation.targetContext
        val notices = historyNotices(context)
        val original = notices.pending()
        HistoryNotice.entries.forEach(notices::dismiss)
        val root = File(context.noBackupFilesDir, "history-ui-test")
        val id = UUID.randomUUID().toString()
        val store = HistoryStore(root)
        store.save(HistoryEntry(id, 123, "Synthetic deletion test"))
        HistoryTestActivity.undoWindow = 300
        val activity = launch()
        val temp = File(root, "$id.tmp")
        try {
            val row = await(activity, "Synthetic deletion test")
            instrumentation.runOnMainSync { HistoryUi.click(row) }
            await(activity, context.getString(R.string.history_copy_transcript))
            instrumentation.runOnMainSync {
                assertTrue(activity.findViewById<TextView>(R.id.history_detail_meta).text.contains(context.getString(R.string.history_delivery_rejected)))
            }
            assertTrue(temp.mkdir())
            File(temp, "block").writeText("synthetic")
            val delete = await(activity, context.getString(R.string.history_delete_transcript))
            instrumentation.runOnMainSync { delete.performClick() }
            await(activity, context.getString(R.string.history_cancel_delete_error))
            await(activity, "Synthetic deletion test")
            assertNotNull(store.get(id))
        } finally {
            HistoryTestActivity.undoWindow = 8000
            instrumentation.runOnMainSync { activity.finish() }
            temp.deleteRecursively(); store.delete(id)
            HistoryNotice.entries.forEach { if (it in original) notices.record(it) else notices.dismiss(it) }
        }
    }
    @Test fun staleDetailCannotCopyAfterTheTranscriptIsGone() {
        val context = instrumentation.targetContext
        val root = File(context.noBackupFilesDir, "history-ui-test")
        val id = UUID.randomUUID().toString()
        val store = HistoryStore(root)
        store.save(HistoryEntry(id, 123, "Synthetic deletion test"))
        val activity = launch()
        try {
            val row = await(activity, "Synthetic deletion test")
            instrumentation.runOnMainSync { HistoryUi.click(row) }
            val copy = await(activity, context.getString(R.string.history_copy_transcript))
            store.delete(id)
            instrumentation.runOnMainSync { copy.performClick() }
            await(activity, context.getString(R.string.history_missing))
            instrumentation.runOnMainSync { assertNull(find(activity.window.decorView, context.getString(R.string.history_copy_transcript))) }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            store.delete(id)
        }
    }
}
