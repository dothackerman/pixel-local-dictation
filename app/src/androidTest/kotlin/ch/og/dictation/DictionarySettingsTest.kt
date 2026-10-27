package ch.og.dictation

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.WindowManager
import android.view.inspector.WindowInspector
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.textfield.TextInputEditText
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** App-owned views only. Synthetic preview state is never saved into the owner's dictionary. */
@RunWith(AndroidJUnit4::class)
class DictionarySettingsTest {
    @Test fun nativeEditorShowsSearchValidationAndLargeTouchTargets() = preview(DictionaryActivity::class.java, "phone")
    @Test fun nativeEditorSupportsLightThemeAndLargeText() = preview(DictionaryLargeTextPreviewActivity::class.java, "phone-large-light")

    private fun preview(type: Class<out DictionaryActivity>, prefix: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(Intent(context, type).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as DictionaryActivity
        fun settle() { android.os.SystemClock.sleep(450); instrumentation.waitForIdleSync() }
        try {
            if (prefix == "phone-large-light") {
                assertEquals(android.content.res.Configuration.UI_MODE_NIGHT_NO,
                    activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                assertEquals(1.3f, activity.resources.configuration.fontScale, 0.01f)
            }
            val loaded = java.util.concurrent.CountDownLatch(1)
            instrumentation.runOnMainSync {
                val model = androidx.lifecycle.ViewModelProvider(activity)[DictionaryModel::class.java]
                model.state.observe(activity) { if (!it.busy) loaded.countDown() }
            }
            check(loaded.await(30, java.util.concurrent.TimeUnit.SECONDS)) { "Unlock the phone and keep the editor foregrounded." }
            val file = File(context.cacheDir, "dictionary-ui-synthetic.txt").apply { writeText("Bärlauch\nGrapheneOS\nMatterhorn\nSBB Mobile\nZürich HB\n") }
            val snapshot = DictionarySnapshot(file, ArtifactFiles.hash(file), file.readLines(), true)
            instrumentation.runOnMainSync {
                val model = androidx.lifecycle.ViewModelProvider(activity)[DictionaryModel::class.java]
                model.state.value = DictionaryModel.State(snapshot, busy = false)
            }
            settle()
            val out = File(context.filesDir, "lab/dictionary-settings").apply { mkdirs() }
            instrumentation.runOnMainSync {
                val list = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.dictionary_list)
                assertEquals(5, list.adapter!!.itemCount)
                for (i in 0 until list.childCount) {
                    val label = list.getChildAt(i).findViewById<android.widget.TextView>(R.id.dictionary_term)
                    assertTrue("capture must contain synthetic terms only", label.text.toString() in snapshot.entries)
                }
                capture(activity.window.decorView, File(out, "$prefix-list.png"))
                activity.findViewById<TextInputEditText>(R.id.dictionary_search).setText("Zür")
                assertEquals(1, list.adapter!!.itemCount)
                activity.findViewById<TextInputEditText>(R.id.dictionary_search).setText("")
                activity.findViewById<View>(R.id.dictionary_add).performClick()
            }
            settle()
            lateinit var dialogRoot: View
            instrumentation.runOnMainSync {
                dialogRoot = WindowInspector.getGlobalWindowViews().last { it.findViewById<View>(R.id.dictionary_entry) != null }
                val button = dialogRoot.findViewById<View>(R.id.dictionary_commit)
                assertFalse(button.isEnabled)
                dialogRoot.findViewById<TextInputEditText>(R.id.dictionary_entry).setText("zürich hb")
                assertFalse(button.isEnabled)
            }
            settle()
            instrumentation.runOnMainSync {
                assertNotNull(dialogRoot.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.dictionary_entry_layout).error)
                capture(dialogRoot, File(out, "$prefix-duplicate.png"), activity.window.decorView)
                dialogRoot.findViewById<TextInputEditText>(R.id.dictionary_entry).setText("Rösti")
            }
            settle()
            instrumentation.runOnMainSync {
                val button = dialogRoot.findViewById<View>(R.id.dictionary_commit)
                assertTrue(button.isEnabled)
                assertNull(dialogRoot.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.dictionary_entry_layout).error)
                val dp = context.resources.displayMetrics.density
                assertTrue(button.height / dp >= 48)
                val keyboard = dialogRoot.rootWindowInsets.getInsets(android.view.WindowInsets.Type.ime()).bottom
                assertTrue("editor must open the keyboard", keyboard > 0)
                val position = IntArray(2).also { button.getLocationInWindow(it) }
                assertTrue("commit must stay above the keyboard", position[1] + button.height <= dialogRoot.height - keyboard)
                capture(dialogRoot, File(out, "$prefix-add.png"), activity.window.decorView)
                dialogRoot.findViewById<View>(R.id.dictionary_editor_close).performClick()
                androidx.lifecycle.ViewModelProvider(activity)[DictionaryModel::class.java].state.value =
                    DictionaryModel.State(snapshot.copy(entries = emptyList()), busy = false)
            }
            settle()
            instrumentation.runOnMainSync {
                assertEquals(0, activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.dictionary_list).adapter!!.itemCount)
                assertTrue(activity.findViewById<View>(R.id.dictionary_add).isEnabled)
                capture(activity.window.decorView, File(out, "$prefix-empty.png"))
            }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    /** Draw only this app's own windows. The keyboard and other apps are never captured. */
    private fun capture(view: View, file: File, underlay: View? = null) {
        check(view.width > 0 && view.height > 0)
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            underlay?.let {
                it.draw(canvas)
                val amount = (view.layoutParams as? WindowManager.LayoutParams)?.dimAmount ?: 0f
                canvas.drawColor(Color.argb((255 * amount).toInt(), 0, 0, 0))
            }
            view.draw(canvas)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
    }
}
