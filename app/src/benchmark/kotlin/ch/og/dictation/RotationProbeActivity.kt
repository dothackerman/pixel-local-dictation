package ch.og.dictation

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.CountDownLatch

/** Owner-operated host. Android recreates it normally on configuration changes. */
class RotationProbeActivity : Activity() {
    companion object { @Volatile var done = CountDownLatch(1) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(TextView(this).apply {
            text = "Rotation probe: use either field; rotate, restart, switch focus, hide or lock. Return here and tap Done."
        })
        val fields = List(2) {
            EditText(this).apply {
                inputType = InputType.TYPE_CLASS_TEXT
                hint = "Tap here, then use the microphone"
                column.addView(this)
            }
        }
        column.addView(Button(this).apply {
            text = "Restart focused input"
            setOnClickListener {
                fields.firstOrNull { it.hasFocus() }?.let { field ->
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
