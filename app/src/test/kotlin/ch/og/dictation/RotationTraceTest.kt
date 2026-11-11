package ch.og.dictation

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class RotationTraceTest {
    @Test fun recordsOnlyTypedMetadataInOrderAndFlushes() {
        val file = Files.createTempDirectory("rotation-trace").resolve("trace.jsonl").toFile()
        val trace = RotationTrace(file)
        trace.event(RotationEvent.SERVICE_CREATE, active = false, authority = false)
        trace.event(RotationEvent.INPUT_START, active = true, authority = true, flag = true, view = 2, take = 3)
        assertTrue(trace.flush())
        val rows = file.readLines().map(::JSONObject)
        assertEquals(2, rows.size)
        assertEquals(listOf("SERVICE_CREATE", "INPUT_START"), rows.map { it.getString("event") })
        assertEquals(listOf(1, 2), rows.map { it.getInt("sequence") })
        assertEquals(setOf("protocol", "process", "service", "sequence", "ns", "event", "active", "authority"), rows[0].keys().asSequence().toSet())
        assertEquals(true, rows[1].getBoolean("flag"))
        assertEquals(2L, rows[1].getLong("view"))
        assertEquals(3L, rows[1].getLong("take"))
        trace.close()
    }

    @Test fun closeFlushesAndIgnoresLaterEvents() {
        val file = Files.createTempDirectory("rotation-trace").resolve("trace.jsonl").toFile()
        val trace = RotationTrace(file)
        trace.event(RotationEvent.SERVICE_CREATE)
        trace.close()
        trace.event(RotationEvent.INPUT_START)
        assertEquals(1, file.readLines().size)
    }

    @Test fun fullFileReportsFailureWithoutAppendingPartialRow() {
        val file = Files.createTempDirectory("rotation-trace").resolve("trace.jsonl").toFile()
        val trace = RotationTrace(file, maxBytes = 1)
        trace.event(RotationEvent.SERVICE_CREATE)
        assertFalse(trace.flush())
        assertFalse(file.exists())
        trace.close()
    }
}

class RotationProbeCompletionTest {
    @Test fun completionRequiresViewTeardownAndTerminalAcknowledgement() {
        val file = Files.createTempDirectory("rotation-completion").resolve("trace.jsonl").toFile()
        RotationTrace.beginProbe()
        val trace = RotationTrace(file)
        trace.event(RotationEvent.INPUT_VIEW_START)
        trace.event(RotationEvent.CAPTURE_START, take = 1)
        assertFalse(RotationTrace.finishProbe(0))
        trace.event(RotationEvent.INPUT_VIEW_FINISH_DONE)
        trace.event(RotationEvent.TAKE_ENDED, take = 1)
        assertFalse(RotationTrace.finishProbe(0))
        trace.event(RotationEvent.CAPTURE_STARTED, take = 1)
        assertFalse(RotationTrace.finishProbe(0))
        trace.event(RotationEvent.TERMINAL_CANCELLED, take = 1)
        assertFalse(RotationTrace.finishProbe(0))
        trace.event(RotationEvent.CAPTURE_RELEASED, take = 1)
        assertTrue(RotationTrace.finishProbe(0))
        assertFalse(RotationTrace.probeActive())
    }

    @Test fun attachingToAnAlreadyActiveTakeCannotProduceAValidProbe() {
        val file = Files.createTempDirectory("rotation-active").resolve("trace.jsonl").toFile()
        RotationTrace.beginProbe()
        try {
            val trace = RotationTrace(file)
            trace.event(RotationEvent.SERVICE_ATTACH, active = true)
            trace.event(RotationEvent.INPUT_VIEW_START)
            trace.event(RotationEvent.CAPTURE_START, take = 1)
            trace.event(RotationEvent.CAPTURE_RELEASED, take = 1)
            trace.event(RotationEvent.TERMINAL_FAILED, take = 1)
            trace.event(RotationEvent.INPUT_VIEW_FINISH_DONE)
            assertFalse(RotationTrace.finishProbe(0))
        } finally { RotationTrace.abortProbe() }
    }

    @Test fun noCaptureCannotProduceAValidProbe() {
        val file = Files.createTempDirectory("rotation-empty").resolve("trace.jsonl").toFile()
        RotationTrace.beginProbe()
        try {
            val trace = RotationTrace(file)
            trace.event(RotationEvent.INPUT_VIEW_START)
            trace.event(RotationEvent.INPUT_VIEW_FINISH_DONE)
            assertFalse(RotationTrace.finishProbe(0))
        } finally { RotationTrace.abortProbe() }
    }

    @Test fun requestedButNeverAcquiredCaptureCannotPass() {
        val file = Files.createTempDirectory("rotation-no-acquire").resolve("trace.jsonl").toFile()
        RotationTrace.beginProbe("capture-rotation", "run-123")
        try {
            val trace = RotationTrace(file)
            trace.event(RotationEvent.INPUT_VIEW_START)
            trace.event(RotationEvent.CAPTURE_START, take = 1)
            trace.event(RotationEvent.CAPTURE_FAILED, take = 1)
            trace.event(RotationEvent.CAPTURE_NOT_ACQUIRED, take = 1)
            trace.event(RotationEvent.TERMINAL_FAILED, take = 1)
            trace.event(RotationEvent.CAPTURE_RELEASED, take = 1)
            trace.event(RotationEvent.INPUT_VIEW_FINISH_DONE)
            assertFalse(RotationTrace.finishProbe(0))
        } finally { RotationTrace.abortProbe() }
    }

    @Test fun rowsIdentifyNamedRunWithoutContentFields() {
        val file = Files.createTempDirectory("rotation-metadata").resolve("trace.jsonl").toFile()
        RotationTrace.beginProbe("finishing-rotation", "run-123")
        try {
            val trace = RotationTrace(file)
            trace.event(RotationEvent.INPUT_START)
            assertTrue(trace.flush())
            val row = JSONObject(file.readLines().single())
            assertEquals("finishing-rotation", row.getString("case"))
            assertEquals("run-123", row.getString("run"))
            assertEquals(setOf("protocol", "process", "service", "sequence", "ns", "event", "case", "run"),
                row.keys().asSequence().toSet())
        } finally { RotationTrace.abortProbe() }
    }
}
