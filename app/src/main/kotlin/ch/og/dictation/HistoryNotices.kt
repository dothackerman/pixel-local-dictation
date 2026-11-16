package ch.og.dictation

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
