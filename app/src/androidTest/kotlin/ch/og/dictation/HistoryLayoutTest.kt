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
