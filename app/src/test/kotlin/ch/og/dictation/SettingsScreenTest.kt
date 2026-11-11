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
            setTheme(R.style.Theme_Dictation)
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
        assertEquals(app.resources.getQuantityString(R.plurals.settings_history_count, 2, 2), page.findViewById<TextView>(R.id.settings_history_count).text.toString())
        assertEquals(app.getString(R.string.history_row_with_count, app.getString(R.string.history_title), 2), history.contentDescription)
    }

    @Test fun deletingBenchmarkDataIsAQuietActionBelowTheNavigation() {
        val page = open()
        val delete = page.findViewById<MaterialButton>(R.id.benchmark_delete)
        val fill = delete.backgroundTintList?.defaultColor ?: 0
        assertEquals("text button, no fill", 0, android.graphics.Color.alpha(fill))
        assertTrue("quiet but marked destructive: underlined", (delete.text as android.text.Spanned).getSpans(0, delete.text.length, android.text.style.UnderlineSpan::class.java).isNotEmpty())
        assertTrue(delete.minimumHeight >= (48 * app.resources.displayMetrics.density).toInt())
        fun top(view: View): Int { val at = IntArray(2); view.getLocationInWindow(at); return at[1] }
        assertTrue(top(delete) > top(page.findViewById(R.id.benchmark_switch)))
        assertTrue(top(page.findViewById(R.id.benchmark_switch)) > top(page.findViewById(R.id.open_history)))
    }

    /** #36: the shared toolbar sits outside the scroll; a Material switch, no platform Switch. */
    @Test fun settingsUsesTheSharedToolbarAndAMaterialSwitch() {
        val page = open()
        val toolbar = page.findViewById<View>(R.id.settings_toolbar)
        var parent = toolbar.parent
        while (parent is View) { assertFalse("toolbar scrolls away", parent is android.widget.ScrollView); parent = parent.parent }
        assertTrue(toolbar.minimumHeight >= (64 * app.resources.displayMetrics.density).toInt())
        assertTrue(page.findViewById<View>(R.id.benchmark_switch) is com.google.android.material.materialswitch.MaterialSwitch)
        // The title sits on the rows' 24dp inset.
        val title = (0 until (toolbar as android.view.ViewGroup).childCount).map(toolbar::getChildAt)
            .first { it is TextView && it.text == app.getString(R.string.settings_label) }
        fun left(view: View): Int { val at = IntArray(2); view.getLocationInWindow(at); return at[0] + view.paddingLeft }
        val row = (page.findViewById<View>(R.id.open_dictionary) as android.view.ViewGroup).getChildAt(0)
        assertEquals(left(row), left(title))
    }

    /** #36 critique: the square switch draws a 52×32dp track with a 16dp-class thumb inset inside it, in both states. */
    @Test @Config(qualifiers = "xxhdpi") @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun switchDrawsAFullSizeTrackWithAnInsetThumb() {
        val page = open()
        val switch = page.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.benchmark_switch)
        val dp = app.resources.displayMetrics.density
        fun color(attr: Int) = com.google.android.material.color.MaterialColors.getColor(switch, attr)
        for (checked in listOf(false, true)) {
            switch.isChecked = checked
            switch.jumpDrawablesToCurrentState()
            switch.measure(View.MeasureSpec.makeMeasureSpec(switch.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(switch.height, View.MeasureSpec.EXACTLY))
            switch.layout(switch.left, switch.top, switch.right, switch.bottom)
            val bitmap = android.graphics.Bitmap.createBitmap(switch.width, switch.height, android.graphics.Bitmap.Config.ARGB_8888)
            switch.draw(android.graphics.Canvas(bitmap))
            val track = switch.trackDrawable!!.bounds
            assertEquals("track width, checked=$checked", (52 * dp).toInt(), track.width())
            assertEquals("track height, checked=$checked", (32 * dp).toInt(), track.height())
            // The thumb sits at its end of the track with the track's fill visible above it.
            val thumbX = if (checked) track.right - (16 * dp).toInt() else track.left + (16 * dp).toInt()
            val (fill, thumb) = if (checked) color(com.google.android.material.R.attr.colorSecondary) to color(com.google.android.material.R.attr.colorOnSecondary)
                else color(com.google.android.material.R.attr.colorSurfaceContainerHigh) to color(com.google.android.material.R.attr.colorOutline)
            assertEquals("thumb, checked=$checked", Integer.toHexString(thumb), Integer.toHexString(bitmap.getPixel(thumbX, track.centerY())))
            assertEquals("track above the thumb, checked=$checked", Integer.toHexString(fill), Integer.toHexString(bitmap.getPixel(thumbX, track.top + (4 * dp).toInt())))
            assertEquals("far end is track, checked=$checked", Integer.toHexString(fill),
                Integer.toHexString(bitmap.getPixel(if (checked) track.left + (8 * dp).toInt() else track.right - (8 * dp).toInt(), track.centerY())))
        }
        // The row breathes above and below the label at large font (the text never touches the rule).
        assertTrue(switch.paddingTop >= (12 * dp).toInt() && switch.paddingBottom >= (12 * dp).toInt())
    }
}
