package ch.og.dictation

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.TextView
import android.widget.ScrollView
import android.widget.AdapterView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.appbar.MaterialToolbar
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoryClearTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val rootSuffix = UUID.randomUUID().toString()
    private val root get() = File(context.noBackupFilesDir, "history-ui-test-$rootSuffix")
    private val registeredIds = linkedSetOf<String>()

    private fun registerActive(id: String) {
        HistoryProcess.owner.register(id)
        registeredIds.add(id)
    }

    private fun findText(view: View, value: String, exact: Boolean = false): TextView? {
        if (view is TextView && (if (exact) view.text.toString() == value else view.text.toString().contains(value))) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findText(view.getChildAt(i), value, exact)?.let { return it }
        return null
    }

    private fun awaitText(value: String, exact: Boolean = false): TextView {
        repeat(100) {
            instrumentation.waitForIdleSync()
            var found: TextView? = null
            instrumentation.runOnMainSync {
                WindowInspector.getGlobalWindowViews().asSequence()
                    .mapNotNull { findText(it, value, exact) }.firstOrNull()?.let { found = it }
            }
            found?.let { return it }
            Thread.sleep(50)
        }
        error("Missing view: $value")
    }

    private fun awaitAbsent(value: String) {
        repeat(100) {
            instrumentation.waitForIdleSync()
            var found = false
            instrumentation.runOnMainSync {
                found = WindowInspector.getGlobalWindowViews().any { findText(it, value) != null }
            }
            if (!found) return
            Thread.sleep(50)
        }
        error("Still showing view: $value")
    }

    private fun launch(): ActivityScenario<HistoryTestActivity> {
        HistoryTestActivity.historyRootSuffix = rootSuffix
        return ActivityScenario.launch(HistoryTestActivity::class.java)
    }

    private fun clickClear(scenario: ActivityScenario<HistoryTestActivity>) {
        scenario.onActivity { activity ->
            val toolbar = findToolbar(activity.window.decorView) ?: error("History toolbar missing")
            assertTrue(toolbar.showOverflowMenu())
        }
        val item = awaitText(context.getString(R.string.history_clear), exact = true)
        instrumentation.runOnMainSync {
            val row = generateSequence(item as View) { it.parent as? View }
                .firstOrNull { it.parent is AdapterView<*> } ?: error("Overflow menu row missing")
            val minimum = (48 * context.resources.displayMetrics.density).toInt()
            assertTrue("Overflow row is too short", row.height >= minimum)
            assertTrue("Overflow row is not visible", row.isShown)
            val menu = row.parent as AdapterView<*>
            val position = menu.getPositionForView(row)
            assertTrue(position != AdapterView.INVALID_POSITION)
            assertTrue(menu.performItemClick(row, position, menu.getItemIdAtPosition(position)))
        }
    }

    private fun awaitClearStatus(status: HistoryClearStatus) {
        repeat(100) {
            if (HistoryIo.currentClear(root)?.status == status) return
            Thread.sleep(50)
        }
        error("History clear did not reach $status")
    }

    private fun findToolbar(view: View): MaterialToolbar? {
        if (view is MaterialToolbar) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findToolbar(view.getChildAt(i))?.let { return it }
        return null
    }

    private fun findScrollView(view: View): ScrollView? {
        if (view is ScrollView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findScrollView(view.getChildAt(i))?.let { return it }
        return null
    }

    private fun confirm(positive: Boolean) {
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            val dialog = WindowInspector.getGlobalWindowViews().last()
            assertNotNull(findText(dialog, context.getString(R.string.history_clear_title)))
            assertEquals("Clear", (dialog.findViewById<View>(android.R.id.button1) as TextView).text.toString())
            dialog.findViewById<View>(if (positive) android.R.id.button1 else android.R.id.button2).performClick()
        }
    }

    private fun closeAndClean(scenario: ActivityScenario<HistoryTestActivity>) {
        scenario.close()
        registeredIds.forEach(HistoryProcess.owner::remove)
        root.deleteRecursively()
        HistoryTestActivity.historyRootSuffix = null
    }

    @Test fun activeOnlyClearShowsCountsAndCancelLeavesTheTakeEligible() {
        root.deleteRecursively()
        val id = UUID.randomUUID().toString()
        registerActive(id)
        val scenario = launch()
        try {
            awaitText(context.getString(R.string.history_empty))
            clickClear(scenario)
            val message = awaitText("Saved transcripts: 0. In progress: 1.")
            assertTrue(message.text.toString().contains("clipboard", ignoreCase = true))
            assertTrue(message.text.toString().contains("benchmark"))
            confirm(positive = false)
            assertFalse(root.resolve("$id.deleted").exists())
            assertEquals(HistoryCompletion.RECORDING, HistoryProcess.owner.state(id))

            clickClear(scenario)
            awaitText("Saved transcripts: 0. In progress: 1.")
            confirm(positive = true)
            awaitClearStatus(HistoryClearStatus.SUCCEEDED)
            assertTrue(root.resolve("$id.deleted").exists())
            assertEquals(HistoryCompletion.RECORDING, HistoryProcess.owner.state(id))
            val lateCheckpoint = HistoryIo.executor.submit<Boolean> {
                HistoryIo.store(root).checkpoint(HistoryEntry(id, 50, "late synthetic result", revision = 1))
            }.get(10, TimeUnit.SECONDS)
            assertFalse(lateCheckpoint)
        } finally { closeAndClean(scenario) }
    }

    @Test fun takeStartedAfterPositiveConfirmationSurvivesQueuedClear() {
        root.deleteRecursively()
        val oldSaved = UUID.randomUUID().toString()
        val activeAtAcceptance = UUID.randomUUID().toString()
        val later = UUID.randomUUID().toString()
        val store = HistoryStore(root)
        store.save(HistoryEntry(oldSaved, 10, "Synthetic saved row"))
        registerActive(activeAtAcceptance)
        val scenario = launch()
        val blockerStarted = CountDownLatch(1)
        val releaseBlocker = CountDownLatch(1)
        try {
            awaitText("Synthetic saved row")
            clickClear(scenario)
            awaitText("Saved transcripts: 1. In progress: 1.")

            HistoryIo.executor.execute { blockerStarted.countDown(); check(releaseBlocker.await(10, TimeUnit.SECONDS)) }
            assertTrue(blockerStarted.await(10, TimeUnit.SECONDS))
            confirm(positive = true)
            val accepted = HistoryIo.currentClear(root)!!
            assertTrue(oldSaved in accepted.ids)
            assertTrue(activeAtAcceptance in accepted.ids)
            registerActive(later)
            releaseBlocker.countDown()
            awaitClearStatus(HistoryClearStatus.SUCCEEDED)
            val savedLater = HistoryIo.executor.submit<Pair<HistoryEntry?, HistoryEntry?>> {
                val current = HistoryIo.store(root)
                current.save(HistoryEntry(later, 20, "Synthetic later take"))
                current.get(oldSaved) to current.get(later)
            }.get(10, TimeUnit.SECONDS)
            assertNull(savedLater.first)
            assertEquals("Synthetic later take", savedLater.second?.text)
            assertTrue(later !in accepted.ids)
            assertEquals(HistoryCompletion.RECORDING, HistoryProcess.owner.state(activeAtAcceptance))
        } finally {
            releaseBlocker.countDown()
            closeAndClean(scenario)
        }
    }

    @Test fun failedClearSurvivesRecreationAndRetryDoesNotSweepLaterTake() {
        root.deleteRecursively()
        val blockedId = UUID.randomUUID().toString()
        val later = UUID.randomUUID().toString()
        val store = HistoryStore(root)
        store.save(HistoryEntry(blockedId, 10, "Synthetic row blocked for retry"))
        var scenario = launch()
        val obstruction = root.resolve("$blockedId.tmp")
        try {
            awaitText("Synthetic row blocked for retry")
            clickClear(scenario)
            awaitText("Saved transcripts: 1. In progress: 0.")
            obstruction.apply { mkdir(); resolve("partial").writeText("synthetic") }
            confirm(positive = true)
            awaitText(context.getString(R.string.history_clear_error))
            val failed = HistoryIo.currentClear(root)!!
            assertEquals(HistoryClearStatus.FAILED, failed.status)
            assertEquals("Synthetic row blocked for retry", store.get(blockedId)?.text)
            assertTrue(store.list().any { it.id == blockedId })
            awaitAbsent("Synthetic row blocked for retry")
            awaitAbsent(context.getString(R.string.history_copy))

            registerActive(later)
            HistoryIo.executor.submit { HistoryIo.store(root).save(HistoryEntry(later, 20, "Synthetic post-boundary take")) }
                .get(10, TimeUnit.SECONDS)
            scenario.onActivity { it.onBackPressed() }
            scenario.close()
            scenario = launch()
            awaitText(context.getString(R.string.history_clear_error))
            awaitText("Synthetic post-boundary take")
            assertTrue(obstruction.deleteRecursively())
            val retry = awaitText("Retry clear")
            instrumentation.runOnMainSync { retry.performClick() }
            awaitClearStatus(HistoryClearStatus.SUCCEEDED)
            assertEquals(failed.token, HistoryIo.currentClear(root)?.token)
            assertEquals(failed.ids, HistoryIo.currentClear(root)?.ids)
            assertNull(store.get(blockedId))
            assertEquals("Synthetic post-boundary take", store.get(later)?.text)
            awaitText("Synthetic post-boundary take")
        } finally {
            obstruction.deleteRecursively()
            closeAndClean(scenario)
        }
    }

    @Test fun staleDetailCopyCannotCopyTextAfterClearAcceptance() {
        root.deleteRecursively()
        val id = UUID.randomUUID().toString()
        val transcript = "Synthetic text removed by Clear"
        HistoryStore(root).save(HistoryEntry(id, 10, transcript))
        val scenario = launch()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        try {
            val row = awaitText(transcript)
            instrumentation.runOnMainSync { HistoryUi.click(row) }
            val staleCopyButton = awaitText(context.getString(R.string.history_copy), exact = true)
            // The detail page has its own title and no Clear History.
            scenario.onActivity {
                assertFalse(it.findViewById<MaterialToolbar>(R.id.history_toolbar).menu.findItem(R.id.history_clear_item).isVisible)
            }
            clipboard.setPrimaryClip(ClipData.newPlainText("sentinel", "keep existing clipboard"))
            // A Clear accepted while the detail is open (the list page's menu is the only entry point).
            val accepted = HistoryIo.acceptClear(root, setOf(id)) ?: error("clear not accepted")
            HistoryIo.executor.submit { HistoryIo.store(root).clear(accepted.ids); HistoryIo.finishClear(root, accepted.token, true) }
                .get(10, TimeUnit.SECONDS)

            instrumentation.runOnMainSync { staleCopyButton.performClick() }
            awaitAbsent(transcript)
            awaitText(context.getString(R.string.history_empty))
            awaitText(context.getString(R.string.history_missing))
            assertEquals("keep existing clipboard", clipboard.primaryClip?.getItemAt(0)?.text.toString())
            scenario.onActivity { assertNull(findText(it.window.decorView, context.getString(R.string.history_copy), exact = true)) }
        } finally {
            clipboard.clearPrimaryClip()
            closeAndClean(scenario)
        }
    }

    @Test fun clearAndHistoryControlsRemainReachableAt320dpWithLargeText() {
        root.deleteRecursively()
        val id = UUID.randomUUID().toString()
        HistoryStore(root).save(HistoryEntry(id, 10, "Synthetic large-text row"))
        HistoryTestActivity.largeTextPreview = true
        val scenario = launch()
        try {
            val row = awaitText("Synthetic large-text row")
            scenario.onActivity { activity ->
                assertEquals(2f, activity.resources.configuration.fontScale, 0.01f)
                val density = activity.resources.displayMetrics.density
                val body = HistoryUi.target(row)
                val width = (320 * density).toInt()
                body.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                assertTrue(body.measuredHeight >= (64 * density).toInt())
                assertTrue(body.measuredWidth <= width)
                val delete = (body.parent as View).findViewById<View>(R.id.history_row_delete)
                assertTrue(delete.layoutParams.width >= (48 * density).toInt() && delete.layoutParams.height >= (48 * density).toInt())
            }
            scenario.onActivity { HistoryUi.click(findText(it.window.decorView, "Synthetic large-text row")!!) }
            val copy = awaitText(context.getString(R.string.history_copy), exact = true)
            scenario.onActivity {
                val density = it.resources.displayMetrics.density
                assertTrue(copy.minimumHeight >= (48 * density).toInt())
                val scroll = findScrollView(it.window.decorView) ?: error("History detail scroll view missing")
                val size = (320 * density).toInt()
                scroll.measure(View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY))
                scroll.layout(0, 0, size, size)
                assertTrue(copy.measuredWidth <= size)
            }
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            clickClear(scenario)
            val cancel = awaitText(context.getString(R.string.cancel), exact = true)
            assertTrue(cancel.height >= (48 * context.resources.displayMetrics.density).toInt())
            confirm(positive = false)
        } finally {
            HistoryTestActivity.largeTextPreview = false
            closeAndClean(scenario)
        }
    }
}
