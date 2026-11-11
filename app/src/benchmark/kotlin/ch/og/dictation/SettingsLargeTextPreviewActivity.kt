package ch.og.dictation

import android.content.Context
import android.content.res.Configuration

/** Test-only app-local large type; does not modify device settings. */
class SettingsLargeTextPreviewActivity : SettingsActivity() {
    override fun attachBaseContext(base: Context) {
        val config = Configuration(base.resources.configuration).apply { fontScale = 2f }
        super.attachBaseContext(base.createConfigurationContext(config))
    }
}
