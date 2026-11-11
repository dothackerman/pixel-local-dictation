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
        awaitView { it is TextView && it.text.toString().contains(text) } as TextView
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
            // List and detail say the same thing: the date, then a status only when the take is not complete and inserted.
            val suffix = listOf(" · " + context.getString(R.string.history_status_uncertain),
                " · " + context.getString(R.string.history_delivery_rejected),
                " · " + context.getString(R.string.history_delivery_rejected), "")
            entries.forEachIndexed { index, _ ->
                val row = scenario.awaitText("Synthetic status $index")
                val rowMeta = (row.parent as View).findViewById<TextView>(R.id.history_row_meta).text.toString()
                scenario.onActivity { HistoryUi.click(row) }
                val expected = HistoryRows.date(100L + index) + suffix[index]
                assertEquals(expected, rowMeta)
                scenario.awaitDetailMeta(expected)
                assertEquals(expected, scenario.detailMeta())
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
            val insertedMeta = scenario.awaitText("Synthetic inserted row").let { (it.parent as View).findViewById<TextView>(R.id.history_row_meta).text.toString() }
            val missedMeta = scenario.awaitText("Synthetic missed row").let { (it.parent as View).findViewById<TextView>(R.id.history_row_meta).text.toString() }
            assertEquals(HistoryRows.date(300), insertedMeta)
            assertEquals(HistoryRows.date(200) + " · " + context.getString(R.string.history_delivery_rejected), missedMeta)
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
            scenario.awaitText(context.getString(R.string.history_recording))
            scenario.recreate()
            scenario.awaitText(context.getString(R.string.history_recording))
            HistoryProcess.owner.finishing(id)
            scenario.recreate()
            scenario.awaitText(context.getString(R.string.history_finishing))
            HistoryProcess.owner.remove(id)
            scenario.recreate()
            scenario.awaitText(context.getString(R.string.history_status_incomplete))
            scenario.awaitText(context.getString(R.string.history_status_uncertain))
            scenario.awaitText(context.getString(R.string.history_explanation))
            val row = scenario.awaitText("Synthetic live checkpoint")
            scenario.onActivity { HistoryUi.click(row) }
            scenario.awaitDetailMeta(context.getString(R.string.history_status_incomplete))
            scenario.awaitDetailMeta(context.getString(R.string.history_status_uncertain))
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
            assertEquals(HistoryRows.date(123), scenario.query { it.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.history_toolbar).title.toString() })
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

    private fun assertCopied(text: String) {
        repeat(100) {
            val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
            if (clip?.getItemAt(0)?.text.toString() == text) {
                assertTrue(clip!!.description.extras!!.getBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE))
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
            scenario.awaitText(context.getString(R.string.history_copied))
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
            scenario.awaitText(context.getString(R.string.history_copied))
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
