package ch.og.dictation

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
    @Test fun ordinaryMessageAndWebEditorsRemainEligible() {
        listOf(InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE, InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE,
            InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT).forEach { variation ->
            assertTrue("variation=$variation", EditorRetentionPolicy.isEligible(EditorInfo().apply {
                inputType = InputType.TYPE_CLASS_TEXT or variation
            }))
        }
    }

    @Test fun ordinaryTextIsEligibleButMissingAndUnclassifiedEditorsAreNot() {
        assertTrue(EditorRetentionPolicy.isEligible(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }))
        assertFalse(EditorRetentionPolicy.isEligible(null))
        assertFalse(EditorRetentionPolicy.isEligible(EditorInfo().apply { inputType = InputType.TYPE_NULL }))
        assertFalse(EditorRetentionPolicy.isEligible(EditorInfo().apply { inputType = InputType.TYPE_CLASS_PHONE }))
        assertFalse(EditorRetentionPolicy.isEligible(EditorInfo().apply { inputType = InputType.TYPE_CLASS_NUMBER }))
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
            assertFalse("variation=$variation", EditorRetentionPolicy.isEligible(EditorInfo().apply {
                inputType = InputType.TYPE_CLASS_TEXT or variation
            }))
        }
        assertFalse(EditorRetentionPolicy.isEligible(EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }))
        assertFalse(EditorRetentionPolicy.isEligible(EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        }))
    }
}
