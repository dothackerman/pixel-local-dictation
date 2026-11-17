package ch.og.dictation.diagnostics

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class LabReceiptsTest {
    private val notices = mutableListOf<Pair<String, Throwable?>>()
    private val defaultNotice = LabReceipts.notice

    @Before fun captureNotices() { LabReceipts.notice = { message, failure -> notices += message to failure } }
    @After fun restoreNotices() { LabReceipts.notice = defaultNotice }

    private fun file() = Files.createTempDirectory("lab-receipts").resolve("lab/trace.jsonl").toFile()

    @Test fun appendsWholeRowsUntilTheCap() {
        val file = file()
        assertTrue(LabReceipts.append(file, "a1\n", maxBytes = 6))
        assertTrue(LabReceipts.append(file, "a2\n", maxBytes = 6))
        assertEquals("a1\na2\n", file.readText())
        assertFalse(LabReceipts.previous(file).exists())
        assertEquals(emptyList<Pair<String, Throwable?>>(), notices)
    }

    @Test fun aRowCrossingTheCapRotatesAndKeepsOnlyOnePreviousGeneration() {
        val file = file()
        for (row in listOf("a1\n", "a2\n", "b1\n", "b2\n", "c1\n")) assertTrue(LabReceipts.append(file, row, maxBytes = 6))
        assertEquals("trace.prev.jsonl", LabReceipts.previous(file).name)
        assertEquals("b1\nb2\n", LabReceipts.previous(file).readText())
        assertEquals("c1\n", file.readText())
        assertEquals(listOf("b1", "b2", "c1"), LabReceipts.lines(file))
        assertEquals(listOf("trace.jsonl", "trace.prev.jsonl"), file.parentFile!!.list()!!.sorted())
    }

    @Test fun rotationIsNoticedOncePerFile() {
        val file = file()
        val other = file()
        for (row in listOf("a1\n", "b1\n", "c1\n")) LabReceipts.append(file, row, maxBytes = 4)
        assertEquals(1, notices.size)
        assertTrue(notices.single().first.contains("trace.prev.jsonl"))
        assertNull(notices.single().second)
        for (row in listOf("a1\n", "b1\n")) LabReceipts.append(other, row, maxBytes = 4)
        assertEquals("each file has its own notice", 2, notices.size)
    }

    @Test fun aRowLargerThanTheCapFailsOnceNoticedWithoutTouchingFiles() {
        val file = file()
        LabReceipts.append(file, "a1\n", maxBytes = 4)
        assertFalse(LabReceipts.append(file, "toolong\n", maxBytes = 4))
        assertFalse(LabReceipts.append(file, "toolong\n", maxBytes = 4))
        assertEquals("a1\n", file.readText())
        assertFalse(LabReceipts.previous(file).exists())
        assertEquals(1, notices.size)
        assertNotNull(notices.single().second)
    }

    @Test fun aFailedRotationKeepsBothGenerationsAndIsNoticedOnce() {
        val file = file()
        LabReceipts.append(file, "a1\n", maxBytes = 4)
        // A non-empty directory in the older generation's place cannot be replaced.
        val blocked = LabReceipts.previous(file).apply { mkdirs() }
        File(blocked, "keep").writeText("older")
        assertFalse(LabReceipts.append(file, "b1\n", maxBytes = 4))
        assertFalse(LabReceipts.append(file, "c1\n", maxBytes = 4))
        assertEquals("a1\n", file.readText())
        assertEquals("older", File(blocked, "keep").readText())
        assertEquals(1, notices.size)
        assertNotNull(notices.single().second)
    }

    @Test fun missingGenerationsReadAsEmpty() {
        assertEquals(emptyList<String>(), LabReceipts.lines(file()))
    }
}
