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
import android.widget.RadioButton
import android.widget.RadioGroup
import android.content.Intent
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.Spinner
import android.widget.ArrayAdapter
import java.util.concurrent.CountDownLatch

/**
 * Owner-operated host. Android recreates it normally on configuration changes. With a "step"
 * extra it is one screen of the guided visit (Do, Expect, answers); with a "case" extra it is
 * the plain probe window used by the automated privacy and rotation probes.
 */
class RotationProbeActivity : Activity() {
    companion object {
        @Volatile var done = CountDownLatch(1)
        @Volatile var result = "skipped"
        /** Index into the visit's step list chosen on the welcome screen. */
        @Volatile var selected = 0
        val cases = listOf(
            "app-capture-rotation", "app-finishing-rotation", "app-restart", "app-focus", "app-hide", "app-lock",
            "signal-capture-rotation", "signal-finishing-rotation", "signal-controls", "privacy-password", "privacy-no-learning", "privacy-ordinary",
            "history-copy-delete", "accessibility"
        )
    }

    private var answers = CharArray(0)

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun text(content: String, sp: Float, bold: Boolean = false, top: Int = 0) = TextView(this).apply {
        text = content
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(top), 0, 0)
    }

    private fun touchButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        minimumHeight = dp(48)
        minHeight = dp(48)
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun spaced(top: Int) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }

    private fun noLearningField() = EditText(this).apply {
        id = R.id.probe_no_learning
        inputType = InputType.TYPE_CLASS_TEXT
        imeOptions = imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        hint = "No-learning field"
        minimumHeight = dp(48)
    }

    private fun passwordField() = EditText(this).apply {
        id = R.id.probe_password
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        hint = "Password field"
        minimumHeight = dp(48)
    }

    private fun scrolling(column: LinearLayout) = ScrollView(this).apply {
        fitsSystemWindows = true
        addView(column)
    }

    private fun page() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(24))
        // An autofill suggestion can replace the keyboard's pinned mic. These fields use synthetic input.
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
    }

    @SuppressLint("AppCompatCustomView") // Plain Activity deliberately uses platform widgets.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_SECURE)
        if (intent.getBooleanExtra("welcome", false)) { welcome(); return }
        val stepId = intent.getStringExtra("step")
        if (stepId != null) { guide(GuidedVisit.script.first { it.id == stepId }, savedInstanceState); return }
        plainProbe()
    }

    private fun welcome() {
        val column = page()
        column.addView(text("Guided visit", 24f, bold = true))
        column.addView(text("Thirteen short steps, one per screen. Use non-sensitive speech only. Your answers are saved as metadata only, never text you dictated.", 18f, top = 8))
        val picker = Spinner(this).apply {
            minimumHeight = dp(48)
            adapter = ArrayAdapter(this@RotationProbeActivity, android.R.layout.simple_spinner_dropdown_item,
                GuidedVisit.script.map { "${it.id}  ${it.title}" })
            setSelection(selected)
        }
        column.addView(text("Start at", 16f, bold = true, top = 16))
        column.addView(picker)
        column.addView(touchButton("Start") { selected = picker.selectedItemPosition; result = "start"; finish() })
        setContentView(scrolling(column))
    }

    private fun guide(step: VisitStep, saved: Bundle?) {
        val position = intent.getIntExtra("position", GuidedVisit.script.indexOf(step) + 1)
        val count = intent.getIntExtra("stepCount", GuidedVisit.script.size)
        answers = saved?.getCharArray("answers")?.takeIf { it.size == step.expect.size }
            ?: CharArray(step.expect.size) { Answer.NOT_OBSERVED.code }
        val column = page()
        column.addView(text("Step $position of $count \u00b7 ${step.id}", 14f))
        column.addView(text(step.title, 26f, bold = true, top = 4))
        column.addView(text(step.where, 16f, top = 8))
        column.addView(text("Do", 18f, bold = true, top = 20))
        step.doItems.forEachIndexed { i, item -> column.addView(text("${i + 1}. $item", 18f, top = 6)) }
        when (step.aid) {
            Aid.SETTINGS -> column.addView(touchButton("Open Settings") {
                startActivity(Intent(this, SettingsActivity::class.java))
            }, spaced(12))
            Aid.NO_LEARNING_FIELD -> column.addView(noLearningField())
            Aid.PASSWORD_FIELD -> column.addView(passwordField())
            Aid.NONE -> Unit
        }
        column.addView(text("Expect", 18f, bold = true, top = 24))
        step.expectations.forEachIndexed { i, expectation ->
            column.addView(text(expectation.text, 18f, top = 12))
            column.addView(RadioGroup(this).apply {
                orientation = RadioGroup.VERTICAL
                Answer.entries.forEach { answer ->
                    addView(RadioButton(this@RotationProbeActivity).apply {
                        text = answer.label
                        tag = "${expectation.id}:${answer.code}"
                        minimumHeight = dp(48)
                        minHeight = dp(48)
                        isChecked = answers[i] == answer.code
                        setOnClickListener { answers[i] = answer.code }
                    })
                }
            })
        }
        fun finishWith(outcome: Outcome) {
            result = StepResult(outcome, answers.map { Answer.of(it) }).encode()
            finish()
        }
        val last = position >= count
        column.addView(touchButton(if (last) "Save and finish" else "Save and next") { finishWith(Outcome.DONE) }, spaced(24))
        column.addView(touchButton("Skip step") { finishWith(Outcome.SKIPPED) })
        column.addView(touchButton("Retry step") { finishWith(Outcome.RETRY) })
        if (count > 1) column.addView(touchButton("End visit") { finishWith(Outcome.END) })
        setContentView(scrolling(column))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putCharArray("answers", answers)
    }

    @SuppressLint("AppCompatCustomView") // Plain Activity deliberately uses platform widgets.
    private fun plainProbe() {
        val caseName = requireNotNull(intent.getStringExtra("case"))
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
            "signal-controls" -> "In Signal, try cancellation during recording: Continue, Finish and Discard. Return here and tap Done."
            "history-copy-delete" -> "Open synthetic History below, copy the synthetic row, then delete that same row. Return here and tap Done."
            "accessibility" -> "Check available spoken labels and controls. Tap Done."
            "privacy-ordinary" -> "Start dictation in an ordinary field, speak a harmless test phrase, tap Stop, and wait for completion. Tap Done."
            "privacy-password" -> "Start dictation in the password field, speak a harmless test phrase, tap Stop, and wait for completion. Tap Done."
            else -> "Start dictation in the no-learning field, speak a harmless test phrase, tap Stop, and wait for completion. Tap Done."
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // An autofill suggestion can replace the keyboard's pinned mic. These fields use synthetic input.
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        val host = if (caseName.startsWith("signal-")) "Do this in an unsent Signal draft. Use Recents to return here afterward.\n" else ""
        val title = caseName.replace("-", " ").replaceFirstChar { it.uppercase() }
        column.addView(TextView(this).apply { text = title + "\n" + host + instruction + "\nHide the keyboard before you tap Done." })
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
        if (caseName == "privacy-password") column.addView(passwordField())
        if (caseName == "privacy-no-learning") column.addView(noLearningField())
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
        column.addView(Button(this).apply {
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
