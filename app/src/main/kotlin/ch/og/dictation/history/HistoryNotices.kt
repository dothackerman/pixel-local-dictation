package ch.og.dictation.history

import ch.og.dictation.R
import android.content.Context

enum class HistoryNotice { SAVE, INCOMPLETE, RECEIPT, DELETE }

/** Stores only warning kinds, never transcript content or take identifiers. */
class HistoryNotices(private val read: () -> Set<String>, private val write: (Set<String>) -> Unit) {
    companion object { private val lock = Any() }
    fun record(notice: HistoryNotice) = synchronized(lock) { write(read() + notice.name) }
    fun pending(): Set<HistoryNotice> = synchronized(lock) {
        val names = read()
        HistoryNotice.entries.filterTo(linkedSetOf()) { it.name in names }
    }
    fun dismiss(notice: HistoryNotice) = synchronized(lock) { write(read() - notice.name) }
}

/** The app's notice store in shared preferences: warning kinds only. */
fun historyNotices(context: Context): HistoryNotices {
    val prefs = context.applicationContext.getSharedPreferences("history_notices", Context.MODE_PRIVATE)
    return HistoryNotices(
        { prefs.getStringSet("pending", emptySet())?.toSet() ?: emptySet() },
        { prefs.edit().putStringSet("pending", it).commit() },
    )
}

fun HistoryNotice.message(): Int = when (this) {
    HistoryNotice.SAVE -> R.string.history_save_error
    HistoryNotice.INCOMPLETE -> R.string.history_recovery_incomplete
    HistoryNotice.RECEIPT -> R.string.history_receipt_error
    HistoryNotice.DELETE -> R.string.history_cancel_delete_error
}
