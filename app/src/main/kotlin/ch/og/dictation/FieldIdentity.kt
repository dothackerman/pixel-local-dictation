package ch.og.dictation

import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo

/**
 * What identifies the field a take started in (ADR-010). After a rotation hold the take's insert
 * authority may only return to an editor that [isSameFieldAs] the take's own field: a write's
 * return value and a non-null read prove nothing (the platform's placeholder connection for a view
 * that is not an editor answers both), so identity is judged from the editor's attributes alone.
 * Values stay in memory; [toString] shows none of them (no package, id or hint is ever logged).
 */
class FieldIdentity(
    private val packageName: String?,
    private val fieldId: Int,
    private val inputType: Int,
    private val imeOptions: Int,
    private val hintText: String?,
    private val initialSelStart: Int,
) {
    constructor(packageName: String?, fieldId: Int, typeClass: Int, variation: Int, imeOptions: Int, hintText: String?, initialSelStart: Int) :
        this(packageName, fieldId, (typeClass and InputType.TYPE_MASK_CLASS) or (variation and InputType.TYPE_MASK_VARIATION),
            imeOptions, hintText, initialSelStart)

    val typeNull: Boolean get() = (inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_NULL
    val selValid: Boolean get() = initialSelStart >= 0
    private val hasId: Boolean get() = fieldId > 0

    /** The same field as [start], the field the take started in. */
    fun isSameFieldAs(start: FieldIdentity?): Boolean {
        if (start == null || typeNull || start.typeNull || !selValid || !start.selValid) return false
        if (packageName != start.packageName) return false
        if ((inputType and TYPE_BITS) != (start.inputType and TYPE_BITS)) return false
        if ((imeOptions and NAVIGATION.inv()) != (start.imeOptions and NAVIGATION.inv())) return false
        if ((hintText != null) != (start.hintText != null)) return false
        return when {
            hasId && start.hasId -> fieldId == start.fieldId
            hasId != start.hasId -> false
            else -> hintText == start.hintText // Neither has an id (Compose): the hint must match too.
        }
    }

    override fun toString() = "FieldIdentity(typeNull=$typeNull, selValid=$selValid, hasId=$hasId, hasHint=${hintText != null})"

    companion object {
        private const val TYPE_BITS = InputType.TYPE_MASK_CLASS or InputType.TYPE_MASK_VARIATION
        /** A field recreated once a second field exists gains or loses these. */
        private const val NAVIGATION = EditorInfo.IME_FLAG_NAVIGATE_NEXT or EditorInfo.IME_FLAG_NAVIGATE_PREVIOUS

        fun of(info: EditorInfo?): FieldIdentity? = info?.let {
            FieldIdentity(it.packageName, it.fieldId.takeIf { id -> id != View.NO_ID } ?: 0, it.inputType, it.imeOptions,
                it.hintText?.toString(), it.initialSelStart)
        }
    }
}
