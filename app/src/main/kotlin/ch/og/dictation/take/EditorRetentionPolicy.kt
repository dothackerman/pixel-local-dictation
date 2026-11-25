package ch.og.dictation.take

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
        EditorBits.TYPE_TEXT_VARIATION_PASSWORD,
        EditorBits.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
        EditorBits.TYPE_TEXT_VARIATION_WEB_PASSWORD,
    )
    private val ordinaryVariations = setOf(
        EditorBits.TYPE_TEXT_VARIATION_NORMAL,
        EditorBits.TYPE_TEXT_VARIATION_SHORT_MESSAGE,
        EditorBits.TYPE_TEXT_VARIATION_LONG_MESSAGE,
        EditorBits.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT,
    )

    /** [inputType] and [imeOptions] as the editor reported them; a null [inputType] means no editor. */
    fun classify(inputType: Int?, imeOptions: Int): EditorRetention {
        if (inputType == null) return EditorRetention.NO_EDITOR
        if ((inputType and EditorBits.TYPE_MASK_CLASS) == EditorBits.TYPE_NULL) return EditorRetention.NO_EDITOR
        val variation = inputType and EditorBits.TYPE_MASK_VARIATION
        val textClass = (inputType and EditorBits.TYPE_MASK_CLASS) == EditorBits.TYPE_CLASS_TEXT
        if (textClass && variation in passwordVariations) return EditorRetention.PASSWORD
        if ((inputType and EditorBits.TYPE_MASK_CLASS) == EditorBits.TYPE_CLASS_NUMBER &&
            variation == EditorBits.TYPE_NUMBER_VARIATION_PASSWORD) return EditorRetention.PASSWORD
        if ((imeOptions and EditorBits.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0) return EditorRetention.NO_LEARNING
        if (!textClass || variation !in ordinaryVariations) return EditorRetention.UNCLASSIFIABLE
        return EditorRetention.ELIGIBLE
    }
}

/**
 * The bits of Android's `InputType`, `EditorInfo` and `View` that take logic reads, as plain values:
 * the take package uses nothing from Android (the adapter in `ime` reads the editor, a test pins the values).
 */
internal object EditorBits {
    const val TYPE_MASK_CLASS = 0x0000000f
    const val TYPE_MASK_VARIATION = 0x00000ff0
    const val TYPE_NULL = 0x00000000
    const val TYPE_CLASS_TEXT = 0x00000001
    const val TYPE_CLASS_NUMBER = 0x00000002
    const val TYPE_TEXT_VARIATION_NORMAL = 0x00000000
    const val TYPE_TEXT_VARIATION_SHORT_MESSAGE = 0x00000040
    const val TYPE_TEXT_VARIATION_LONG_MESSAGE = 0x00000050
    const val TYPE_TEXT_VARIATION_PASSWORD = 0x00000080
    const val TYPE_TEXT_VARIATION_VISIBLE_PASSWORD = 0x00000090
    const val TYPE_TEXT_VARIATION_WEB_EDIT_TEXT = 0x000000a0
    const val TYPE_TEXT_VARIATION_WEB_PASSWORD = 0x000000e0
    const val TYPE_NUMBER_VARIATION_PASSWORD = 0x00000010
    const val IME_FLAG_NO_PERSONALIZED_LEARNING = 0x01000000
    const val IME_FLAG_NAVIGATE_NEXT = 0x08000000
    const val IME_FLAG_NAVIGATE_PREVIOUS = 0x04000000
}
