package ch.og.dictation.storage

import ch.og.dictation.R
import ch.og.dictation.TakeBench
import ch.og.dictation.diagnostics.BenchmarkStore
import android.view.View
import java.io.File
import org.json.JSONObject
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

/** The lab build keeps the Benchmark switch; its recordings are born in a test folder (ADR-028). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LabBuildRecordingsTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val recordings get() = File(app.filesDir, "test/recordings")
    private val bench = TakeBench()

    @After fun tearDown() {
        bench.close()
        BenchmarkStore.setEnabled(app, false)
        recordings.deleteRecursively()
    }

    private fun take() = with(bench) {
        step("open on a text field") { open() }
        step("speech") { speak() }
        step("Stop") { press(R.id.microphone) }
        step("microphone released") { releaseMicrophone() }
        step("text prepared") { finish() }
        assertEquals(1, commits())
    }

    @Test fun withTheSwitchOnAnEligibleTakeIsSavedInTheTestRecordingsFolder() {
        BenchmarkStore.setEnabled(app, true)
        take()
        assertEquals(View.VISIBLE, bench.panel.findViewById<View>(R.id.benchmark_banner).visibility)
        val saved = recordings.listFiles()!!.sortedBy { it.name }
        assertEquals(saved.toString(), listOf("json", "wav"), saved.map { it.extension })
        assertEquals("one take, one base name", 1, saved.map { it.nameWithoutExtension }.toSet().size)
        assertEquals("RIFF", String(saved[1].readBytes(), 0, 4, Charsets.US_ASCII))
        assertEquals("first words", JSONObject(saved[0].readText()).getString("text"))
        assertFalse("never the owner's recordings", File(app.filesDir, "benchmark").exists())
        assertEquals(1, BenchmarkStore.of(app).takeCount())
    }

    @Test fun withTheSwitchOffNothingIsSaved() {
        take()
        assertFalse(recordings.exists())
        assertFalse(File(app.filesDir, "benchmark").exists())
        assertTrue(BenchmarkStore.of(app).deleteAll())
    }
}
