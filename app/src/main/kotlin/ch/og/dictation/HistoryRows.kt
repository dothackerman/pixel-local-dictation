package ch.og.dictation

import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The one marker a History row carries (#36 Crate B2), with its glyph and its one sentence: the
 * same words in the detail line, in TalkBack and in "What the marks mean". A finished, inserted
 * take carries none.
 */
enum class HistoryMarker(val glyph: Int, val title: Int, val sentence: Int, val line: Int) {
    RECORDING(R.drawable.ic_marker_recording, R.string.history_recording, R.string.marker_recording_sentence, R.string.marker_recording_line),
    PREPARING(R.drawable.ic_marker_preparing, R.string.history_finishing, R.string.marker_preparing_sentence, R.string.marker_preparing_line),
    CUT_SHORT(R.drawable.ic_marker_cut_short, R.string.history_status_incomplete, R.string.marker_cut_short_sentence, R.string.marker_cut_short_line),
    NOT_INSERTED(R.drawable.ic_marker_not_inserted, R.string.history_delivery_rejected, R.string.marker_not_inserted_sentence, R.string.marker_not_inserted_line),
    UNCERTAIN(R.drawable.ic_marker_uncertain, R.string.history_status_uncertain, R.string.marker_uncertain_sentence, R.string.marker_uncertain_line),
}

/** The day a take belongs to, for the list's day headers. */
sealed interface HistoryDay {
    data object Today : HistoryDay
    data object Yesterday : HistoryDay
    data class Date(val year: Int, val dayOfYear: Int) : HistoryDay
}

/** What a History row says about a take; a complete, inserted take says nothing. */
object HistoryRows {
    /** At most one marker, by precedence: in progress, cut short, unable to insert, uncertain. */
    fun marker(entry: HistoryEntry): HistoryMarker? = when {
        entry.completion == HistoryCompletion.RECORDING -> HistoryMarker.RECORDING
        entry.completion == HistoryCompletion.FINISHING -> HistoryMarker.PREPARING
        entry.completion == HistoryCompletion.INTERRUPTED -> HistoryMarker.CUT_SHORT
        entry.delivery == HistoryDelivery.REJECTED || entry.delivery == HistoryDelivery.NOT_ATTEMPTED -> HistoryMarker.NOT_INSERTED
        entry.delivery == HistoryDelivery.UNKNOWN -> HistoryMarker.UNCERTAIN
        else -> null
    }

    fun day(timestamp: Long, now: Long = System.currentTimeMillis(), zone: TimeZone = TimeZone.getDefault()): HistoryDay {
        val at = Calendar.getInstance(zone).apply { timeInMillis = timestamp }
        val today = Calendar.getInstance(zone).apply { timeInMillis = now }
        fun same(a: Calendar, b: Calendar) = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
        if (same(at, today)) return HistoryDay.Today
        today.add(Calendar.DAY_OF_YEAR, -1)
        if (same(at, today)) return HistoryDay.Yesterday
        return HistoryDay.Date(at.get(Calendar.YEAR), at.get(Calendar.DAY_OF_YEAR))
    }

    /** "Mon 9 Nov" this year, with the year otherwise; Today and Yesterday are the caller's words. */
    fun dayText(timestamp: Long, now: Long = System.currentTimeMillis(), locale: Locale = Locale.getDefault(),
                zone: TimeZone = TimeZone.getDefault()): String {
        val year = Calendar.getInstance(zone).apply { timeInMillis = now }.get(Calendar.YEAR)
        val at = Calendar.getInstance(zone).apply { timeInMillis = timestamp }
        val pattern = if (at.get(Calendar.YEAR) == year) "EEE d MMM" else "EEE d MMM yyyy"
        return SimpleDateFormat(pattern, locale).apply { timeZone = zone }.format(Date(timestamp))
    }

    /** Short time, no seconds: leads each row. */
    fun time(timestamp: Long, locale: Locale = Locale.getDefault(), zone: TimeZone = TimeZone.getDefault()): String =
        DateFormat.getTimeInstance(DateFormat.SHORT, locale).apply { timeZone = zone }.format(Date(timestamp))

    fun words(text: String): Int = text.trim().split(Regex("\\s+")).count { it.isNotEmpty() }

    /** The first words, for naming a row to a screen reader. */
    fun previewWords(text: String, count: Int = 5): String =
        text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.take(count).joinToString(" ")

    /** Medium date, short time, no seconds: names a transcript where the day is not shown. */
    fun date(timestamp: Long, locale: Locale = Locale.getDefault(), zone: TimeZone = TimeZone.getDefault()): String =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale).apply { timeZone = zone }.format(Date(timestamp))
}

/**
 * One transcript may be deleted-with-Undo at a time. A second delete settles the first at once.
 * Settled ids stay hidden until storage has finished, so a row never flashes back.
 */
class HistoryUndo {
    private var pending: String? = null
    private val committing = mutableSetOf<String>()

    /** Returns the earlier pending id, which the caller must now remove from storage. */
    fun delete(id: String): String? {
        val earlier = settle()
        pending = id
        return earlier
    }
    fun undo(): String? = pending.also { pending = null }
    fun settle(): String? = pending?.also { committing.add(it); pending = null }
    fun finished(id: String) { committing.remove(id) }
    fun hidden(id: String) = id == pending || id in committing
}
