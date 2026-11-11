package ch.og.dictation

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inspector.WindowInspector
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ActivityScenario
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
    @Test fun completionAndDeliveryAppearSeparatelyInDetails() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = HistoryStore(File(context.noBackupFilesDir, "history-ui-test"))
        val entries = HistoryDelivery.entries.mapIndexed { index, status ->
            HistoryEntry(UUID.randomUUID().toString(), 100L + index, "Synthetic status $index", status)
        }
        val ready = CountDownLatch(1)
        HistoryIo.executor.execute { entries.forEach(store::save); ready.countDown() }
        assertTrue(ready.await(10, TimeUnit.SECONDS))
        val scenario = ActivityScenario.launch(HistoryTestActivity::class.java)
        fun find(root: View, needle: String): View? {
            if (root is TextView && root.text.toString().contains(needle)) return root
            if (root is ViewGroup) for (i in 0 until root.childCount) find(root.getChildAt(i), needle)?.let { return it }
            return null
        }
        fun await(needle: String): View {
            repeat(100) {
                var found: View? = null
                scenario.onActivity { found = find(it.window.decorView, needle) }
                if (found != null) return found!!
                Thread.sleep(50)
            }
            error("Missing view: $needle")
        }
        try {
            val labels = listOf(R.string.history_delivery_unknown, R.string.history_delivery_not_attempted,
                R.string.history_delivery_rejected, R.string.history_delivery_accepted)
            entries.forEachIndexed { index, _ ->
                val row = await("Synthetic status $index")
                scenario.onActivity { row.performClick() }
                await(context.getString(R.string.history_complete))
                await(context.getString(labels[index]))
                scenario.onActivity { it.onBackPressed() }
            }
        } finally {
            scenario.close()
            val done = CountDownLatch(1)
            HistoryIo.executor.execute { entries.forEach { store.delete(it.id) }; done.countDown() }
            done.await(10, TimeUnit.SECONDS)
        }
    }
    @Test fun liveAndAbandonedCheckpointsShowTheirCompletionTruth() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
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
        fun contains(root: View, needle: String): Boolean {
            if (root is TextView && root.text.toString().contains(needle)) return true
            if (root is ViewGroup) for (i in 0 until root.childCount) if (contains(root.getChildAt(i), needle)) return true
            return false
        }
        fun await(needle: String) {
            repeat(100) {
                var found = false
                scenario.onActivity { found = contains(it.window.decorView, needle) }
                if (found) return
                Thread.sleep(50)
            }
            error("Missing view: $needle")
        }
        try {
            await(context.getString(R.string.history_recording))
            scenario.recreate()
            await(context.getString(R.string.history_recording))
            HistoryProcess.owner.finishing(id)
            scenario.recreate()
            await(context.getString(R.string.history_finishing))
            HistoryProcess.owner.remove(id)
            scenario.recreate()
            await(context.getString(R.string.history_interrupted))
            await(context.getString(R.string.history_delivery_unknown))
            await(context.getString(R.string.history_explanation))
        } finally {
            scenario.close()
            HistoryProcess.owner.remove(id)
            val done = CountDownLatch(1)
            HistoryIo.executor.execute { store.delete(id); done.countDown() }
            done.await(10, TimeUnit.SECONDS)
        }
    }
    @Test fun recreatedDetailRetainsTakeAndAccessibleBackReturnsToList() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = UUID.randomUUID().toString()
        val store = HistoryStore(File(context.noBackupFilesDir, "history-ui-test"))
        val ready = CountDownLatch(1)
        HistoryIo.executor.execute { store.save(HistoryEntry(id, 123, "Synthetic row\nFull detail after recreation")); ready.countDown() }
        assertTrue(ready.await(10, TimeUnit.SECONDS))
        val scenario = ActivityScenario.launch(HistoryTestActivity::class.java)
        fun find(root: View, predicate: (View) -> Boolean): View? {
            if (predicate(root)) return root
            if (root is ViewGroup) for (i in 0 until root.childCount) find(root.getChildAt(i), predicate)?.let { return it }
            return null
        }
        fun awaitView(predicate: (View) -> Boolean): View {
            repeat(100) {
                var found: View? = null
                scenario.onActivity { found = find(it.window.decorView, predicate) }
                if (found != null) return found!!
                Thread.sleep(50)
            }
            error("Missing view")
        }
        fun text(value: String): View = awaitView { it is TextView && it.text.toString().contains(value) }
        try {
            val row = text("Synthetic row")
            scenario.onActivity { row.performClick() }
            awaitView { it is TextView && it.text.toString() == "Synthetic row\nFull detail after recreation" }
            scenario.recreate()
            awaitView { it is TextView && it.text.toString() == "Synthetic row\nFull detail after recreation" }
            val back = awaitView { it.contentDescription == context.getString(R.string.history_back) }
            scenario.onActivity { back.performClick() }
            text(context.getString(R.string.history_explanation))
            text("Synthetic row")
        } finally {
            scenario.close()
            val done = CountDownLatch(1)
            HistoryIo.executor.execute { store.delete(id); done.countDown() }
            done.await(10, TimeUnit.SECONDS)
        }
    }

    @Test fun rejectedTranscriptCanBeReadCopiedAndDeleted() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val id = UUID.randomUUID().toString()
        val store = HistoryStore(File(context.noBackupFilesDir, "history-ui-test"))
        val text = "Synthetic History test\nFull second line"
        val ready = CountDownLatch(1)
        HistoryIo.executor.execute { store.save(HistoryEntry(id, 123, text)); ready.countDown() }
        assertTrue(ready.await(10, TimeUnit.SECONDS))
        val activity = instrumentation.startActivitySync(Intent(context, HistoryTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as HistoryActivity
        fun find(root: View, needle: String): TextView? {
            if (root is TextView && root.text.toString().contains(needle)) return root
            if (root is ViewGroup) for (i in 0 until root.childCount) find(root.getChildAt(i), needle)?.let { return it }
            return null
        }
        fun awaitText(needle: String): TextView {
            repeat(100) {
                instrumentation.waitForIdleSync()
                var view: TextView? = null
                instrumentation.runOnMainSync { view = find(activity.window.decorView, needle) }
                if (view != null) return view!!
                Thread.sleep(50)
            }
            error("Missing view: $needle")
        }
        try {
            assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            val row = awaitText("Synthetic History test")
            instrumentation.runOnMainSync { row.performClick() }
            awaitText("Full second line")
            val copy = awaitText(context.getString(R.string.history_copy))
            assertTrue(copy.minHeight / context.resources.displayMetrics.density >= 48)
            instrumentation.runOnMainSync { copy.performClick() }
            var copied = false
            repeat(100) {
                val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
                if (clip?.getItemAt(0)?.text.toString() == text) {
                    assertTrue(clip!!.description.extras!!.getBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE))
                    copied = true
                    return@repeat
                }
                Thread.sleep(50)
            }
            assertTrue(copied)
            val delete = awaitText(context.getString(R.string.history_delete))
            instrumentation.runOnMainSync { delete.performClick() }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val dialog = WindowInspector.getGlobalWindowViews().last()
                assertNotNull(find(dialog, context.getString(R.string.history_delete_confirm)))
                dialog.findViewById<View>(android.R.id.button1).performClick()
            }
            repeat(100) { if (store.get(id) == null) return@repeat; Thread.sleep(50) }
            assertNull(store.get(id))
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            val done = CountDownLatch(1)
            HistoryIo.executor.execute { store.delete(id); done.countDown() }
            done.await(10, TimeUnit.SECONDS)
        }
    }
}
