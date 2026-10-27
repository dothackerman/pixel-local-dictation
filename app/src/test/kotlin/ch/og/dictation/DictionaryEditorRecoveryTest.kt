package ch.og.dictation

import android.app.Application
import android.os.Bundle
import android.os.Looper
import android.widget.TextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.button.MaterialButton
import org.robolectric.Robolectric
import org.robolectric.shadows.ShadowDialog
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import java.io.File
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DictionaryEditorRecoveryTest {
    // AndroidX's process-wide default factory retains the first Application across Robolectric
    // cases. Give each simulated process its actual Application without replacing model behavior.
    class EditorActivity : DictionaryActivity() {
        override val defaultViewModelProviderFactory: ViewModelProvider.Factory
            get() = ViewModelProvider.AndroidViewModelFactory(application)
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_Dictation_Settings)
            super.onCreate(savedInstanceState)
        }
    }
    private lateinit var app: Application
    private val owners = mutableListOf<ViewModelStore>()

    @Before fun provisionAvailabilityFixture() {
        app = RuntimeEnvironment.getApplication()
        val manifest = JSONObject(app.resources.openRawResource(R.raw.candidate_a).bufferedReader().use { it.readText() })
        val artifacts = manifest.getJSONObject("artifacts")
        for (path in artifacts.keys()) File(app.filesDir, "lab/$path").apply {
            parentFile!!.mkdirs()
            writeText(if (path == manifest.getJSONObject("variant").getJSONObject("hotwords").getString("file")) "Original\n" else "fixture")
        }
    }

    private fun model(): DictionaryModel {
        val owner = ViewModelStore().also(owners::add)
        return ViewModelProvider(owner, ViewModelProvider.AndroidViewModelFactory(app))[DictionaryModel::class.java].also { idle(it) }
    }

    private fun idle(model: DictionaryModel) {
        val deadline = System.nanoTime() + 5_000_000_000
        do {
            shadowOf(Looper.getMainLooper()).idle()
            if (model.state.value?.busy == false) return
            Thread.sleep(5)
        } while (System.nanoTime() < deadline)
        fail("Dictionary operation did not complete")
    }

    @After fun clearModels() { owners.forEach { it.clear() } }

    @Test fun staleEditorRefreshesBeforeRetryAndPreservesOtherEditorsAddition() {
        val first = model()
        val reopened = model()
        first.save(first.state.value!!.snapshot!!.entries + "First addition", "add")
        idle(first)
        reopened.save(reopened.state.value!!.snapshot!!.entries + "My draft", "add")
        idle(reopened)
        assertTrue(reopened.state.value!!.error)
        assertTrue(reopened.state.value!!.snapshot!!.entries.contains("First addition"))
        reopened.save(reopened.state.value!!.snapshot!!.entries + "My draft", "add")
        idle(reopened)
        assertFalse(reopened.state.value!!.error)
        assertEquals(listOf("Original", "First addition", "My draft"), Engine.dictionaryStore(app).read().entries)
        assertEquals(0, Engine.dictionaryModelLoads)
    }

    @Test fun firstSaveAfterProcessDeathClearsDraftAndReportsCompletion() {
        val original = Robolectric.buildActivity(EditorActivity::class.java).setup()
        val firstModel = ViewModelProvider(original.get())[DictionaryModel::class.java]
        idle(firstModel)
        original.get().findViewById<android.view.View>(R.id.dictionary_add).performClick()
        var editor = ShadowDialog.getLatestDialog()
        editor.findViewById<TextInputEditText>(R.id.dictionary_entry).setText("Before death")
        editor.findViewById<MaterialButton>(R.id.dictionary_commit).performClick()
        idle(firstModel)
        assertEquals("", editor.findViewById<TextInputEditText>(R.id.dictionary_entry).text.toString())
        val saved = Bundle()
        original.saveInstanceState(saved).pause().stop().destroy()

        // A new controller/ViewModel models process death; recreate() would retain the model.
        val restored = Robolectric.buildActivity(EditorActivity::class.java).setup(saved)
        try {
            val freshModel = ViewModelProvider(restored.get())[DictionaryModel::class.java]
            assertNotSame(firstModel, freshModel)
            idle(freshModel)
            editor = ShadowDialog.getLatestDialog()
            editor.findViewById<TextInputEditText>(R.id.dictionary_entry).setText("After death")
            editor.findViewById<MaterialButton>(R.id.dictionary_commit).performClick()
            idle(freshModel)
            assertTrue(Engine.dictionaryStore(app).read().entries.contains("After death"))
            assertEquals("", editor.findViewById<TextInputEditText>(R.id.dictionary_entry).text.toString())
            assertEquals(app.getString(R.string.dictionary_added), editor.findViewById<TextView>(R.id.dictionary_editor_feedback).text.toString())
        } finally { restored.pause().stop().destroy() }
    }


    @Test fun undoConflictDiscardsStaleUndoInsteadOfOverwritingNewTerms() {
        val deleting = model()
        deleting.save(emptyList(), "delete")
        idle(deleting)
        assertNotNull(deleting.undoEntries)
        val other = model()
        other.save(listOf("Other addition"), "add")
        idle(other)
        deleting.undo()
        idle(deleting)
        assertTrue(deleting.state.value!!.error)
        assertNull(deleting.undoEntries)
        assertEquals(listOf("Other addition"), deleting.state.value!!.snapshot!!.entries)
        deleting.undo()
        assertEquals(listOf("Other addition"), Engine.dictionaryStore(app).read().entries)
    }

    @Test fun rotationRetainsDraftWithoutReplayingConsumedAddCompletion() {
        val controller = Robolectric.buildActivity(EditorActivity::class.java).setup()
        try {
            val before = ViewModelProvider(controller.get())[DictionaryModel::class.java]
            idle(before)
            controller.get().findViewById<android.view.View>(R.id.dictionary_add).performClick()
            var editor = ShadowDialog.getLatestDialog()
            editor.findViewById<TextInputEditText>(R.id.dictionary_entry).setText("Saved")
            editor.findViewById<MaterialButton>(R.id.dictionary_commit).performClick()
            idle(before)
            editor.findViewById<TextInputEditText>(R.id.dictionary_entry).setText("Unfinished draft")
            controller.recreate()
            val after = ViewModelProvider(controller.get())[DictionaryModel::class.java]
            assertSame(before, after)
            idle(after)
            editor = ShadowDialog.getLatestDialog()
            assertEquals("Unfinished draft", editor.findViewById<TextInputEditText>(R.id.dictionary_entry).text.toString())
            assertNull(after.consumeCompletion())
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun deletedEditTargetKeepsDraftAndRequiresExplicitAddAsNewTerm() {
        val controller = Robolectric.buildActivity(EditorActivity::class.java).setup()
        try {
            val editing = ViewModelProvider(controller.get())[DictionaryModel::class.java]
            idle(editing)
            val list = controller.get().findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.dictionary_list)
            val adapter = list.adapter!!
            val holder = adapter.createViewHolder(list, 0)
            adapter.bindViewHolder(holder, 0)
            holder.itemView.findViewById<TextView>(R.id.dictionary_term).performClick()
            val editor = ShadowDialog.getLatestDialog()
            val input = editor.findViewById<TextInputEditText>(R.id.dictionary_entry)
            val commit = editor.findViewById<MaterialButton>(R.id.dictionary_commit)
            input.setText("My edit")
            val other = model()
            other.save(listOf("Replacement"), "edit")
            idle(other)
            commit.performClick()
            idle(editing)
            assertEquals("My edit", input.text.toString())
            assertTrue(editor.isShowing)
            assertEquals(app.getString(R.string.dictionary_add_as_new), commit.text.toString())
            assertEquals(listOf("Replacement"), Engine.dictionaryStore(app).read().entries)
            commit.performClick()
            idle(editing)
            assertEquals(listOf("Replacement", "My edit"), Engine.dictionaryStore(app).read().entries)
            assertEquals("", input.text.toString())
        } finally { controller.pause().stop().destroy() }
    }

}
