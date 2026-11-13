package ch.og.dictation

import android.text.InputType
import android.view.inputmethod.EditorInfo

/**
 * Why an editor may or may not retain content. Fixed names only; the rotation trace records them.
 * PASSWORD and NO_LEARNING are positively private. UNCLASSIFIABLE is a text-less or unusual
 * editor. NO_EDITOR is an input start without a field (null or TYPE_NULL): not private by itself.
 */
enum class EditorRetention {
    ELIGIBLE, PASSWORD, NO_LEARNING, UNCLASSIFIABLE, NO_EDITOR;

    val eligible: Boolean get() = this == ELIGIBLE
    /** Excludes a take the moment it is seen, even during a rotation hold. */
    val positivelyPrivate: Boolean get() = this == PASSWORD || this == NO_LEARNING
    /** Excludes a take when it is the editor the take settles on (panel returned and settled, or Stop). */
    val excludesSettledTake: Boolean get() = positivelyPrivate || this == UNCLASSIFIABLE
}

/** Content may be retained only when the originating editor is clearly ordinary text. */
object EditorRetentionPolicy {
    private val passwordVariations = setOf(
        InputType.TYPE_TEXT_VARIATION_PASSWORD,
        InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
        InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
    )
    private val ordinaryVariations = setOf(
        InputType.TYPE_TEXT_VARIATION_NORMAL,
        InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE,
        InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE,
        InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT,
    )

    fun classify(editor: EditorInfo?): EditorRetention {
        if (editor == null) return EditorRetention.NO_EDITOR
        val inputType = editor.inputType
        if ((inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_NULL) return EditorRetention.NO_EDITOR
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        val textClass = (inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT
        if (textClass && variation in passwordVariations) return EditorRetention.PASSWORD
        if ((inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_NUMBER &&
            variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD) return EditorRetention.PASSWORD
        if ((editor.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0) return EditorRetention.NO_LEARNING
        if (!textClass || variation !in ordinaryVariations) return EditorRetention.UNCLASSIFIABLE
        return EditorRetention.ELIGIBLE
    }

    fun isEligible(editor: EditorInfo?): Boolean = classify(editor).eligible
}
