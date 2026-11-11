package ch.og.dictation

import android.content.Intent
import android.view.View
import android.widget.ScrollView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoryLayoutTest {
    @Test fun historyListAndDetailControlsFitLargeTypeInLightAndDark() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val root = java.io.File(context.noBackupFilesDir, "history-ui-test-layout")
        root.deleteRecursively()
        val entry = HistoryEntry(java.util.UUID.randomUUID().toString(), 1, "Synthetic layout transcript")
        HistoryStore(root).save(entry)
        HistoryTestActivity.largeTextPreview = true
        HistoryTestActivity.historyRootSuffix = "layout"
        try {
            for (mode in listOf(android.content.res.Configuration.UI_MODE_NIGHT_NO, android.content.res.Configuration.UI_MODE_NIGHT_YES)) {
                HistoryTestActivity.nightMode = mode
                val activity = instrumentation.startActivitySync(Intent(context, HistoryTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as HistoryActivity
                try {
                    fun find(view: View, text: String): android.widget.TextView? {
                        if (view is android.widget.TextView && view.text.toString().contains(text)) return view
                        if (view is android.view.ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i), text)?.let { return it }
                        return null
                    }
                    fun await(text: String): android.widget.TextView {
                        repeat(100) {
                            instrumentation.waitForIdleSync()
                            var result: android.widget.TextView? = null
                            instrumentation.runOnMainSync { result = find(activity.window.decorView, text) }
                            result?.let { return it }
                            Thread.sleep(50)
                        }
                        error("Missing $text")
                    }
                    val row = await(entry.text)
                    fun checkRow(text: android.widget.TextView) {
                        val density = activity.resources.displayMetrics.density
                        val body = HistoryUi.target(text)
                        val size = (320 * density).toInt()
                        body.measure(View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                        assertTrue(body.measuredWidth <= size)
                        assertTrue(body.measuredHeight >= (64 * density).toInt())
                        // Preview stays within two lines in the primary text role.
                        assertTrue(text.maxLines <= 2)
                    }
                    fun checkControl(control: View) {
                        val scroll = generateSequence(control.parent) { it.parent }.filterIsInstance<ScrollView>().first()
                        val density = activity.resources.displayMetrics.density
                        val size = (320 * density).toInt()
                        scroll.measure(View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY))
                        scroll.layout(0, 0, size, size)
                        assertTrue(control.measuredWidth <= size)
                        assertTrue(control.measuredHeight >= (48 * density).toInt())
                        val rect = android.graphics.Rect()
                        control.getDrawingRect(rect)
                        scroll.offsetDescendantRectToMyCoords(control, rect)
                        scroll.scrollTo(0, rect.top)
                        assertTrue(rect.bottom > scroll.scrollY && rect.top < scroll.scrollY + scroll.height)
                    }
                    instrumentation.runOnMainSync {
                        assertEquals(2f, activity.resources.configuration.fontScale, 0.01f)
                        assertEquals(mode, activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                        checkRow(row)
                        HistoryUi.click(row)
                    }
                    val copy = await(context.getString(R.string.history_copy))
                    val delete = await(context.getString(R.string.history_delete))
                    instrumentation.runOnMainSync { checkControl(copy); checkControl(delete) }
                } finally { instrumentation.runOnMainSync { activity.finish() } }
            }
        } finally {
            HistoryTestActivity.largeTextPreview = false
            HistoryTestActivity.historyRootSuffix = null
            HistoryTestActivity.nightMode = null
            root.deleteRecursively()
        }
    }

    @Test fun settingsControlsRemainReachableAt320dpLargeTypeAndLandscapeHeight() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(Intent(context, SettingsLargeTextPreviewActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SettingsActivity
        try {
            instrumentation.runOnMainSync {
                assertEquals(2f, activity.resources.configuration.fontScale, 0.01f)
                val density = activity.resources.displayMetrics.density
                val root = activity.findViewById<ScrollView>(R.id.settings_root)
                val width = (320 * density).toInt()
                val height = (320 * density).toInt()
                root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, width, height)
                for (id in listOf(R.id.open_dictionary, R.id.open_history, R.id.benchmark_switch, R.id.benchmark_delete)) {
                    val control = activity.findViewById<View>(id)
                    assertTrue(control.measuredHeight >= (48 * density).toInt())
                    val rect = android.graphics.Rect()
                    control.getDrawingRect(rect)
                    root.offsetDescendantRectToMyCoords(control, rect)
                    root.scrollTo(0, rect.top)
                    assertTrue(rect.bottom > root.scrollY && rect.top < root.scrollY + root.height)
                }
                assertTrue(root.getChildAt(0).measuredHeight > height)
            }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
