package ch.og.dictation

import android.content.ContextWrapper
import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real receipt seam; cold IME lifecycle placement still needs a supervised handoff. */
@RunWith(AndroidJUnit4::class)
class StartupTraceTest {
    @Test fun persistsCapturedClocksAndOwnProcessBounds() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(target.cacheDir, "startup-trace-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(target) { override fun getFilesDir() = directory }
        try {
            val trace = StartupTrace(context)
            // Literal historical stamps must survive the asynchronous file writer unchanged.
            trace.event("serviceCreate", ns = 123_000_000L, uptimeMs = 123L)
            trace.event("panelDraw", "test-take")
            trace.event("modelReady", ns = 456_000_000L)
            val file = File(directory, "lab/startup.jsonl")
            val deadline = SystemClock.uptimeMillis() + 5_000
            var rows = emptyList<JSONObject>()
            while (SystemClock.uptimeMillis() < deadline) {
                rows = runCatching { file.readLines().map { JSONObject(it) } }.getOrDefault(emptyList())
                if (rows.size == 3) break
                SystemClock.sleep(10)
            }
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
            assertTrue(file.length() <= 2 * 1024 * 1024)
        } finally {
            directory.deleteRecursively()
        }
    }
}
