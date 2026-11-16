package ch.og.dictation

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatDelegate
import java.io.File

/** Isolated synthetic History for instrumentation. */
class HistoryTestActivity : HistoryActivity() {
    companion object {
        @Volatile var largeTextPreview = false
        @Volatile var nightMode: Int? = null
        @Volatile var historyRootSuffix: String? = null
        @Volatile var undoWindow: Long = 8000L
    }
    override val undoWindowMs: Long get() = undoWindow
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Test window only: the screen cannot time out mid-run (no wake lock, no setting).
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    override fun attachBaseContext(base: Context) {
        nightMode?.let {
            delegate.localNightMode = if (it == Configuration.UI_MODE_NIGHT_YES) {
                AppCompatDelegate.MODE_NIGHT_YES
            } else {
                AppCompatDelegate.MODE_NIGHT_NO
            }
        }
        val themed = if (largeTextPreview) {
            val configuration = Configuration(base.resources.configuration).apply {
                fontScale = 2f
            }
            base.createConfigurationContext(configuration)
        } else base
        super.attachBaseContext(themed)
    }
    override fun historyRoot() = File(noBackupFilesDir, "history-ui-test${historyRootSuffix?.let { "-$it" } ?: ""}")
}
