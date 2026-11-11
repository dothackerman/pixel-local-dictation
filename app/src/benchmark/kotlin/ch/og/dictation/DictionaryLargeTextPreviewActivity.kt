package ch.og.dictation

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatDelegate

/** UI verification only: local light/large-text configuration, never device settings. */
open class DictionaryLargeTextPreviewActivity : DictionaryActivity() {
    protected open val previewFontScale: Float get() = 1.3f

    /** The add sheet held sideways at font 2× (manifest: landscape for this window only). */
    class Landscape : DictionaryLargeTextPreviewActivity() {
        override val previewFontScale: Float get() = 2f
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        delegate.localNightMode = AppCompatDelegate.MODE_NIGHT_NO
        super.onCreate(savedInstanceState)
        // Test window only: the screen cannot time out mid-run (no wake lock, no setting).
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    override fun attachBaseContext(base: Context) {
        val preview = Configuration(base.resources.configuration).apply {
            fontScale = previewFontScale
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_NO
        }
        super.attachBaseContext(base.createConfigurationContext(preview))
    }
}
