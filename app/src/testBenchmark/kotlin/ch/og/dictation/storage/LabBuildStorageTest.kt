package ch.og.dictation.storage

import ch.og.dictation.DictationApplication
import ch.og.dictation.R
import ch.og.dictation.Wiring
import ch.og.dictation.diagnostics.BenchmarkStore
import ch.og.dictation.dictionary.DictionaryStore
import ch.og.dictation.history.HistoryNotice
import ch.og.dictation.history.copyTranscript
import ch.og.dictation.history.historyNotices
import ch.og.dictation.overrideWiring
import ch.og.dictation.wiring
import android.content.ClipData
import android.content.ClipboardManager
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

    @Test fun theDictionaryResolvesInsideTheTestFolders() {
        assertEquals(File(app.filesDir, "test/dictionary"), app.wiring.storage.dictionaryRoot)
        assertEquals(File(app.filesDir, "test/dictionary"), Wiring.forApp(app).storage.dictionaryRoot)
        val published = JSONObject(File("src/benchmark/test-folders.json").readText()).getJSONArray("testFolders")
        val root = app.wiring.storage.dictionaryRoot.relativeTo(app.filesDir.parentFile!!).invariantSeparatorsPath
        assertTrue("the Dictionary lies in a published test folder",
            (0 until published.length()).any { root.startsWith(published.getString(it) + "/") })
    }

    @Test fun recordingsResolveInsideTheTestFoldersAndTheLabFolderIsTheLabFolder() {
        val storage = app.wiring.storage
        assertTrue(storage.savesRecordings)
        assertEquals(File(app.filesDir, "test/recordings"), storage.recordingsRoot)
        assertEquals(File(app.filesDir, "test/recordings"), Wiring.forApp(app).storage.recordingsRoot)
        assertEquals(File(app.filesDir, "test/scratch"), storage.scratchFolder)
        assertEquals(File(app.filesDir, "lab"), storage.labFolder)
        val published = JSONObject(File("src/benchmark/test-folders.json").readText())
        val folders = published.getJSONArray("testFolders").let { list -> (0 until list.length()).map(list::getString) }
        val data = app.filesDir.parentFile!!
        for (root in listOf(storage.recordingsRoot, storage.scratchFolder))
            assertTrue("$root lies in a published test folder",
                folders.any { root.relativeTo(data).invariantSeparatorsPath.startsWith("$it/") })
        assertEquals(published.getString("labFolder"), storage.labFolder.relativeTo(data).invariantSeparatorsPath)
        assertFalse("never the owner's recordings", storage.recordingsRoot == File(app.filesDir, "benchmark"))
        val base = Files.createTempDirectory("storage").toFile()
        assertEquals(File(base, "files/test/recordings"), Storage.under(base).recordingsRoot)
        assertEquals(File(base, "files/lab"), Storage.under(base).labFolder)
        base.deleteRecursively()
    }

    /** Settings, the Dictionary screen, the keyboard and the checkpoint producer all read through this engine. */
    @Test fun everyProcessReadsTheTestDictionaryAndNeverTheOwners() {
        fun save(root: File, term: String) = DictionaryStore(root, File(root, "none")).let { it.commit(it.prepare(listOf(term)), it.read()) }
        save(File(app.filesDir, "dictionary"), "Owner term")
        assertFalse("nothing saved in the test folders: the provisioned list applies", app.wiring.engine.dictionaryStore().read().edited)
        save(File(app.filesDir, "test/dictionary"), "Test term")
        assertEquals(listOf("Test term"), app.wiring.engine.dictionaryStore().read().entries)
        ShadowApplication.setProcessName(app.packageName + ":checkpoint_producer")
        try {
            assertEquals(listOf("Test term"), Wiring.forApp(app).engine.dictionaryStore().read().entries)
        } finally { ShadowApplication.setProcessName(app.packageName) }
    }

    @Test fun historyNoticesAndSettingsResolveInsideTheTestFolders() {
        val storage = app.wiring.storage
        storage.historyNotices.setNames("pending", setOf("SAVE"))
        storage.settings.setFlag("benchmark", true)
        assertEquals(setOf("SAVE"), app.getSharedPreferences("test_history_notices", 0).getStringSet("pending", null))
        assertTrue(app.getSharedPreferences("test_settings", 0).getBoolean("benchmark", false))
        assertTrue(File(app.dataDir, "shared_prefs/test_history_notices.xml").isFile)
        assertFalse("the owner's notices stay untouched", app.getSharedPreferences("history_notices", 0).contains("pending"))
        assertFalse("the owner's settings stay untouched", app.getSharedPreferences("settings", 0).contains("benchmark"))
        // The keyboard, the History page and Settings read and write there, in every process.
        assertEquals(setOf(HistoryNotice.SAVE), historyNotices(app).pending())
        assertTrue(BenchmarkStore.isEnabled(app))
        historyNotices(app).dismiss(HistoryNotice.SAVE)
        BenchmarkStore.setEnabled(app, false)
        ShadowApplication.setProcessName(app.packageName + ":checkpoint_producer")
        try {
            val second = Wiring.forApp(app).storage
            assertEquals(emptySet<String>(), second.historyNotices.names("pending"))
            assertFalse(second.settings.flag("benchmark"))
        } finally { ShadowApplication.setProcessName(app.packageName) }
        val published = JSONObject(File("src/benchmark/test-folders.json").readText()).getJSONArray("testFolders")
        val names = (0 until published.length()).map(published::getString)
        assertTrue(names.containsAll(listOf("shared_prefs/test_history_notices.xml", "shared_prefs/test_settings.xml")))
    }

    @Test fun aTemporaryRootKeepsNoticesAndSettingsInTheTestFolders() {
        val base = Files.createTempDirectory("storage").toFile()
        Storage.under(base).historyNotices.setNames("pending", setOf("DELETE", "RECEIPT"))
        Storage.under(base).settings.setFlag("benchmark", true)
        assertEquals("saved, not held in memory", setOf("DELETE", "RECEIPT"), Storage.under(base).historyNotices.names("pending"))
        assertTrue(Storage.under(base).settings.flag("benchmark"))
        assertFalse(Storage.under(base).settings.flag("other"))
        assertEquals(setOf("test_history_notices.xml", "test_settings.xml"), File(base, "shared_prefs").list()!!.toSet())
        Storage.under(base).historyNotices.setNames("pending", emptySet())
        assertEquals(emptySet<String>(), Storage.under(base).historyNotices.names("pending"))
        base.deleteRecursively()
    }

    @Test fun copyStaysInTheAppAndNeverReachesTheSystemClipboard() {
        val system = app.getSystemService(ClipboardManager::class.java)
        system.setPrimaryClip(ClipData.newPlainText("owner", "the owner's clipboard"))
        val storage = app.wiring.storage
        storage.clipboard.copy("Test transcript")
        assertEquals("Test transcript", storage.copiedText)
        // History's copy and the panel's Copy go the same way.
        copyTranscript(app, "Second test transcript")
        assertEquals("Second test transcript", storage.copiedText)
        assertEquals("one clipboard, whichever History root", "Second test transcript", storage.withTestHistory("demo").copiedText)
        assertEquals("the owner's clipboard", system.primaryClip!!.getItemAt(0).text.toString())
        assertEquals(1, system.primaryClip!!.itemCount)
        // A temporary root: its stand-in for the system clipboard is never written either.
        val base = Files.createTempDirectory("storage").toFile()
        val temporary = Storage.under(base)
        assertEquals(null, temporary.copiedText)
        temporary.clipboard.copy("Third test transcript")
        assertEquals("Third test transcript", temporary.copiedText)
        assertFalse(File(base, "clipboard").exists())
        assertEquals("the owner's clipboard", system.primaryClip!!.getItemAt(0).text.toString())
        base.deleteRecursively()
    }

    @Test fun aTemporaryRootKeepsTheLayout() {
        val base = Files.createTempDirectory("storage").toFile()
        app.overrideWiring { it.with(storage = Storage.under(base)) }.use {
            assertEquals(File(base, "no_backup/test/history"), app.wiring.storage.historyRoot)
            assertEquals(File(base, "files/test/dictionary"), app.wiring.storage.dictionaryRoot)
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
        assertTrue(storage.dictionaryRoot.toPath().startsWith(File(app.filesDir, "test").toPath()))
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
        ShadowApplication.setProcessName(app.packageName + ":checkpoint_producer")
        try {
            assertEquals(app.packageName + ":checkpoint_producer", android.app.Application.getProcessName())
            val second = Wiring.forApp(app).storage
            assertEquals("test", second.layout)
            assertEquals(main, second.historyRoot)
            assertEquals(main, (app as DictationApplication).wiring.storage.historyRoot)
        } finally { ShadowApplication.setProcessName(app.packageName) }
    }

    @Test fun theBuildMarkerNamesTheTestLayout() {
        val marker = app.packageManager.getApplicationInfo(app.packageName, PackageManager.GET_META_DATA)
            .metaData.getString("ch.og.dictation.build")!!
        assertTrue(marker, Regex("layout=test;commit=[0-9a-f]{40};hash=[0-9a-f]{64}").matches(marker))
        assertEquals(app.wiring.storage.layout, marker.substringAfter("layout=").substringBefore(';'))
    }

    /** The lab app is its own package beside release (#98): Android gives it its own storage. The code
     *  namespace stays; the keyboard label tells the two apart in FUTO's voice input dropdown. */
    @Test fun theLabAppIsItsOwnPackageWithItsOwnKeyboardLabel() {
        assertEquals("ch.og.dictation.lab", app.packageName)
        assertEquals("ch.og.dictation", R::class.java.packageName)
        assertEquals("Local dictation lab", app.getString(R.string.ime_label))
    }
}
