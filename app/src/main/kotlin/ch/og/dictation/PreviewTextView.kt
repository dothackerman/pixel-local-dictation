package ch.og.dictation

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.widget.TextView

/**
 * The panel preview (#36 critique): it takes the column's width but never more than [MAX_EMS] of
 * its own text size (about 70 characters), so the wide landscape column does not run lines
 * across the whole panel. Scales with the font like the text. A platform TextView like the rest of
 * the panel, which inflates without AppCompat's view factory.
 */
@SuppressLint("AppCompatCustomView")
class PreviewTextView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : TextView(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val cap = (MAX_EMS * textSize).toInt() + totalPaddingLeft + totalPaddingRight
        val spec = if (MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED && MeasureSpec.getSize(widthMeasureSpec) > cap)
            MeasureSpec.makeMeasureSpec(cap, MeasureSpec.EXACTLY) else widthMeasureSpec
        super.onMeasure(spec, heightMeasureSpec)
    }

    private companion object { const val MAX_EMS = 28 }
}
