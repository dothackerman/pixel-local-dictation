package ch.og.dictation.ime

import ch.og.dictation.take.EditorRetention
import ch.og.dictation.take.EditorRetentionPolicy
import ch.og.dictation.take.FieldIdentity
import android.view.View
import android.view.inputmethod.EditorInfo

// What take logic needs from Android's EditorInfo, read here so the take package sees plain values only.

/** The retention class of [editor]; null is an input start without an editor. */
fun retentionOf(editor: EditorInfo?): EditorRetention = EditorRetentionPolicy.classify(editor?.inputType, editor?.imeOptions ?: 0)

/** The identity of the field [info] describes; null without an editor. */
fun fieldIdentityOf(info: EditorInfo?): FieldIdentity? = info?.let {
    FieldIdentity(it.packageName, it.fieldId.takeIf { id -> id != View.NO_ID } ?: 0, it.inputType, it.imeOptions,
        it.hintText?.toString(), it.initialSelStart)
}
