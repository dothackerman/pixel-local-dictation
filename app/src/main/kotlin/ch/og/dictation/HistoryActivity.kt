package ch.og.dictation

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.annotation.SuppressLint
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import java.io.File

/** Same patterns as Dictionary: layouts and a list, delete with Undo, one inline status slot. Text is kept out of saved view state, intents and task previews. */
open class HistoryActivity : AppCompatActivity() {
    private lateinit var root: View
    private lateinit var toolbar: MaterialToolbar
    private lateinit var listPane: View
    private lateinit var list: RecyclerView
    private lateinit var empty: View
    /** One status slot: the page's own state, else the first pending warning. */
    private lateinit var status: com.google.android.material.card.MaterialCardView
    private lateinit var statusText: TextView
    private lateinit var statusAction: MaterialButton
    private lateinit var detailContainer: ViewGroup
    private val rows = RowsAdapter()
    private val undo = HistoryUndo()
    private val handler = Handler(Looper.getMainLooper())
    private val commitPending = Runnable { settleDelete() }
    private val notices by lazy { historyNotices(this) }
    private var snackbar: Snackbar? = null
    private var marks: BottomSheetDialog? = null
    private var entries = emptyList<HistoryEntry>()
    private var selected: String? = null
    private var detail: View? = null
    private var loadedOnce = false
    private var readError = false
    private var missing = false
    private var generation = 0
    private var inFlightLoads = 0
    private var visibleSavedCount = 0
    private var hasStoredHistoryCopies = false
    private var submittedClearToken: Long? = null
    protected open fun historyRoot() = historyRoot(this)
    /** How long a deleted transcript can be brought back. */
    protected open val undoWindowMs: Long get() = 8000L
    private val storeRoot by lazy { historyRoot() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.history)
        root = findViewById(R.id.history_root)
        toolbar = findViewById(R.id.history_toolbar)
        listPane = findViewById(R.id.history_list_pane)
        empty = findViewById(R.id.history_empty)
        status = findViewById(R.id.history_status)
        statusText = findViewById(R.id.history_status_text)
        statusAction = findViewById(R.id.history_status_action)
        detailContainer = findViewById(R.id.history_detail_container)
        list = findViewById<RecyclerView>(R.id.history_list).apply {
            layoutManager = LinearLayoutManager(this@HistoryActivity)
            adapter = rows
            itemAnimator = null
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { back() }
        })
        selected = savedInstanceState?.getString(SELECTED_ID)
        visibleSavedCount = savedInstanceState?.getInt(SAVED_COUNT) ?: 0
        toolbar.setNavigationOnClickListener { back() }
        toolbar.setOnMenuItemClickListener { item: MenuItem ->
            when (item.itemId) {
                R.id.history_clear_item -> { onClearMenu(); true }
                R.id.history_marks_item -> { showMarks(null); true }
                R.id.history_copy_item -> { selected?.let(::copy); true }
                R.id.history_delete_item -> { selected?.let(::requestDelete); true }
                else -> false
            }
        }
        styleMenu()
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        updateClearMenu()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onStop() {
        // Leaving the page ends the Undo window.
        handler.removeCallbacks(commitPending)
        settleDelete()
        snackbar?.dismiss()
        super.onStop()
    }

    override fun onDestroy() {
        marks?.dismiss()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        selected?.let { outState.putString(SELECTED_ID, it) }
        outState.putInt(SAVED_COUNT, visibleSavedCount)
        super.onSaveInstanceState(outState)
    }

    private companion object {
        const val SELECTED_ID = "history_selected_id"
        const val SAVED_COUNT = "history_visible_count"
    }

    private fun back() { if (selected != null) showList() else finish() }

    private fun <T> load(work: (HistoryStore) -> T, done: (Result<T>) -> Unit) {
        val request = ++generation
        inFlightLoads++
        updateClearMenu()
        HistoryIo.executor.execute {
            var storedHistoryCopies = false
            val result = runCatching {
                val store = HistoryIo.store(storeRoot)
                HistoryProcess.owner.remember(store.clearableIds())
                storedHistoryCopies = store.storedIds().isNotEmpty()
                work(store)
            }
            runOnUiThread {
                hasStoredHistoryCopies = storedHistoryCopies
                inFlightLoads = (inFlightLoads - 1).coerceAtLeast(0)
                updateClearMenu()
                if (!isFinishing && !isDestroyed && generation == request) done(result)
            }
        }
    }

    /** Reload without clearing what is on screen, so the list keeps its place and never flashes a loading state. */
    private fun refresh() {
        renderStatus()
        load({ it.list() }) { result ->
            result.fold({ list ->
                loadedOnce = true; readError = false; entries = list
                renderList()
                selected?.let { id -> if (detail == null) showDetail(id) else showDetail(id, keepMissingNote = true) }
            }, {
                loadedOnce = true; readError = true; entries = emptyList()
                renderList()
            })
        }
    }

    private fun showList() {
        selected = null
        removeDetail()
        renderList()
        refresh()
    }

    private fun visibleEntries() = entries.filterNot { undo.hidden(it.id) }

    private fun renderList() {
        val clear = HistoryIo.currentClear(storeRoot)
        val visible = visibleEntries()
        visibleSavedCount = visible.size
        rows.show(visible)
        rows.pendingClear = if (clear?.status == HistoryClearStatus.FAILED) clear.ids else emptySet()
        rows.notifyDataSetChanged()
        val clearing = submittedClearToken != null
        list.visibility = if (clearing) View.INVISIBLE else View.VISIBLE
        renderStatus()
        updateClearMenu()
    }

    private fun renderStatus() {
        val clear = HistoryIo.currentClear(storeRoot)
        val clearing = submittedClearToken != null || clear?.status == HistoryClearStatus.RUNNING
        val clearFailed = clear?.status == HistoryClearStatus.FAILED
        var action: Pair<Int, () -> Unit>? = null
        val message: Int? = when {
            clearing -> R.string.history_clear_busy
            readError -> { action = R.string.history_retry to { refresh() }; R.string.history_read_error }
            clearFailed -> { action = R.string.history_clear_retry to { retryClear() }; R.string.history_clear_error }
            !loadedOnce -> R.string.history_loading
            missing -> R.string.history_missing
            else -> null
        }
        empty.visibility = if (message == null && loadedOnce && visibleEntries().isEmpty() && detail == null) View.VISIBLE else View.GONE
        // The page's own state comes first (it says what the list shows now); a warning waits for the slot.
        val warning = if (message == null && detail == null) notices.pending().firstOrNull() else null
        status.visibility = if (message == null && warning == null) View.GONE else View.VISIBLE
        // A page state reads as a plain line; only a warning is a filled row.
        status.setCardBackgroundColor(if (warning != null) MaterialColors.getColor(status, com.google.android.material.R.attr.colorSecondaryContainer)
            else android.graphics.Color.TRANSPARENT)
        statusText.text = when {
            message != null -> getString(message)
            warning != null -> getString(warning.message())
            else -> ""
        }
        if (warning != null) action = R.string.history_notice_dismiss to {
            notices.dismiss(warning)
            renderStatus()
        }
        statusAction.visibility = if (action == null) View.GONE else View.VISIBLE
        statusAction.contentDescription = if (warning != null) getString(R.string.history_notice_dismiss) + ": " + getString(warning.message()) else null
        action?.let { (label, run) -> statusAction.setText(label); statusAction.setOnClickListener { run() } }
    }

    private fun removeDetail() {
        detail?.let(detailContainer::removeView)
        detail = null
        listPane.visibility = View.VISIBLE
        toolbar.title = getString(R.string.history_title)
        updateClearMenu()
    }

    private fun leaveDetailMissing() {
        selected = null
        missing = true
        removeDetail()
        renderList()
        refresh()
    }

    private fun showDetail(id: String, keepMissingNote: Boolean = false) {
        selected = id
        if (!keepMissingNote) missing = false
        if (isClearTarget(id)) { leaveDetailMissing(); return }
        val known = entries.firstOrNull { it.id == id }
        if (known != null) { bindDetail(known); return }
        if (!loadedOnce) return // refresh() will call back with the loaded list
        load({ it.get(id) }) { result ->
            result.fold({ entry ->
                if (entry == null || isClearTarget(id)) leaveDetailMissing() else bindDetail(entry)
            }, { selected = null; readError = true; removeDetail(); renderList() })
        }
    }

    private fun bindDetail(entry: HistoryEntry) {
        val id = entry.id
        val view = detail ?: layoutInflater.inflate(R.layout.history_detail, detailContainer, false).also {
            detailContainer.addView(it); detail = it
        }
        listPane.visibility = View.GONE
        toolbar.title = dayLabel(entry.timestamp)
        val marker = HistoryRows.marker(entry)
        val words = HistoryRows.words(entry.text)
        view.findViewById<TextView>(R.id.history_detail_meta).text = getString(R.string.history_detail_meta,
            HistoryRows.time(entry.timestamp), resources.getQuantityString(
                if (marker == HistoryMarker.RECORDING) R.plurals.history_words_so_far else R.plurals.history_words, words, words))
        view.findViewById<View>(R.id.history_detail_marker).apply {
            visibility = if (marker == null) View.GONE else View.VISIBLE
            if (marker != null) {
                findViewById<ImageView>(R.id.history_detail_marker_glyph).setImageResource(marker.glyph)
                findViewById<TextView>(R.id.history_detail_marker_text).setText(marker.line)
                contentDescription = getString(R.string.history_marks_open, getString(marker.line))
                setOnClickListener { showMarks(marker) }
            }
        }
        view.findViewById<TextView>(R.id.history_detail_text).text = entry.text
        renderStatus()
        updateClearMenu()
    }

    private fun copy(id: String) {
        if (isClearTarget(id)) { leaveDetailMissing(); return }
        load({ it.get(id) }) { current ->
            current.fold({ saved ->
                if (saved == null || isClearTarget(id)) { leaveDetailMissing(); return@fold }
                copyTranscript(this, saved.text)
            }, { selected = null; readError = true; removeDetail(); renderList() })
        }
    }

    /** No dialog: the row goes at once and Undo is offered; storage is touched when the window ends or the page is left. */
    private fun requestDelete(id: String) {
        handler.removeCallbacks(commitPending)
        undo.delete(id)?.let(::commitDelete)
        handler.postDelayed(commitPending, undoWindowMs)
        if (selected == id) { selected = null; removeDetail() }
        renderList()
        snackbar?.dismiss()
        snackbar = Snackbar.make(root, R.string.history_deleted, undoWindowMs.toInt().coerceAtLeast(1))
            .setAction(R.string.dictionary_undo) { undoDelete() }.also { it.show() }
    }

    private fun undoDelete() {
        handler.removeCallbacks(commitPending)
        undo.undo()
        renderList()
    }

    private fun settleDelete() {
        handler.removeCallbacks(commitPending)
        undo.settle()?.let(::commitDelete)
    }

    private fun commitDelete(id: String) {
        val app = applicationContext
        val rootDir = storeRoot
        HistoryIo.executor.execute {
            val removed = runCatching { HistoryIo.store(rootDir).delete(id) }.isSuccess
            if (!removed) historyNotices(app).record(HistoryNotice.DELETE)
            runOnUiThread {
                undo.finished(id)
                if (!isDestroyed) { if (removed) entries = entries.filterNot { it.id == id }; renderList(); refresh() }
            }
        }
    }

    private fun updateClearMenu() {
        if (!::toolbar.isInitialized) return
        val operation = HistoryIo.currentClear(storeRoot)
        val retrying = operation?.status == HistoryClearStatus.FAILED
        val busy = inFlightLoads > 0 || operation?.status == HistoryClearStatus.RUNNING
        val hasHistoryTargets = visibleSavedCount > 0 || hasStoredHistoryCopies || HistoryProcess.owner.activeIds().isNotEmpty()
        toolbar.menu.findItem(R.id.history_clear_item)?.apply {
            isVisible = detail == null
            title = clearTitle(getString(if (retrying) R.string.history_clear_retry else R.string.history_clear))
            isEnabled = if (retrying) !busy else !busy && hasHistoryTargets
        }
        toolbar.menu.findItem(R.id.history_marks_item)?.isVisible = detail == null
        // Copy and Delete are the detail's icon actions; the list has its own per-row gestures.
        toolbar.menu.findItem(R.id.history_copy_item)?.isVisible = detail != null
        toolbar.menu.findItem(R.id.history_delete_item)?.isVisible = detail != null
    }

    private fun onClearMenu() {
        when (HistoryIo.currentClear(storeRoot)?.status) {
            HistoryClearStatus.FAILED -> { retryClear(); return }
            HistoryClearStatus.RUNNING -> return
            else -> Unit
        }
        val activeCount = HistoryProcess.owner.activeIds().size
        if (visibleSavedCount == 0 && !hasStoredHistoryCopies && HistoryProcess.owner.activeIds().isEmpty()) { updateClearMenu(); return }
        MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_Dictation_Dialog_Destructive)
            .setTitle(R.string.history_clear_title)
            .setMessage(clearConfirmation(visibleSavedCount, activeCount))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.history_clear_action) { _, _ ->
                settleDelete()
                // The synchronized snapshot is the acceptance boundary. Later registrations
                // update the owner inventory but cannot expand this immutable operation set.
                val acceptedIds = HistoryProcess.owner.clearSnapshot()
                if (acceptedIds.isEmpty()) { showList(); return@setPositiveButton }
                val operation = HistoryIo.acceptClear(storeRoot, acceptedIds) ?: return@setPositiveButton
                executeClear(operation)
            }.show()
    }

    /** "Deletes 4 saved transcripts and 1 take in progress. Clipboard copies and benchmark recordings stay." */
    private fun clearConfirmation(saved: Int, active: Int): String {
        val savedPart = resources.getQuantityString(R.plurals.history_clear_saved, saved, saved)
        val activePart = resources.getQuantityString(R.plurals.history_clear_active, active, active)
        val deletes = when {
            saved > 0 && active > 0 -> getString(R.string.history_clear_both, savedPart, activePart)
            saved > 0 -> savedPart
            active > 0 -> activePart
            else -> getString(R.string.history_clear_copies)
        }
        return getString(R.string.history_clear_confirmation, deletes)
    }

    private fun retryClear() {
        val operation = HistoryIo.retryClear(storeRoot) ?: return
        executeClear(operation)
    }

    private fun executeClear(operation: HistoryClearOperation) {
        if (submittedClearToken == operation.token || operation.status != HistoryClearStatus.RUNNING) return
        submittedClearToken = operation.token
        selected = null
        removeDetail()
        renderList()
        HistoryIo.executor.execute {
            val succeeded = runCatching { HistoryIo.store(storeRoot).clear(operation.ids) }.isSuccess
            HistoryIo.finishClear(storeRoot, operation.token, succeeded)
            runOnUiThread {
                if (submittedClearToken == operation.token) submittedClearToken = null
                if (!isFinishing && !isDestroyed) { renderList(); refresh() }
            }
        }
    }

    private fun isClearTarget(id: String): Boolean = HistoryIo.currentClear(storeRoot)?.ids?.contains(id) == true

    private fun dayLabel(timestamp: Long): String = when (HistoryRows.day(timestamp)) {
        HistoryDay.Today -> getString(R.string.history_today)
        HistoryDay.Yesterday -> getString(R.string.history_yesterday)
        is HistoryDay.Date -> HistoryRows.dayText(timestamp)
    }

    /** Overflow icons shown (the marks sheet's info, the error-coloured Clear); Delete's 16dp gap. */
    @SuppressLint("RestrictedApi")
    private fun styleMenu() {
        (toolbar.menu as? androidx.appcompat.view.menu.MenuBuilder)?.setOptionalIconsVisible(true)
        toolbar.overflowIcon = androidx.appcompat.content.res.AppCompatResources.getDrawable(toolbar.context, R.drawable.ic_more_vert)
        val error = MaterialColors.getColor(toolbar, androidx.appcompat.R.attr.colorError)
        toolbar.menu.findItem(R.id.history_clear_item)?.apply {
            icon?.mutate()?.setTint(error)
        }
        toolbar.menu.findItem(R.id.history_delete_item)?.actionView?.findViewById<View>(R.id.history_delete_action)
            ?.setOnClickListener { selected?.let(::requestDelete) }
    }

    private fun clearTitle(text: CharSequence) = SpannableString(text).also {
        it.setSpan(ForegroundColorSpan(MaterialColors.getColor(toolbar, androidx.appcompat.R.attr.colorError)), 0, it.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    /** "Marks explained": every mark with its one sentence; [highlight] is the mark the detail line named. */
    private fun showMarks(highlight: HistoryMarker?) {
        val dialog = BottomSheetDialog(this)
        val sheet = layoutInflater.inflate(R.layout.history_marks, null)
        val rowsView = sheet.findViewById<LinearLayout>(R.id.history_marks_rows)
        var focus: View? = null
        for (mark in HistoryMarker.entries.map { it as HistoryMarker? } + null) {
            val row = layoutInflater.inflate(R.layout.history_mark_row, rowsView, false)
            val glyph = row.findViewById<ImageView>(R.id.history_mark_glyph)
            if (mark != null) glyph.setImageResource(mark.glyph) else glyph.visibility = View.INVISIBLE
            row.findViewById<TextView>(R.id.history_mark_title).setText(mark?.title ?: R.string.marker_none_title)
            row.findViewById<TextView>(R.id.history_mark_sentence).setText(mark?.sentence ?: R.string.marker_none_sentence)
            val body = row.findViewById<View>(R.id.history_mark_body)
            if (mark != null && mark == highlight) {
                body.setBackgroundResource(R.drawable.mark_highlight)
                body.isSelected = true
                focus = body
            }
            rowsView.addView(row)
        }
        sheet.findViewById<View>(R.id.history_marks_close).setOnClickListener { dialog.dismiss() }
        dialog.setContentView(sheet)
        dialog.window?.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        dialog.setOnShowListener {
            dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
            dialog.behavior.skipCollapsed = true
            focus?.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED)
        }
        marks = dialog
        dialog.show()
    }

    private sealed interface Item {
        data object Header : Item
        data class Day(val label: String) : Item
        data class Row(val entry: HistoryEntry) : Item
    }

    /** Item 0 is the explanation, so it scrolls away and the first row is on screen at large font; rows sit under their day. */
    private inner class RowsAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        var items: List<Item> = listOf(Item.Header)
        var pendingClear = emptySet<String>()
        fun show(entries: List<HistoryEntry>) {
            val next = mutableListOf<Item>(Item.Header)
            var day: String? = null
            for (entry in entries) {
                val label = dayLabel(entry.timestamp)
                if (label != day) { next.add(Item.Day(label)); day = label }
                next.add(Item.Row(entry))
            }
            items = next
        }
        inner class Header(view: View) : RecyclerView.ViewHolder(view)
        inner class Day(view: View) : RecyclerView.ViewHolder(view) { val label: TextView = view.findViewById(R.id.history_day) }
        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val body: View = view.findViewById(R.id.history_row_body)
            val time: TextView = view.findViewById(R.id.history_row_time)
            val marker: ImageView = view.findViewById(R.id.history_row_marker)
            val preview: TextView = view.findViewById(R.id.history_row_preview)
            val delete: View = view.findViewById(R.id.history_row_delete)
        }
        override fun getItemViewType(position: Int) = when (items[position]) { Item.Header -> 0; is Item.Day -> 2; is Item.Row -> 1 }
        override fun onCreateViewHolder(parent: ViewGroup, type: Int): RecyclerView.ViewHolder = when (type) {
            0 -> Header(layoutInflater.inflate(R.layout.history_list_header, parent, false))
            2 -> Day(layoutInflater.inflate(R.layout.history_day, parent, false))
            else -> Holder(layoutInflater.inflate(R.layout.history_row, parent, false))
        }
        override fun getItemCount() = items.size
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                Item.Header -> return
                is Item.Day -> { (holder as Day).label.text = item.label; return }
                is Item.Row -> bindRow(holder as Holder, item.entry)
            }
        }
        private fun bindRow(holder: Holder, entry: HistoryEntry) {
            val clearPending = entry.id in pendingClear
            val date = HistoryRows.date(entry.timestamp)
            val time = HistoryRows.time(entry.timestamp)
            val marker = if (clearPending) null else HistoryRows.marker(entry)
            val text = if (clearPending) getString(R.string.history_clear_pending) else entry.text.replace('\n', ' ').take(200)
            holder.time.text = time
            holder.preview.text = text
            holder.marker.visibility = if (marker == null) View.GONE else View.VISIBLE
            marker?.let { holder.marker.setImageResource(it.glyph) }
            // One node: time, preview, then the mark's one sentence (the same words as the detail line).
            holder.body.contentDescription = listOfNotNull(getString(R.string.history_row_description, time, text),
                marker?.let { getString(it.line) }).joinToString(", ")
            holder.body.setOnClickListener { showDetail(entry.id) }
            // Long-press copies (the framework adds the long-press haptic when the listener handles it).
            holder.body.setOnLongClickListener { if (clearPending) false else { copy(entry.id); true } }
            // Touch and hold is invisible to a screen reader, so Copy and Delete are named actions too.
            ViewCompat.replaceAccessibilityAction(holder.body, AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_LONG_CLICK,
                getString(R.string.history_copy_transcript)) { _, _ -> if (clearPending) false else { copy(entry.id); true } }
            ViewCompat.addAccessibilityAction(holder.body, getString(R.string.history_delete_transcript)) { _, _ ->
                if (clearPending) false else { requestDelete(entry.id); true }
            }
            val words = if (clearPending) "" else HistoryRows.previewWords(entry.text)
            holder.delete.contentDescription = getString(R.string.history_delete_row, date, words).trimEnd(' ', ':')
            holder.delete.isEnabled = !clearPending
            holder.delete.setOnClickListener { requestDelete(entry.id) }
        }
    }
}
