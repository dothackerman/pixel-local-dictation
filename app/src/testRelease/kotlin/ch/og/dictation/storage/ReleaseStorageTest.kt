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
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.pm.PackageManager
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test fun historyNoticesAndSettingsAreTheOwners() {
        // What the owner's daily keyboard has saved under these names stays in force.
        app.getSharedPreferences("history_notices", 0).edit().putStringSet("pending", setOf("INCOMPLETE")).commit()
        app.getSharedPreferences("settings", 0).edit().putBoolean("benchmark", true).commit()
        assertEquals(setOf(HistoryNotice.INCOMPLETE), historyNotices(app).pending())
        assertTrue(app.wiring.storage.settings.flag("benchmark"))
        historyNotices(app).record(HistoryNotice.SAVE)
        app.wiring.storage.settings.setFlag("benchmark", false)
        assertEquals(setOf("INCOMPLETE", "SAVE"), app.getSharedPreferences("history_notices", 0).getStringSet("pending", null))
        assertFalse(app.getSharedPreferences("settings", 0).getBoolean("benchmark", true))
        assertTrue(File(app.dataDir, "shared_prefs/history_notices.xml").isFile)
        assertFalse(File(app.dataDir, "shared_prefs/test_history_notices.xml").exists())
        assertFalse(File(app.dataDir, "shared_prefs/test_settings.xml").exists())
        assertEquals(setOf("INCOMPLETE", "SAVE"), Wiring.forApp(app).storage.historyNotices.names("pending"))
    }

    @Test fun aTemporaryRootKeepsNoticesAndSettingsUnderTheOwnersNames() {
        val base = Files.createTempDirectory("storage").toFile()
        Storage.under(base).historyNotices.setNames("pending", setOf("DELETE"))
        Storage.under(base).settings.setFlag("benchmark", true)
        assertEquals(setOf("DELETE"), Storage.under(base).historyNotices.names("pending"))
        assertTrue(Storage.under(base).settings.flag("benchmark"))
        assertEquals(setOf("history_notices.xml", "settings.xml"), File(base, "shared_prefs").list()!!.toSet())
        base.deleteRecursively()
    }

    @Test fun copyPutsPlainTextOnTheSystemClipboard() {
        val system = app.getSystemService(ClipboardManager::class.java)
        system.clearPrimaryClip()
        copyTranscript(app, "Owner transcript")
        val clip = system.primaryClip!!
        assertEquals("Owner transcript", clip.getItemAt(0).text.toString())
        assertEquals("Transcript", clip.description.label.toString())
        assertFalse("no sensitive-content flag, by owner decision", clip.description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) ?: false)
        app.wiring.storage.clipboard.copy("Second owner transcript")
        assertEquals("Second owner transcript", system.primaryClip!!.getItemAt(0).text.toString())
        // A temporary root stands in for the system clipboard too, so host tests leave the real one alone.
        val base = Files.createTempDirectory("storage").toFile()
        Storage.under(base).clipboard.copy("Temporary transcript")
        assertEquals("Temporary transcript", File(base, "clipboard").readText())
        assertEquals("Second owner transcript", system.primaryClip!!.getItemAt(0).text.toString())
        base.deleteRecursively()
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

    @Test fun recordingsAndTheLabFolderAreTheOwners() {
        val storage = app.wiring.storage
        assertEquals(File(app.filesDir, "benchmark"), storage.recordingsRoot)
        assertTrue(storage.recordingsRoot.path, storage.recordingsRoot.path.endsWith("/files/benchmark"))
        // The speech model is read where the wrapper put it.
        assertEquals(File(app.filesDir, "lab"), storage.labFolder)
        assertTrue(storage.labFolder.path, storage.labFolder.path.endsWith("/files/lab"))
        assertEquals(storage.recordingsRoot, Wiring.forApp(app).storage.recordingsRoot)
        val base = Files.createTempDirectory("storage").toFile()
        assertEquals(File(base, "files/benchmark"), Storage.under(base).recordingsRoot)
        assertEquals(File(base, "files/lab"), Storage.under(base).labFolder)
        base.deleteRecursively()
    }

    @Test fun releaseNeverSavesRecordingsWhateverTheOldSwitchSays() {
        assertFalse(app.wiring.storage.savesRecordings)
        // The owner's daily keyboard may still hold the switch of an earlier build, switched on.
        app.getSharedPreferences("settings", 0).edit().putBoolean("benchmark", true).commit()
        assertFalse(BenchmarkStore.isEnabled(app))
        BenchmarkStore.setEnabled(app, true)
        assertFalse(BenchmarkStore.isEnabled(app))
    }

    @Test fun theTimingReceiptFolderIsReserved() {
        assertEquals("files/timing-receipts", OwnerLayout.TIMING_RECEIPTS)
    }

    @Test fun theBuildMarkerNamesTheOwnerLayout() {
        val marker = app.packageManager.getApplicationInfo(app.packageName, PackageManager.GET_META_DATA)
            .metaData.getString("ch.og.dictation.build")!!
        assertTrue(marker, Regex("layout=owner;commit=[0-9a-f]{40};hash=[0-9a-f]{64}").matches(marker))
    }

    /** Release keeps its package, so the owner's app updates in place; the lab app is another (#98). */
    @Test fun releaseKeepsItsPackageAndKeyboardLabel() {
        assertEquals("ch.og.dictation", app.packageName)
        assertEquals("Local dictation", app.getString(R.string.ime_label))
    }
}
