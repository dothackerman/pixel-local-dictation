package ch.og.dictation

import android.widget.TextView

/**
 * The panel preview: the end of the text so far. When the text needs more lines than the
 * preview shows (narrow panel, large font), the view scrolls so its newest line sits at the
 * bottom; TextView alone would show the oldest lines.
 */
object PanelPreview {
    /**
     * The end of [text], at most [limit] characters, starting at a whole word: a cut inside a
     * word drops that fragment, and no leading space is shown. A single word longer than the
     * limit is kept cut rather than showing nothing.
     */
    fun tail(text: String, limit: Int = 240): String {
        if (text.length <= limit) return text
        val start = text.length - limit
        var tail = text.substring(start)
        if (!text[start - 1].isWhitespace()) {
            val space = tail.indexOfFirst { it.isWhitespace() }
            if (space >= 0) tail = tail.substring(space)
        }
        return tail.trimStart()
    }

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
