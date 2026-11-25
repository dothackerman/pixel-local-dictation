package ch.og.dictation

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain logic stays testable on the host: these modules use nothing from Android (`android.`,
 * `androidx.`, `com.google.android.`) except the app's resource ids (`ch.og.dictation.R`). The list
 * only grows (#65); the take package is guarded as a whole, new files included (#84).
 */
class PlainLogicImportTest {
    private val root = File("src/main/kotlin/ch/og/dictation")

    private val plain = listOf(
        "ime/InputSession.kt",
        "recognition/WrittenForm.kt",
        "recognition/Recognizer.kt",
        "recognition/DictationRuntime.kt",
        "recognition/Variant.kt",
        "dictionary/DictionaryStore.kt",
        "history/HistoryRows.kt",
        "history/HistoryTakeOutput.kt",
        "audio/RecordingFeedback.kt",
        "panel/PanelFrame.kt",
    )

    /** An Android package reference outside comments: imports and fully qualified uses alike. */
    private val androidUse = Regex("""(?<![\w.])(?:android|androidx|com\.google\.android)\.[a-z]""")

    private fun offenders(name: String, source: String): List<String> =
        code(source).lines().withIndex()
            .filter { androidUse.containsMatchIn(it.value) }
            .map { "$name:${it.index + 1}: ${it.value.trim()}" }

    @Test fun plainLogicImportsNothingFromAndroid() {
        val offenders = plain.flatMap { name ->
            val file = File(root, name)
            assertTrue("listed plain-logic module missing: $name", file.isFile)
            offenders(name, file.readText())
        }
        assertEquals(emptyList<String>(), offenders)
    }

    /** Every file of the take package, so the take controller and its ports cannot let Android in. */
    @Test fun takePackageUsesNothingFromAndroid() {
        val files = File(root, "take").walkTopDown().filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }.toList()
        assertTrue("take package not found", files.any { it.name == "TakeSession.kt" })
        assertEquals(emptyList<String>(), files.flatMap { offenders(it.relativeTo(root).path, it.readText()) })
    }

    @Test fun guardSeesAnAndroidImport() {
        assertTrue(androidUse.containsMatchIn(code("import android.os.Handler")))
        assertTrue(androidUse.containsMatchIn(code("val c: android.view.inputmethod.InputConnection? = null")))
        assertTrue(!androidUse.containsMatchIn(code("// android.os.Handler is not used\nimport ch.og.dictation.R")))
    }

    @Test fun guardFailsOnAPlantedAndroidxOrGoogleImport() {
        val clean = "package ch.og.dictation.take\n\nimport ch.og.dictation.R\nimport java.util.concurrent.Executor\n"
        assertEquals(emptyList<String>(), offenders("take/Planted.kt", clean))
        assertEquals(listOf("take/Planted.kt:3: import androidx.annotation.MainThread"),
            offenders("take/Planted.kt", "package ch.og.dictation.take\n\nimport androidx.annotation.MainThread\n"))
        assertEquals(listOf("take/Planted.kt:1: import com.google.android.material.button.MaterialButton"),
            offenders("take/Planted.kt", "import com.google.android.material.button.MaterialButton\n"))
        assertEquals(listOf("take/Planted.kt:1: val l: androidx.lifecycle.Lifecycle? = null"),
            offenders("take/Planted.kt", "val l: androidx.lifecycle.Lifecycle? = null"))
        // Other packages that merely end in these names are not Android.
        assertEquals(emptyList<String>(), offenders("take/Planted.kt", "import org.example.android.Thing\nimport org.androidx.Thing\n"))
    }

    /** Source with comments blanked (line structure kept); string contents stay, they are rare here. */
    private fun code(source: String): String =
        source.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)) { m -> m.value.replace(Regex("[^\n]"), " ") }
            .replace(Regex("""//[^\n]*"""), "")
}
