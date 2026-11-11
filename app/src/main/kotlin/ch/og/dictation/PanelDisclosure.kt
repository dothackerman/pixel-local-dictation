package ch.og.dictation

import android.view.View
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Renders the take's secondary notice (the private-field line) independently of its recording
 * status. The lock glyph keeps the mock's 16dp-to-14sp proportion, so it grows with the font. In
 * landscape (`panel_preview_yields_to_notice`) the preview beside it gives up one of its three
 * lines while the line shows, so a private field costs the editor above almost no height.
 */
object PanelDisclosure {
    private const val PREVIEW_LINES = 3

    fun bind(view: TextView, message: Int?) {
        if (message == null) view.text = "" else view.setText(message)
        view.visibility = if (message == null) View.GONE else View.VISIBLE
        view.compoundDrawablesRelative[0]?.let { lock ->
            val size = (view.textSize * 16 / 14).roundToInt()
            lock.setBounds(0, 0, size, size)
            view.setCompoundDrawablesRelative(lock, null, null, null)
        }
        if (view.resources.getBoolean(R.bool.panel_preview_yields_to_notice)) {
            (view.parent as? View)?.findViewById<TextView>(R.id.preview)?.let { preview ->
                val lines = if (message == null) PREVIEW_LINES else PREVIEW_LINES - 1
                preview.minLines = lines
                preview.maxLines = lines
            }
        }
    }
}
