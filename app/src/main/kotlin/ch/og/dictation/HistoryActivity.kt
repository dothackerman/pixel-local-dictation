package ch.og.dictation

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.DynamicColors
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
    private lateinit var state: TextView
    private lateinit var stateAction: MaterialButton
    private lateinit var notice: View
    private lateinit var noticeText: TextView
    private lateinit var detailContainer: ViewGroup
    private val rows = RowsAdapter()
    private val undo = HistoryUndo()
    private val handler = Handler(Looper.getMainLooper())
    private val commitPending = Runnable { settleDelete() }
    private val notices by lazy { historyNotices(this) }
    private var snackbar: Snackbar? = null
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
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.history)
        root = findViewById(R.id.history_root)
        toolbar = findViewById(R.id.history_toolbar)
        listPane = findViewById(R.id.history_list_pane)
        empty = findViewById(R.id.history_empty)
        state = findViewById(R.id.history_state)
        stateAction = findViewById(R.id.history_state_action)
        notice = findViewById(R.id.history_notice)
        noticeText = findViewById(R.id.history_notice_text)
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
            if (item.itemId == R.id.history_clear_item) { onClearMenu(); true } else false
        }
        findViewById<View>(R.id.history_notice_dismiss).setOnClickListener {
            notices.pending().firstOrNull()?.let(notices::dismiss)
            renderStatus()
        }
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
        rows.items = visible
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
        state.visibility = if (message == null) View.GONE else View.VISIBLE
        message?.let(state::setText)
        stateAction.visibility = if (action == null) View.GONE else View.VISIBLE
        action?.let { (label, run) -> stateAction.setText(label); stateAction.setOnClickListener { run() } }
        empty.visibility = if (message == null && loadedOnce && visibleEntries().isEmpty() && detail == null) View.VISIBLE else View.GONE
        val pending = notices.pending().firstOrNull()
        notice.visibility = if (pending == null || detail != null) View.GONE else View.VISIBLE
        if (pending != null) {
            noticeText.setText(pending.message())
            findViewById<View>(R.id.history_notice_dismiss).contentDescription =
                getString(R.string.history_notice_dismiss) + ": " + getString(pending.message())
        }
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
        toolbar.title = HistoryRows.date(entry.timestamp)
        view.findViewById<TextView>(R.id.history_detail_meta).text = completionLabel(entry.completion) + " · " + deliveryLabel(entry.delivery)
        view.findViewById<TextView>(R.id.history_detail_text).text = entry.text
        view.findViewById<View>(R.id.history_copy_button).setOnClickListener { copy(id) }
        view.findViewById<View>(R.id.history_delete_button).setOnClickListener { requestDelete(id) }
        renderStatus()
        updateClearMenu()
    }

    private fun copy(id: String) {
        if (isClearTarget(id)) { leaveDetailMissing(); return }
        load({ it.get(id) }) { current ->
            current.fold({ saved ->
                if (saved == null || isClearTarget(id)) { leaveDetailMissing(); return@fold }
                val clip = ClipData.newPlainText("Transcript", saved.text)
                clip.description.extras = android.os.PersistableBundle().apply {
                    putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                }
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
                snackbar?.dismiss()
                snackbar = Snackbar.make(root, R.string.history_copied, Snackbar.LENGTH_SHORT).also { it.show() }
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
            setTitle(if (retrying) R.string.history_clear_retry else R.string.history_clear)
            isEnabled = if (retrying) !busy else !busy && hasHistoryTargets
        }
    }

    private fun onClearMenu() {
        when (HistoryIo.currentClear(storeRoot)?.status) {
            HistoryClearStatus.FAILED -> { retryClear(); return }
            HistoryClearStatus.RUNNING -> return
            else -> Unit
        }
        val activeCount = HistoryProcess.owner.activeIds().size
        if (visibleSavedCount == 0 && !hasStoredHistoryCopies && HistoryProcess.owner.activeIds().isEmpty()) { updateClearMenu(); return }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.history_clear_title)
            .setMessage(getString(R.string.history_clear_confirmation, visibleSavedCount, activeCount))
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

    private fun completionLabel(completion: HistoryCompletion): String = getString(when (completion) {
        HistoryCompletion.RECORDING -> R.string.history_recording
        HistoryCompletion.FINISHING -> R.string.history_finishing
        HistoryCompletion.COMPLETE -> R.string.history_complete
        HistoryCompletion.INTERRUPTED -> R.string.history_interrupted
    })
    private fun deliveryLabel(delivery: HistoryDelivery): String = getString(when (delivery) {
        HistoryDelivery.UNKNOWN -> R.string.history_delivery_unknown
        HistoryDelivery.NOT_ATTEMPTED -> R.string.history_delivery_not_attempted
        HistoryDelivery.REJECTED -> R.string.history_delivery_rejected
        HistoryDelivery.ACCEPTED -> R.string.history_delivery_accepted
    })
    private fun statusLabel(status: HistoryRowStatus): String = getString(when (status) {
        HistoryRowStatus.RECORDING -> R.string.history_recording
        HistoryRowStatus.FINISHING -> R.string.history_finishing
        HistoryRowStatus.MAY_BE_INCOMPLETE -> R.string.history_status_incomplete
        HistoryRowStatus.NOT_INSERTED -> R.string.history_delivery_rejected
        HistoryRowStatus.INSERTION_UNCERTAIN -> R.string.history_status_uncertain
    })

    private inner class RowsAdapter : RecyclerView.Adapter<RowsAdapter.Holder>() {
        var items = emptyList<HistoryEntry>()
        var pendingClear = emptySet<String>()
        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val body: View = view.findViewById(R.id.history_row_body)
            val preview: TextView = view.findViewById(R.id.history_row_preview)
            val meta: TextView = view.findViewById(R.id.history_row_meta)
            val delete: View = view.findViewById(R.id.history_row_delete)
        }
        override fun onCreateViewHolder(parent: ViewGroup, type: Int) =
            Holder(layoutInflater.inflate(R.layout.history_row, parent, false))
        override fun getItemCount() = items.size
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val entry = items[position]
            val clearPending = entry.id in pendingClear
            val date = HistoryRows.date(entry.timestamp)
            holder.preview.text = if (clearPending) getString(R.string.history_clear_pending) else entry.text.replace('\n', ' ').take(200)
            holder.meta.text = if (clearPending) date
                else (listOf(date) + HistoryRows.statuses(entry).map(::statusLabel)).joinToString(" · ")
            holder.body.setOnClickListener { showDetail(entry.id) }
            holder.delete.contentDescription = getString(R.string.history_delete_row, date)
            holder.delete.isEnabled = !clearPending
            holder.delete.setOnClickListener { requestDelete(entry.id) }
        }
    }
}
