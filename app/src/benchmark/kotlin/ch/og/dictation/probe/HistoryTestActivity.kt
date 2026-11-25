package ch.og.dictation.probe

import ch.og.dictation.storage.testHistoryRoot
import ch.og.dictation.wiring
import ch.og.dictation.history.HistoryActivity
import android.os.Bundle
import android.view.WindowManager

/** Isolated synthetic History for the guided visit. */
class HistoryTestActivity : HistoryActivity() {
    companion object {
        @Volatile var historyRootSuffix: String? = null
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Test window only: the screen cannot time out mid-run (no wake lock, no setting).
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    override fun historyRoot() = wiring.storage.testHistoryRoot("history-ui-test${historyRootSuffix?.let { "-$it" } ?: ""}")
}
