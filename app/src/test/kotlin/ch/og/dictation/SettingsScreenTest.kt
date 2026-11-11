package ch.og.dictation

import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Settings through its real layout: Dictionary and History as equal navigation rows, a quiet delete. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SettingsScreenTest {
    class Page : SettingsActivity() {
        override fun historyRoot() = File(noBackupFilesDir, "history-settings-screen-test")
        override fun onCreate(savedInstanceState: android.os.Bundle?) {
            setTheme(R.style.Theme_Dictation_Settings)
            super.onCreate(savedInstanceState)
        }
    }
    private val app get() = RuntimeEnvironment.getApplication()
    private val root get() = File(app.noBackupFilesDir, "history-settings-screen-test")

    @Before fun seed() {
        root.deleteRecursively()
        HistoryStore(root).save(HistoryEntry("b3de7a72-84ce-4721-8412-497b90c09315", 1, "Synthetic", HistoryDelivery.ACCEPTED))
        HistoryStore(root).save(HistoryEntry("9a67d378-1c8c-405e-bdb1-c113df823988", 2, "Synthetic", HistoryDelivery.ACCEPTED))
    }
    @After fun clean() { root.deleteRecursively() }

    private fun open(): Page {
        val page = Robolectric.buildActivity(Page::class.java).create().start().resume().visible().get()
        val count = page.findViewById<TextView>(R.id.settings_history_count)
        val deadline = System.nanoTime() + 5_000_000_000
        while (count.visibility != View.VISIBLE && System.nanoTime() < deadline) {
            HistoryIo.executor.submit {}.get()
            shadowOf(Looper.getMainLooper()).idle()
        }
        return page
    }

    @Test fun dictionaryAndHistoryAreNavigationRowsOfEqualWeight() {
        val page = open()
        val dictionary = page.findViewById<View>(R.id.open_dictionary)
        val history = page.findViewById<View>(R.id.open_history)
        for (row in listOf(dictionary, history)) {
            assertFalse("a row, not a filled button", row is Button)
            assertTrue(row.isClickable)
            assertTrue(row.minimumHeight >= (64 * app.resources.displayMetrics.density).toInt())
        }
        assertEquals(dictionary.height, history.height)
        // Row titles sit on the page's text inset, like the benchmark controls below them.
        fun left(view: View): Int { val at = IntArray(2); view.getLocationInWindow(at); return at[0] + view.paddingLeft }
        val title = (dictionary as android.view.ViewGroup).getChildAt(0)
        assertEquals(left(page.findViewById(R.id.benchmark_switch)), left(title))
        assertEquals(dictionary.width, history.width)
        // History keeps only its short count, in the subdued role.
        assertEquals("2", page.findViewById<TextView>(R.id.settings_history_count).text.toString())
        assertEquals(app.getString(R.string.history_row_with_count, app.getString(R.string.history_title), 2), history.contentDescription)
    }

    @Test fun deletingBenchmarkDataIsAQuietActionBelowTheNavigation() {
        val page = open()
        val delete = page.findViewById<MaterialButton>(R.id.benchmark_delete)
        val fill = delete.backgroundTintList?.defaultColor ?: 0
        assertEquals("text button, no fill", 0, android.graphics.Color.alpha(fill))
        assertTrue(delete.minimumHeight >= (48 * app.resources.displayMetrics.density).toInt())
        fun top(view: View): Int { val at = IntArray(2); view.getLocationInWindow(at); return at[1] }
        assertTrue(top(delete) > top(page.findViewById(R.id.benchmark_switch)))
        assertTrue(top(page.findViewById(R.id.benchmark_switch)) > top(page.findViewById(R.id.open_history)))
    }
}
