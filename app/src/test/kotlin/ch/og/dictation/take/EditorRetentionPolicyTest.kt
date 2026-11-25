package ch.og.dictation.take

import ch.og.dictation.ime.retentionOf
import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class EditorRetentionPolicyTest {
    private fun eligible(editor: EditorInfo?) = retentionOf(editor).eligible

    /** The take package reads the editor's bits as plain values; they are Android's. */
    @Test fun plainEditorBitsAreAndroids() {
        assertEquals(InputType.TYPE_MASK_CLASS, EditorBits.TYPE_MASK_CLASS)
        assertEquals(InputType.TYPE_MASK_VARIATION, EditorBits.TYPE_MASK_VARIATION)
        assertEquals(InputType.TYPE_NULL, EditorBits.TYPE_NULL)
        assertEquals(InputType.TYPE_CLASS_TEXT, EditorBits.TYPE_CLASS_TEXT)
        assertEquals(InputType.TYPE_CLASS_NUMBER, EditorBits.TYPE_CLASS_NUMBER)
        assertEquals(InputType.TYPE_TEXT_VARIATION_NORMAL, EditorBits.TYPE_TEXT_VARIATION_NORMAL)
        assertEquals(InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE, EditorBits.TYPE_TEXT_VARIATION_SHORT_MESSAGE)
        assertEquals(InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE, EditorBits.TYPE_TEXT_VARIATION_LONG_MESSAGE)
        assertEquals(InputType.TYPE_TEXT_VARIATION_PASSWORD, EditorBits.TYPE_TEXT_VARIATION_PASSWORD)
        assertEquals(InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, EditorBits.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
        assertEquals(InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT, EditorBits.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT)
        assertEquals(InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD, EditorBits.TYPE_TEXT_VARIATION_WEB_PASSWORD)
        assertEquals(InputType.TYPE_NUMBER_VARIATION_PASSWORD, EditorBits.TYPE_NUMBER_VARIATION_PASSWORD)
        assertEquals(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING, EditorBits.IME_FLAG_NO_PERSONALIZED_LEARNING)
        assertEquals(EditorInfo.IME_FLAG_NAVIGATE_NEXT, EditorBits.IME_FLAG_NAVIGATE_NEXT)
        assertEquals(EditorInfo.IME_FLAG_NAVIGATE_PREVIOUS, EditorBits.IME_FLAG_NAVIGATE_PREVIOUS)
    }

    @Test fun ordinaryMessageAndWebEditorsRemainEligible() {
        listOf(InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE, InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE,
            InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT).forEach { variation ->
            assertTrue("variation=$variation", eligible(EditorInfo().apply {
                inputType = InputType.TYPE_CLASS_TEXT or variation
            }))
        }
    }

    @Test fun ordinaryTextIsEligibleButMissingAndUnclassifiedEditorsAreNot() {
        assertTrue(eligible(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }))
        assertFalse(eligible(null))
        assertFalse(eligible(EditorInfo().apply { inputType = InputType.TYPE_NULL }))
        assertFalse(eligible(EditorInfo().apply { inputType = InputType.TYPE_CLASS_PHONE }))
        assertFalse(eligible(EditorInfo().apply { inputType = InputType.TYPE_CLASS_NUMBER }))
    }

    @Test fun sensitiveTextVariantsAndNoLearningAreExcluded() {
        val sensitiveTypes = listOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
        )
        sensitiveTypes.forEach { variation ->
            assertFalse("variation=$variation", eligible(EditorInfo().apply {
                inputType = InputType.TYPE_CLASS_TEXT or variation
            }))
        }
        assertFalse(eligible(EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }))
        assertFalse(eligible(EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        }))
    }
}
