package ch.og.dictation.storage

import ch.og.dictation.DictationApplication
import ch.og.dictation.Wiring
import ch.og.dictation.dictionary.DictionaryStore
import ch.og.dictation.overrideWiring
import ch.og.dictation.wiring
import android.content.pm.PackageManager
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowApplication

/** Release is the owner's daily build: literal owner paths and the owner marker (ADR-028). */
@RunWith(RobolectricTestRunner::class)
class ReleaseStorageTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Test fun historyIsTheOwnersHistory() {
        val root = app.wiring.storage.historyRoot
        assertEquals("owner", app.wiring.storage.layout)
        assertEquals(File(app.noBackupFilesDir, "history"), root)
        assertTrue(root.path, root.path.endsWith("/no_backup/history"))
    }

    @Test fun theDictionaryIsTheOwnersDictionary() {
        val root = app.wiring.storage.dictionaryRoot
        assertEquals(File(app.filesDir, "dictionary"), root)
        assertTrue(root.path, root.path.endsWith("/files/dictionary"))
        // The engine saves and reads there: the owner's list, where it has always been.
        val store = DictionaryStore(root, File(root, "none"))
        store.commit(store.prepare(listOf("Owner term")), store.read())
        assertEquals(listOf("Owner term"), app.wiring.engine.dictionaryStore().read().entries)
        assertTrue(File(app.filesDir, "dictionary/current.json").isFile)
    }

    @Test fun aTemporaryRootKeepsTheLayoutAndNoOtherHistoryRootExists() {
        val base = Files.createTempDirectory("storage").toFile()
        app.overrideWiring { it.with(storage = Storage.under(base)) }.use {
            assertEquals(File(base, "no_backup/history"), app.wiring.storage.historyRoot)
            assertEquals(File(base, "files/dictionary"), app.wiring.storage.dictionaryRoot)
        }
        assertThrows(IllegalArgumentException::class.java) {
            app.wiring.storage.withHistoryRoot(File(app.noBackupFilesDir, "test/history"))
        }
        base.deleteRecursively()
    }

    @Test fun aSecondProcessGetsTheSameLayout() {
        val main = app.wiring.storage.historyRoot
        ShadowApplication.setProcessName("ch.og.dictation:other")
        try {
            assertEquals(main, Wiring.forApp(app).storage.historyRoot)
            assertEquals(main, (app as DictationApplication).wiring.storage.historyRoot)
        } finally { ShadowApplication.setProcessName("ch.og.dictation") }
    }

    @Test fun theTimingReceiptFolderIsReserved() {
        assertEquals("files/timing-receipts", OwnerLayout.TIMING_RECEIPTS)
    }

    @Test fun theBuildMarkerNamesTheOwnerLayout() {
        val marker = app.packageManager.getApplicationInfo(app.packageName, PackageManager.GET_META_DATA)
            .metaData.getString("ch.og.dictation.build")!!
        assertTrue(marker, Regex("layout=owner;commit=[0-9a-f]{40};hash=[0-9a-f]{64}").matches(marker))
    }
}
