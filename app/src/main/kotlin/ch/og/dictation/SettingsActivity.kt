package ch.og.dictation

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.color.DynamicColors
import android.app.AlertDialog
import android.os.Bundle
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import android.widget.LinearLayout

/** Reached from Android's keyboard list (method.xml settingsActivity). */
open class SettingsActivity : AppCompatActivity() {
    private val store by lazy { BenchmarkStore.of(this) }
    private var stored: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.settings)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.settings_root)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val padding = (24 * resources.displayMetrics.density).toInt()
            view.setPadding(padding + bars.left, padding + bars.top, padding + bars.right, padding + bars.bottom)
            insets
        }
        findViewById<Button>(R.id.open_dictionary).setOnClickListener {
            startActivity(Intent(this, DictionaryActivity::class.java))
        }
        findViewById<Button>(R.id.open_history).setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
        stored = findViewById(R.id.benchmark_stored)
        findViewById<Switch>(R.id.benchmark_switch).apply {
            isChecked = BenchmarkStore.isEnabled(this@SettingsActivity)
            setOnCheckedChangeListener { _, on -> BenchmarkStore.setEnabled(this@SettingsActivity, on) }
        }
        findViewById<Button>(R.id.benchmark_delete).setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage(R.string.benchmark_delete_confirm)
                .setPositiveButton(R.string.benchmark_delete) { _, _ ->
                    if (!store.deleteAll()) stored?.setText(R.string.benchmark_delete_failed) else showStored()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    override fun onResume() { super.onResume(); showStored(); showHistoryNotices() }

    private fun showHistoryNotices() {
        val container = findViewById<LinearLayout>(R.id.history_notices)
        container.removeAllViews()
        val notices = historyNotices(this)
        notices.pending().forEach { notice ->
            container.addView(TextView(this).apply {
                setText(notice.message())
                accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
            })
            container.addView(Button(this).apply {
                setText(R.string.history_notice_dismiss)
                contentDescription = getString(R.string.history_notice_dismiss) + ": " + getString(notice.message())
                minHeight = (48 * resources.displayMetrics.density).toInt()
                setOnClickListener { notices.dismiss(notice); showHistoryNotices() }
            })
        }
    }

    private fun showStored() {
        stored?.text = getString(R.string.benchmark_stored, store.takeCount())
    }
}
