package ch.og.dictation.storage

import ch.og.dictation.R
import ch.og.dictation.SettingsActivity
import android.content.DialogInterface
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/** Release Settings: the product only. No Benchmark switch; old recordings can still be deleted (ADR-010 amendment). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReleaseSettingsTest {
    class Page : SettingsActivity() {
        override fun onCreate(savedInstanceState: android.os.Bundle?) {
            setTheme(R.style.Theme_Dictation)
            super.onCreate(savedInstanceState)
        }
    }
    private val app get() = RuntimeEnvironment.getApplication()
    private val recordings get() = File(app.filesDir, "benchmark")

    @Before fun anEarlierBuildLeftTheSwitchOnAndTwoRecordings() {
        app.getSharedPreferences("settings", 0).edit().putBoolean("benchmark", true).commit()
        recordings.mkdirs()
        for (name in listOf("20261001-080000-000", "20261002-080000-000")) {
            File(recordings, "$name.wav").writeBytes(ByteArray(44))
            File(recordings, "$name.json").writeText("{}")
        }
    }
    @After fun clean() { recordings.deleteRecursively() }

    private fun open(): Page = Robolectric.buildActivity(Page::class.java).create().start().resume().visible().get()

    private fun texts(view: View): List<String> = when (view) {
        is ViewGroup -> (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
        is TextView -> listOf(view.text.toString())
        else -> emptyList()
    }

    @Test fun settingsHasNoBenchmarkSwitch() {
        val page = open()
        assertNull(page.findViewById<View>(R.id.benchmark_switch))
        val shown = texts(page.findViewById(R.id.settings_root))
        assertFalse(shown.toString(), app.getString(R.string.benchmark_switch) in shown)
        assertFalse("nothing explains a switch that is not there", app.getString(R.string.benchmark_explanation) in shown)
        // The rest of the page stays: both navigation rows and the delete control.
        assertTrue(page.findViewById<View>(R.id.open_dictionary).isShown)
        assertTrue(page.findViewById<View>(R.id.open_history).isShown)
        assertTrue(page.findViewById<View>(R.id.benchmark_delete).isShown)
        assertTrue("the earlier build's setting is left as it was", app.getSharedPreferences("settings", 0).getBoolean("benchmark", false))
    }

    @Test fun theDeleteControlCountsAndDeletesTheOldRecordings() {
        val page = open()
        val stored = page.findViewById<TextView>(R.id.benchmark_stored)
        assertEquals(app.resources.getQuantityString(R.plurals.benchmark_stored, 2, 2), stored.text.toString())
        val delete = page.findViewById<View>(R.id.benchmark_delete)
        assertEquals(app.getString(R.string.benchmark_delete), (delete as TextView).text.toString())
        delete.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertNotNull(dialog)
        assertEquals(4, recordings.list()!!.size)
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(recordings.exists())
        assertEquals(app.resources.getQuantityString(R.plurals.benchmark_stored, 0, 0), stored.text.toString())
    }
}
