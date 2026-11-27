package ch.og.dictation.diagnostics

import ch.og.dictation.take.TakeOutcome
import ch.og.dictation.take.TakeResult
import android.os.Process
import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class StartupTraceFileTest {
    private fun rows(file: File, count: Int): List<JSONObject> {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val rows = if (file.exists()) file.readLines().filter { it.isNotBlank() }.map { JSONObject(it) } else emptyList()
            if (rows.size >= count) return rows
            Thread.sleep(10)
        }
        fail("expected $count rows in ${file.name}")
        throw AssertionError()
    }

    /** The receipt seam is the lab folder Storage names; moved here from the device suite (#80). */
    @Test fun persistsCapturedClocksAndOwnProcessBounds() {
        val directory = Files.createTempDirectory("trace").toFile()
        try {
            val trace = StartupTrace(ch.og.dictation.storage.Storage.under(directory).labFolder)
            // Literal historical stamps must survive the asynchronous file writer unchanged.
            trace.event("serviceCreate", ns = 123_000_000L, uptimeMs = 123L)
            trace.event("panelDraw", "test-take")
            trace.event("modelReady", ns = 456_000_000L)
            val file = File(directory, "files/lab/startup.jsonl")
            val rows = rows(file, 3)
            assertEquals("all asynchronous receipts must reach disk", 3, rows.size)
            val create = rows[0]
            assertEquals(123_000_000L, create.getLong("ns"))
            assertEquals(123L, create.getLong("uptimeMs"))
            assertEquals(Process.getStartRequestedUptimeMillis(), create.getLong("processStartRequestedUptimeMs"))
            assertEquals(Process.getStartUptimeMillis(), create.getLong("processStartUptimeMs"))
            val draw = rows[1]
            assertEquals("test-take", draw.getString("takeId"))
            assertTrue(draw.getLong("uptimeMs") >= Process.getStartUptimeMillis())
            assertFalse(draw.has("processStartUptimeMs"))
            assertEquals(create.getString("processId"), draw.getString("processId"))
            assertEquals(456_000_000L, rows[2].getLong("ns"))
            assertFalse("observer uptime must not be paired with a historical event", rows[2].has("uptimeMs"))
            assertTrue(file.length() <= LabReceipts.MAX_BYTES)
            assertEquals(listOf("startup.jsonl"), File(directory, "files/lab").list()!!.toList())
            assertEquals("nothing outside the lab folder", listOf("files"), directory.list()!!.toList())
        } finally { directory.deleteRecursively() }
    }

    @Test fun eachReceiptKeepsItsProtocolFileAndRotatesAtTheCap() {
        val directory = Files.createTempDirectory("trace").toFile()
        try {
            val trace = StartupTrace(File(directory, "lab"))
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

            // A row that would cross the cap rotates the file instead of being lost (#64).
            val bound = LabReceipts.MAX_BYTES
            live.writeBytes(ByteArray((bound - 10).toInt()) { ' '.code.toByte() })
            trace.liveResult(take, JSONObject(), TakeResult(TakeOutcome.CANCELLED, null, emptyMap()))
            trace.liveResult(take, JSONObject(), TakeResult(TakeOutcome.FAILED, null, emptyMap()))
            val after = rows(live, 2)
            assertEquals(listOf("CANCELLED", "FAILED"), after.map { it.getString("outcome") })
            assertEquals(bound - 10, LabReceipts.previous(live).length())
        } finally { directory.deleteRecursively() }
    }
}
