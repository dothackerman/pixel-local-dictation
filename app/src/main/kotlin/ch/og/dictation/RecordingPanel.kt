package ch.og.dictation

import android.view.View
import android.widget.ImageButton
import android.widget.TextView

/** Take presentation survives view replacement and rejects callbacks from older takes. */
class RecordingPanel(view: View? = null) {
    private enum class State { OPENING, RECORDING, INTERRUPTED, FINISHING }
    private var view = view
    private var state: State? = null
    private var generation = 0L

    fun bind(view: View) { this.view = view; render() }
    fun beginTake(): Long { generation++; state = State.OPENING; render(); return generation }
    fun captureStarted(take: Long) {
        if (take == generation && state == State.OPENING) { state = State.RECORDING; render() }
    }
    fun captureEnded(take: Long) {
        if (take == generation && state in setOf(State.OPENING, State.RECORDING)) {
            state = State.INTERRUPTED; render()
        }
    }
    fun finishTake(take: Long) {
        if (take == generation && state in setOf(State.OPENING, State.RECORDING, State.INTERRUPTED)) {
            state = State.FINISHING; render()
        }
    }
    fun completeTake(take: Long): Boolean = take == generation && state != null
    fun cancelTake() { generation++; state = null; setFinishing(false) }

    private fun setFinishing(finishing: Boolean) {
        view?.findViewById<View>(R.id.microphone)?.visibility = if (finishing) View.GONE else View.VISIBLE
        view?.findViewById<View>(R.id.recording_ring)?.visibility = if (finishing) View.GONE else View.VISIBLE
        view?.findViewById<View>(R.id.processing)?.visibility = if (finishing) View.VISIBLE else View.GONE
    }

    private fun render() {
        val state = state ?: return
        val view = view ?: return
        val status = when (state) {
            State.OPENING -> R.string.status_opening
            State.RECORDING -> R.string.status_recording
            State.INTERRUPTED -> R.string.status_capture_ended
            State.FINISHING -> R.string.status_finishing
        }
        val label = when (state) {
            State.OPENING -> R.string.status_opening
            State.RECORDING -> R.string.stop_recording
            State.INTERRUPTED -> R.string.finish_transcription
            State.FINISHING -> R.string.status_finishing
        }
        view.findViewById<TextView>(R.id.status).setText(status)
        view.findViewById<ImageButton>(R.id.microphone).apply {
            isEnabled = state == State.RECORDING || state == State.INTERRUPTED
            alpha = if (isEnabled) 1f else 0.55f
            contentDescription = context.getString(label)
        }
        setFinishing(state == State.FINISHING)
    }
}
