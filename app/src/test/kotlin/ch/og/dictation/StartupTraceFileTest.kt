package ch.og.dictation

import android.content.ContextWrapper
import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class StartupTraceFileTest {
    private fun rows(file: File, count: Int): List<JSONObject> {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val rows = if (file.exists()) file.readLines().map { JSONObject(it) } else emptyList()
            if (rows.size >= count) return rows
            Thread.sleep(10)
        }
        fail("expected $count rows in ${file.name}")
        throw AssertionError()
    }

    @Test fun eachReceiptKeepsItsProtocolFileAndStopsBeforeTwoMebibytes() {
        val directory = Files.createTempDirectory("trace").toFile()
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) { override fun getFilesDir() = directory }
        try {
            val trace = StartupTrace(context)
            val take = trace.beginTake(modelReady = true)
            trace.geometry("preDraw", take, 7L, JSONObject().put("panelHeightPx", 1))
            trace.liveResult(take, JSONObject().put("verified", true),
                TakeResult(TakeOutcome.COMPLETED, "never written", mapOf("finalReady" to 3L), true, true))
            val startup = rows(File(directory, "lab/startup.jsonl"), 1).single()
            assertEquals("ime-startup-v1", startup.getString("protocol"))
            assertEquals("panelRequested", startup.getString("phase"))
            assertTrue(startup.getBoolean("modelReadyAtPanelRequest"))
            val geometry = rows(File(directory, "lab/geometry.jsonl"), 1).single()
            assertEquals("ime-geometry-v1", geometry.getString("protocol"))
            assertEquals(take, geometry.getString("takeId"))
            assertEquals(7L, geometry.getLong("ns"))
            val live = File(directory, "lab/live-results.jsonl")
            val result = rows(live, 1).single()
            assertEquals("ime-candidate-live-v1", result.getString("protocol"))
            assertEquals("COMPLETED", result.getString("outcome"))
            assertEquals(3L, result.getJSONObject("eventsNs").getLong("finalReady"))
            assertFalse("no recognized text in receipts", live.readText().contains("never written"))

            // A file just under the bound refuses a row that would cross it.
            val bound = 2 * 1024 * 1024L
            live.writeBytes(ByteArray((bound - 10).toInt()) { ' '.code.toByte() })
            trace.liveResult(take, JSONObject(), TakeResult(TakeOutcome.CANCELLED, null, emptyMap()))
            trace.event("marker")
            rows(File(directory, "lab/startup.jsonl"), 2) // the single writer has passed the refused row
            assertEquals(bound - 10, live.length())
        } finally { directory.deleteRecursively() }
    }
}
