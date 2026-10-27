package ch.og.dictation

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.inspector.WindowInspector
import android.widget.TextView
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
    @Test fun nativeEditorShowsSearchValidationAndLargeTouchTargets() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(Intent(context, DictionaryActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as DictionaryActivity
        try {
            val loaded = java.util.concurrent.CountDownLatch(1)
            instrumentation.runOnMainSync {
                val model = androidx.lifecycle.ViewModelProvider(activity)[DictionaryModel::class.java]
                model.state.observe(activity) { if (!it.busy) loaded.countDown() }
            }
            check(loaded.await(30, java.util.concurrent.TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            val file = File(context.cacheDir, "dictionary-ui-synthetic.txt").apply { writeText("Bärlauch\nGrapheneOS\nMatterhorn\nSBB Mobile\nZürich HB\n") }
            val snapshot = DictionarySnapshot(file, ArtifactFiles.hash(file), file.readLines(), true)
            instrumentation.runOnMainSync {
                val model = androidx.lifecycle.ViewModelProvider(activity)[DictionaryModel::class.java]
                model.state.value = DictionaryModel.State(snapshot, busy = false)
            }
            instrumentation.waitForIdleSync()
            val out = File(context.filesDir, "lab/dictionary-settings").apply { mkdirs() }
            instrumentation.runOnMainSync {
                assertEquals(5, activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.dictionary_list).adapter!!.itemCount)
                capture(activity.window.decorView, File(out, "phone-list.png"))
                activity.findViewById<View>(R.id.dictionary_add).performClick()
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val dialogRoot = WindowInspector.getGlobalWindowViews().last { it.findViewById<View>(R.id.dictionary_entry) != null }
                val input = dialogRoot.findViewById<TextInputEditText>(R.id.dictionary_entry)
                val button = dialogRoot.findViewById<View>(R.id.dictionary_commit)
                assertFalse(button.isEnabled)
                input.setText("zürich hb")
                assertFalse(button.isEnabled)
                assertNotNull(dialogRoot.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.dictionary_entry_layout).error)
                input.setText("Rösti")
                assertTrue(button.isEnabled)
                val dp = context.resources.displayMetrics.density
                assertTrue(button.height / dp >= 48)
                capture(dialogRoot, File(out, "phone-add.png"))
            }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    private fun capture(view: View, file: File) {
        check(view.width > 0 && view.height > 0)
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try { view.draw(Canvas(bitmap)); file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }
}
