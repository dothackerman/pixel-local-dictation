package ch.og.dictation.storage

import ch.og.dictation.R
import ch.og.dictation.TakeBench
import ch.og.dictation.history.HistoryCompletion
import ch.og.dictation.history.HistoryDelivery
import ch.og.dictation.history.HistoryIo
import android.view.View
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/** A take on release: History keeps it, nothing is recorded to storage (ADR-010 amendment). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReleaseTakeTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val recordings get() = File(app.filesDir, "benchmark")
    private val bench = TakeBench()

    @After fun tearDown() { bench.close(); recordings.deleteRecursively() }

    private fun files(folder: File) = folder.walkTopDown().filter { it.isFile }.map { it.relativeTo(folder).path }.toSet()

    @Test fun aFinishedTakeWritesNothingUnderTheRecordingsFolderAndHistoryStillSavesIt() {
        // An earlier build left its switch on and one recording.
        app.getSharedPreferences("settings", 0).edit().putBoolean("benchmark", true).commit()
        recordings.mkdirs()
        File(recordings, "20261001-080000-000.wav").writeBytes(ByteArray(44))
        val filesBefore = files(app.filesDir)
        with(bench) {
            step("open on a text field") { open() }
            assertEquals("no banner: nothing is being saved", View.GONE, panel.findViewById<View>(R.id.benchmark_banner).visibility)
            step("speech") { speak() }
            step("Stop") { press(R.id.microphone) }
            step("microphone released") { releaseMicrophone() }
            step("text prepared") { finish() }
            assertEquals(1, commits())
        }
        assertEquals(setOf("20261001-080000-000.wav"), recordings.list()!!.toSet())
        assertEquals("no audio or metadata anywhere in the files folder", filesBefore, files(app.filesDir))
        assertFalse(File(app.filesDir, "test").exists())
        val root = File(app.noBackupFilesDir, "history")
        val saved = HistoryIo.executor.submit<List<ch.og.dictation.history.HistoryEntry>> { HistoryIo.store(root).list() }
            .get(2, TimeUnit.SECONDS).single()
        assertEquals("first words", saved.text)
        assertEquals(HistoryDelivery.ACCEPTED, saved.delivery)
        assertEquals(HistoryCompletion.COMPLETE, saved.completion)
        assertTrue(root.list()!!.any { it.endsWith(".history") })
    }
}
