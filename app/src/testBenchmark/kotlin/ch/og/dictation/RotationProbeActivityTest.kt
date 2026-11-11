package ch.og.dictation

import android.content.Intent
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RotationProbeActivityTest {
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
