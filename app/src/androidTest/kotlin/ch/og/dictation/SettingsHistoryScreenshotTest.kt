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
 * for Settings, list, detail, empty state, warning row and clear dialog.
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
        val now = 1735812000000
        val entries = listOf(
            HistoryEntry(UUID.randomUUID().toString(), now, "Pick up bread and milk on the way home.", HistoryDelivery.ACCEPTED),
            HistoryEntry(UUID.randomUUID().toString(), now - 86_400_000, "Meeting notes: review the project plan tomorrow, then send the summary to everyone who joined the call.", HistoryDelivery.REJECTED),
            HistoryEntry(UUID.randomUUID().toString(), now - 172_800_000, "Remind me to call the dentist about the appointment next week.", HistoryDelivery.NOT_ATTEMPTED, HistoryCompletion.INTERRUPTED, 1),
        )
        entries.forEach { HistoryStore(full).save(it) }
        try {
            for (night in listOf(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES)) {
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
                        instrumentation.runOnMainSync { capture(settings.window.decorView, File(output, "settings-$tag.png")) }
                    } finally { instrumentation.runOnMainSync { settings.finish() } }

                    val activity = launchHistory()
                    try {
                        val row = await(activity, entries.first().text)
                        await(activity, entries.last().text)
                        settle()
                        instrumentation.runOnMainSync { capture(activity.window.decorView, File(output, "history-list-$tag.png")) }

                        // Warning row on the History page.
                        notices.record(HistoryNotice.SAVE)
                        instrumentation.runOnMainSync { activity.recreate() }
                        settle()
                        val warned = currentHistory(activity)
                        await(warned, context.getString(R.string.history_save_error))
                        settle()
                        instrumentation.runOnMainSync { capture(warned.window.decorView, File(output, "history-warning-$tag.png")) }
                        notices.dismiss(HistoryNotice.SAVE)

                        val page = currentHistory(warned)
                        val tapped = await(page, entries.first().text)
                        instrumentation.runOnMainSync { HistoryUi.click(tapped) }
                        await(page, context.getString(R.string.history_copy))
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
                    } finally { instrumentation.runOnMainSync { currentHistoryOrNull()?.finish() } }

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
            full.deleteRecursively(); empty.deleteRecursively()
        }
    }

    private var latest: HistoryActivity? = null
    private fun launchHistory(): HistoryActivity =
        (instrumentation.startActivitySync(Intent(context, HistoryTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as HistoryActivity).also { latest = it }
    private fun currentHistoryOrNull(): HistoryActivity? = latest
    /** After recreate() the instance changes; find the resumed one. */
    private fun currentHistory(previous: HistoryActivity): HistoryActivity {
        repeat(100) {
            val resumed = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).filterIsInstance<HistoryActivity>().firstOrNull()
            if (resumed != null) { latest = resumed; return resumed }
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
