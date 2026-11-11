package ch.og.dictation

import android.content.Intent
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.UnderlineSpan
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import java.util.concurrent.Executors

/** Reached from Android's keyboard list (method.xml settingsActivity). */
open class SettingsActivity : AppCompatActivity() {
    private val store by lazy { BenchmarkStore.of(this) }
    private var stored: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.settings)
        // The shared toolbar structure: bars inset the whole page; content keeps the 24dp text inset.
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.settings_root)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        findViewById<View>(R.id.open_dictionary).setOnClickListener {
            startActivity(Intent(this, DictionaryActivity::class.java))
        }
        findViewById<View>(R.id.open_history).setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
        stored = findViewById(R.id.benchmark_stored)
        findViewById<MaterialSwitch>(R.id.benchmark_switch).apply {
            isChecked = BenchmarkStore.isEnabled(this@SettingsActivity)
            setOnCheckedChangeListener { _, on -> BenchmarkStore.setEnabled(this@SettingsActivity, on) }
        }
        findViewById<MaterialButton>(R.id.benchmark_delete).apply {
            text = SpannableString(getString(R.string.benchmark_delete)).also {
                it.setSpan(UnderlineSpan(), 0, it.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            setOnClickListener {
                MaterialAlertDialogBuilder(this@SettingsActivity)
                    .setTitle(R.string.benchmark_delete_title)
                    .setMessage(R.string.benchmark_delete_confirm)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.benchmark_delete) { _, _ ->
                        if (!store.deleteAll()) stored?.setText(R.string.benchmark_delete_failed) else showStored()
                    }
                    .show()
            }
        }
    }

    override fun onResume() { super.onResume(); showStored(); showHistoryCount(); showDictionaryCount() }

    protected open fun historyRoot() = historyRoot(this)

    /** The History row shows only how many transcripts are saved; warnings live on the History page. */
    private fun showHistoryCount() {
        val row = findViewById<View>(R.id.open_history)
        val countView = findViewById<TextView>(R.id.settings_history_count)
        val root = historyRoot()
        HistoryIo.executor.execute {
            val count = runCatching { HistoryIo.store(root).savedCount() }.getOrDefault(0)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                countView.text = resources.getQuantityString(R.plurals.settings_history_count, count, count)
                countView.visibility = if (count > 0) View.VISIBLE else View.GONE
                row.contentDescription = if (count > 0) getString(R.string.history_row_with_count, getString(R.string.history_title), count)
                    else getString(R.string.history_title)
            }
        }
    }

    /** The Dictionary row shows its term count, read off the main thread; no terms are kept here. */
    private fun showDictionaryCount() {
        val countView = findViewById<TextView>(R.id.settings_dictionary_count)
        val app = applicationContext
        counts.execute {
            val count = runCatching { Engine.dictionaryStore(app).read().entries.size }.getOrNull()
            runOnUiThread {
                if (isDestroyed || count == null) return@runOnUiThread
                countView.text = resources.getQuantityString(R.plurals.dictionary_count, count, count)
                countView.visibility = View.VISIBLE
            }
        }
    }

    private fun showStored() {
        val count = store.takeCount()
        stored?.text = resources.getQuantityString(R.plurals.benchmark_stored, count, count)
    }

    private companion object { val counts = Executors.newSingleThreadExecutor() }
}
