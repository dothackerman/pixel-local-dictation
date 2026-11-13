package ch.og.dictation

import android.content.ClipboardManager
import android.content.Context
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoryActivityTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    private fun <T> ActivityScenario<HistoryTestActivity>.query(block: (HistoryTestActivity) -> T?): T? {
        var result: T? = null
        onActivity { result = block(it) }
        return result
    }
    private fun ActivityScenario<HistoryTestActivity>.awaitView(predicate: (View) -> Boolean): View {
        repeat(100) {
            query { HistoryUi.find(it.window.decorView, predicate) }?.let { return it }
            Thread.sleep(50)
        }
        error("Missing view")
    }
    private fun ActivityScenario<HistoryTestActivity>.awaitText(text: String) =
        awaitView { it is TextView && HistoryUi.label(it).contains(text) } as TextView
    /** A row read as one node: its spoken description names the preview and, if any, its mark's sentence. */
    private fun ActivityScenario<HistoryTestActivity>.awaitRow(text: String, sentence: Int?): View = awaitView {
        it.id == R.id.history_row_body && it.contentDescription?.toString()?.contains(text) == true &&
            (sentence == null || it.contentDescription.toString().endsWith(context.getString(sentence)))
    }
    private fun ActivityScenario<HistoryTestActivity>.detailLine(): String? = query {
        it.findViewById<View>(R.id.history_detail_marker)?.takeIf { line -> line.visibility == View.VISIBLE }
            ?.findViewById<TextView>(R.id.history_detail_marker_text)?.text?.toString()
    }
    private fun ActivityScenario<HistoryTestActivity>.awaitDetailLine(sentence: Int?) {
        val expected = sentence?.let(context::getString)
        repeat(100) { if (query { it.findViewById<View>(R.id.history_detail_text)?.isShown } == true && detailLine() == expected) return; Thread.sleep(50) }
        error("Detail line never became $expected (was: ${detailLine()})")
    }
    private fun ActivityScenario<HistoryTestActivity>.detailMeta(): String =
        query { it.findViewById<TextView>(R.id.history_detail_meta)?.text?.toString() }.orEmpty()
    private fun ActivityScenario<HistoryTestActivity>.awaitDetailMeta(text: String) {
        repeat(100) { if (detailMeta().contains(text)) return; Thread.sleep(50) }
        error("Detail metadata never contained: $text (was: ${detailMeta()})")
    }
    private fun save(vararg entries: HistoryEntry) {
        val store = HistoryStore(File(context.noBackupFilesDir, "history-ui-test"))
        val ready = CountDownLatch(1)
        HistoryIo.executor.execute { entries.forEach(store::save); ready.countDown() }
        assertTrue(ready.await(10, TimeUnit.SECONDS))
    }
    private fun deleteAll(vararg ids: String) {
        val store = HistoryStore(File(context.noBackupFilesDir, "history-ui-test"))
        val done = CountDownLatch(1)
        HistoryIo.executor.execute { ids.forEach(store::delete); done.countDown() }
        done.await(10, TimeUnit.SECONDS)
    }

    @Test fun completionAndDeliveryAppearSeparatelyInDetails() {
        val entries = HistoryDelivery.entries.mapIndexed { index, status ->
            HistoryEntry(UUID.randomUUID().toString(), 100L + index, "Synthetic status $index", status)
        }
        save(*entries.toTypedArray())
        val scenario = ActivityScenario.launch(HistoryTestActivity::class.java)
        try {
            // List and detail say the same sentence; a complete, inserted take says nothing.
            val lines = listOf(R.string.marker_uncertain_line, R.string.marker_not_inserted_line, R.string.marker_not_inserted_line, null)
            entries.forEachIndexed { index, _ ->
                val row = scenario.awaitRow("Synthetic status $index", lines[index])
                val marker = scenario.query { (row.parent as View).findViewById<View>(R.id.history_row_marker).visibility }
                assertEquals(if (lines[index] == null) View.GONE else View.VISIBLE, marker)
                scenario.onActivity { HistoryUi.click(row) }
                scenario.awaitDetailLine(lines[index])
                scenario.awaitDetailMeta(HistoryRows.time(100L + index))
                scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            }
        } finally {
            scenario.close()
            deleteAll(*entries.map { it.id }.toTypedArray())
        }
    }

    @Test fun rowsNameAStatusOnlyWhenTheTakeIsNotCompleteAndInserted() {
        val inserted = HistoryEntry(UUID.randomUUID().toString(), 300, "Synthetic inserted row", HistoryDelivery.ACCEPTED)
        val notInserted = HistoryEntry(UUID.randomUUID().toString(), 200, "Synthetic missed row", HistoryDelivery.REJECTED)
        save(inserted, notInserted)
        val scenario = ActivityScenario.launch(HistoryTestActivity::class.java)
        try {
            val inRow = scenario.awaitRow("Synthetic inserted row", null)
            val missedRow = scenario.awaitRow("Synthetic missed row", R.string.marker_not_inserted_line)
            assertFalse(inRow.contentDescription.toString().contains(context.getString(R.string.marker_not_inserted_line)))
            assertEquals(View.GONE, scenario.query { (inRow.parent as View).findViewById<View>(R.id.history_row_marker).visibility })
            assertEquals(View.VISIBLE, scenario.query { (missedRow.parent as View).findViewById<View>(R.id.history_row_marker).visibility })
        } finally {
            scenario.close()
            deleteAll(inserted.id, notInserted.id)
        }
    }

    @Test fun liveAndAbandonedCheckpointsShowTheirCompletionTruth() {
        val id = UUID.randomUUID().toString()
        val store = HistoryStore(File(context.noBackupFilesDir, "history-ui-test"))
        HistoryProcess.owner.register(id)
        val ready = CountDownLatch(1)
        HistoryIo.executor.execute {
            store.checkpoint(HistoryEntry(id, 123, "Synthetic live checkpoint", HistoryDelivery.UNKNOWN, HistoryCompletion.RECORDING, 1))
            ready.countDown()
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS))
        val scenario = ActivityScenario.launch(HistoryTestActivity::class.java)
        try {
            scenario.awaitRow("Synthetic live checkpoint", R.string.marker_recording_line)
            scenario.recreate()
            scenario.awaitRow("Synthetic live checkpoint", R.string.marker_recording_line)
            HistoryProcess.owner.finishing(id)
            scenario.recreate()
            scenario.awaitRow("Synthetic live checkpoint", R.string.marker_preparing_line)
            HistoryProcess.owner.remove(id)
            scenario.recreate()
            // An abandoned checkpoint is cut short; that one marker wins over its delivery truth.
            val row = scenario.awaitRow("Synthetic live checkpoint", R.string.marker_cut_short_line)
            scenario.awaitText(context.getString(R.string.history_explanation))
            scenario.onActivity { HistoryUi.click(row) }
            scenario.awaitDetailLine(R.string.marker_cut_short_line)
        } finally {
            scenario.close()
            HistoryProcess.owner.remove(id)
            deleteAll(id)
        }
    }

    @Test fun recreatedDetailRetainsTakeAndAccessibleBackReturnsToList() {
        val id = UUID.randomUUID().toString()
        save(HistoryEntry(id, 123, "Synthetic row\nFull detail after recreation"))
        val scenario = ActivityScenario.launch(HistoryTestActivity::class.java)
        val full = "Synthetic row\nFull detail after recreation"
        try {
            val row = scenario.awaitText("Synthetic row")
            scenario.onActivity { HistoryUi.click(row) }
            scenario.awaitView { it is TextView && it.text.toString() == full }
            assertEquals(HistoryRows.dayText(123), scenario.query { it.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.history_toolbar).title.toString() })
            scenario.recreate()
            scenario.awaitView { it is TextView && it.text.toString() == full }
            val back = scenario.awaitView { it.contentDescription == context.getString(R.string.history_back) }
            scenario.onActivity { back.performClick() }
            scenario.awaitText(context.getString(R.string.history_explanation))
            scenario.awaitText("Synthetic row")
            assertEquals(View.VISIBLE, scenario.query { it.findViewById<View>(R.id.history_list_pane).visibility })
            assertNull(scenario.query { it.findViewById<View>(R.id.history_detail_text) })
            assertEquals(context.getString(R.string.history_title),
                scenario.query { it.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.history_toolbar).title.toString() })
        } finally {
            scenario.close()
            deleteAll(id)
        }
    }

    /** minSdk 36: the system confirms the copy; the page shows no "Copied" Snackbar. */
    private fun assertCopyConfirmation(scenario: ActivityScenario<HistoryTestActivity>) {
        Thread.sleep(600)
        scenario.onActivity { assertNull(HistoryUi.findText(it.window.decorView, "Copied", exact = true)) }
    }

    private fun assertCopied(text: String) {
        repeat(100) {
            val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
            if (clip?.getItemAt(0)?.text.toString() == text) {
                assertFalse(clip!!.description.extras?.getBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE) ?: false)
                return
            }
            Thread.sleep(50)
        }
        error("Not copied: $text")
    }

    @Test fun longPressOnARowCopiesTheFullTextWithAConfirmationAndStaysOnTheList() {
        val id = UUID.randomUUID().toString()
        val text = (1..80).joinToString(" ") { "synthetic$it" }
        save(HistoryEntry(id, 123, text))
        val scenario = ActivityScenario.launch(HistoryTestActivity::class.java)
        try {
            val row = scenario.awaitText("synthetic1 ")
            scenario.onActivity {
                assertTrue(HistoryUi.target(row).performLongClick())
                // The accessibility actions name the long-press and Delete.
                val labels = HistoryUi.target(row).createAccessibilityNodeInfo().actionList.map { a -> a.label?.toString() }
                assertTrue(labels.contains(context.getString(R.string.history_copy_transcript)))
                assertTrue(labels.contains(context.getString(R.string.history_delete_transcript)))
            }
            assertCopied(text)
            assertCopyConfirmation(scenario)
            scenario.onActivity { assertNull(it.findViewById<View>(R.id.history_detail_text)) }
        } finally {
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).clearPrimaryClip()
            scenario.close()
            deleteAll(id)
        }
    }

    @Test fun rejectedTranscriptCanBeReadCopiedAndDeleted() {
        val id = UUID.randomUUID().toString()
        val store = HistoryStore(File(context.noBackupFilesDir, "history-ui-test"))
        val text = "Synthetic History test\nFull second line"
        save(HistoryEntry(id, 123, text))
        HistoryTestActivity.undoWindow = 400
        val scenario = ActivityScenario.launch(HistoryTestActivity::class.java)
        try {
            scenario.onActivity { assertTrue(it.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0) }
            val row = scenario.awaitText("Synthetic History test")
            scenario.onActivity { HistoryUi.click(row) }
            scenario.awaitText("Full second line")
            // Copy and Delete are icon actions in the toolbar, side by side at the same height.
            val copy = scenario.awaitText(context.getString(R.string.history_copy_transcript))
            val delete = scenario.awaitText(context.getString(R.string.history_delete_transcript))
            val density = context.resources.displayMetrics.density
            scenario.onActivity {
                assertTrue(copy.height >= (48 * density).toInt() && delete.height >= (48 * density).toInt())
                assertEquals(IntArray(2).also(copy::getLocationOnScreen)[1], IntArray(2).also(delete::getLocationOnScreen)[1])
                assertTrue(copy.text.isNullOrEmpty() && delete.text.isNullOrEmpty())
                copy.performClick()
            }
            assertCopied(text)
            assertCopyConfirmation(scenario)
            scenario.onActivity { delete.performClick() }
            repeat(100) { if (store.get(id) == null) return@repeat; Thread.sleep(50) }
            assertNull(store.get(id))
        } finally {
            HistoryTestActivity.undoWindow = 8000
            scenario.close()
            deleteAll(id)
        }
    }
}
