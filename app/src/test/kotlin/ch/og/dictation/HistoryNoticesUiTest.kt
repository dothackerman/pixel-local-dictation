package ch.og.dictation

import ch.og.dictation.history.HistoryNotice
import ch.og.dictation.history.HistoryPageCase
import ch.og.dictation.history.historyNotices
import ch.og.dictation.history.message
import ch.og.dictation.settings.SettingsScreenTest
import android.view.View
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * History warnings live in one inline status row on the History page, never on Settings (#79; the
 * phone suite of this name is gone). The notices are the build's own.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-port-xxhdpi")
class HistoryNoticesUiTest : HistoryPageCase() {
    private fun statusText(page: Page) = page.findViewById<TextView>(R.id.history_status_text)
    private fun status(page: Page) = page.findViewById<View>(R.id.history_status)

    @Test fun hiddenWarningAppearsOnReturnAndSurvivesRecreationUntilDismissed() {
        val message = app.getString(R.string.history_cancel_delete_error)
        val controller = open()
        await("the empty page") { shows(controller.get(), app.getString(R.string.history_empty)) }
        assertEquals(View.GONE, status(controller.get()).visibility)
        controller.pause().stop()
        historyNotices(app).record(HistoryNotice.DELETE)
        controller.start().resume()
        idle()
        assertEquals(View.VISIBLE, status(controller.get()).visibility)
        assertEquals(message, statusText(controller.get()).text.toString())
        controller.recreate()
        val page = controller.get()
        await("the warning after recreation") { status(page).visibility == View.VISIBLE && statusText(page).text.toString() == message }
        val dismiss = page.findViewById<View>(R.id.history_status_action)
        assertTrue(dismiss.height >= (48 * density).toInt())
        dismiss.performClick()
        assertEquals(View.GONE, status(page).visibility)
        assertFalse(HistoryNotice.DELETE in historyNotices(app).pending())
    }

    @Test fun incompleteRecoveryWarningIsPersistentTextWithALiveRegionAndCanBeDismissed() {
        val controller = open()
        controller.pause().stop()
        historyNotices(app).record(HistoryNotice.INCOMPLETE)
        controller.start().resume()
        val page = controller.get()
        await("the warning") { statusText(page).text.toString() == app.getString(R.string.history_recovery_incomplete) }
        assertTrue(statusText(page).isShown)
        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, statusText(page).accessibilityLiveRegion)
        advance(60_000)
        assertTrue("it stays until dismissed", statusText(page).isShown)
        page.findViewById<View>(R.id.history_status_action).performClick()
        assertEquals(View.GONE, status(page).visibility)
        assertFalse(HistoryNotice.INCOMPLETE in historyNotices(app).pending())
    }

    @Test fun settingsShowsNoWarningBlockWhileANoticeIsPending() {
        HistoryNotice.entries.forEach(historyNotices(app)::record)
        val controller = Robolectric.buildActivity(SettingsScreenTest.Page::class.java).create().start().resume().visible()
        try {
            val settings = controller.get()
            onHistoryThread {}
            idle()
            HistoryNotice.entries.forEach { notice ->
                assertNull(find(settings.window.decorView) { it is TextView && it.text.toString().contains(app.getString(notice.message())) })
            }
            assertNotNull(settings.findViewById<View>(R.id.open_history))
            assertNotNull(settings.findViewById<View>(R.id.open_dictionary))
        } finally { controller.pause().stop().destroy() }
    }
}
