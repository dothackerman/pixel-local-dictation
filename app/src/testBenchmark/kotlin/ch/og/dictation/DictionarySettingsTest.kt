package ch.og.dictation

import ch.og.dictation.dictionary.DictionaryActivity
import ch.og.dictation.dictionary.DictionaryModel
import ch.og.dictation.dictionary.DictionarySnapshot
import ch.og.dictation.probe.DictionaryLargeTextPreviewActivity
import ch.og.dictation.recognition.ArtifactFiles
import android.content.res.Configuration
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * The Dictionary editor in portrait, in its default look and in the light theme at large text: list,
 * search, duplicate error, commit target, empty state. Synthetic preview state; nothing is saved.
 * The add sheet above the real keyboard stays a phone check (device DictionarySettingsTest).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DictionarySettingsTest {
    class Editor : DictionaryActivity() {
        override val defaultViewModelProviderFactory: ViewModelProvider.Factory
            get() = ViewModelProvider.AndroidViewModelFactory(application)
        override fun onCreate(savedInstanceState: Bundle?) { setTheme(R.style.Theme_Dictation); super.onCreate(savedInstanceState) }
    }
    class LightLargeTextEditor : DictionaryLargeTextPreviewActivity() {
        override val defaultViewModelProviderFactory: ViewModelProvider.Factory
            get() = ViewModelProvider.AndroidViewModelFactory(application)
        override fun onCreate(savedInstanceState: Bundle?) { setTheme(R.style.Theme_Dictation); super.onCreate(savedInstanceState) }
    }

    @Test fun nativeEditorShowsSearchValidationAndLargeTouchTargets() = preview(Editor::class.java) {}

    @Test @Config(qualifiers = "night")
    fun nativeEditorSupportsLightThemeAndLargeText() = preview(LightLargeTextEditor::class.java) { page ->
        assertEquals(Configuration.UI_MODE_NIGHT_NO, page.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
        assertEquals(1.3f, page.resources.configuration.fontScale, 0.01f)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))

    private fun preview(type: Class<out DictionaryActivity>, look: (DictionaryActivity) -> Unit) {
        val app = RuntimeEnvironment.getApplication()
        val controller = Robolectric.buildActivity(type).setup()
        try {
            val page = controller.get()
            look(page)
            val model = ViewModelProvider(page)[DictionaryModel::class.java]
            val deadline = System.nanoTime() + 5_000_000_000
            while (model.state.value?.busy != false) {
                assertTrue("the Dictionary loads", System.nanoTime() < deadline)
                shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5)
            }
            val file = File(app.cacheDir, "dictionary-ui-synthetic.txt").apply { writeText("Bärlauch\nGrapheneOS\nMatterhorn\nSBB Mobile\nZürich HB\n") }
            val snapshot = DictionarySnapshot(file, ArtifactFiles.hash(file), file.readLines(), true)
            model.state.value = DictionaryModel.State(snapshot, busy = false)
            idle()
            val list = page.findViewById<RecyclerView>(R.id.dictionary_list)
            val search = page.findViewById<TextInputEditText>(R.id.dictionary_search)
            assertEquals(5, list.adapter!!.itemCount)
            assertTrue("the rows are laid out", list.childCount > 0)
            for (i in 0 until list.childCount) {
                val label = list.getChildAt(i).findViewById<TextView>(R.id.dictionary_term)
                assertTrue("rows show the synthetic terms", label.text.toString() in snapshot.entries)
            }
            search.setText("Zür")
            assertEquals(1, list.adapter!!.itemCount)
            search.setText("")
            assertEquals(5, list.adapter!!.itemCount)

            val add = page.findViewById<View>(R.id.dictionary_add)
            assertTrue("portrait: Add term floats over the list", add.isShown)
            add.performClick()
            idle()
            val sheet = ShadowDialog.getLatestDialog()
            val entry = sheet.findViewById<TextInputEditText>(R.id.dictionary_entry)
            val entryLayout = sheet.findViewById<TextInputLayout>(R.id.dictionary_entry_layout)
            val commit = sheet.findViewById<View>(R.id.dictionary_commit)
            assertFalse(commit.isEnabled)
            entry.setText("zürich hb")
            idle()
            assertFalse("a duplicate cannot be added", commit.isEnabled)
            assertEquals("Already in your dictionary.", entryLayout.error.toString())
            assertTrue(sheet.findViewById<View>(com.google.android.material.R.id.textinput_error).isShown)
            entry.setText("Rösti")
            idle()
            assertTrue(commit.isEnabled)
            assertNull(entryLayout.error)
            val dp = page.resources.displayMetrics.density
            assertTrue("commit is a 48dp target: ${commit.height / dp}", commit.height / dp >= 48)
            sheet.findViewById<View>(R.id.dictionary_editor_close).performClick()
            idle()
            assertFalse(sheet.isShowing)

            model.state.value = DictionaryModel.State(snapshot.copy(entries = emptyList()), busy = false)
            idle()
            assertEquals(0, list.adapter!!.itemCount)
            assertTrue(add.isEnabled)
            val state = page.findViewById<TextView>(R.id.dictionary_state)
            assertTrue(state.isShown)
            assertEquals(app.getString(R.string.dictionary_empty), state.text.toString())
            assertNull("nothing is saved", File(app.filesDir, "test/dictionary").list())
        } finally { controller.pause().stop().destroy() }
    }
}
