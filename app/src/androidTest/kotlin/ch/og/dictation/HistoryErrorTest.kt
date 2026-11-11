package ch.og.dictation

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
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
    private fun find(root: View, text: String): TextView? {
        if (root is TextView && root.text.toString().contains(text)) return root
        if (root is ViewGroup) for (i in 0 until root.childCount) find(root.getChildAt(i), text)?.let { return it }
        return null
    }
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
            val retry = await(activity, context.getString(R.string.history_read_error))
            assertTrue(bad.exists())
            assertTrue(bad.delete())
            instrumentation.runOnMainSync { retry.parent.let { (it as View).performClick() } }
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
            instrumentation.runOnMainSync { assertNull(find(activity.window.decorView, context.getString(R.string.history_read_error))) }
        } finally { instrumentation.runOnMainSync { activity.finish() }; root.deleteRecursively() }
    }
    @Test fun failedDeleteKeepsRecordAndStaleDetailCannotCopy() {
        val context = instrumentation.targetContext
        val root = File(context.noBackupFilesDir, "history-ui-test")
        val id = UUID.randomUUID().toString()
        val store = HistoryStore(root)
        store.save(HistoryEntry(id, 123, "Synthetic deletion test"))
        val activity = launch()
        val temp = File(root, "$id.tmp")
        try {
            val row = await(activity, "Synthetic deletion test")
            instrumentation.runOnMainSync { row.performClick() }
            await(activity, context.getString(R.string.history_delivery_rejected))
            assertTrue(temp.mkdir())
            File(temp, "block").writeText("synthetic")
            val delete = await(activity, context.getString(R.string.history_delete))
            instrumentation.runOnMainSync { delete.performClick() }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val dialog = WindowInspector.getGlobalWindowViews().last()
                dialog.findViewById<View>(android.R.id.button1).performClick()
            }
            await(activity, "Synthetic deletion test")
            assertNotNull(store.get(id))
            temp.deleteRecursively()
            store.delete(id)
            val copy = await(activity, context.getString(R.string.history_copy))
            instrumentation.runOnMainSync { copy.performClick() }
            await(activity, context.getString(R.string.history_missing))
            instrumentation.runOnMainSync { assertNull(find(activity.window.decorView, context.getString(R.string.history_copy))) }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            temp.deleteRecursively(); store.delete(id)
        }
    }
}
