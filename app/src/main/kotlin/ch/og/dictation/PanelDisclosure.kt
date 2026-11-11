package ch.og.dictation

import android.view.View
import android.widget.TextView

/** Renders the take's secondary notice independently of its recording status. */
object PanelDisclosure {
    fun bind(view: TextView, message: Int?) {
        if (message == null) view.text = "" else view.setText(message)
        view.visibility = if (message == null) View.GONE else View.VISIBLE
    }
}
