package ch.og.dictation.storage

import ch.og.dictation.DictationApplication
import ch.og.dictation.Wiring
import ch.og.dictation.overrideWiring
import ch.og.dictation.wiring
import android.content.pm.PackageManager
import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowApplication

/** The lab build (Gradle `benchmark`) reaches only test folders (ADR-028). */
@RunWith(RobolectricTestRunner::class)
class LabBuildStorageTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val testFolder get() = File(app.noBackupFilesDir, "test")
    private val ownersHistory get() = File(app.noBackupFilesDir, "history")

    @Test fun historyResolvesInsideTheTestFolders() {
        assertEquals("test", app.wiring.storage.layout)
        assertEquals(File(testFolder, "history"), app.wiring.storage.historyRoot)
        assertEquals(File(testFolder, "history-ui-test"), app.wiring.storage.testHistoryRoot("history-ui-test"))
        assertEquals(File(testFolder, "issue54-producer/case"), app.wiring.storage.testHistoryRoot("issue54-producer/case"))
    }

    @Test fun aTemporaryRootKeepsTheLayout() {
        val base = Files.createTempDirectory("storage").toFile()
        app.overrideWiring { it.with(storage = Storage.under(base)) }.use {
            assertEquals(File(base, "no_backup/test/history"), app.wiring.storage.historyRoot)
            val moved = app.wiring.storage.withTestHistory("demo")
            assertEquals(File(base, "no_backup/test/demo"), moved.historyRoot)
        }
        base.deleteRecursively()
    }

    @Test fun aPathOutsideTheTestFoldersThrows() {
        val storage = app.wiring.storage
        for (name in listOf("../history", "..", ".", "", "../../files/lab/history", "a/../../history")) {
            assertThrows(name, IllegalArgumentException::class.java) { storage.testHistoryRoot(name) }
            assertThrows(name, IllegalArgumentException::class.java) { storage.withTestHistory(name) }
        }
        assertThrows(IllegalArgumentException::class.java) { storage.withHistoryRoot(ownersHistory) }
        assertThrows(IllegalArgumentException::class.java) { storage.withHistoryRoot(File(app.filesDir, "lab")) }
        val link = File(testFolder.apply { mkdirs() }, "link")
        Files.createSymbolicLink(link.toPath(), ownersHistory.apply { mkdirs() }.toPath())
        try { assertThrows(IllegalArgumentException::class.java) { storage.testHistoryRoot("link") } } finally { link.delete() }
    }

    @Test fun thereIsNoOwnerPath() {
        val storage = app.wiring.storage
        val handedOut = listOf(storage.historyRoot, storage.testHistoryRoot("history"), storage.testHistoryRoot("a/b"),
            Wiring.forApp(app).storage.historyRoot)
        assertTrue(handedOut.all { it.toPath().startsWith(testFolder.toPath()) })
        assertFalse(handedOut.any { it == ownersHistory })
    }

    @Test fun theFolderSetIsPublishedForTheWrapperAsTheLayoutHasIt() {
        val published = JSONObject(File("src/benchmark/test-folders.json").readText())
        val folders = published.getJSONArray("testFolders").let { list -> (0 until list.length()).map(list::getString) }
        assertEquals(listOf("files/test", "no_backup/test", "shared_prefs/test_history_notices.xml",
            "shared_prefs/test_settings.xml"), folders)
        assertEquals("files/lab", published.getString("labFolder"))
        assertEquals(TestLayout.TEST_FOLDERS, folders)
        assertEquals(TestLayout.LAB_FOLDER, published.getString("labFolder"))
        val root = app.wiring.storage.historyRoot.relativeTo(app.noBackupFilesDir.parentFile!!).invariantSeparatorsPath
        assertTrue("History lies in a published test folder", folders.any { root.startsWith("$it/") })
    }

    @Test fun aSecondProcessGetsTheSameLayout() {
        val main = app.wiring.storage.historyRoot
        ShadowApplication.setProcessName("ch.og.dictation:checkpoint_producer")
        try {
            assertEquals("ch.og.dictation:checkpoint_producer", android.app.Application.getProcessName())
            val second = Wiring.forApp(app).storage
            assertEquals("test", second.layout)
            assertEquals(main, second.historyRoot)
            assertEquals(main, (app as DictationApplication).wiring.storage.historyRoot)
        } finally { ShadowApplication.setProcessName("ch.og.dictation") }
    }

    @Test fun theBuildMarkerNamesTheTestLayout() {
        val marker = app.packageManager.getApplicationInfo(app.packageName, PackageManager.GET_META_DATA)
            .metaData.getString("ch.og.dictation.build")!!
        assertTrue(marker, Regex("layout=test;commit=[0-9a-f]{40};hash=[0-9a-f]{64}").matches(marker))
        assertEquals(app.wiring.storage.layout, marker.substringAfter("layout=").substringBefore(';'))
    }
}
