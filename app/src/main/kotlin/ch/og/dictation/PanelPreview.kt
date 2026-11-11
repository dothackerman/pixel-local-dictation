package ch.og.dictation

import android.widget.TextView

/**
 * The panel preview: the end of the text so far. When the text needs more lines than the
 * preview shows (narrow panel, large font), the view scrolls so its newest line sits at the
 * bottom; TextView alone would show the oldest lines.
 */
object PanelPreview {
    fun bind(view: TextView, text: CharSequence) {
        if (view.getTag(R.id.preview) == null) {
            view.setTag(R.id.preview, true)
            view.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> showEnd(v as TextView) }
        }
        view.text = text
        showEnd(view)
    }

    private fun showEnd(view: TextView) {
        val layout = view.layout ?: return
        val box = view.height - view.compoundPaddingTop - view.compoundPaddingBottom
        if (layout.lineCount == 0 || box <= 0) return
        // Start at a whole line so no clipped line shows above the newest ones.
        val bottom = layout.getLineBottom(layout.lineCount - 1)
        var first = layout.lineCount - 1
        while (first > 0 && bottom - layout.getLineTop(first - 1) <= box) first--
        view.scrollTo(0, layout.getLineTop(first))
    }
}
