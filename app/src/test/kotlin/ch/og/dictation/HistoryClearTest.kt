package ch.og.dictation

import ch.og.dictation.history.HistoryClearStatus
import ch.og.dictation.history.HistoryCompletion
import ch.og.dictation.history.HistoryEntry
import ch.og.dictation.history.HistoryIo
import ch.og.dictation.history.HistoryPageCase
import ch.og.dictation.history.HistoryProcess
import ch.og.dictation.storage.lastCopy
import android.content.DialogInterface
import android.view.View
import android.view.inspector.WindowInspector
import android.widget.AdapterView
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/** Clear History against saved and active takes, on the laptop (#79; the phone suite of this name is gone). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-port-xxhdpi")
class HistoryClearTest : HistoryPageCase() {
    /** Opens the real overflow menu and taps its Clear History row, a 48dp target. */
    private fun clickClear(page: Page) {
        assertTrue(toolbar(page).showOverflowMenu())
        idle()
        val item = WindowInspector.getGlobalWindowViews().asSequence()
            .mapNotNull { findText(it, app.getString(R.string.history_clear), exact = true) }.firstOrNull() ?: error("overflow menu did not open")
        val row = generateSequence(item as View) { it.parent as? View }.firstOrNull { it.parent is AdapterView<*> } ?: error("Overflow menu row missing")
        assertTrue("Overflow row is too short", row.height >= (48 * density).toInt())
        assertTrue("Overflow row is not visible", row.isShown)
        val menu = row.parent as AdapterView<*>
        val position = menu.getPositionForView(row)
        assertTrue(position != AdapterView.INVALID_POSITION)
        assertTrue(menu.performItemClick(row, position, menu.getItemIdAtPosition(position)))
        idle()
    }

    private fun dialog() = ShadowDialog.getLatestDialog() as AlertDialog

    private fun awaitConfirmation(message: String): AlertDialog {
        await("the confirmation \"$message\"") { (ShadowDialog.getLatestDialog() as? AlertDialog)?.isShowing == true }
        assertNotNull(findText(dialog().window!!.decorView, app.getString(R.string.history_clear_title)))
        assertEquals(message, dialog().findViewById<TextView>(android.R.id.message)!!.text.toString())
        return dialog()
    }

    private fun confirm(positive: Boolean) {
        val button = dialog().getButton(if (positive) DialogInterface.BUTTON_POSITIVE else DialogInterface.BUTTON_NEGATIVE)
        if (positive) assertEquals("Clear", button.text.toString())
        button.performClick()
        idle()
        assertFalse(dialog().isShowing)
    }

    private fun awaitClearStatus(status: HistoryClearStatus) =
        await("clear $status (is ${HistoryIo.currentClear(root)?.status})") { HistoryIo.currentClear(root)?.status == status }

    @Test fun activeOnlyClearShowsCountsAndCancelLeavesTheTakeEligible() {
        val id = UUID.randomUUID().toString()
        HistoryProcess.owner.register(id)
        val page = open().get()
        awaitText(page, app.getString(R.string.history_empty))
        clickClear(page)
        val message = "Deletes 1 take in progress. Clipboard copies and benchmark recordings stay."
        awaitConfirmation(message)
        confirm(positive = false)
        assertFalse(root.resolve("$id.deleted").exists())
        assertEquals(HistoryCompletion.RECORDING, HistoryProcess.owner.state(id))

        clickClear(page)
        awaitConfirmation(message)
        confirm(positive = true)
        awaitClearStatus(HistoryClearStatus.SUCCEEDED)
        assertTrue(root.resolve("$id.deleted").exists())
        assertEquals(HistoryCompletion.RECORDING, HistoryProcess.owner.state(id))
        assertFalse("a result arriving after the clear is not saved",
            onHistoryThread { HistoryIo.store(root).checkpoint(HistoryEntry(id, 50, "late synthetic result", revision = 1)) })
    }

    @Test fun takeStartedAfterPositiveConfirmationSurvivesQueuedClear() {
        val oldSaved = UUID.randomUUID().toString()
        val activeAtAcceptance = UUID.randomUUID().toString()
        val later = UUID.randomUUID().toString()
        save(HistoryEntry(oldSaved, 10, "Synthetic saved row"))
        HistoryProcess.owner.register(activeAtAcceptance)
        val page = open().get()
        val blockerStarted = CountDownLatch(1)
        val releaseBlocker = CountDownLatch(1)
        try {
            awaitText(page, "Synthetic saved row")
            clickClear(page)
            awaitConfirmation("Deletes 1 saved transcript and 1 take in progress. Clipboard copies and benchmark recordings stay.")
            HistoryIo.executor.execute { blockerStarted.countDown(); check(releaseBlocker.await(10, TimeUnit.SECONDS)) }
            assertTrue(blockerStarted.await(10, TimeUnit.SECONDS))
            confirm(positive = true)
            val accepted = HistoryIo.currentClear(root)!!
            assertEquals(HistoryClearStatus.RUNNING, accepted.status)
            assertTrue(oldSaved in accepted.ids)
            assertTrue(activeAtAcceptance in accepted.ids)
            HistoryProcess.owner.register(later)
            releaseBlocker.countDown()
            awaitClearStatus(HistoryClearStatus.SUCCEEDED)
            val (old, kept) = onHistoryThread {
                val current = HistoryIo.store(root)
                current.save(HistoryEntry(later, 20, "Synthetic later take"))
                current.get(oldSaved) to current.get(later)
            }
            assertNull(old)
            assertEquals("Synthetic later take", kept?.text)
            assertTrue(later !in accepted.ids)
            assertEquals(HistoryCompletion.RECORDING, HistoryProcess.owner.state(activeAtAcceptance))
        } finally { releaseBlocker.countDown() }
    }

    @Test fun failedClearSurvivesRecreationAndRetryDoesNotSweepLaterTake() {
        val blockedId = UUID.randomUUID().toString()
        val later = UUID.randomUUID().toString()
        save(HistoryEntry(blockedId, 10, "Synthetic row blocked for retry"))
        val first = open()
        val obstruction = root.resolve("$blockedId.tmp")
        awaitText(first.get(), "Synthetic row blocked for retry")
        clickClear(first.get())
        awaitConfirmation("Deletes 1 saved transcript. Clipboard copies and benchmark recordings stay.")
        obstruction.apply { mkdir(); resolve("partial").writeText("synthetic") }
        confirm(positive = true)
        awaitText(first.get(), app.getString(R.string.history_clear_error))
        val failed = HistoryIo.currentClear(root)!!
        assertEquals(HistoryClearStatus.FAILED, failed.status)
        assertEquals("Synthetic row blocked for retry", onHistoryThread { store.get(blockedId) }?.text)
        assertTrue(onHistoryThread { store.list() }.any { it.id == blockedId })
        awaitGone(first.get(), "Synthetic row blocked for retry")
        assertFalse(shows(first.get(), app.getString(R.string.history_copy_transcript)))

        HistoryProcess.owner.register(later)
        onHistoryThread { HistoryIo.store(root).save(HistoryEntry(later, 20, "Synthetic post-boundary take")) }
        first.get().onBackPressedDispatcher.onBackPressed()
        first.pause().stop().destroy()
        val page = open().get()
        awaitText(page, app.getString(R.string.history_clear_error))
        awaitText(page, "Synthetic post-boundary take")
        assertTrue(obstruction.deleteRecursively())
        awaitText(page, "Retry clear").performClick()
        awaitClearStatus(HistoryClearStatus.SUCCEEDED)
        assertEquals(failed.token, HistoryIo.currentClear(root)?.token)
        assertEquals(failed.ids, HistoryIo.currentClear(root)?.ids)
        assertNull(onHistoryThread { store.get(blockedId) })
        assertEquals("Synthetic post-boundary take", onHistoryThread { store.get(later) }?.text)
        awaitText(page, "Synthetic post-boundary take")
        assertFalse(shows(page, app.getString(R.string.history_clear_error)))
    }

    @Test fun staleDetailCopyCannotCopyTextAfterClearAcceptance() {
        val id = UUID.randomUUID().toString()
        val transcript = "Synthetic text removed by Clear"
        save(HistoryEntry(id, 10, transcript))
        val page = open().get()
        click(awaitRow(page, transcript))
        val staleCopyButton = awaitText(page, app.getString(R.string.history_copy_transcript), exact = true)
        // The detail page has its own title and no Clear History.
        assertFalse(toolbar(page).menu.findItem(R.id.history_clear_item).isVisible)
        app.wiring.storage.clipboard.copy("keep existing clipboard")
        // A Clear accepted while the detail is open (the list page's menu is the only entry point).
        val accepted = HistoryIo.acceptClear(root, setOf(id)) ?: error("clear not accepted")
        onHistoryThread { HistoryIo.store(root).clear(accepted.ids); HistoryIo.finishClear(root, accepted.token, true) }

        staleCopyButton.performClick()
        awaitText(page, app.getString(R.string.history_missing))
        awaitGone(page, transcript)
        assertNull("back on the list", page.findViewById<View>(R.id.history_detail_text))
        assertEquals("keep existing clipboard", lastCopy(app))
        assertFalse(shows(page, app.getString(R.string.history_copy_transcript), exact = true))
    }

    @Test @Config(qualifiers = "w320dp-h569dp-port-xhdpi")
    fun clearAndHistoryControlsRemainReachableAt320dpWithLargeText() {
        RuntimeEnvironment.setFontScale(2f)
        save(HistoryEntry(UUID.randomUUID().toString(), 10, "Synthetic large-text row"))
        val page = open().get()
        val width = (320 * density).toInt()
        assertEquals(2f, page.resources.configuration.fontScale, 0.01f)
        assertEquals(width, page.window.decorView.width)
        val body = awaitRow(page, "Synthetic large-text row")
        assertTrue(body.height >= (64 * density).toInt())
        val delete = rowTrash(body)
        assertTrue(delete.width >= (48 * density).toInt() && delete.height >= (48 * density).toInt())
        assertTrue("the row and its trash fit the screen", IntArray(2).also(delete::getLocationOnScreen)[0] + delete.width <= width)
        click(body)
        val copy = awaitText(page, app.getString(R.string.history_copy_transcript), exact = true)
        assertTrue(copy.height >= (48 * density).toInt() && copy.width >= (48 * density).toInt())
        assertTrue(IntArray(2).also(copy::getLocationOnScreen)[0] + copy.width <= width)
        val scroll = find(page.findViewById(R.id.history_detail_container)) { it is ScrollView } ?: error("History detail scroll view missing")
        assertTrue(scroll.width <= width)
        page.onBackPressedDispatcher.onBackPressed()
        idle()
        clickClear(page)
        awaitConfirmation("Deletes 1 saved transcript. Clipboard copies and benchmark recordings stay.")
        val cancel = dialog().getButton(DialogInterface.BUTTON_NEGATIVE)
        assertEquals(app.getString(R.string.cancel), cancel.text.toString())
        assertTrue(cancel.height >= (48 * density).toInt())
        assertTrue("the dialog fits the screen", dialog().window!!.decorView.width <= width)
        confirm(positive = false)
        assertNull(HistoryIo.currentClear(root))
    }
}
