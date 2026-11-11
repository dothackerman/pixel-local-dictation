package ch.og.dictation

import android.app.Application
import android.os.Bundle
import android.os.Looper
import android.widget.TextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
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
            setTheme(R.style.Theme_Dictation)
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

    private fun shownText(root: android.view.View, value: String): Boolean = when (root) {
        is TextView -> root.isShown && root.text.toString() == value
        is android.view.ViewGroup -> (0 until root.childCount).any { shownText(root.getChildAt(it), value) }
        else -> false
    }

    /** #36 critique: an empty dictionary is its state line alone; no search, no orphaned languages. */
    @Test fun emptyDictionaryShowsOnlyItsStateLine() {
        val clearing = model()
        clearing.save(emptyList(), "delete")
        idle(clearing)
        val controller = Robolectric.buildActivity(EditorActivity::class.java).setup()
        try {
            val page = controller.get()
            idle(ViewModelProvider(page)[DictionaryModel::class.java])
            assertTrue(shownText(page.window.decorView, app.getString(R.string.dictionary_empty)))
            assertFalse(page.findViewById<android.view.View>(R.id.dictionary_search).isShown)
            assertFalse(shownText(page.window.decorView, app.getString(R.string.dictionary_languages)))
        } finally { controller.pause().stop().destroy() }
        val filled = Robolectric.buildActivity(EditorActivity::class.java).setup()
        try {
            val page = filled.get()
            val model = ViewModelProvider(page)[DictionaryModel::class.java]
            idle(model)
            model.save(listOf("Bärlauch"), "add")
            idle(model)
            assertTrue(page.findViewById<android.view.View>(R.id.dictionary_search).isShown)
            assertTrue(shownText(page.window.decorView, app.getString(R.string.dictionary_languages)))
        } finally { filled.pause().stop().destroy() }
    }

    /** #36 critique: Add term is filled primary like every other commit; the search hint is centred on its icon. */
    @Test fun addIsAFilledCommitAndTheSearchHintSitsOnTheIconLine() {
        val controller = Robolectric.buildActivity(EditorActivity::class.java).setup()
        try {
            val page = controller.get()
            idle(ViewModelProvider(page)[DictionaryModel::class.java])
            val add = page.findViewById<com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton>(R.id.dictionary_add)
            fun role(attr: Int) = com.google.android.material.color.MaterialColors.getColor(add, attr)
            assertEquals(role(androidx.appcompat.R.attr.colorPrimary), add.backgroundTintList!!.getColorForState(add.drawableState, 0))
            assertEquals(role(com.google.android.material.R.attr.colorOnPrimary), add.currentTextColor)
            val search = page.findViewById<TextInputEditText>(R.id.dictionary_search)
            assertEquals("hint centred in the field", search.paddingTop, search.paddingBottom)
            page.findViewById<android.view.View>(R.id.dictionary_add).performClick()
            val editor = ShadowDialog.getLatestDialog()
            val layout = editor.findViewById<TextInputLayout>(R.id.dictionary_entry_layout)
            val helper = layout.findViewById<TextView>(com.google.android.material.R.id.textinput_helper_text)
            val feedback = editor.findViewById<TextView>(R.id.dictionary_editor_feedback)
            fun left(view: android.view.View): Int { val at = IntArray(2); view.getLocationInWindow(at); return at[0] + view.paddingLeft }
            assertEquals("helper and feedback share one inset", left(helper), left(feedback))
            // The header never scrolls away: the commit is outside the field's scroller (device capture, landscape 2×).
            var parent = editor.findViewById<android.view.View>(R.id.dictionary_commit).parent
            while (parent is android.view.View) { assertFalse("commit scrolls with the field", parent is androidx.core.widget.NestedScrollView); parent = parent.parent }
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


    private fun left(view: android.view.View): Int { val at = IntArray(2); view.getLocationInWindow(at); return at[0] + view.paddingLeft }

    private fun <T> withDuplicate(check: (DictionaryActivity, android.app.Dialog) -> T): T {
        val controller = Robolectric.buildActivity(EditorActivity::class.java).setup()
        try {
            val page = controller.get()
            idle(ViewModelProvider(page)[DictionaryModel::class.java])
            page.findViewById<android.view.View>(R.id.dictionary_add).performClick()
            val editor = ShadowDialog.getLatestDialog()
            editor.findViewById<TextInputEditText>(R.id.dictionary_entry).setText("original")
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1)) // the error's entrance animation
            return check(page, editor)
        } finally { controller.pause().stop().destroy() }
    }

    /** #36 critique round 3: at font 1.3× Material moves the filled box's text down; the search hint stays on the icon line. */
    @Test @Config(fontScale = 1.3f)
    fun searchHintStaysOnTheIconLineAtLargeFont() {
        val controller = Robolectric.buildActivity(EditorActivity::class.java).setup()
        try {
            val search = controller.get().findViewById<TextInputEditText>(R.id.dictionary_search)
            assertEquals("hint centred in the field", search.paddingTop, search.paddingBottom)
        } finally { controller.pause().stop().destroy() }
    }

    /** #36 critique round 3: at font 1.3× the error line starts where the feedback line does. */
    @Test @Config(fontScale = 1.3f)
    fun errorAndFeedbackShareOneInsetAtLargeFont() = withDuplicate { _, editor ->
        val error = editor.findViewById<TextView>(com.google.android.material.R.id.textinput_error)
        assertEquals("Already in your dictionary.", error.text.toString())
        assertEquals("error and feedback share one inset", left(error), left(editor.findViewById<TextView>(R.id.dictionary_editor_feedback)))
    }

    /** #36 critique round 3: landscape 2×: one header row (field, Add, Close); the error sits right under it, outside the scroller. */
    @Test @Config(qualifiers = "w891dp-h411dp-land", fontScale = 2f)
    fun landscapeEditorIsOneRowWithTheErrorDirectlyUnderTheField() = withDuplicate { _, editor ->
        val layout = editor.findViewById<TextInputLayout>(R.id.dictionary_entry_layout)
        val commit = editor.findViewById<MaterialButton>(R.id.dictionary_commit)
        val close = editor.findViewById<android.view.View>(R.id.dictionary_editor_close)
        assertNull("no separate title", editor.findViewById<android.view.View>(R.id.dictionary_editor_title))
        assertSame(layout.parent, commit.parent)
        assertSame(layout.parent, close.parent)
        assertFalse("label lives in the placeholder", layout.isHintEnabled)
        assertEquals(app.getString(R.string.dictionary_entry_hint), editor.findViewById<TextInputEditText>(R.id.dictionary_entry).hint.toString())
        val error = editor.findViewById<TextView>(com.google.android.material.R.id.textinput_error)
        assertTrue(error.isShown)
        var parent = error.parent
        while (parent is android.view.View) { assertFalse("error scrolls away", parent is androidx.core.widget.NestedScrollView); parent = parent.parent }
        // Directly under the field, inside the header: nothing between the field and its error.
        val header = editor.findViewById<android.view.View>(R.id.dictionary_editor_header)
        fun top(view: android.view.View) = IntArray(2).also(view::getLocationInWindow)[1]
        val field = editor.findViewById<android.view.View>(R.id.dictionary_entry)
        assertTrue("error inside the header: ${top(error)}+${error.height} ty=${error.translationY} vs ${top(header)}+${header.height}; field ${top(field)}+${field.height} layout ${top(layout)}+${layout.height}", top(error) + error.height <= top(header) + header.height)
        assertTrue("error right under the field", top(error) - (top(field) + field.height) <= 6 * app.resources.displayMetrics.density)
    }

    /** #36 critique round 3: landscape 2×: the explanation, search and count scroll away, and Add term sits in the top bar. */
    @Test @Config(qualifiers = "w891dp-h411dp-land", fontScale = 2f)
    fun landscapeListScrollsItsHeaderAwayAndAddTermLeavesTheRows() {
        val controller = Robolectric.buildActivity(EditorActivity::class.java).setup()
        try {
            val page = controller.get()
            idle(ViewModelProvider(page)[DictionaryModel::class.java])
            shadowOf(Looper.getMainLooper()).idle()
            for (id in intArrayOf(R.id.dictionary_explanation, R.id.dictionary_search_layout, R.id.dictionary_summary)) {
                val params = page.findViewById<android.view.View>(id).layoutParams as com.google.android.material.appbar.AppBarLayout.LayoutParams
                assertTrue("scrolls away", params.scrollFlags and com.google.android.material.appbar.AppBarLayout.LayoutParams.SCROLL_FLAG_SCROLL != 0)
            }
            assertFalse("no button over the rows", page.findViewById<android.view.View>(R.id.dictionary_add).isShown)
            val toolbar = page.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.dictionary_toolbar)
            val item = toolbar.menu.findItem(R.id.dictionary_add_item)
            assertTrue(item.isVisible && item.isEnabled)
            assertEquals(app.getString(R.string.dictionary_add), item.title.toString())
            toolbar.menu.performIdentifierAction(R.id.dictionary_add_item, 0)
            shadowOf(Looper.getMainLooper()).idle()
            assertNotNull(ShadowDialog.getLatestDialog().findViewById<android.view.View>(R.id.dictionary_entry))
        } finally { controller.pause().stop().destroy() }
    }
}
