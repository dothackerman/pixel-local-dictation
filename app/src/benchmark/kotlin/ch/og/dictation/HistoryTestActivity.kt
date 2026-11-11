package ch.og.dictation

import android.content.Context
import android.content.res.Configuration
import java.io.File

/** Isolated synthetic History for instrumentation. */
class HistoryTestActivity : HistoryActivity() {
    companion object {
        @Volatile var largeTextPreview = false
        @Volatile var nightMode: Int? = null
        @Volatile var historyRootSuffix: String? = null
    }
    override fun attachBaseContext(base: Context) {
        val themed = if (largeTextPreview) {
            val configuration = Configuration(base.resources.configuration).apply {
                fontScale = 2f
                nightMode?.let { uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or it }
            }
            base.createConfigurationContext(configuration)
        } else base
        super.attachBaseContext(themed)
    }
    override fun historyRoot() = File(noBackupFilesDir, "history-ui-test${historyRootSuffix?.let { "-$it" } ?: ""}")
}
