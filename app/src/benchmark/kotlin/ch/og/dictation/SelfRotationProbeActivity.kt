package ch.og.dictation

import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * App-owned editor that rotates itself through its requested orientation; no phone setting
 * changes. This class is recreated on rotation and leaves the keyboard hidden afterwards, as
 * Signal does. [Keep] handles the rotation itself, keeps focus and asks for the keyboard again.
 * The field disables personalized learning, so takes here are never kept in History.
 */
open class SelfRotationProbeActivity : Activity() {
    /** Keeps focus across rotation and shows the keyboard again. */
    class Keep : SelfRotationProbeActivity() {
        override fun onConfigurationChanged(newConfig: Configuration) {
            super.onConfigurationChanged(newConfig)
            showKeyboard()
        }
    }

    companion object {
        @Volatile var current: SelfRotationProbeActivity? = null
    }

    lateinit var editor: EditText
        private set

    @SuppressLint("AppCompatCustomView")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_SECURE)
        if (savedInstanceState == null) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        column.addView(TextView(this).apply {
            text = "Automated rotation probe. Please do not touch the phone until it closes."
        })
        editor = EditText(this).apply {
            id = R.id.probe_no_learning
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_FLAG_NO_FULLSCREEN
            hint = "Probe field (not kept)"
        }
        column.addView(editor)
        setContentView(column)
        current = this
        if (this is Keep || savedInstanceState == null) editor.requestFocus()
    }

    fun showKeyboard() {
        editor.requestFocus()
        editor.post { getSystemService(InputMethodManager::class.java).showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT) }
    }

    fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(editor.windowToken, 0)
    }

    fun rotate(landscape: Boolean) {
        requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    /** Is any part of the editor on screen above the keyboard? Geometry only, never text. */
    fun editorVisibleAboveKeyboard(): Boolean {
        val insets = window.decorView.rootWindowInsets ?: return false
        if (!insets.isVisible(WindowInsets.Type.ime())) return false
        val imeTop = window.decorView.height - insets.getInsets(WindowInsets.Type.ime()).bottom
        val r = Rect()
        if (!editor.getGlobalVisibleRect(r)) return false
        return r.top < imeTop && r.height() > 0
    }

    override fun onDestroy() {
        if (current === this) current = null
        super.onDestroy()
    }
}
