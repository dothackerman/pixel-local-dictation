package ch.og.dictation

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatDelegate
import java.io.File

/** Test-only app-local large type, night mode and History store; does not modify device settings. */
class SettingsLargeTextPreviewActivity : SettingsActivity() {
    companion object {
        @Volatile var fontScale = 2f
        @Volatile var nightMode: Int? = null
        @Volatile var historyRootSuffix: String? = null
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Test window only: the screen cannot time out mid-run (no wake lock, no setting).
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    override fun attachBaseContext(base: Context) {
        nightMode?.let {
            delegate.localNightMode = if (it == Configuration.UI_MODE_NIGHT_YES) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        }
        val config = Configuration(base.resources.configuration).apply { fontScale = Companion.fontScale }
        super.attachBaseContext(base.createConfigurationContext(config))
    }
    override fun historyRoot() = historyRootSuffix?.let { File(noBackupFilesDir, "history-ui-test-$it") } ?: super.historyRoot()
}
