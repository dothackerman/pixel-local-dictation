package ch.og.dictation

import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test

class HistoryRowsTest {
    private fun entry(delivery: HistoryDelivery, completion: HistoryCompletion) =
        HistoryEntry("00000000-0000-0000-0000-000000000001", 0, "x", delivery, completion)

    @Test fun completeAndInsertedTakeShowsNoStatus() {
        assertEquals(emptyList<HistoryRowStatus>(), HistoryRows.statuses(entry(HistoryDelivery.ACCEPTED, HistoryCompletion.COMPLETE)))
    }

    @Test fun completeButNotInsertedSaysSo() {
        for (d in listOf(HistoryDelivery.REJECTED, HistoryDelivery.NOT_ATTEMPTED)) {
            assertEquals(listOf(HistoryRowStatus.NOT_INSERTED), HistoryRows.statuses(entry(d, HistoryCompletion.COMPLETE)))
        }
        assertEquals(listOf(HistoryRowStatus.INSERTION_UNCERTAIN), HistoryRows.statuses(entry(HistoryDelivery.UNKNOWN, HistoryCompletion.COMPLETE)))
    }

    @Test fun liveTakesShowOnlyTheirProgress() {
        assertEquals(listOf(HistoryRowStatus.RECORDING), HistoryRows.statuses(entry(HistoryDelivery.UNKNOWN, HistoryCompletion.RECORDING)))
        assertEquals(listOf(HistoryRowStatus.FINISHING), HistoryRows.statuses(entry(HistoryDelivery.UNKNOWN, HistoryCompletion.FINISHING)))
    }

    @Test fun interruptedTakeIsIncompleteAndKeepsItsDeliveryTruth() {
        assertEquals(listOf(HistoryRowStatus.MAY_BE_INCOMPLETE, HistoryRowStatus.NOT_INSERTED),
            HistoryRows.statuses(entry(HistoryDelivery.NOT_ATTEMPTED, HistoryCompletion.INTERRUPTED)))
        assertEquals(listOf(HistoryRowStatus.MAY_BE_INCOMPLETE, HistoryRowStatus.INSERTION_UNCERTAIN),
            HistoryRows.statuses(entry(HistoryDelivery.UNKNOWN, HistoryCompletion.INTERRUPTED)))
    }

    @Test fun dateIsMediumWithShortTimeAndNoSeconds() {
        val text = HistoryRows.date(1_735_812_045_000L, Locale.US, TimeZone.getTimeZone("UTC"))
        assertTrue(text, text.startsWith("Jan 2, 2025"))
        assertFalse(text, Regex("\\d:\\d\\d:\\d\\d").containsMatchIn(text))
        assertTrue(text, text.contains("10:00"))
    }

    @Test fun listAndDetailShareOneStatusLine() {
        val label = { s: HistoryRowStatus -> s.name.lowercase() }
        val at = HistoryRows.date(0)
        assertEquals(at, HistoryRows.line(entry(HistoryDelivery.ACCEPTED, HistoryCompletion.COMPLETE), label))
        assertEquals("$at · may_be_incomplete · not_inserted",
            HistoryRows.line(entry(HistoryDelivery.NOT_ATTEMPTED, HistoryCompletion.INTERRUPTED), label))
    }

    @Test fun previewWordsKeepsWholeFirstWordsOnOneLine() {
        assertEquals("Pick up bread and milk", HistoryRows.previewWords("Pick up bread and milk on the way home."))
        assertEquals("one two", HistoryRows.previewWords("  one\n\ntwo  ", 5))
        assertEquals("", HistoryRows.previewWords("   "))
    }
}
