package ch.og.dictation

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain logic stays testable on the host: these modules use nothing from Android except the
 * app's resource ids (`ch.og.dictation.R`). The list only grows (#65).
 */
class PlainLogicImportTest {
    private val root = File("src/main/kotlin/ch/og/dictation")

    private val plain = listOf(
        "take/EditorEventPolicy.kt",
        "take/TakeSession.kt",
        "take/TakeTranscriber.kt",
        "take/InsertReadBack.kt",
        "ime/InputSession.kt",
        "recognition/WrittenForm.kt",
        "recognition/Recognizer.kt",
        "recognition/DictationRuntime.kt",
        "recognition/Variant.kt",
        "dictionary/DictionaryStore.kt",
        "history/HistoryRows.kt",
        "history/HistoryTakeOutput.kt",
        "audio/RecordingFeedback.kt",
    )

    /** `android.` as a package reference outside comments: imports and fully qualified uses alike. */
    private val androidUse = Regex("""(?<![\w.])android\.[a-z]""")

    @Test fun plainLogicImportsNothingFromAndroid() {
        val offenders = plain.flatMap { name ->
            val file = File(root, name)
            assertTrue("listed plain-logic module missing: $name", file.isFile)
            code(file.readText()).lines().withIndex()
                .filter { androidUse.containsMatchIn(it.value) }
                .map { "$name:${it.index + 1}: ${it.value.trim()}" }
        }
        assertEquals(emptyList<String>(), offenders)
    }

    @Test fun guardSeesAnAndroidImport() {
        assertTrue(androidUse.containsMatchIn(code("import android.os.Handler")))
        assertTrue(androidUse.containsMatchIn(code("val c: android.view.inputmethod.InputConnection? = null")))
        assertTrue(!androidUse.containsMatchIn(code("// android.os.Handler is not used\nimport ch.og.dictation.R")))
    }

    /** Source with comments blanked (line structure kept); string contents stay, they are rare here. */
    private fun code(source: String): String =
        source.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)) { m -> m.value.replace(Regex("[^\n]"), " ") }
            .replace(Regex("""//[^\n]*"""), "")
}
