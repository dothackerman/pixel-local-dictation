package ch.og.dictation.storage

import ch.og.dictation.DictationApplication
import ch.og.dictation.R
import ch.og.dictation.Wiring
import ch.og.dictation.history.copyTranscript
import ch.og.dictation.wiring
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowApplication

/** The lab build is its own app (#98): release's paths in its own storage, its own clipboard and label. */
@RunWith(RobolectricTestRunner::class)
class LabBuildStorageTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Test fun theLabAppKeepsItsDataWhereReleaseDoesInItsOwnStorage() {
        val storage = app.wiring.storage
        assertEquals(File(app.noBackupFilesDir, "history"), storage.historyRoot)
        assertEquals(File(app.filesDir, "dictionary"), storage.dictionaryRoot)
        assertEquals(File(app.filesDir, "benchmark"), storage.recordingsRoot)
        assertEquals(File(app.filesDir, "lab"), storage.labFolder)
        assertEquals(File(app.noBackupFilesDir, "test/history-ui-test"), storage.testHistoryRoot("history-ui-test"))
        assertEquals(File(app.filesDir, "test/scratch"), storage.scratchFolder)
        storage.settings.setFlag("benchmark", true)
        assertTrue(File(app.dataDir, "shared_prefs/settings.xml").isFile)
        storage.settings.setFlag("benchmark", false)
    }

    @Test fun theLabBuildSavesRecordings() {
        assertTrue(app.wiring.storage.savesRecordings)
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
        assertEquals("the owner's clipboard", system.primaryClip!!.getItemAt(0).text.toString())
        // A temporary root: its stand-in for the system clipboard is never written either.
        val base = Files.createTempDirectory("storage").toFile()
        val temporary = Storage.under(base)
        temporary.clipboard.copy("Third test transcript")
        assertEquals("Third test transcript", temporary.copiedText)
        assertFalse(File(base, "clipboard").exists())
        assertEquals("the owner's clipboard", system.primaryClip!!.getItemAt(0).text.toString())
        base.deleteRecursively()
    }

    @Test fun aSecondProcessGetsTheSameStorage() {
        val main = app.wiring.storage.historyRoot
        ShadowApplication.setProcessName(app.packageName + ":checkpoint_producer")
        try {
            assertEquals(main, Wiring.forApp(app).storage.historyRoot)
            assertEquals(main, (app as DictationApplication).wiring.storage.historyRoot)
        } finally { ShadowApplication.setProcessName(app.packageName) }
    }

    /** The phone wrapper tells the lab build by this marker. */
    @Test fun theBuildMarkerNamesTheTestLayout() {
        val marker = app.packageManager.getApplicationInfo(app.packageName, PackageManager.GET_META_DATA)
            .metaData.getString("ch.og.dictation.build")!!
        assertTrue(marker, Regex("layout=test;commit=[0-9a-f]{40};hash=[0-9a-f]{64}").matches(marker))
    }

    /** The lab app is its own package beside release (#98): Android gives it its own storage. The code
     *  namespace stays; the keyboard label tells the two apart in FUTO's voice input dropdown. */
    @Test fun theLabAppIsItsOwnPackageWithItsOwnKeyboardLabel() {
        assertEquals("ch.og.dictation.lab", app.packageName)
        assertEquals("ch.og.dictation", R::class.java.packageName)
        assertEquals("Local dictation lab", app.getString(R.string.ime_label))
    }
}
