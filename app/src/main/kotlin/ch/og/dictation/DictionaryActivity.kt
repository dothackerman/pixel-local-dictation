package ch.og.dictation

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.text.Collator
import java.util.Locale

class DictionaryActivity : AppCompatActivity() {
    private lateinit var model: DictionaryModel
    private lateinit var list: RecyclerView
    private lateinit var root: View
    private lateinit var add: ExtendedFloatingActionButton
    private lateinit var search: TextInputEditText
    private lateinit var status: TextView
    private val terms = TermsAdapter()
    private var sheet: BottomSheetDialog? = null
    private var entry: TextInputEditText? = null
    private var entryLayout: TextInputLayout? = null
    private var commit: MaterialButton? = null
    private var feedback: TextView? = null
    private var editing: String? = null
    private var handled = 0
    private var snackbar: Snackbar? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dictionary)
        root = findViewById(R.id.dictionary_root)
        add = findViewById(R.id.dictionary_add)
        search = findViewById(R.id.dictionary_search)
        status = findViewById(R.id.dictionary_state)
        list = findViewById<RecyclerView>(R.id.dictionary_list).apply {
            layoutManager = LinearLayoutManager(this@DictionaryActivity)
            adapter = terms
        }
        findViewById<MaterialToolbar>(R.id.dictionary_toolbar).setNavigationOnClickListener { finish() }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, keyboard.bottom))
            insets
        }
        handled = savedInstanceState?.getInt("handled") ?: 0
        model = ViewModelProvider(this)[DictionaryModel::class.java]
        add.setOnClickListener { showEditor(null) }
        search.watch { render() }
        status.setOnClickListener { if (model.state.value?.snapshot == null) model.reload() }
        model.state.observe(this) { state ->
            render()
            if (!state.busy && state.event > handled) {
                handled = state.event
                if (state.error) {
                    feedback?.setText(R.string.dictionary_save_failed)
                    entry?.isEnabled = true
                    validate()
                    showMessage(R.string.dictionary_save_failed)
                } else when (state.action) {
                    "add" -> {
                        entry?.isEnabled = true
                        entry?.setText("")
                        feedback?.setText(R.string.dictionary_added)
                        entry?.requestFocus()
                    }
                    "edit" -> { sheet?.dismiss(); showMessage(R.string.dictionary_updated) }
                    "delete" -> {
                        snackbar = Snackbar.make(root, R.string.dictionary_deleted, 8000)
                            .setAnchorView(add).setAction(R.string.dictionary_undo) { model.undo() }.also { it.show() }
                    }
                    "undo" -> showMessage(R.string.dictionary_restored)
                }
            }
            entry?.isEnabled = !state.busy
            commit?.setText(if (state.busy) R.string.dictionary_saving else if (editing == null) R.string.dictionary_add_action else R.string.dictionary_done)
            if (state.busy) feedback?.setText(R.string.dictionary_saving_explanation)
            validate()
        }
        if (savedInstanceState?.getBoolean("editor") == true) {
            showEditor(savedInstanceState.getString("editing"), savedInstanceState.getString("draft") ?: "")
        }
    }

    private fun render() {
        val state = model.state.value ?: return
        add.isEnabled = !state.busy && state.snapshot != null
        val all = state.snapshot?.entries.orEmpty()
        findViewById<TextView>(R.id.dictionary_count).text = resources.getQuantityString(R.plurals.dictionary_count, all.size, all.size)
        val query = search.text?.toString().orEmpty().trim()
        terms.items = all.filter { it.contains(query, ignoreCase = true) }.sortedWith(Collator.getInstance(Locale.GERMAN))
        terms.enabled = !state.busy
        terms.notifyDataSetChanged()
        val message = when {
            state.busy && state.snapshot == null -> R.string.dictionary_loading
            state.busy -> R.string.dictionary_saving_explanation
            state.snapshot == null -> R.string.dictionary_load_failed
            terms.items.isEmpty() && query.isNotEmpty() -> R.string.dictionary_no_matches
            all.isEmpty() -> R.string.dictionary_empty
            else -> null
        }
        status.visibility = if (message == null) View.GONE else View.VISIBLE
        message?.let(status::setText)
        status.isClickable = state.snapshot == null && !state.busy
        status.isFocusable = status.isClickable
    }

    private fun showEditor(term: String?, draft: String = term ?: "") {
        editing = term
        val dialog = BottomSheetDialog(this)
        val content = layoutInflater.inflate(R.layout.dictionary_editor, null)
        dialog.setContentView(content)
        sheet = dialog
        entry = content.findViewById(R.id.dictionary_entry)
        entryLayout = content.findViewById(R.id.dictionary_entry_layout)
        commit = content.findViewById(R.id.dictionary_commit)
        feedback = content.findViewById(R.id.dictionary_editor_feedback)
        content.findViewById<TextView>(R.id.dictionary_editor_title).setText(if (term == null) R.string.dictionary_add else R.string.dictionary_edit)
        commit!!.setText(if (term == null) R.string.dictionary_add_action else R.string.dictionary_done)
        entry!!.setText(draft)
        entry!!.watch { validate() }
        content.findViewById<View>(R.id.dictionary_editor_close).setOnClickListener { dialog.dismiss() }
        commit!!.setOnClickListener { saveEntry() }
        entry!!.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) { if (commit?.isEnabled == true) saveEntry(); true } else false
        }
        dialog.setOnDismissListener {
            sheet = null; entry = null; entryLayout = null; commit = null; feedback = null
            add.requestFocus()
        }
        dialog.setOnShowListener {
            dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
            dialog.behavior.skipCollapsed = true
            entry!!.requestFocus()
            entry!!.setSelection(entry!!.length())
            entry!!.post { getSystemService(InputMethodManager::class.java).showSoftInput(entry, InputMethodManager.SHOW_IMPLICIT) }
        }
        val busy = model.state.value?.busy == true
        entry!!.isEnabled = !busy
        if (busy) { commit!!.setText(R.string.dictionary_saving); feedback!!.setText(R.string.dictionary_saving_explanation) }
        dialog.show()
        validate()
    }

    private fun validate(): String? {
        val text = entry?.text?.toString() ?: return null
        val all = model.state.value?.snapshot?.entries.orEmpty().filter { it != editing }
        val result = runCatching { DictionaryStore.normalise(text).also { DictionaryStore.validate(all + it) } }
        entryLayout?.error = if (text.isEmpty()) null else result.exceptionOrNull()?.message
        commit?.isEnabled = model.state.value?.busy == false && result.isSuccess && result.getOrNull() != editing
        return result.getOrNull()
    }

    private fun saveEntry() {
        val term = validate() ?: return
        val all = model.state.value?.snapshot?.entries ?: return
        snackbar?.dismiss()
        model.save(if (editing == null) all + term else all.map { if (it == editing) term else it }, if (editing == null) "add" else "edit")
    }

    private fun delete(term: String) {
        val all = model.state.value?.snapshot?.entries ?: return
        fun apply() { snackbar?.dismiss(); model.save(all.filter { it != term }, "delete") }
        if (all.size == 1) MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dictionary_last_title).setMessage(R.string.dictionary_last_explanation)
            .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.dictionary_delete) { _, _ -> apply() }.show()
        else apply()
    }

    private fun showMessage(message: Int) {
        snackbar?.dismiss()
        snackbar = Snackbar.make(root, message, Snackbar.LENGTH_LONG).setAnchorView(add).also { it.show() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("handled", handled)
        outState.putBoolean("editor", sheet != null)
        outState.putString("editing", editing)
        outState.putString("draft", entry?.text?.toString())
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        sheet?.dismiss()
        super.onDestroy()
    }

    private fun TextInputEditText.watch(changed: () -> Unit) = addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { changed() }
        override fun afterTextChanged(s: Editable?) {}
    })

    private inner class TermsAdapter : RecyclerView.Adapter<TermsAdapter.Holder>() {
        var items = emptyList<String>()
        var enabled = true
        inner class Holder(val row: LinearLayout, val label: TextView, val remove: MaterialButton) : RecyclerView.ViewHolder(row)
        override fun onCreateViewHolder(parent: ViewGroup, type: Int): Holder {
            val row = layoutInflater.inflate(R.layout.dictionary_row, parent, false) as LinearLayout
            val label = row.findViewById<TextView>(R.id.dictionary_term)
            val remove = row.findViewById<MaterialButton>(R.id.dictionary_remove)
            return Holder(row, label, remove)
        }
        override fun getItemCount() = items.size
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val term = items[position]
            holder.label.text = term
            holder.label.isEnabled = enabled
            holder.remove.isEnabled = enabled
            holder.remove.contentDescription = getString(R.string.dictionary_delete_term, term)
            holder.label.setOnClickListener { showEditor(term) }
            holder.remove.setOnClickListener { delete(term) }
        }
    }
}
