package ch.og.dictation.panel

import ch.og.dictation.R
import ch.og.dictation.audio.RecordingFeedback
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import java.io.ByteArrayOutputStream
import java.io.File
import org.robolectric.RuntimeEnvironment

/**
 * Panel frames as images on the laptop (#77): each frame on a fresh panel, through [renderPanel],
 * in every [Look]. Needs Robolectric's native graphics. The same frame gives the same bytes on
 * every run: a recording ring draws its first frame at a fixed loudness.
 */
object PanelImages {
    class Look(val name: String, val qualifiers: String, val widthDp: Int, val fontScale: Float = 1f)

    private fun looks(name: String, size: String, widthDp: Int) = listOf(
        Look("$name-light", "$size-notnight-xxhdpi", widthDp),
        Look("$name-dark", "$size-night-xxhdpi", widthDp),
        Look("$name-font2", "$size-notnight-xxhdpi", widthDp, fontScale = 2f))

    /** Portrait, narrow landscape (under 600dp) and the phone's landscape; light, dark and font 2×. */
    val LOOKS = looks("portrait", "w411dp-h891dp-port", 411) +
        looks("narrow-landscape", "w540dp-h360dp-land", 540) +
        looks("wide-landscape", "w960dp-h440dp-land", 960)

    /** The gallery: `after/` is what this checkout renders, `before/` what a ticket stored at its start (tools/visible_change.py). */
    val GALLERY = File("build/panel-gallery")

    fun png(frame: PanelFrame, look: Look): ByteArray {
        RuntimeEnvironment.setQualifiers(look.qualifiers)
        RuntimeEnvironment.setFontScale(look.fontScale)
        val context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_Dictation)
        val panel = LayoutInflater.from(context).inflate(R.layout.ime, null)
        // Mid-loudness speech, so a recording frame shows the ribbon as it looks while talking.
        val feedback = if (frame.ringLive) RecordingFeedback().apply { start(); accept(FloatArray(256) { 0.05f }) } else null
        val observer = onPanelFrame
        onPanelFrame = null
        try { renderPanel(panel, frame, null, feedback) } finally { onPanelFrame = observer }
        val width = (look.widthDp * context.resources.displayMetrics.density).toInt()
        panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        panel.layout(0, 0, panel.measuredWidth, panel.measuredHeight)
        val image = Bitmap.createBitmap(panel.width, panel.height, Bitmap.Config.ARGB_8888)
        try {
            panel.draw(Canvas(image))
            return ByteArrayOutputStream().also { image.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        } finally { image.recycle() }
    }

    /** Writes [frames] under [folder] as `<look>/<name>.png`, replacing what was there. */
    fun write(folder: File, frames: List<Pair<String, PanelFrame>>) {
        folder.deleteRecursively()
        for (look in LOOKS) {
            val into = File(folder, look.name).apply { mkdirs() }
            for ((name, frame) in frames) File(into, "$name.png").writeBytes(png(frame, look))
        }
    }
}
