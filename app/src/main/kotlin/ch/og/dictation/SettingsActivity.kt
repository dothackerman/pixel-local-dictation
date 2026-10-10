package ch.og.dictation

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.widget.Button
import android.widget.Switch
import android.widget.TextView

/** Reached from Android's keyboard list (method.xml settingsActivity). */
class SettingsActivity : Activity() {
    private val store by lazy { BenchmarkStore.of(this) }
    private var stored: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.settings)
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

    override fun onResume() { super.onResume(); showStored() }

    private fun showStored() {
        stored?.text = getString(R.string.benchmark_stored, store.takeCount())
    }
}
