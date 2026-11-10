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
    private var idleEnabled = false
    private var idleLabel = R.string.status_opening

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
    fun acceptsCompletion(take: Long): Boolean = take == generation && state != null
    fun cancelTake() { generation++; state = null; setFinishing(false) }

    fun setMicrophone(enabled: Boolean, label: Int) {
        idleEnabled = enabled
        idleLabel = label
        applyMicrophone(enabled, label)
    }

    private fun applyMicrophone(enabled: Boolean, label: Int) {
        view?.findViewById<ImageButton>(R.id.microphone)?.apply {
            isEnabled = enabled
            alpha = if (enabled) 1f else 0.55f
            contentDescription = context.getString(label)
        }
    }

    private fun setFinishing(finishing: Boolean) {
        view?.findViewById<View>(R.id.microphone)?.visibility = if (finishing) View.GONE else View.VISIBLE
        view?.findViewById<View>(R.id.recording_ring)?.visibility = if (finishing) View.GONE else View.VISIBLE
        view?.findViewById<View>(R.id.processing)?.visibility = if (finishing) View.VISIBLE else View.GONE
    }

    private fun render() {
        val view = view ?: return
        val state = state
        if (state == null) {
            applyMicrophone(idleEnabled, idleLabel)
            return
        }
        val (status, label) = when (state) {
            State.OPENING -> R.string.status_opening to R.string.status_opening
            State.RECORDING -> R.string.status_recording to R.string.stop_recording
            State.INTERRUPTED -> R.string.status_capture_ended to R.string.finish_transcription
            State.FINISHING -> R.string.status_finishing to R.string.status_finishing
        }
        view.findViewById<TextView>(R.id.status).setText(status)
        applyMicrophone(state == State.RECORDING || state == State.INTERRUPTED, label)
        setFinishing(state == State.FINISHING)
    }
}
