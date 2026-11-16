package ch.og.dictation

import android.content.Context

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
