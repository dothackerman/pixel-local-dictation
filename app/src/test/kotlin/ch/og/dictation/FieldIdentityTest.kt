package ch.og.dictation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The same-field rule (ADR-010): after a rotation hold only the take's own field regains insert authority. */
class FieldIdentityTest {
    private val text = 0x00000001
    private val uri = 0x00000011
    private val next = 0x08000000
    private val previous = 0x04000000

    private fun field(
        pkg: String? = "org.example.chat", id: Int = 42, type: Int = text, variation: Int = 0, ime: Int = 0,
        hint: String? = "Message", sel: Int = 0,
    ) = FieldIdentity(pkg, id, type, variation, ime, hint, sel)

    @Test fun identicalFieldsMatch() = assertTrue(field().isSameFieldAs(field()))

    @Test fun eachClauseThatDiffersIsNoMatch() {
        val start = field()
        assertFalse("package", field(pkg = "org.example.other").isSameFieldAs(start))
        assertFalse("package missing", field(pkg = null).isSameFieldAs(start))
        assertFalse("field id", field(id = 43).isSameFieldAs(start))
        assertFalse("class", field(type = 0x00000002).isSameFieldAs(start))
        assertFalse("variation", field(variation = uri and 0xff0).isSameFieldAs(start))
        assertFalse("ime options", field(ime = 3).isSameFieldAs(start))
        assertFalse("hint appeared", field(hint = "Message").isSameFieldAs(field(hint = null)))
        assertFalse("hint disappeared", field(hint = null).isSameFieldAs(start))
    }

    @Test fun navigationFlagsAreIgnored() {
        assertTrue(field(ime = next or previous).isSameFieldAs(field(ime = 0)))
        assertTrue(field(ime = next).isSameFieldAs(field(ime = previous)))
        assertFalse("another flag still counts", field(ime = next or 0x02000000).isSameFieldAs(field(ime = 0)))
    }

    @Test fun hintTextOfAFieldWithAnIdIsNotCompared() = assertTrue(field(hint = "Message").isSameFieldAs(field(hint = "Nachricht")))

    @Test fun fieldsWithoutAnIdAlsoNeedTheSameHint() {
        for (none in listOf(0, -1)) {
            val start = field(id = none, hint = "Message")
            assertTrue(field(id = none, hint = "Message").isSameFieldAs(start))
            assertFalse(field(id = none, hint = "Search").isSameFieldAs(start))
        }
        assertTrue("0 and NO_ID are both no id", field(id = -1).isSameFieldAs(field(id = 0)))
    }

    @Test fun aFieldWithAnIdNeverMatchesOneWithout() {
        assertFalse(field(id = 0).isSameFieldAs(field(id = 42)))
        assertFalse(field(id = 42).isSameFieldAs(field(id = -1)))
    }

    @Test fun typeNullOrNoSelectionIsNeverAMatch() {
        assertFalse(field(type = 0).isSameFieldAs(field(type = 0)))
        assertFalse(field(sel = -1).isSameFieldAs(field()))
        assertFalse(field().isSameFieldAs(field(sel = -1)))
        assertTrue(field(type = 0).typeNull)
        assertFalse(field(sel = -1).selValid)
    }

    @Test fun noEditorInfoIsNeverAMatch() {
        assertFalse(field().isSameFieldAs(null))
    }

    @Test fun descriptionHoldsNoFieldData() {
        val text = field(hint = "secret hint").toString() + field(id = 0x7f0a0101).toString()
        assertFalse(text.contains("secret hint"))
        assertFalse(text.contains("example"))
        assertFalse(text.contains(0x7f0a0101.toString()))
    }
}
