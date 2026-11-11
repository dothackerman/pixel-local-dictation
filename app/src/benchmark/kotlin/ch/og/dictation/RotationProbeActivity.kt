package ch.og.dictation

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import java.util.concurrent.CountDownLatch

/** Owner-operated host. Android recreates it normally on configuration changes. */
class RotationProbeActivity : Activity() {
    companion object { @Volatile var done = CountDownLatch(1) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(TextView(this).apply { text = "Choose the manual case for this run. Use Signal or this editor. Rotate during capture, finish input during capture, or exercise restart/focus/hide/lock." })
        val cases = listOf("capture-rotation", "finishing-rotation", "restart-focus-hide-lock")
        val choices = RadioGroup(this)
        cases.forEachIndexed { index, name ->
            choices.addView(RadioButton(this).apply { text = name; id = index + 1 })
        }
        choices.check(1)
        column.addView(choices)
        val state = TextView(this).apply { text = "Not started" }
        column.addView(state)
        val ordinary = List(2) { index ->
            EditText(this).apply {
                inputType = InputType.TYPE_CLASS_TEXT
                hint = "Ordinary field ${index + 1} (same settings)"
                column.addView(this)
            }
        }
        column.addView(EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "Password field"
        })
        column.addView(EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            hint = "No-learning field"
        })
        column.addView(Button(this).apply {
            text = "Tag run with case"
            setOnClickListener {
                val caseName = cases[choices.checkedRadioButtonId - 1]
                RotationTrace.selectProbeCase(caseName)
                state.text = "$caseName · ${RotationTrace.currentProbeRun()}"
                isEnabled = false
            }
            isFocusable = false
        })
        column.addView(Button(this).apply {
            text = "Restart focused input"
            setOnClickListener {
                ordinary.firstOrNull { it.hasFocus() }?.let { field ->
                    getSystemService(InputMethodManager::class.java).restartInput(field)
                }
            }
            isFocusable = false
        })
        column.addView(Button(this).apply {
            text = "Done"
            isFocusable = false
            setOnClickListener { finish() }
        })
        setContentView(column)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) done.countDown()
    }
}
