package ch.og.dictation

import android.content.Intent
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RotationProbeActivityTest {
    @Test fun privacyCardsPresentOnlyTheirOwnSyntheticFieldAndKeepItsEditorPolicy() {
        for (password in listOf(true, false)) {
            val name = if (password) "privacy-password" else "privacy-no-learning"
            val activity = Robolectric.buildActivity(RotationProbeActivity::class.java,
                Intent().putExtra("case", name)).setup().get()
            val field = activity.findViewById<EditText>(if (password) R.id.probe_password else R.id.probe_no_learning)
            assertNull(activity.findViewById<View>(R.id.probe_ordinary_one))
            assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS, (field.parent as View).importantForAutofill)
            val info = EditorInfo()
            field.onCreateInputConnection(info)
            if (password) assertEquals(InputType.TYPE_TEXT_VARIATION_PASSWORD, info.inputType and InputType.TYPE_MASK_VARIATION)
            else assertEquals(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING, info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)
        }
    }

    @Test fun ordinaryFieldsReportMatchingEditorInfo() {
        val activity = Robolectric.buildActivity(RotationProbeActivity::class.java,
            Intent().putExtra("case", "app-focus")).setup().get()
        fun info(id: Int): EditorInfo = EditorInfo().also {
            activity.findViewById<EditText>(id).onCreateInputConnection(it)
        }
        val first = info(R.id.probe_ordinary_one)
        val second = info(R.id.probe_ordinary_two)
        assertEquals(first.fieldId, second.fieldId)
        assertEquals(first.inputType, second.inputType)
        assertEquals(first.imeOptions, second.imeOptions)
        assertEquals(first.fieldName, second.fieldName)
        assertEquals(first.packageName, second.packageName)
        assertEquals(first.hintText, second.hintText)
    }
}
