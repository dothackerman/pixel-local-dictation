package ch.og.dictation

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
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

/** Captures app-owned windows; History uses a separate store containing only synthetic text. */
@RunWith(AndroidJUnit4::class)
class SettingsHistoryScreenshotTest {
    @Test fun settingsAndHistory() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val output = File(context.filesDir, "lab/settings-history").apply { mkdirs() }
        fun settle() { Thread.sleep(450); instrumentation.waitForIdleSync() }
        val settings = instrumentation.startActivitySync(Intent(context, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SettingsActivity
        try {
            settle()
            instrumentation.runOnMainSync { capture(settings.window.decorView, File(output, "settings.png")) }
        } finally { instrumentation.runOnMainSync { settings.finish() } }

        val root = File(context.noBackupFilesDir, "history-ui-test-screenshots")
        root.deleteRecursively()
        val entries = listOf(
            HistoryEntry(UUID.randomUUID().toString(), 1735812000000, "Pick up bread and milk on the way home.", HistoryDelivery.ACCEPTED),
            HistoryEntry(UUID.randomUUID().toString(), 1735725600000, "Meeting notes: review the project plan tomorrow.", HistoryDelivery.REJECTED),
        )
        entries.forEach { HistoryStore(root).save(it) }
        HistoryTestActivity.historyRootSuffix = "screenshots"
        val activity = instrumentation.startActivitySync(Intent(context, HistoryTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as HistoryActivity
        try {
            fun find(view: View, text: String): TextView? {
                if (view is TextView && view.text.toString().contains(text)) return view
                if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i), text)?.let { return it }
                return null
            }
            fun await(text: String): TextView {
                repeat(100) {
                    instrumentation.waitForIdleSync()
                    var result: TextView? = null
                    instrumentation.runOnMainSync { result = find(activity.window.decorView, text) }
                    result?.let { return it }
                    Thread.sleep(50)
                }
                error("Missing $text")
            }
            val row = await(entries.first().text)
            await(entries.last().text)
            settle()
            instrumentation.runOnMainSync {
                capture(activity.window.decorView, File(output, "history-list.png"))
                row.performClick()
            }
            await(context.getString(R.string.history_copy))
            settle()
            instrumentation.runOnMainSync {
                capture(activity.window.decorView, File(output, "history-detail.png"))
                activity.onBackPressedDispatcher.onBackPressed()
            }
            await(entries.first().text)
            settle()
            instrumentation.runOnMainSync {
                val toolbar = (activity.window.decorView as ViewGroup).let { rootView ->
                    fun findToolbar(view: View): MaterialToolbar? {
                        if (view is MaterialToolbar) return view
                        if (view is ViewGroup) for (i in 0 until view.childCount) findToolbar(view.getChildAt(i))?.let { return it }
                        return null
                    }
                    requireNotNull(findToolbar(rootView))
                }
                assertTrue(toolbar.menu.getItem(0).isEnabled)
                toolbar.menu.performIdentifierAction(toolbar.menu.getItem(0).itemId, 0)
            }
            settle()
            instrumentation.runOnMainSync {
                val dialog = WindowInspector.getGlobalWindowViews().last { it.findViewById<View>(android.R.id.message) != null }
                capture(dialog, File(output, "history-clear-confirmation.png"), activity.window.decorView)
                dialog.findViewById<View>(android.R.id.button2).performClick()
            }
            settle()
            assertTrue(root.listFiles().orEmpty().count { it.name.endsWith(".history") } == entries.size)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            HistoryTestActivity.historyRootSuffix = null
            root.deleteRecursively()
        }
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
