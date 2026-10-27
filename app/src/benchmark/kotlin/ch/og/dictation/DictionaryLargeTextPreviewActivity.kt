package ch.og.dictation

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.appcompat.app.AppCompatDelegate

/** UI verification only: local light/large-text configuration, never device settings. */
class DictionaryLargeTextPreviewActivity : DictionaryActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        delegate.localNightMode = AppCompatDelegate.MODE_NIGHT_NO
        super.onCreate(savedInstanceState)
    }
    override fun attachBaseContext(base: Context) {
        val preview = Configuration(base.resources.configuration).apply {
            fontScale = 1.3f
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_NO
        }
        super.attachBaseContext(base.createConfigurationContext(preview))
    }
}
