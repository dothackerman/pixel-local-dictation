package ch.og.dictation

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class LabReceiptsTest {
    private fun file() = Files.createTempDirectory("lab-receipts").resolve("lab/trace.jsonl").toFile()

    @Test fun appendsWholeRowsUntilTheCap() {
        val file = file()
        assertEquals(LabReceipts.Appended.APPENDED, LabReceipts.append(file, "a1\n", maxBytes = 6))
        assertEquals(LabReceipts.Appended.APPENDED, LabReceipts.append(file, "a2\n", maxBytes = 6))
        assertEquals("a1\na2\n", file.readText())
        assertFalse(LabReceipts.previous(file).exists())
    }

    @Test fun aRowCrossingTheCapRotatesAndKeepsOnlyOnePreviousGeneration() {
        val file = file()
        for (row in listOf("a1\n", "a2\n", "b1\n", "b2\n", "c1\n")) LabReceipts.append(file, row, maxBytes = 6)
        assertEquals("trace.prev.jsonl", LabReceipts.previous(file).name)
        assertEquals("b1\nb2\n", LabReceipts.previous(file).readText())
        assertEquals("c1\n", file.readText())
        assertEquals(listOf("b1", "b2", "c1"), LabReceipts.lines(file))
        assertEquals(listOf("trace.jsonl", "trace.prev.jsonl"), file.parentFile!!.list()!!.sorted())
    }

    @Test fun reportsRotationOnTheRowThatRotates() {
        val file = file()
        LabReceipts.append(file, "a1\n", maxBytes = 4)
        assertEquals(LabReceipts.Appended.ROTATED, LabReceipts.append(file, "a2\n", maxBytes = 4))
    }

    @Test fun refusesARowLargerThanTheCapWithoutTouchingFiles() {
        val file = file()
        LabReceipts.append(file, "a1\n", maxBytes = 4)
        assertThrows(IllegalArgumentException::class.java) { LabReceipts.append(file, "toolong\n", maxBytes = 4) }
        assertEquals("a1\n", file.readText())
        assertFalse(LabReceipts.previous(file).exists())
    }

    @Test fun missingGenerationsReadAsEmpty() {
        assertEquals(emptyList<String>(), LabReceipts.lines(file()))
    }

    @Test fun eachNoticeIsGivenOncePerFileAndKind() {
        val file = file()
        assertTrue(LabReceipts.firstNotice(file, "rotated"))
        assertFalse(LabReceipts.firstNotice(file, "rotated"))
        assertTrue(LabReceipts.firstNotice(file, "failed"))
        assertTrue(LabReceipts.firstNotice(file(), "rotated"))
    }
}
