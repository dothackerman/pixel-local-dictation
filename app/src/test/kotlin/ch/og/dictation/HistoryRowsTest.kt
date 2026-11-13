package ch.og.dictation

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test

class HistoryRowsTest {
    private fun entry(delivery: HistoryDelivery, completion: HistoryCompletion, text: String = "x", timestamp: Long = 0) =
        HistoryEntry("00000000-0000-0000-0000-000000000001", timestamp, text, delivery, completion)

    @Test fun completeAndInsertedTakeCarriesNoMarker() {
        assertNull(HistoryRows.marker(entry(HistoryDelivery.ACCEPTED, HistoryCompletion.COMPLETE)))
    }

    @Test fun notInsertedAndNotAttemptedShareOneMarker() {
        for (d in listOf(HistoryDelivery.REJECTED, HistoryDelivery.NOT_ATTEMPTED)) {
            assertEquals(HistoryMarker.NOT_INSERTED, HistoryRows.marker(entry(d, HistoryCompletion.COMPLETE)))
        }
        assertEquals(HistoryMarker.UNCERTAIN, HistoryRows.marker(entry(HistoryDelivery.UNKNOWN, HistoryCompletion.COMPLETE)))
    }

    @Test fun inProgressWinsOverEverything() {
        for (d in HistoryDelivery.entries) {
            assertEquals(HistoryMarker.RECORDING, HistoryRows.marker(entry(d, HistoryCompletion.RECORDING)))
            assertEquals(HistoryMarker.PREPARING, HistoryRows.marker(entry(d, HistoryCompletion.FINISHING)))
        }
    }

    /** Precedence: in progress, then cut short, then unable to insert, then uncertain; at most one. */
    @Test fun cutShortWinsOverDeliveryTruth() {
        for (d in HistoryDelivery.entries) {
            assertEquals(HistoryMarker.CUT_SHORT, HistoryRows.marker(entry(d, HistoryCompletion.INTERRUPTED)))
        }
    }

    @Test fun everyMarkerHasItsOwnGlyphAndOneSentence() {
        val glyphs = HistoryMarker.entries.map { it.glyph }
        assertEquals("no two markers share a glyph", glyphs.size, glyphs.toSet().size)
        val lines = HistoryMarker.entries.map { it.line }
        assertEquals(lines.size, lines.toSet().size)
    }

    @Test fun dayLabelSaysTodayYesterdayOrTheDate() {
        val zone = TimeZone.getTimeZone("Europe/Zurich")
        val now = Calendar.getInstance(zone, Locale.UK).apply { set(2026, Calendar.NOVEMBER, 11, 18, 42, 0) }.timeInMillis
        val today = Calendar.getInstance(zone, Locale.UK).apply { set(2026, Calendar.NOVEMBER, 11, 0, 5, 0) }.timeInMillis
        val yesterday = Calendar.getInstance(zone, Locale.UK).apply { set(2026, Calendar.NOVEMBER, 10, 23, 59, 0) }.timeInMillis
        val earlier = Calendar.getInstance(zone, Locale.UK).apply { set(2026, Calendar.NOVEMBER, 9, 16, 20, 0) }.timeInMillis
        val lastYear = Calendar.getInstance(zone, Locale.UK).apply { set(2025, Calendar.NOVEMBER, 9, 16, 20, 0) }.timeInMillis
        assertEquals(HistoryDay.Today, HistoryRows.day(today, now, zone))
        assertEquals(HistoryDay.Yesterday, HistoryRows.day(yesterday, now, zone))
        val date = HistoryRows.day(earlier, now, zone)
        assertTrue(date is HistoryDay.Date)
        assertEquals("Mon 9 Nov", HistoryRows.dayText(earlier, now, Locale.UK, zone))
        assertTrue(HistoryRows.dayText(lastYear, now, Locale.UK, zone).contains("2025"))
        assertNotEquals(HistoryRows.day(earlier, now, zone), HistoryRows.day(lastYear, now, zone))
    }

    @Test fun timeIsShortWithoutSeconds() {
        val text = HistoryRows.time(1_735_812_045_000L, Locale.UK, TimeZone.getTimeZone("UTC"))
        assertEquals("10:00", text)
    }

    @Test fun wordCountCountsWholeWords() {
        assertEquals(5, HistoryRows.words("Pick up  bread\nand milk"))
        assertEquals(0, HistoryRows.words("   "))
    }

    @Test fun previewWordsKeepsWholeFirstWordsOnOneLine() {
        assertEquals("Pick up bread and milk", HistoryRows.previewWords("Pick up bread and milk on the way home."))
        assertEquals("one two", HistoryRows.previewWords("  one\n\ntwo  ", 5))
        assertEquals("", HistoryRows.previewWords("   "))
    }

    @Test fun dateIsMediumWithShortTimeAndNoSeconds() {
        val text = HistoryRows.date(1_735_812_045_000L, Locale.US, TimeZone.getTimeZone("UTC"))
        assertTrue(text, text.startsWith("Jan 2, 2025"))
        assertFalse(text, Regex("\\d:\\d\\d:\\d\\d").containsMatchIn(text))
        assertTrue(text, text.contains("10:00"))
    }
}
