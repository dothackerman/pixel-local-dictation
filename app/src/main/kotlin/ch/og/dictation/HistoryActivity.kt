package ch.og.dictation

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.DateFormat
import java.util.Date

/** Text is kept out of saved view state, intents and task previews. */
open class HistoryActivity : AppCompatActivity() {
    private lateinit var content: LinearLayout
    private lateinit var toolbar: MaterialToolbar
    private var clearMenuItem: MenuItem? = null
    private var selected: String? = null
    private var generation = 0
    private var inFlightLoads = 0
    private var visibleSavedCount = 0
    private var hasStoredHistoryCopies = false
    private var submittedClearToken: Long? = null
    protected open fun historyRoot() = java.io.File(noBackupFilesDir, "history")
    private val root by lazy { historyRoot() }

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { back() }
        })
        selected = savedInstanceState?.getString(SELECTED_ID)
        visibleSavedCount = savedInstanceState?.getInt(SAVED_COUNT) ?: 0
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; isSaveEnabled = false }
        toolbar = MaterialToolbar(this).apply {
            title = getString(R.string.history_title)
            setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            navigationContentDescription = getString(R.string.history_back)
            setNavigationOnClickListener { back() }
            clearMenuItem = menu.add(Menu.NONE, MENU_CLEAR, Menu.NONE, R.string.history_clear).apply {
                setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            }
            setOnMenuItemClickListener { item ->
                if (item.itemId == MENU_CLEAR) { onClearMenu(); true } else false
            }
        }
        outer.addView(toolbar)
        val scroll = ScrollView(this).apply { isSaveEnabled = false; isFillViewport = true }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isSaveEnabled = false
            val p = dp(24); setPadding(p, p, p, p)
        }
        scroll.addView(content)
        outer.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(outer)
        ViewCompat.setOnApplyWindowInsetsListener(outer) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        updateClearMenu()
    }

    override fun onResume() {
        super.onResume()
        selected?.let { showDetail(it) } ?: showList()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        selected?.let { outState.putString(SELECTED_ID, it) }
        outState.putInt(SAVED_COUNT, visibleSavedCount)
        super.onSaveInstanceState(outState)
    }

    private companion object {
        const val SELECTED_ID = "history_selected_id"
        const val SAVED_COUNT = "history_visible_count"
        const val MENU_CLEAR = 0x48535443
    }

    private fun back() { if (selected != null) showList() else finish() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun label(text: CharSequence): TextView = TextView(this).apply {
        this.text = text; setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
        isSaveEnabled = false; setTextIsSelectable(false)
    }
    private fun button(text: CharSequence, action: () -> Unit): MaterialButton = MaterialButton(this).apply {
        this.text = text; minHeight = dp(48); isSaveEnabled = false; setOnClickListener { action() }
    }
    private fun reset(message: Int) {
        content.setOnClickListener(null)
        content.removeAllViews()
        content.addView(label(getString(message)).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE })
    }
    private fun <T> load(work: (HistoryStore) -> T, done: (Result<T>) -> Unit) {
        val request = ++generation
        inFlightLoads++
        updateClearMenu()
        HistoryIo.executor.execute {
            var storedHistoryCopies = false
            val result = runCatching {
                val store = HistoryIo.store(root)
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
    private fun showList() {
        selected = null
        visibleSavedCount = 0
        reset(R.string.history_loading)
        load({ it.list() }) { result ->
            result.fold({ entries ->
                visibleSavedCount = entries.size
                content.removeAllViews()
                content.addView(label(getString(R.string.history_explanation)))
                if (entries.isEmpty()) content.addView(label(getString(R.string.history_empty)))
                val clear = HistoryIo.currentClear(root)
                if (clear?.status == HistoryClearStatus.FAILED) {
                    content.addView(label(getString(R.string.history_clear_error)).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE })
                    content.addView(button(getString(R.string.history_clear_retry)) { retryClear() })
                }
                entries.forEach { entry ->
                    val date = DateFormat.getDateTimeInstance().format(Date(entry.timestamp))
                    val clearPending = clear?.status == HistoryClearStatus.FAILED && entry.id in clear.ids
                    val preview = if (clearPending) getString(R.string.history_clear_pending)
                        else entry.text.replace('\n', ' ').take(100)
                    val row = if (clearPending) "$date\n$preview"
                        else "$date\n${completionLabel(entry.completion)} · ${deliveryLabel(entry.delivery)}\n$preview"
                    content.addView(button(row) { showDetail(entry.id) })
                }
                updateClearMenu()
            }, {
                visibleSavedCount = 0
                updateClearMenu()
                reset(R.string.history_read_error)
                if (HistoryIo.currentClear(root)?.status == HistoryClearStatus.FAILED) {
                    content.addView(button(getString(R.string.history_clear_retry)) { retryClear() })
                }
                content.setOnClickListener { content.setOnClickListener(null); showList() }
            })
        }
    }
    private fun showDetail(id: String) {
        selected = id
        if (isClearTarget(id)) { reset(R.string.history_missing); return }
        reset(R.string.history_loading)
        load({ it.get(id) }) { result ->
            result.fold({ entry ->
                if (entry == null || isClearTarget(id)) { reset(R.string.history_missing); return@fold }
                content.removeAllViews()
                content.addView(label(completionLabel(entry.completion)))
                content.addView(label(deliveryLabel(entry.delivery)))
                content.addView(label(entry.text))
                content.addView(button(getString(R.string.history_copy)) {
                    if (isClearTarget(id)) { reset(R.string.history_missing); return@button }
                    load({ it.get(id) }) { current ->
                        current.fold({ saved ->
                            if (saved == null || isClearTarget(id)) { reset(R.string.history_missing); return@fold }
                            val clip = ClipData.newPlainText("Transcript", saved.text)
                            clip.description.extras = android.os.PersistableBundle().apply {
                                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                            }
                            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
                            android.widget.Toast.makeText(this, R.string.history_copied, android.widget.Toast.LENGTH_SHORT).show()
                        }, { reset(R.string.history_read_error); content.setOnClickListener { showDetail(id) } })
                    }
                })
                content.addView(button(getString(R.string.history_delete)) { confirmDelete(id) })
            }, { reset(R.string.history_read_error); content.setOnClickListener { content.setOnClickListener(null); showDetail(id) } })
        }
    }
    private fun confirmDelete(id: String) {
        MaterialAlertDialogBuilder(this).setMessage(R.string.history_delete_confirm)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.history_delete) { _, _ ->
                reset(R.string.history_loading)
                load({ it.delete(id) }) { result ->
                    result.fold({ showList() }, { showDetailAfterDeleteError(id) })
                }
            }.show()
    }

    private fun updateClearMenu() {
        if (!::toolbar.isInitialized) return
        val operation = HistoryIo.currentClear(root)
        val retrying = operation?.status == HistoryClearStatus.FAILED
        val busy = inFlightLoads > 0 || operation?.status == HistoryClearStatus.RUNNING
        val hasHistoryTargets = visibleSavedCount > 0 || hasStoredHistoryCopies || HistoryProcess.owner.activeIds().isNotEmpty()
        clearMenuItem?.apply {
            setTitle(if (retrying) R.string.history_clear_retry else R.string.history_clear)
            isEnabled = if (retrying) !busy else !busy && hasHistoryTargets
        }
    }

    private fun onClearMenu() {
        when (HistoryIo.currentClear(root)?.status) {
            HistoryClearStatus.FAILED -> { retryClear(); return }
            HistoryClearStatus.RUNNING -> return
            else -> Unit
        }
        val activeCount = HistoryProcess.owner.activeIds().size
        if (visibleSavedCount == 0 && !hasStoredHistoryCopies && HistoryProcess.owner.activeIds().isEmpty()) { updateClearMenu(); return }
        MaterialAlertDialogBuilder(this).setMessage(
            getString(R.string.history_clear_confirmation, visibleSavedCount, activeCount)
        ).setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.history_clear) { _, _ ->
                // The synchronized snapshot is the acceptance boundary. Later registrations
                // update the owner inventory but cannot expand this immutable operation set.
                val acceptedIds = HistoryProcess.owner.clearSnapshot()
                if (acceptedIds.isEmpty()) { showList(); return@setPositiveButton }
                val operation = HistoryIo.acceptClear(root, acceptedIds) ?: return@setPositiveButton
                executeClear(operation)
            }.show()
    }

    private fun retryClear() {
        val operation = HistoryIo.retryClear(root) ?: return
        executeClear(operation)
    }

    private fun executeClear(operation: HistoryClearOperation) {
        if (submittedClearToken == operation.token || operation.status != HistoryClearStatus.RUNNING) return
        submittedClearToken = operation.token
        selected = null
        reset(R.string.history_clear_busy)
        updateClearMenu()
        HistoryIo.executor.execute {
            val succeeded = runCatching { HistoryIo.store(root).clear(operation.ids) }.isSuccess
            HistoryIo.finishClear(root, operation.token, succeeded)
            runOnUiThread {
                if (submittedClearToken == operation.token) submittedClearToken = null
                if (!isFinishing && !isDestroyed) showList()
            }
        }
    }

    private fun isClearTarget(id: String): Boolean = HistoryIo.currentClear(root)?.ids?.contains(id) == true

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
    private fun showDetailAfterDeleteError(id: String) {
        showDetail(id)
        android.widget.Toast.makeText(this, R.string.history_delete_error, android.widget.Toast.LENGTH_LONG).show()
    }
}
