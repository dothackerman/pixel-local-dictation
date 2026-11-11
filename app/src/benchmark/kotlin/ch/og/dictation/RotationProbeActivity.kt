package ch.og.dictation

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.CountDownLatch

/** Owner-operated host. Android recreates it normally on configuration changes. */
class RotationProbeActivity : Activity() {
    companion object { @Volatile var done = CountDownLatch(1) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val caseName = requireNotNull(intent.getStringExtra("case"))
        require(caseName in RotationTrace.allowedCases)
        val instruction = when (caseName) {
            "signal-capture-rotation" -> "In Signal, start dictation and rotate during capture. Return here and tap Done."
            "app-capture-rotation" -> "Start dictation in an ordinary field and rotate during capture. Tap Done."
            "app-finishing-rotation" -> "Start dictation in an ordinary field, finish input during capture and rotate. Tap Done."
            "app-restart" -> "Start dictation in an ordinary field, then tap Restart focused input. Tap Done."
            "app-focus" -> "Start dictation in one ordinary field, then switch to the other. Tap Done."
            "app-hide" -> "Start dictation in an ordinary field, then hide the keyboard. Tap Done."
            "app-lock" -> "Start dictation in an ordinary field, then lock and unlock the phone. Tap Done."
            "app-password" -> "Start dictation in an ordinary field, then switch to the password field. Tap Done."
            else -> "Start dictation in an ordinary field, then switch to the no-learning field. Tap Done."
        }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(TextView(this).apply { text = "$caseName\n$instruction" })
        val ordinary = List(2) { index ->
            EditText(this).apply {
                id = if (index == 0) R.id.probe_ordinary_one else R.id.probe_ordinary_two
                inputType = InputType.TYPE_CLASS_TEXT
                hint = "Ordinary field ${index + 1} (same settings)"
                column.addView(this)
            }
        }
        column.addView(EditText(this).apply {
            id = R.id.probe_password
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "Password field"
        })
        column.addView(EditText(this).apply {
            id = R.id.probe_no_learning
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            hint = "No-learning field"
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
        setContentView(ScrollView(this).apply { addView(column) })
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) done.countDown()
    }
}
