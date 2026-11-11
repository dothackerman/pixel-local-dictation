package ch.og.dictation

import android.text.InputType
import android.view.inputmethod.EditorInfo

/** Content may be retained only when the originating editor is clearly ordinary text. */
object EditorRetentionPolicy {
    fun isEligible(editor: EditorInfo?): Boolean {
        if (editor == null) return false
        val inputType = editor.inputType
        if ((inputType and InputType.TYPE_MASK_CLASS) != InputType.TYPE_CLASS_TEXT) return false
        if ((inputType and InputType.TYPE_MASK_VARIATION) !in setOf(
                InputType.TYPE_TEXT_VARIATION_NORMAL,
                InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE,
                InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE,
                InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT,
            )) return false
        if ((editor.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0) return false
        return true
    }
}
