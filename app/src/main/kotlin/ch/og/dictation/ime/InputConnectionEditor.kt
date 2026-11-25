package ch.og.dictation.ime

import ch.og.dictation.take.EditorConnection
import ch.og.dictation.take.EditorPort
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection

/** Android's [InputConnection] behind the editor port; equal exactly when it is the same connection object. */
private class InputConnectionEditor(private val connection: InputConnection) : EditorConnection {
    override fun write(text: String): Boolean = connection.commitText(text, 1)
    override fun textBeforeCursor(chars: Int): CharSequence? = connection.getTextBeforeCursor(chars, 0)
    override fun extractedLength(): Int? =
        connection.getExtractedText(ExtractedTextRequest().apply { hintMaxChars = 100_000 }, 0)?.text?.length
    override fun equals(other: Any?) = other is InputConnectionEditor && other.connection === connection
    override fun hashCode() = System.identityHashCode(connection)
}

/** The editor port over whatever connection [current] answers at each call. */
fun editorPort(current: () -> InputConnection?) = EditorPort { current()?.let(::InputConnectionEditor) }
