package ch.og.dictation.take

import ch.og.dictation.R
import ch.og.dictation.audio.MicrophoneCapture
import ch.og.dictation.audio.MicrophoneOwner
import ch.og.dictation.audio.Recorder
import ch.og.dictation.audio.RecordingFeedback
import ch.og.dictation.diagnostics.RotationEvent
import ch.og.dictation.diagnostics.RotationTrace
import ch.og.dictation.diagnostics.StartupTrace
import ch.og.dictation.history.HistoryNotice
import ch.og.dictation.history.HistoryStore
import ch.og.dictation.history.HistoryTakeOutput
import ch.og.dictation.panel.PanelFrame
import ch.og.dictation.recognition.Chunk
import ch.og.dictation.recognition.DictationRuntime
import ch.og.dictation.recognition.Recognizer
import ch.og.dictation.recognition.Segmenter
import ch.og.dictation.recognition.Variant
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The take controller at its interface, without Android (#85): the service's events go in, the
 * panel frame and the effects come out through the ports. The microphone owner is the real one and
 * is released when each test ends.
 */
class TakeControllerTest {
    private class Host : TakeHost {
        val frames = mutableListOf<PanelFrame>()
        var returns = 0
        var panelRequests = 0
        var asked = 0
        var permission = true
        override fun show(frame: PanelFrame, feedback: RecordingFeedback?) { frames += frame }
        override fun returnToKeyboard(): Boolean { returns++; return true }
        override fun requestPanel() { panelRequests++ }
        override fun copy(text: String) {}
        override fun askPermission() { asked++ }
        override fun historyNotice(notice: HistoryNotice) {}
        override fun hasMicPermission() = permission
        override fun captureSilenced() = false
        override fun currentField(): FieldIdentity? = FIELD
    }

    /** The take's thread is the test's: nothing runs until [drain]. Delayed work is kept and never run here. */
    private class Scheduler : TakeScheduler {
        private val thread = Thread.currentThread()
        private val queue = ConcurrentLinkedQueue<Runnable>()
        override fun post(action: Runnable) { queue.add(action) }
        override fun postDelayed(delayMs: Long, action: Runnable): TakeScheduler.Cancellable {
            if (delayMs == 0L) queue.add(action)
            return TakeScheduler.Cancellable { queue.remove(action) }
        }
        override val executor = Executor { queue.add(it) }
        override fun checkThread(what: String) { check(Thread.currentThread() === thread) { "$what off the take's thread" } }
        fun drain() { while (true) (queue.poll() ?: return).run() }
    }

    /** The microphone: it reads what [say] feeds; its end is the recorder's own stop. */
    private class Input : Recorder.AudioInput {
        override val bufferSize = 8
        private val frames = LinkedBlockingQueue<ShortArray>()
        override fun start() { frames.clear() }
        override fun read(buffer: ShortArray): Int {
            val frame = frames.take()
            frame.copyInto(buffer)
            return if (frame.isEmpty()) -1 else frame.size
        }
        override fun stop() { frames.offer(shortArrayOf()) }
        override fun release() {}
        fun say() { frames.add(shortArrayOf(1000)) }
    }

    private class Field : EditorConnection {
        val written = mutableListOf<String>()
        override fun write(text: String): Boolean { written += text; return true }
        override fun textBeforeCursor(chars: Int): CharSequence = ""
        override fun extractedLength(): Int? = null
    }

    private class Rotations : RotationCounter {
        var observed = 0
        override fun observe(): Int { observed++; return 0 }
        override val changes = 0L
        override val view: Long? = null
    }

    private val lab = object : TakeLab {
        override val startup: StartupTrace? = null
        override var startupTake: String? = null
        override fun beginStartupTake(): String? = null
        override fun observeGeometry(take: String?) {}
        override fun recordingEnabled(liveCheck: Boolean) = false
        override fun saveRecording(enabled: Boolean, pcm: ByteArray, result: TakeResult) {}
    }

    private val log = object : TakeLog {
        override fun info(message: String) {}
        override fun warn(message: String) {}
        override fun error(message: String) {}
    }

    private val folder: File = Files.createTempDirectory("take-controller").toFile()
    private val host = Host()
    private val scheduler = Scheduler()
    private val input = Input()
    private val field = Field()
    private val rotations = Rotations()
    private val diagnostics = TakeDiagnostics()
    private val runtime = DictationRuntime(Variant.HISTORICAL_INT8, Executor { it.run() }, { Recognizer { "word" } }, {
        object : Segmenter {
            override fun accept(samples: FloatArray) = listOf(Chunk(samples, true))
            override fun flush() = emptyList<Chunk>()
            override fun reset() {}
        }
    })
    private var opened = 0
    private val opener = TakeOpener {
        object : TakeOpener.Opening {
            override fun liveCheck() = false
            override fun capture(onStarted: () -> Unit, feedback: RecordingFeedback, onInterrupted: () -> Unit) =
                MicrophoneCapture(onStarted, feedback, onInterrupted, Recorder { input })
            override fun postCaptureEnd(action: Runnable) = scheduler.post(action)
            override fun open(
                output: TakeOutput, capture: TakeCapture, executor: Executor, observer: TimelineObserver?, retentionEligible: Boolean,
                notice: (HistoryNotice) -> Unit, attached: (history: HistoryTakeOutput, receipt: () -> JSONObject?) -> Unit,
            ): TakeSession {
                opened++
                val history = HistoryTakeOutput(output, { HistoryStore(File(folder, "history")) }, Executor { it.run() },
                    { notice(HistoryNotice.SAVE) }, delivery = executor)
                attached(history) { null }
                return runtime.open(capture, history, executor, observer, retentionEligible = retentionEligible).also { it.start() }
            }
        }
    }
    private val controller = TakeController(host, scheduler, opener, EditorPort { field }, diagnostics, rotations, lab, log)

    @Before fun freeMicrophone() {
        assertFalse(MicrophoneOwner.state().occupied)
        MicrophoneOwner.acceptExplicitStart()
    }

    @After fun releaseMicrophone() {
        controller.beginDestroy()
        controller.finishDestroy()
        await { !MicrophoneOwner.state().occupied }
        runtime.close().join()
        MicrophoneOwner.acceptExplicitStart()
        folder.deleteRecursively()
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) { scheduler.drain(); if (condition()) return; Thread.sleep(2) }
        scheduler.drain()
        assertTrue("timed out waiting for the take", condition())
    }

    private fun openPanel() {
        controller.inputStart(EditorRetention.ELIGIBLE, FIELD_PACKAGE, FIELD)
        controller.inputViewStart()
    }

    @Test fun openingThePanelOnAnEditorStartsOneTakeAndShowsRecording() {
        openPanel()
        assertEquals(1, opened)
        assertTrue(controller.takeActive)
        assertTrue(MicrophoneOwner.state().occupied)
        assertEquals("until the platform recording has started", R.string.status_opening, host.frames.last().status)
        scheduler.drain()
        assertEquals(R.string.status_recording, host.frames.last().status)
        assertTrue(host.frames.last().stopGlyph)
        // The panel shown again on the same take starts no second one.
        controller.inputViewStart()
        assertEquals(1, opened)
    }

    @Test fun stopWritesTheTextOnceIntoTheStartingFieldAndReturnsToTheKeyboard() {
        openPanel()
        scheduler.drain()
        input.say()
        await { host.frames.last().preview == "word" }
        assertEquals("nothing is written before Stop", emptyList<String>(), field.written)
        controller.microphonePressed()
        assertEquals(R.string.status_finishing, host.frames.last().status)
        await { host.returns == 1 }
        assertEquals(listOf("word"), field.written)
        await { !controller.takeActive && !MicrophoneOwner.state().occupied }
        assertEquals(R.string.status_idle, host.frames.last().status)
        assertEquals(1, host.returns)
        assertEquals(0, host.panelRequests)
    }

    @Test fun withoutTheMicrophonePermissionNoTakeStartsAndThePromptOpensOnce() {
        host.permission = false
        openPanel()
        assertEquals(0, opened)
        assertEquals(1, host.asked)
        assertEquals(R.string.status_no_permission, host.frames.last().status)
        controller.inputViewHidden()
        controller.editorEvent(EditorEvent.INPUT_VIEW_FINISH)
        controller.inputViewStart()
        assertEquals("asked once per service lifetime", 1, host.asked)
        assertEquals(R.string.status_no_permission, host.frames.last().status)
        assertFalse(MicrophoneOwner.state().occupied)
    }

    /** Reading the rotation counts changes: a traced event reads it only when a trace is attached (#84). */
    @Test fun aTracedEventReadsTheRotationOnlyWhenATraceIsAttached() {
        controller.rotationEvent(RotationEvent.WINDOW_SHOWN)
        assertEquals(0, rotations.observed)
        controller.editorEvent(EditorEvent.WINDOW_HIDDEN)
        assertEquals("an editor event reads it once, traced or not", 1, rotations.observed)
        val file = File(folder, "trace.jsonl")
        val trace = RotationTrace(file)
        diagnostics.attach(trace)
        controller.rotationEvent(RotationEvent.WINDOW_SHOWN)
        assertEquals(2, rotations.observed)
        assertTrue(trace.flush())
        val row = JSONObject(file.readLines().single())
        assertEquals("WINDOW_SHOWN", row.getString("event"))
        assertFalse(row.getBoolean("active"))
        assertFalse(row.getBoolean("authority"))
        assertEquals(0, row.getInt("rotation"))
        assertFalse("no view and no take yet", row.has("view") || row.has("take"))
    }

    private companion object {
        const val FIELD_PACKAGE = "org.example.editor"
        val FIELD = FieldIdentity(FIELD_PACKAGE, 7, 1, 0, null, 0)
    }
}
