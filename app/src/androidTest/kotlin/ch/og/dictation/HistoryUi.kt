package ch.og.dictation

import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/** Shared view lookups for the History instrumentation tests. */
object HistoryUi {
    /** What a TextView says: its text, or for an icon-only toolbar action its content description. */
    fun label(view: TextView): String =
        if (view is androidx.appcompat.view.menu.ActionMenuItemView && view.isShown) view.contentDescription?.toString().orEmpty() else view.text.toString()

    fun findText(view: View, value: String, exact: Boolean = false): TextView? {
        if (view is TextView && (if (exact) label(view) == value else label(view).contains(value))) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findText(view.getChildAt(i), value, exact)?.let { return it }
        return null
    }

    fun find(view: View, predicate: (View) -> Boolean): View? {
        if (predicate(view)) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i), predicate)?.let { return it }
        return null
    }

    /** Rows are clickable as a whole; a tap on their text reaches the row, as on a phone. */
    fun click(view: View) {
        generateSequence(view) { it.parent as? View }.first { it.isClickable }.performClick()
    }

    /** The nearest clickable ancestor, i.e. the row or button a tap on this view would reach. */
    fun target(view: View): View = generateSequence(view) { it.parent as? View }.first { it.isClickable }
}
