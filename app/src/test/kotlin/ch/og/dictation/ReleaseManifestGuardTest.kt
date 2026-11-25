package ch.og.dictation

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element

/**
 * Fixed through every refactoring step (#65): the phone stores the enabled keyboard by class
 * name, and the release manifest keeps its permissions (no INTERNET, R3) and components.
 */
class ReleaseManifestGuardTest {
    private val android = "http://schemas.android.com/apk/res/android"
    private val tools = "http://schemas.android.com/tools"
    private val manifest by lazy {
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File("src/main/AndroidManifest.xml")).documentElement
    }

    private fun elements(tag: String) = manifest.getElementsByTagName(tag).let { list ->
        (0 until list.length).map { list.item(it) as Element }
    }

    @Test fun serviceAndApplicationKeepTheirFullyQualifiedNames() {
        assertEquals("ch.og.dictation.DictationImeService", DictationImeService::class.java.name)
        assertEquals("ch.og.dictation.DictationApplication", DictationApplication::class.java.name)
        assertEquals("ch.og.dictation", manifest.getAttribute("package").ifEmpty { "ch.og.dictation" })
        assertEquals(".DictationApplication", elements("application").single().getAttributeNS(android, "name"))
    }

    @Test fun releasePermissionsAreOnlyTheMicrophone() {
        val kept = elements("uses-permission").filter { it.getAttributeNS(tools, "node") != "remove" }
            .map { it.getAttributeNS(android, "name") }
        assertEquals(listOf("android.permission.RECORD_AUDIO"), kept)
    }

    @Test fun componentsAndTheirExportsAreUnchanged() {
        val components = elements("service") + elements("activity") + elements("receiver") + elements("provider")
        // Exported components keep their full names: Android's keyboard list and launcher hold them.
        assertEquals(listOf("service .DictationImeService", "activity .SettingsActivity"),
            components.filter { it.getAttributeNS(android, "exported") == "true" }
                .map { "${it.tagName} ${it.getAttributeNS(android, "name")}" })
        // Private components may change package, never kind or class.
        assertEquals(listOf("service DictationImeService", "activity SettingsActivity", "activity HistoryActivity",
            "activity DictionaryActivity", "activity PermissionActivity"),
            components.map { "${it.tagName} ${it.getAttributeNS(android, "name").substringAfterLast('.')}" })
        assertEquals(listOf("false", "false", "false"),
            components.drop(2).map { it.getAttributeNS(android, "exported") })
        val ime = elements("service").single()
        assertEquals("android.permission.BIND_INPUT_METHOD", ime.getAttributeNS(android, "permission"))
    }

    /** FUTO and Android read subtypes by enabled count, so a later voice subtype can be skipped (#98). */
    @Test fun theKeyboardDeclaresOneSubtypeAndItIsVoice() {
        val method = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File("src/main/res/xml/method.xml")).documentElement
        val subtypes = method.getElementsByTagName("subtype").let { list -> (0 until list.length).map { list.item(it) as Element } }
        assertEquals(listOf("voice"), subtypes.map { it.getAttributeNS(android, "imeSubtypeMode") })
    }
}

