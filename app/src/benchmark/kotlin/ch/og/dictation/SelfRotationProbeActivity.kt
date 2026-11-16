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

    /**
     * Signal's order (2026-11-13 log): recreated, its editor takes input and the platform shows
     * the keyboard, then the app hides it at once and starts no further input. With
     * [DETACH_AFTER_HIDE] it also swaps the focused field for a fresh, unfocused one, so the input
     * connection the keyboard holds belongs to a detached view.
     */
    class SignalOrder : SelfRotationProbeActivity() {
        private var recreated = false
        private var hidden = false
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            recreated = savedInstanceState != null
            if (!recreated) return
            val column = editor.parent as LinearLayout
            // Only the first recreation retargets; rotating back (the test's cleanup) returns focus to the field.
            val retarget = if (savedInstanceState?.getBoolean(RETARGETED) == true) null else intent.getStringExtra(RETARGET)
            when (retarget) {
                // Signal 2026-11-13 diagnostics: after recreation input focus is on a view that is not a
                // text editor; the platform starts input with TYPE_NULL and a fallback connection.
                RETARGET_NON_EDITOR -> column.addView(View(this).apply {
                    id = R.id.probe_non_editor
                    isFocusable = true
                    isFocusableInTouchMode = true
                }, 0, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 48)) // A plain View would fill the column.
                    .also { column.getChildAt(0).requestFocus() }
                // A different real text field of the same app takes focus (a search box, say).
                RETARGET_OTHER_FIELD -> column.addView(EditText(this).apply {
                    id = R.id.probe_other_field
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                    imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or
                        EditorInfo.IME_ACTION_GO
                    hint = "Other probe field (not kept)"
                    other = this
                }, 0).also { other?.requestFocus() }
                else -> editor.requestFocus()
            }
        }
        /** View state restores focus to the field; the retarget wins, as in Signal, after restoration. */
        override fun onRestoreInstanceState(savedInstanceState: Bundle) {
            super.onRestoreInstanceState(savedInstanceState)
            if (savedInstanceState.getBoolean(RETARGETED)) return
            when (intent.getStringExtra(RETARGET)) {
                RETARGET_NON_EDITOR -> findViewById<View>(R.id.probe_non_editor)?.requestFocus()
                RETARGET_OTHER_FIELD -> other?.requestFocus()
            }
        }
        override fun onSaveInstanceState(outState: Bundle) {
            super.onSaveInstanceState(outState)
            outState.putBoolean(RETARGETED, recreated)
        }
        override fun onWindowFocusChanged(hasFocus: Boolean) {
            super.onWindowFocusChanged(hasFocus)
            if (!recreated || !hasFocus || hidden) return
            hidden = true
            editor.post {
                hideKeyboard()
                if (intent.getBooleanExtra(DETACH_AFTER_HIDE, false)) {
                    val column = editor.parent as LinearLayout
                    column.removeView(editor)
                    column.post { column.addView(newEditor()) }
                }
            }
        }
    }

    companion object {
        /** Intent extra for [SignalOrder]: what takes input focus after recreation instead of the field. */
        const val RETARGET = "retarget"
        private const val RETARGETED = "retargeted"
        const val RETARGET_NON_EDITOR = "nonEditor"
        const val RETARGET_OTHER_FIELD = "otherField"
        /** Intent extra for [SignalOrder]: swap the field after hiding the keyboard. */
        const val DETACH_AFTER_HIDE = "detachAfterHide"
        @Volatile var current: SelfRotationProbeActivity? = null
        /**
         * Intent extra: an ordinary field whose takes are kept in History, so a test can reach the
         * History save acknowledgement. The test points the service at an isolated test History
         * root (DictationImeService.historyRootFor) and clears it; the owner's History is never used.
         */
        const val HISTORY_ELIGIBLE = "historyEligible"
    }

    lateinit var editor: EditText
        private set
    /** [SignalOrder] with [RETARGET_OTHER_FIELD]: the other field that took focus. */
    var other: EditText? = null
        protected set

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
        column.addView(newEditor())
        setContentView(column)
        current = this
        if (this is Keep || savedInstanceState == null) editor.requestFocus()
    }

    /** The probe field; becomes [editor]. */
    protected fun newEditor(): EditText = EditText(this).apply {
        id = R.id.probe_no_learning
        inputType = InputType.TYPE_CLASS_TEXT
        val kept = intent.getBooleanExtra(HISTORY_ELIGIBLE, false)
        imeOptions = imeOptions or EditorInfo.IME_FLAG_NO_FULLSCREEN or
            if (kept) 0 else EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        hint = if (kept) "Probe field (kept in test History only)" else "Probe field (not kept)"
        editor = this
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
