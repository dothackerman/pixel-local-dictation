package ch.og.dictation

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure lift (#85): take state and decisions are the take controller's; the service forwards
 * events and carries out effects, and Wiring holds neither the controller nor the scheduler.
 */
class TakeControllerLiftTest {
    private val root = File("src/main/kotlin/ch/og/dictation")

    /** Source with comments blanked. */
    private fun code(name: String): String = File(root, name).readText()
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "").replace(Regex("""//[^\n]*"""), "")

    /** The service's mutable fields are its own business only: the panel, rotation counting, geometry and startup. */
    @Test fun theServiceKeepsNoTakeState() {
        val fields = Regex("""^\s*private var (\w+)""", RegexOption.MULTILINE).findAll(code("DictationImeService.kt")).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("shownFrame", "panel", "panelConfiguration", "rotationView", "displayRotation", "rotationChanges",
            "configurationOrientation", "rotationsAtConfiguration", "lastConfiguration", "displayListening", "startup", "startupTake",
            "geometryTake", "geometryUntilNs", "geometryRows", "contentTopInsets", "visibleTopInsets", "touchableInsets"), fields)
    }

    /** What decides a take is not reachable from the service except through the controller. */
    @Test fun theServiceDecidesNothingAboutATake() {
        val service = code("DictationImeService.kt")
        val used = listOf("InputSession", "EditorEventPolicy", "InsertReadBack", "commitToInput", "DeliveryHold", "PanelPhase",
            "PanelState", "panelFrame(", "TakeOutcome", ".holdDelivery(", ".releaseDelivery(", ".interrupt", ".stop()", ".cancel(")
            .filter { it in service }
        assertEquals(emptyList<String>(), used)
        assertTrue(Regex("""private val controller = TakeController\(""").containsMatchIn(service))
    }

    @Test fun wiringHoldsNeitherTheControllerNorTheScheduler() {
        val wiring = code("Wiring.kt")
        assertEquals(emptyList<String>(), listOf("TakeController", "TakeScheduler", "TakeHost", "TakeOpener").filter { it in wiring })
        val take = File(root, "take").walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { Regex("""\bWiring\b|\bwiring\b""").containsMatchIn(code("take/" + it.name)) }.map { it.name }.toList()
        assertEquals("the take package knows no Wiring", emptyList<String>(), take)
    }
}
