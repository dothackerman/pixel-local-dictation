package ch.og.dictation

import android.view.View
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** History warnings live in one inline status row on the History page, never on Settings or the recording panel. */
@RunWith(AndroidJUnit4::class)
class HistoryNoticesUiTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun hiddenWarningAppearsOnReturnAndSurvivesRecreationUntilDismissed() {
        val notices = historyNotices(context)
        val original = notices.pending()
        HistoryNotice.entries.forEach(notices::dismiss)
        val scenario = ActivityScenario.launch(HistoryTestActivity::class.java)
        val message = context.getString(R.string.history_cancel_delete_error)
        try {
            scenario.moveToState(Lifecycle.State.CREATED)
            notices.record(HistoryNotice.DELETE)
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity { assertEquals(message, it.findViewById<TextView>(R.id.history_notice_text).text.toString()) }
            scenario.recreate()
            scenario.onActivity { activity ->
                val warning = activity.findViewById<TextView>(R.id.history_notice_text)
                assertEquals(message, warning.text.toString())
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.history_notice).visibility)
                val dismiss = activity.findViewById<View>(R.id.history_notice_dismiss)
                assertTrue(dismiss.height >= (48 * activity.resources.displayMetrics.density).toInt())
                dismiss.performClick()
                assertEquals(View.GONE, activity.findViewById<View>(R.id.history_notice).visibility)
            }
            assertFalse(HistoryNotice.DELETE in historyNotices(context).pending())
        } finally {
            scenario.close()
            HistoryNotice.entries.forEach { if (it in original) notices.record(it) else notices.dismiss(it) }
        }
    }

    @Test fun incompleteRecoveryWarningIsPersistentTextWithALiveRegionAndCanBeDismissed() {
        val notices = historyNotices(context)
        val original = notices.pending()
        HistoryNotice.entries.forEach(notices::dismiss)
        val scenario = ActivityScenario.launch(HistoryTestActivity::class.java)
        try {
            scenario.moveToState(Lifecycle.State.CREATED)
            notices.record(HistoryNotice.INCOMPLETE)
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity { activity ->
                val warning = activity.findViewById<TextView>(R.id.history_notice_text)
                assertEquals(context.getString(R.string.history_recovery_incomplete), warning.text.toString())
                assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, warning.accessibilityLiveRegion)
                activity.findViewById<View>(R.id.history_notice_dismiss).performClick()
                assertEquals(View.GONE, activity.findViewById<View>(R.id.history_notice).visibility)
            }
            assertFalse(HistoryNotice.INCOMPLETE in historyNotices(context).pending())
        } finally {
            scenario.close()
            HistoryNotice.entries.forEach { if (it in original) notices.record(it) else notices.dismiss(it) }
        }
    }

    @Test fun settingsShowsNoWarningBlockWhileANoticeIsPending() {
        val notices = historyNotices(context)
        val original = notices.pending()
        HistoryNotice.entries.forEach(notices::record)
        val scenario = ActivityScenario.launch(SettingsActivity::class.java)
        try {
            scenario.onActivity { activity ->
                HistoryNotice.entries.forEach { assertNull(HistoryUi.findText(activity.window.decorView, context.getString(it.message()))) }
                assertNotNull(activity.findViewById<View>(R.id.open_history))
                assertNotNull(activity.findViewById<View>(R.id.open_dictionary))
            }
        } finally {
            scenario.close()
            HistoryNotice.entries.forEach { if (it in original) notices.record(it) else notices.dismiss(it) }
        }
    }
}
