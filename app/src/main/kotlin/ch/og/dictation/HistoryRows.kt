package ch.og.dictation

import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

enum class HistoryRowStatus { RECORDING, FINISHING, MAY_BE_INCOMPLETE, NOT_INSERTED, INSERTION_UNCERTAIN }

/** What a History row says about a take; a complete, inserted take says nothing. */
object HistoryRows {
    fun statuses(entry: HistoryEntry): List<HistoryRowStatus> {
        when (entry.completion) {
            HistoryCompletion.RECORDING -> return listOf(HistoryRowStatus.RECORDING)
            HistoryCompletion.FINISHING -> return listOf(HistoryRowStatus.FINISHING)
            else -> Unit
        }
        val delivery = when (entry.delivery) {
            HistoryDelivery.ACCEPTED -> null
            HistoryDelivery.UNKNOWN -> HistoryRowStatus.INSERTION_UNCERTAIN
            HistoryDelivery.REJECTED, HistoryDelivery.NOT_ATTEMPTED -> HistoryRowStatus.NOT_INSERTED
        }
        return listOfNotNull(
            if (entry.completion == HistoryCompletion.INTERRUPTED) HistoryRowStatus.MAY_BE_INCOMPLETE else null,
            delivery,
        )
    }

    /** The one status line, date first, shared by the list row and the detail. */
    fun line(entry: HistoryEntry, label: (HistoryRowStatus) -> String): String =
        (listOf(date(entry.timestamp)) + statuses(entry).map(label)).joinToString(" · ")

    /** The first words, for naming a row to a screen reader. */
    fun previewWords(text: String, count: Int = 5): String =
        text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.take(count).joinToString(" ")

    /** Medium date, short time, no seconds. */
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
