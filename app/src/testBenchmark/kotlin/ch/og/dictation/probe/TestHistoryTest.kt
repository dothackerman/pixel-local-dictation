package ch.og.dictation.probe

import ch.og.dictation.wiring
import java.io.File
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class TestHistoryTest {
    @Test fun testScreensRefuseTheOwnersHistoryRoot() {
        val app = RuntimeEnvironment.getApplication()
        assertThrows(IllegalStateException::class.java) { app.refuseOwnersHistory(app.wiring.historyRoot) }
        assertThrows(IllegalStateException::class.java) { app.refuseOwnersHistory(File(app.wiring.historyRoot.path)) }
        app.refuseOwnersHistory(File(app.noBackupFilesDir, "history-ui-test"))
    }
}
