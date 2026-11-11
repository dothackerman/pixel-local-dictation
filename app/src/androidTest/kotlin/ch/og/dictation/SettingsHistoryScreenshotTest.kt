package ch.og.dictation

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.WindowManager
import android.view.inspector.WindowInspector
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.appbar.MaterialToolbar
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Captures app-owned windows into app-private files (pull with `phone pull files/lab/settings-history/<name>.png`).
 * History uses separate stores containing only synthetic text. Names: <screen>-<light|dark>-<font1|font2>.png
 * for Settings with the switch off and on, list (trash icons), overflow menu, detail (toolbar icon actions), one detail per
 * mark (detail-<mark>), empty state, warning row, marks sheet and clear dialog. Long-press copy has no capture: the
 * confirmation is the system's, outside our windows.
 */
@RunWith(AndroidJUnit4::class)
class SettingsHistoryScreenshotTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun settle() { Thread.sleep(450); instrumentation.waitForIdleSync() }

    @Test fun settingsAndHistory() {
        val output = File(context.filesDir, "lab/settings-history").apply { mkdirs() }
        val notices = historyNotices(context)
        val originalNotices = notices.pending()
        HistoryNotice.entries.forEach(notices::dismiss)
        val full = File(context.noBackupFilesDir, "history-ui-test-shots").apply { deleteRecursively() }
        val empty = File(context.noBackupFilesDir, "history-ui-test-shots-empty").apply { deleteRecursively() }
        // Today, yesterday and an earlier day, so the day headers read as on the phone (#36 mock).
        val now = System.currentTimeMillis()
        val entries = listOf(
            HistoryEntry(UUID.randomUUID().toString(), now - 60_000, "Pick up bread and milk on the way home.", HistoryDelivery.ACCEPTED),
            HistoryEntry(UUID.randomUUID().toString(), now - 3_600_000, "Grüße an alle, wir kommen etwa um halb acht und bringen Brot mit.", HistoryDelivery.UNKNOWN),
            HistoryEntry(UUID.randomUUID().toString(), now - 86_400_000, "Meeting notes: review the project plan tomorrow, then send the summary to everyone who joined the call.", HistoryDelivery.REJECTED),
            HistoryEntry(UUID.randomUUID().toString(), now - 172_800_000, "Remind me to call the dentist about the appointment next week.", HistoryDelivery.NOT_ATTEMPTED, HistoryCompletion.INTERRUPTED, 1),
        )
        entries.forEach { HistoryStore(full).save(it) }
        val marks = File(context.noBackupFilesDir, "history-ui-test-shots-marks").apply { deleteRecursively() }
        val markEntries = linkedMapOf(
            HistoryMarker.RECORDING to HistoryEntry(UUID.randomUUID().toString(), now - 30_000, "Still talking about the garden plan and the new", HistoryDelivery.NOT_ATTEMPTED, HistoryCompletion.RECORDING),
            HistoryMarker.PREPARING to HistoryEntry(UUID.randomUUID().toString(), now - 90_000, "Book the train to Bern for Friday morning.", HistoryDelivery.NOT_ATTEMPTED, HistoryCompletion.FINISHING),
            HistoryMarker.CUT_SHORT to HistoryEntry(UUID.randomUUID().toString(), now - 600_000, "Notes for the call: budget, timeline and the", HistoryDelivery.NOT_ATTEMPTED, HistoryCompletion.INTERRUPTED),
            HistoryMarker.NOT_INSERTED to HistoryEntry(UUID.randomUUID().toString(), now - 1_200_000, "Bitte bring morgen das Ladekabel mit.", HistoryDelivery.REJECTED),
            HistoryMarker.UNCERTAIN to HistoryEntry(UUID.randomUUID().toString(), now - 1_800_000, "See you at the station at seven.", HistoryDelivery.UNKNOWN),
        )
        markEntries.values.forEach { HistoryStore(marks).save(it) }
        try {
            for (night in listOf(Configuration.UI_MODE_NIGHT_NO)) { // One fixed light scheme (#36).
                for (scale in listOf(1f, 2f)) {
                    val tag = "${if (night == Configuration.UI_MODE_NIGHT_YES) "dark" else "light"}-font${scale.toInt()}"
                    HistoryTestActivity.nightMode = night
                    HistoryTestActivity.largeTextPreview = scale > 1f
                    SettingsLargeTextPreviewActivity.nightMode = night
                    SettingsLargeTextPreviewActivity.fontScale = scale
                    SettingsLargeTextPreviewActivity.historyRootSuffix = "shots"
                    HistoryTestActivity.historyRootSuffix = "shots"

                    val settings = instrumentation.startActivitySync(Intent(context, SettingsLargeTextPreviewActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SettingsActivity
                    try {
                        settle()
                        // Both switch states, drawn without touching the stored benchmark setting.
                        val switch = settings.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.benchmark_switch)
                        for (on in listOf(false, true)) {
                            instrumentation.runOnMainSync { switch.setOnCheckedChangeListener(null); switch.isChecked = on; switch.jumpDrawablesToCurrentState() }
                            settle()
                            instrumentation.runOnMainSync { capture(settings.window.decorView, File(output, "settings-switch-${if (on) "on" else "off"}-$tag.png")) }
                        }
                    } finally { instrumentation.runOnMainSync { settings.finish() } }

                    val activity = launchHistory()
                    try {
                        await(activity, entries.first().text)
                        await(activity, entries.last().text)
                        settle()
                        instrumentation.runOnMainSync { capture(activity.window.decorView, File(output, "history-list-$tag.png")) }

                        // The overflow menu: what the marks mean, then Clear History… in the error colour.
                        instrumentation.runOnMainSync {
                            requireNotNull(HistoryUi.find(activity.window.decorView) { it is MaterialToolbar } as MaterialToolbar).showOverflowMenu()
                        }
                        settle()
                        instrumentation.runOnMainSync {
                            val menu = WindowInspector.getGlobalWindowViews().last()
                            check(menu.rootView !== activity.window.decorView) { "overflow menu did not open" }
                            capture(menu, File(output, "history-menu-$tag.png"), activity.window.decorView)
                            requireNotNull(HistoryUi.find(activity.window.decorView) { it is MaterialToolbar } as MaterialToolbar).hideOverflowMenu()
                        }
                        settle()

                        // Warning row on the History page.
                        notices.record(HistoryNotice.SAVE)
                        instrumentation.runOnMainSync { activity.recreate() }
                        settle()
                        val warned = currentHistory(activity)
                        await(warned, context.getString(R.string.history_save_error))
                        settle()
                        instrumentation.runOnMainSync { capture(warned.window.decorView, File(output, "history-warning-$tag.png")) }
                        // Dismiss through the row's own button, as the owner would; the page redraws without it.
                        instrumentation.runOnMainSync { warned.findViewById<View>(R.id.history_status_action).performClick() }
                        settle()
                        instrumentation.runOnMainSync { assertEquals(View.GONE, warned.findViewById<View>(R.id.history_status).visibility) }

                        val page = currentHistory(warned)
                        // What the marks mean, from the overflow menu.
                        instrumentation.runOnMainSync {
                            val toolbar = requireNotNull(HistoryUi.find(page.window.decorView) { it is MaterialToolbar } as MaterialToolbar)
                            toolbar.menu.performIdentifierAction(R.id.history_marks_item, 0)
                        }
                        settle()
                        instrumentation.runOnMainSync {
                            val sheet = WindowInspector.getGlobalWindowViews().last { it.findViewById<View>(R.id.history_marks_rows) != null }
                            capture(sheet, File(output, "history-marks-$tag.png"), page.window.decorView)
                            sheet.findViewById<View>(R.id.history_marks_close).performClick()
                        }
                        settle()
                        // A detail with a mark: its line opens the sheet at that mark.
                        val marked = await(page, entries[2].text)
                        instrumentation.runOnMainSync { HistoryUi.click(marked) }
                        await(page, context.getString(R.string.history_copy_transcript))
                        settle()
                        instrumentation.runOnMainSync { capture(page.window.decorView, File(output, "history-detail-marked-$tag.png")) }
                        instrumentation.runOnMainSync { page.findViewById<View>(R.id.history_detail_marker).performClick() }
                        settle()
                        instrumentation.runOnMainSync {
                            val sheet = WindowInspector.getGlobalWindowViews().last { it.findViewById<View>(R.id.history_marks_rows) != null }
                            capture(sheet, File(output, "history-marks-at-mark-$tag.png"), page.window.decorView)
                            sheet.findViewById<View>(R.id.history_marks_close).performClick()
                        }
                        settle()
                        instrumentation.runOnMainSync { page.onBackPressedDispatcher.onBackPressed() }
                        val tapped = await(page, entries.first().text)
                        instrumentation.runOnMainSync { HistoryUi.click(tapped) }
                        await(page, context.getString(R.string.history_copy_transcript))
                        settle()
                        instrumentation.runOnMainSync { capture(page.window.decorView, File(output, "history-detail-$tag.png")) }
                        instrumentation.runOnMainSync { page.onBackPressedDispatcher.onBackPressed() }
                        await(page, entries.first().text)
                        settle()
                        instrumentation.runOnMainSync {
                            val toolbar = requireNotNull(HistoryUi.find(page.window.decorView) { it is MaterialToolbar } as MaterialToolbar)
                            assertTrue(toolbar.menu.findItem(R.id.history_clear_item).isEnabled)
                            toolbar.menu.performIdentifierAction(R.id.history_clear_item, 0)
                        }
                        settle()
                        instrumentation.runOnMainSync {
                            val dialog = WindowInspector.getGlobalWindowViews().last { it.findViewById<View>(android.R.id.message) != null }
                            capture(dialog, File(output, "history-clear-dialog-$tag.png"), page.window.decorView)
                            dialog.findViewById<View>(android.R.id.button2).performClick()
                        }
                        settle()
                        assertEquals(entries.size, full.listFiles().orEmpty().count { it.name.endsWith(".history") })
                        // Delete from a row: the row goes, the Snackbar offers Undo; Undo brings it back.
                        val trash = await(page, context.getString(R.string.history_delete_row, HistoryRows.date(entries.first().timestamp),
                            HistoryRows.previewWords(entries.first().text)))
                        instrumentation.runOnMainSync { trash.performClick() }
                        settle()
                        instrumentation.runOnMainSync { capture(page.window.decorView, File(output, "history-deleted-undo-$tag.png")) }
                        val undo = await(page, context.getString(R.string.dictionary_undo))
                        instrumentation.runOnMainSync { undo.performClick() }
                        await(page, entries.first().text)
                    } finally { instrumentation.runOnMainSync { currentHistoryOrNull()?.finish() } }

                    // One detail per mark: the mark's sentence under the meta line.
                    HistoryTestActivity.historyRootSuffix = "shots-marks"
                    // In-progress marks need a live take in this process; the registrations end below and in finally.
                    HistoryProcess.owner.register(markEntries.getValue(HistoryMarker.RECORDING).id)
                    markEntries.getValue(HistoryMarker.PREPARING).id.let { HistoryProcess.owner.register(it); HistoryProcess.owner.finishing(it) }
                    val markedPage = launchHistory()
                    try {
                        for ((mark, entry) in markEntries) {
                            val rowText = await(markedPage, entry.text)
                            instrumentation.runOnMainSync { HistoryUi.click(rowText) }
                            await(markedPage, context.getString(mark.line))
                            settle()
                            instrumentation.runOnMainSync { capture(markedPage.window.decorView, File(output, "history-detail-${mark.name.lowercase().replace('_', '-')}-$tag.png")) }
                            instrumentation.runOnMainSync { markedPage.onBackPressedDispatcher.onBackPressed() }
                            settle()
                        }
                    } finally {
                        instrumentation.runOnMainSync { markedPage.finish() }
                        markEntries.values.forEach { HistoryProcess.owner.remove(it.id) }
                    }

                    HistoryTestActivity.historyRootSuffix = "shots-empty"
                    val blank = launchHistory()
                    try {
                        await(blank, context.getString(R.string.history_empty))
                        settle()
                        instrumentation.runOnMainSync { capture(blank.window.decorView, File(output, "history-empty-$tag.png")) }
                    } finally { instrumentation.runOnMainSync { blank.finish() } }
                }
            }
        } finally {
            HistoryTestActivity.nightMode = null
            HistoryTestActivity.largeTextPreview = false
            HistoryTestActivity.historyRootSuffix = null
            SettingsLargeTextPreviewActivity.nightMode = null
            SettingsLargeTextPreviewActivity.fontScale = 2f
            SettingsLargeTextPreviewActivity.historyRootSuffix = null
            HistoryNotice.entries.forEach { if (it in originalNotices) notices.record(it) else notices.dismiss(it) }
            (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).clearPrimaryClip()
            markEntries.values.forEach { HistoryProcess.owner.remove(it.id) }
            full.deleteRecursively(); empty.deleteRecursively(); marks.deleteRecursively()
        }
    }

    private var latest: HistoryActivity? = null
    private fun launchHistory(): HistoryActivity =
        (instrumentation.startActivitySync(Intent(context, HistoryTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as HistoryActivity).also { latest = it }
    private fun currentHistoryOrNull(): HistoryActivity? = latest
    /** After recreate() the instance changes; find the resumed one. */
    private fun currentHistory(previous: HistoryActivity): HistoryActivity {
        repeat(100) {
            var resumed: HistoryActivity? = null
            // The lifecycle monitor may only be queried on the main thread.
            instrumentation.runOnMainSync {
                resumed = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).filterIsInstance<HistoryActivity>().firstOrNull()
            }
            resumed?.let { latest = it; return it }
            Thread.sleep(50)
        }
        return previous
    }

    private fun await(activity: HistoryActivity, text: String): TextView {
        repeat(100) {
            instrumentation.waitForIdleSync()
            var result: TextView? = null
            instrumentation.runOnMainSync { result = HistoryUi.findText(activity.window.decorView, text) }
            result?.let { return it }
            Thread.sleep(50)
        }
        error("Missing $text")
    }

    private fun capture(view: View, file: File, underlay: View? = null) {
        check(view.width > 0 && view.height > 0)
        val page = underlay ?: view
        val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            underlay?.let {
                it.draw(canvas)
                val amount = (view.layoutParams as? WindowManager.LayoutParams)?.dimAmount ?: 0f
                canvas.drawColor(Color.argb((255 * amount).toInt(), 0, 0, 0))
                val dialogOrigin = IntArray(2).also(view::getLocationOnScreen)
                val pageOrigin = IntArray(2).also(it::getLocationOnScreen)
                canvas.translate((dialogOrigin[0] - pageOrigin[0]).toFloat(), (dialogOrigin[1] - pageOrigin[1]).toFloat())
            }
            view.draw(canvas)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
    }
}
