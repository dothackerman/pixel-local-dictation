package ch.og.dictation.take

import ch.og.dictation.audio.MicrophoneCapture
import ch.og.dictation.audio.RecordingFeedback
import ch.og.dictation.diagnostics.StartupTrace
import ch.og.dictation.history.HistoryNotice
import ch.og.dictation.history.HistoryTakeOutput
import ch.og.dictation.panel.PanelFrame
import java.util.concurrent.Executor
import org.json.JSONObject

/**
 * The take host: what the take controller asks of the input method, synchronously on the main
 * thread. The six effects, and three facts the controller reads at the moment it decides.
 */
interface TakeHost {
    /** Shows [frame] on the current panel, if there is one; [feedback] drives the live ring. */
    fun show(frame: PanelFrame, feedback: RecordingFeedback?)
    /** Hands back to the previous keyboard; false when there is none (the panel hides instead). */
    fun returnToKeyboard(): Boolean
    /** Asks Android to show the input method's own panel. */
    fun requestPanel()
    /** Puts [text] on the clipboard. */
    fun copy(text: String)
    /** Opens the microphone permission prompt. */
    fun askPermission()
    /** A History warning, shown on the History page only. */
    fun historyNotice(notice: HistoryNotice)

    fun hasMicPermission(): Boolean
    /** Android silenced this app's capture (a client whose window is hidden). */
    fun captureSilenced(): Boolean
    /** The identity of the field the input is bound to now; null without an editor. */
    fun currentField(): FieldIdentity?
}

/**
 * The take scheduler: the take's one thread (the IME main thread, ADR-010). No clock: a delay is
 * only ever handed over. [executor] is the one FIFO queue the take session's and History's
 * deliveries share with [post].
 */
interface TakeScheduler {
    fun interface Cancellable { fun cancel() }

    /** Runs [action] on the take's thread after what is queued now; safe from any thread. */
    fun post(action: Runnable)
    /** Runs [action] on the take's thread after [delayMs], in order with [post] at 0; cancelled, it never runs. */
    fun postDelayed(delayMs: Long, action: Runnable): Cancellable
    val executor: Executor
    /** Throws unless called on the take's thread; [what] names the work. */
    fun checkThread(what: String)
}

/** The take opener: opens a take's session from the Engine with History attached. */
fun interface TakeOpener {
    /** What one take is built from, read once at its start: a take keeps what it started with. */
    fun begin(): Opening

    interface Opening {
        /** Lab build: the running Candidate A has a live receipt. */
        fun liveCheck(): Boolean
        /** The take's microphone capture on a fresh recorder. The callbacks arrive on the capture's threads. */
        fun capture(onStarted: () -> Unit, feedback: RecordingFeedback, onInterrupted: () -> Unit): MicrophoneCapture
        /** Delivers an unexpected capture end to the take's thread. */
        fun postCaptureEnd(action: Runnable)
        /**
         * Opens and starts the session with History between it and [output]. [attached] gets the
         * History side and the take's receipt source before the session starts.
         */
        fun open(
            output: TakeOutput, capture: TakeCapture, executor: Executor, observer: TimelineObserver?, retentionEligible: Boolean,
            notice: (HistoryNotice) -> Unit, attached: (history: HistoryTakeOutput, receipt: () -> JSONObject?) -> Unit,
        ): TakeSession
    }
}

/** Rotation counting, which stays with the service: it sees the display and its own configuration. */
interface RotationCounter {
    /** Reads the display rotation now and counts a change; null when it cannot be read. */
    fun observe(): Int?
    /** How many rotation changes were counted so far. */
    val changes: Long
    /** The number of the panel view created last; null before the first. */
    val view: Long?
}

/** The lab build's side of a take: the startup trace's fields and the recording save stay with the service. */
interface TakeLab {
    val startup: StartupTrace?
    /** The current take's id in the startup trace. */
    var startupTake: String?
    /** Begins a take in the startup trace; null without one. */
    fun beginStartupTake(): String?
    fun observeGeometry(take: String?)
    /** Recordings are saved: the owner's switch is on and no live receipt is collected ([liveCheck]). */
    fun recordingEnabled(liveCheck: Boolean): Boolean
    fun saveRecording(enabled: Boolean, pcm: ByteArray, result: TakeResult)
}

/** The take's log lines; never text of a take. */
interface TakeLog {
    fun info(message: String)
    fun warn(message: String)
    fun error(message: String)
}
