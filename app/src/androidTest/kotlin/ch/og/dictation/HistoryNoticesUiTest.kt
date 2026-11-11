package ch.og.dictation

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoryNoticesUiTest {
    @Test fun hiddenWarningAppearsOnReturnAndSurvivesRecreationUntilDismissed() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val notices = historyNotices(context)
        val original = notices.pending()
        val scenario = ActivityScenario.launch(SettingsActivity::class.java)
        fun find(root: View, message: String): TextView? {
            if (root is TextView && root.text.toString() == message) return root
            if (root is ViewGroup) for (i in 0 until root.childCount) find(root.getChildAt(i), message)?.let { return it }
            return null
        }
        try {
            scenario.moveToState(Lifecycle.State.CREATED)
            notices.record(HistoryNotice.DELETE)
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity { assertNotNull(find(it.window.decorView, context.getString(R.string.history_cancel_delete_error))) }
            scenario.recreate()
            scenario.onActivity { activity ->
                assertNotNull(find(activity.window.decorView, context.getString(R.string.history_cancel_delete_error)))
                val container = activity.findViewById<ViewGroup>(R.id.history_notices)
                val warning = find(container, context.getString(R.string.history_cancel_delete_error))!!
                val index = (0 until container.childCount).first { container.getChildAt(it) === warning }
                container.getChildAt(index + 1).performClick()
                assertNull(find(container, context.getString(R.string.history_cancel_delete_error)))
                assertNotNull(activity.findViewById<View>(R.id.open_history))
            }
            assertFalse(HistoryNotice.DELETE in historyNotices(context).pending())
        } finally {
            scenario.close()
            HistoryNotice.entries.forEach { if (it in original) notices.record(it) else notices.dismiss(it) }
        }
    }

    @Test fun incompleteRecoveryWarningIsPersistentTextAndCanBeDismissed() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val notices = historyNotices(context)
        val original = notices.pending()
        val scenario = ActivityScenario.launch(SettingsActivity::class.java)
        val message = context.getString(R.string.history_recovery_incomplete)
        fun find(root: View, text: String): TextView? {
            if (root is TextView && root.text.toString() == text) return root
            if (root is ViewGroup) for (i in 0 until root.childCount) find(root.getChildAt(i), text)?.let { return it }
            return null
        }
        try {
            scenario.moveToState(Lifecycle.State.CREATED)
            notices.record(HistoryNotice.INCOMPLETE)
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity { activity ->
                val warning = find(activity.window.decorView, message)
                assertNotNull(warning)
                assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, warning!!.accessibilityLiveRegion)
                val container = activity.findViewById<ViewGroup>(R.id.history_notices)
                val index = (0 until container.childCount).first { container.getChildAt(it) === warning }
                container.getChildAt(index + 1).performClick()
                assertNull(find(container, message))
            }
            assertFalse(HistoryNotice.INCOMPLETE in historyNotices(context).pending())
        } finally {
            scenario.close()
            HistoryNotice.entries.forEach { if (it in original) notices.record(it) else notices.dismiss(it) }
        }
    }
}
