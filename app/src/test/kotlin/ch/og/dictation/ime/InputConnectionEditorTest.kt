package ch.og.dictation.ime

import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The editor port's adapter: every call goes to Android's connection as it is; identity is the connection's. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class InputConnectionEditorTest {
    private class Field(var accepts: Boolean = true, var extracted: ExtractedText? = null) :
        BaseInputConnection(View(RuntimeEnvironment.getApplication()), false) {
        val writes = mutableListOf<Pair<String, Int>>()
        val reads = mutableListOf<Pair<Int, Int>>()
        var request: ExtractedTextRequest? = null
        var before: CharSequence? = "so far"
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean { writes += text.toString() to newCursorPosition; return accepts }
        override fun getTextBeforeCursor(length: Int, flags: Int): CharSequence? { reads += length to flags; return before }
        override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? { this.request = request; return extracted }
    }

    @Test fun theSameConnectionIsEqualAndAnotherIsNot() {
        val first = Field()
        var current: Field? = first
        val port = editorPort { current }
        val start = port.live()
        assertEquals(start, port.live())
        assertEquals(start.hashCode(), port.live().hashCode())
        current = Field()
        assertNotEquals(start, port.live())
        current = first
        assertEquals("the same connection again is the same identity", start, port.live())
        current = null
        assertNull(port.live())
    }

    @Test fun writeAndReadsAnswerWhatTheEditorAnswers() {
        val field = Field()
        val editor = editorPort { field }.live()!!
        assertTrue(editor.write(" hello"))
        assertEquals(listOf(" hello" to 1), field.writes)
        field.accepts = false
        assertFalse(editor.write("more"))
        assertEquals("so far", editor.textBeforeCursor(256))
        assertEquals(listOf(256 to 0), field.reads)
        field.before = null
        assertNull(editor.textBeforeCursor(1))
        assertNull(editor.extractedLength())
        assertEquals(100_000, field.request!!.hintMaxChars)
        field.extracted = ExtractedText().apply { text = "twelve chars" }
        assertEquals(12, editor.extractedLength())
    }
}
