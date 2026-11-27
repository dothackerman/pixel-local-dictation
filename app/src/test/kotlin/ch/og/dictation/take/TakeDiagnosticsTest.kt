package ch.og.dictation.take

import ch.og.dictation.diagnostics.RotationEvent
import ch.og.dictation.diagnostics.RotationTrace
import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Take diagnostics (#84): the trace gets the take's facts as arguments and writes the same rows as before. */
class TakeDiagnosticsTest {
    private val file: File = Files.createTempDirectory("take-diagnostics").resolve("trace.jsonl").toFile()
    private val trace = RotationTrace(file)
    private val diagnostics = TakeDiagnostics().also { it.attach(trace) }

    private fun rows(): List<JSONObject> { assertTrue(trace.flush()); return if (file.isFile) file.readLines().map(::JSONObject) else emptyList() }
    private fun events() = rows().map { it.getString("event") }

    @Test fun anEventCarriesExactlyTheFactsItWasGiven() {
        diagnostics.event(RotationEvent.INPUT_START_DONE,
            TakeTraceFacts(active = true, liveSession = false, view = 2, take = 3, rotation = 1, rotations = 4),
            flag = true, retention = EditorRetention.PASSWORD)
        diagnostics.event(RotationEvent.SERVICE_CREATE,
            TakeTraceFacts(active = false, liveSession = false, view = null, take = null, rotation = null, rotations = 0))
        val (first, second) = rows()
        assertEquals(true, first.getBoolean("active"))
        assertEquals("the live-session fact is the trace's authority column", false, first.getBoolean("authority"))
        assertEquals(true, first.getBoolean("flag"))
        assertEquals(2L, first.getLong("view"))
        assertEquals(3L, first.getLong("take"))
        assertEquals(1, first.getInt("rotation"))
        assertEquals(4L, first.getLong("rotations"))
        assertEquals("PASSWORD", first.getString("retention"))
        assertEquals(setOf("protocol", "process", "service", "sequence", "ns", "event", "active", "authority", "rotations"),
            second.keys().asSequence().toSet())
    }

    @Test fun aTakeEventCarriesOnlyItsTakeNumber() {
        diagnostics.takeEvent(RotationEvent.CAPTURE_PAUSED, 7)
        val row = rows().single()
        assertEquals("CAPTURE_PAUSED", row.getString("event"))
        assertEquals(setOf("protocol", "process", "service", "sequence", "ns", "event", "take"), row.keys().asSequence().toSet())
        assertEquals(7L, row.getLong("take"))
    }

    @Test fun everyOutcomeHasItsTerminalEvent() {
        TakeOutcome.entries.forEach { diagnostics.terminal(it, 1) }
        assertEquals(listOf("TERMINAL_COMPLETED", "TERMINAL_CANCELLED", "TERMINAL_INPUT_CHANGED", "TERMINAL_FAILED", "TERMINAL_COMMIT_REJECTED"), events())
    }

    @Test fun captureEventsAreWrittenBeforeTheTakeHearsOfThem() {
        val order = mutableListOf<String>()
        fun seen(what: String) { order += events().lastOrNull() + " then " + what }
        var stopResult: Result<ByteArray> = Result.success(byteArrayOf())
        var cancelResult: Result<Unit> = Result.success(Unit)
        var startFails = false
        val calls = mutableListOf<String>()
        val capture = diagnostics.forTake(5, null, null).traced(object : TakeCapture {
            override fun start(accept: (FloatArray) -> Unit) { if (startFails) error("no microphone") }
            override fun stop(done: (Result<ByteArray>) -> Unit) = done(stopResult)
            override fun cancel(done: () -> Unit) = done()
            override fun cancelWithResult(done: (Result<Unit>) -> Unit) = done(cancelResult)
            override fun pause() { calls += "pause" }
            override fun resume() { calls += "resume" }
        })
        capture.start {}
        assertEquals(emptyList<String>(), events())
        capture.stop { seen("stopped") }
        stopResult = Result.failure(CaptureReleaseUnconfirmed())
        capture.stop { seen("stop failed") }
        capture.cancel { seen("cancelled") }
        capture.cancelWithResult { seen("cancelled with result") }
        cancelResult = Result.failure(CaptureReleaseUnconfirmed())
        capture.cancelWithResult { seen("cancel failed") }
        startFails = true
        assertThrows(IllegalStateException::class.java) { capture.start {} }
        capture.pause(); capture.resume()
        assertEquals(listOf("pause", "resume"), calls)
        assertEquals(listOf("CAPTURE_RELEASED then stopped", "CAPTURE_FAILED then stop failed", "CAPTURE_RELEASED then cancelled",
            "CAPTURE_RELEASED then cancelled with result", "CAPTURE_FAILED then cancel failed"), order)
        assertEquals("CAPTURE_FAILED", events().last())
        assertTrue(rows().all { it.getLong("take") == 5L && !it.has("active") })
    }

    @Test fun onlyFourTimelinePhasesReachTheRotationTrace() {
        val take = diagnostics.forTake(2, null, null)
        take.captureStarted()
        take.event(RotationEvent.CAPTURE_INTERRUPTED)
        val observer = take.observer!!
        listOf("captureStart", "firstAudio", "stopRequested", "chunkReady", "captureStopped", "finalReady", "commitAccepted", "takeEnded")
            .forEach { observer.record(TimelineEvent(it, ns = 1)) }
        assertEquals(listOf("CAPTURE_STARTED", "CAPTURE_INTERRUPTED", "STOP_REQUESTED", "CAPTURE_STOPPED", "FINAL_READY", "TAKE_ENDED"), events())
    }

    @Test fun aTakeKeepsTheTraceItStartedWith() {
        val take = diagnostics.forTake(1, null, null)
        val later = Files.createTempDirectory("take-diagnostics").resolve("later.jsonl").toFile()
        val second = RotationTrace(later)
        diagnostics.attach(second)
        take.event(RotationEvent.CAPTURE_RELEASED)
        diagnostics.takeEvent(RotationEvent.CAPTURE_RESUMED, 1)
        assertEquals(listOf("CAPTURE_RELEASED"), events())
        assertTrue(second.flush())
        assertEquals("CAPTURE_RESUMED", JSONObject(later.readLines().single()).getString("event"))
    }

    @Test fun withoutATraceNothingIsWrittenAndATakeHasNoObserver() {
        val silent = TakeDiagnostics()
        assertFalse(silent.tracing)
        assertTrue(silent.attachable)
        silent.event(RotationEvent.INPUT_START, TakeTraceFacts(false, false, null, null, null, 0))
        silent.takeEvent(RotationEvent.CAPTURE_PAUSED, 1)
        silent.terminal(TakeOutcome.COMPLETED, 1)
        val take = silent.forTake(1, null, null)
        take.captureStarted()
        assertNull(take.observer)
        assertTrue(diagnostics.tracing)
        assertFalse("a live trace is not replaced", diagnostics.attachable)
        trace.close()
        assertTrue("a closed trace is", diagnostics.attachable)
        assertTrue(diagnostics.tracing)
    }
}
