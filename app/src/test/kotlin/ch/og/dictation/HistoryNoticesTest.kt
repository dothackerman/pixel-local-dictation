package ch.og.dictation

import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryNoticesTest {
    @Test fun warningsSurviveReopeningUntilDismissed() {
        var persisted = emptySet<String>()
        fun open() = HistoryNotices({ persisted }, { persisted = it })
        open().record(HistoryNotice.DELETE)
        open().record(HistoryNotice.RECEIPT)
        open().record(HistoryNotice.INCOMPLETE)
        assertEquals(setOf(HistoryNotice.DELETE, HistoryNotice.RECEIPT, HistoryNotice.INCOMPLETE), open().pending())
        open().dismiss(HistoryNotice.RECEIPT)
        assertEquals(setOf(HistoryNotice.DELETE, HistoryNotice.INCOMPLETE), open().pending())
    }
}
