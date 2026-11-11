package ch.og.dictation

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputConnection
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.CheckBox
import android.widget.Spinner
import android.widget.ArrayAdapter
import java.util.concurrent.CountDownLatch

/** Owner-operated host. Android recreates it normally on configuration changes. */
class RotationProbeActivity : Activity() {
    companion object {
        @Volatile var done = CountDownLatch(1)
        @Volatile var result = "skipped"
        @Volatile var selected = 0
        val cases = listOf(
            "app-capture-rotation", "app-finishing-rotation", "app-restart", "app-focus", "app-hide", "app-lock",
            "signal-capture-rotation", "signal-finishing-rotation", "signal-controls", "privacy-password", "privacy-no-learning", "privacy-ordinary",
            "history-copy-delete", "accessibility"
        )
    }

    @SuppressLint("AppCompatCustomView") // Plain Activity deliberately uses platform widgets.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_SECURE)
        if (intent.getBooleanExtra("welcome", false)) {
            val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
            column.addView(TextView(this).apply { text = "Guided visit\nChoose a case to start or resume. Your observations are recorded as metadata only." })
            val picker = Spinner(this).apply { adapter = ArrayAdapter(this@RotationProbeActivity, android.R.layout.simple_spinner_dropdown_item, cases.map { it.replace("-", " ").replaceFirstChar { c -> c.uppercase() } }); setSelection(selected) }
            column.addView(picker)
            column.addView(Button(this).apply { text = "Start"; setOnClickListener { selected = picker.selectedItemPosition; result = "start"; finish() } })
            setContentView(column)
            return
        }
        val caseName = requireNotNull(intent.getStringExtra("case"))
        val guided = intent.getBooleanExtra("guided", false)
        require(caseName in RotationTrace.allowedCases || caseName in cases)
        val instruction = when (caseName) {
            "signal-capture-rotation" -> "In Signal, start dictation and rotate during capture. Return here and tap Done."
            "signal-finishing-rotation" -> "In Signal, start dictation, tap Stop, and rotate while waiting for text. Return here and tap Done."
            "app-capture-rotation" -> "Start dictation in an ordinary field and rotate during capture. Tap Done."
            "app-finishing-rotation" -> "Start dictation in an ordinary field, tap Stop, and rotate while waiting for text. Tap Done."
            "app-restart" -> "Start dictation in an ordinary field, then tap Restart focused input. Tap Done."
            "app-focus" -> "Start dictation in one ordinary field, then switch to the other. Tap Done."
            "app-hide" -> "Start dictation in an ordinary field, then hide the keyboard. Tap Done."
            "app-lock" -> "Start dictation in an ordinary field, then lock and unlock the phone. Tap Done."
            "signal-controls" -> "In Signal, try cancellation during recording: Continue, Finish and Discard. Then for two more takes, tap Stop, tap Cancel/Keyboard while Finishing is visible, wait for decoding, and choose Keep finishing once and Discard once. Return here and check what you observed."
            "history-copy-delete" -> "Open synthetic History below, copy the synthetic row, then delete that same row. Return here and check what you observed."
            "accessibility" -> "Check available spoken labels and controls. Record an issue or skip if unavailable."
            "privacy-ordinary" -> "Start dictation in an ordinary field, speak a harmless test phrase, tap Stop, and wait for completion. Tap Done."
            "privacy-password" -> "Start dictation in the password field, speak a harmless test phrase, tap Stop, and wait for completion. Tap Done."
            else -> "Start dictation in the no-learning field, speak a harmless test phrase, tap Stop, and wait for completion. Tap Done."
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // An autofill suggestion can replace the keyboard's pinned mic. These fields use synthetic input.
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        val cue = when {
            !guided -> instruction
            caseName.endsWith("capture-rotation") ->
                "Speak a short phrase, rotate while recording, then speak again. Did the same recording continue?"
            caseName.endsWith("finishing-rotation") ->
                "Speak a phrase, tap Stop, and immediately rotate while Finishing is visible. Did finishing continue? If the text was already inserted before rotation, choose Uncertain."
            else -> instruction
        }
        val host = if (caseName.startsWith("signal-")) "Use an unsent Signal draft. Return here through Recents.\n" else ""
        val title = caseName.replace("-", " ").replaceFirstChar { it.uppercase() }
        column.addView(TextView(this).apply {
            text = (if (guided) "${selected + 1}/${cases.size}  " else "") + title + "\n" + host + cue +
                "\nHide the keyboard before recording your observation."
        })
        val ordinaryCount = when {
            caseName == "app-focus" -> 2
            caseName.startsWith("app-") || caseName == "privacy-ordinary" -> 1
            else -> 0
        }
        val ordinary = List(ordinaryCount) { index ->
            object : EditText(this) {
                override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? =
                    super.onCreateInputConnection(outAttrs).also {
                        outAttrs.fieldId = R.id.probe_ordinary_one
                        outAttrs.imeOptions = EditorInfo.IME_ACTION_DONE
                    }
            }.apply {
                id = if (index == 0) R.id.probe_ordinary_one else R.id.probe_ordinary_two
                inputType = InputType.TYPE_CLASS_TEXT
                hint = "Ordinary field (same settings)"
                column.addView(this)
            }
        }
        if (caseName == "privacy-password") column.addView(EditText(this).apply {
            id = R.id.probe_password
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "Password field"
        })
        if (caseName == "privacy-no-learning") column.addView(EditText(this).apply {
            id = R.id.probe_no_learning
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            hint = "No-learning field"
        })
        if (caseName == "app-restart") column.addView(Button(this).apply {
            text = "Restart focused input"
            setOnClickListener {
                ordinary.firstOrNull { it.hasFocus() }?.let { field ->
                    getSystemService(InputMethodManager::class.java).restartInput(field)
                }
            }
            isFocusable = false
        })
        if (caseName.startsWith("history-")) column.addView(Button(this).apply {
            text = "Open synthetic History"
            setOnClickListener {
                val suffix = "guided-visit"
                HistoryTestActivity.historyRootSuffix = suffix
                val root = java.io.File(noBackupFilesDir, "history-ui-test-$suffix")
                HistoryStore(root).save(HistoryEntry(java.util.UUID.randomUUID().toString(), System.currentTimeMillis(), "Synthetic guided visit row"))
                startActivity(android.content.Intent(this@RotationProbeActivity, HistoryTestActivity::class.java))
            }
        })
        if (guided) {
            val checks = when (caseName) {
                "signal-controls" -> listOf("Continue worked", "Finish worked", "Discard worked", "Keep finishing worked", "Finishing discard worked")
                "history-copy-delete" -> listOf("Copied synthetic row", "Deleted same synthetic row")
                else -> emptyList()
            }.map { label -> CheckBox(this).apply { text = label; column.addView(this) } }
            val choices = if (caseName.endsWith("rotation")) listOf("Same take continued" to "continued", "Take stopped" to "stopped", "Uncertain" to "uncertain", "Skip" to "skipped", "Retry" to "retry")
                else listOf("Done" to "observed", "Issue / missing observation" to "issue", "Skip" to "skipped", "Retry" to "retry")
            choices.forEach { (label, value) ->
                column.addView(Button(this).apply { text = label; setOnClickListener {
                    result = if (checks.isEmpty()) value else value + ":" + checks.joinToString("") { if (it.isChecked) "1" else "0" }
                    finish()
                } })
            }
        } else column.addView(Button(this).apply {
            text = "Done"
            isFocusable = false
            setOnClickListener { finish() }
        })
        setContentView(ScrollView(this).apply { addView(column) })
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) done.countDown()
    }
}
