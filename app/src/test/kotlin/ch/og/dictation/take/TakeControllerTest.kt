package ch.og.dictation.take

import ch.og.dictation.R
import ch.og.dictation.audio.MicrophoneCapture
import ch.og.dictation.audio.MicrophoneOwner
import ch.og.dictation.audio.Recorder
import ch.og.dictation.audio.RecordingFeedback
import ch.og.dictation.diagnostics.RotationEvent
import ch.og.dictation.diagnostics.RotationTrace
import ch.og.dictation.diagnostics.StartupTrace
import ch.og.dictation.history.HistoryDelivery
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
 * The take controller at its interface, without Android (#85, #86): the service's events go in, the
 * panel frame and the effects come out through the ports. The scheduler is a fake with its own
 * clock; the microphone owner is the real one and is released when each test ends.
 */
class TakeControllerTest {
    /** What the controller did at its ports, in order: `status:…` (a changed status line), `write:…`, `return`. */
    private val effects = mutableListOf<String>()

    private inner class Host : TakeHost {
        val frames = mutableListOf<PanelFrame>()
        val copied = mutableListOf<String>()
        var returns = 0
        var panelRequests = 0
        var asked = 0
        var permission = true
        /** What Android does inside the hand-back, before it returns. */
        var duringReturn: () -> Unit = {}
        override fun show(frame: PanelFrame, feedback: RecordingFeedback?) {
            if (frames.lastOrNull()?.status != frame.status) effects += "status:" + STATUS.getValue(frame.status)
            frames += frame
        }
        override fun returnToKeyboard(): Boolean { returns++; effects += "return"; duringReturn(); return true }
        override fun requestPanel() { panelRequests++ }
        override fun copy(text: String) { copied += text }
        override fun askPermission() { asked++ }
        override fun historyNotice(notice: HistoryNotice) {}
        override fun hasMicPermission() = permission
        override fun captureSilenced() = false
        override fun currentField(): FieldIdentity? = FIELD
    }

    /**
     * The take's thread is the test's, and the clock is the test's too: what is posted runs at
     * [drain], what is delayed runs when [advance] reaches its time, in order of time and posting.
     */
    private class Scheduler : TakeScheduler {
        private class Entry(val at: Long, val order: Long, val action: Runnable)
        private val thread = Thread.currentThread()
        private val queue = mutableListOf<Entry>()
        private var now = 0L
        private var posted = 0L
        /** The next thread check fails, as on any thread but the take's. */
        var wrongThread = false
        private fun add(delayMs: Long, action: Runnable) = synchronized(queue) { queue += Entry(now + delayMs, posted++, action); Unit }
        private fun next(until: Long): Entry? = synchronized(queue) {
            queue.filter { it.at <= until }.minWithOrNull(compareBy({ it.at }, { it.order }))?.also { queue.remove(it); now = maxOf(now, it.at) }
        }
        override fun post(action: Runnable) = add(0, action)
        override fun postDelayed(delayMs: Long, action: Runnable): TakeScheduler.Cancellable {
            add(delayMs, action)
            return TakeScheduler.Cancellable { synchronized(queue) { queue.removeAll { it.action === action } } }
        }
        override val executor = Executor { add(0, it) }
        override fun checkThread(what: String) { check(!wrongThread && Thread.currentThread() === thread) { "$what off the take's thread" } }
        fun drain() { while (true) (next(synchronized(queue) { now }) ?: return).action.run() }
        fun advance(ms: Long) {
            val end = synchronized(queue) { now } + ms
            while (true) (next(end) ?: break).action.run()
            synchronized(queue) { now = end }
        }
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

    /** The editor's field. [shows]: what was written can be read back; false is an editor that answers the write and shows nothing (yet). */
    private inner class Field : EditorConnection {
        val written = mutableListOf<String>()
        var shows = true
        var reads = 0
        /** What the editor does inside a read, before it answers. */
        var duringRead: () -> Unit = {}
        private val content = StringBuilder()
        override fun write(text: String): Boolean { written += text; effects += "write:$text"; content.append(text); return true }
        override fun textBeforeCursor(chars: Int): CharSequence { reads++; duringRead(); return if (shows) content.takeLast(chars) else "" }
        override fun extractedLength(): Int? = null
    }

    private class Rotations : RotationCounter {
        var observed = 0
        override fun observe(): Int { observed++; return 0 }
        override var changes = 0L
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
    /** The next take's recorder cannot be created. */
    private var recorderFails = false
    /** Each take's output as the controller handed it over: what a take's callbacks arrive on. */
    private val outputs = mutableListOf<TakeOutput>()
    private val opener = TakeOpener {
        object : TakeOpener.Opening {
            override fun liveCheck() = false
            override fun capture(onStarted: () -> Unit, feedback: RecordingFeedback, onInterrupted: () -> Unit): MicrophoneCapture {
                if (recorderFails) error("synthetic recorder failure")
                return MicrophoneCapture(onStarted, feedback, onInterrupted, Recorder { input })
            }
            override fun postCaptureEnd(action: Runnable) = scheduler.post(action)
            override fun open(
                output: TakeOutput, capture: TakeCapture, executor: Executor, observer: TimelineObserver?, retentionEligible: Boolean,
                notice: (HistoryNotice) -> Unit, attached: (history: HistoryTakeOutput, receipt: () -> JSONObject?) -> Unit,
            ): TakeSession {
                opened++
                outputs += output
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
        assertTrue(host.frames.last().microphoneEnabled)
        assertFalse("an inserted take holds nothing", host.frames.last().held || host.frames.last().unableToInsert)
        assertEquals(null, host.frames.last().choice)
        assertEquals(HistoryDelivery.ACCEPTED, historyRows().single().delivery)
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

    private fun historyRows() = HistoryStore(File(folder, "history")).list()

    /** A take that is recording, with one chunk of speech decoded and previewed. */
    private fun recordingWithSpeech() {
        openPanel()
        scheduler.drain()
        input.say()
        await { host.frames.last().preview == "word" }
    }

    private fun awaitTakeEnded() = await { !controller.takeActive && !MicrophoneOwner.state().occupied }

    /**
     * Pixel order (#56 trace): the display turns, Android recreates the panel, restarts input on the
     * kept editor and shows the panel again; then the rotation settles. The take went through a
     * rotation hold, so its write is read back.
     */
    private fun rotateAndSettle() {
        rotations.changes++
        controller.panelCreated()
        controller.render()
        controller.settleAfterRotation()
        controller.inputStart(EditorRetention.ELIGIBLE, FIELD_PACKAGE, FIELD)
        controller.inputViewStart()
        scheduler.advance(ROTATION_HOLD_GAP_MS * 2)
        assertTrue(controller.takeActive)
    }

    /** After a rotation hold, Stop writes into an editor that answers the write and does not show it: the second look is pending. */
    private fun stoppedWithAWriteNotSeenAtOnce() {
        recordingWithSpeech()
        rotateAndSettle()
        field.shows = false
        controller.microphonePressed()
        awaitTakeEnded()
        assertEquals(listOf("word"), field.written)
        assertEquals("idle, and the microphone stays off until the second look", R.string.status_idle, host.frames.last().status)
        assertFalse(host.frames.last().microphoneEnabled)
        assertFalse(host.frames.last().held)
        assertEquals(0, host.returns)
    }

    @Test fun aTakeWithoutARotationHoldIsNotReadBack() {
        field.shows = false
        recordingWithSpeech()
        controller.microphonePressed()
        await { host.returns == 1 }
        awaitTakeEnded()
        assertEquals(listOf("word"), field.written)
        assertEquals("only the read before the write, for the leading space", 1, field.reads)
        assertEquals(HistoryDelivery.ACCEPTED, historyRows().single().delivery)
        assertFalse(host.frames.last().held)
        assertEquals(R.string.status_idle, host.frames.last().status)
        assertTrue(host.frames.last().microphoneEnabled)
    }

    /** The write answered true and is never visible: not inserted, held with a status that admits the doubt. */
    @Test fun aWriteTheReadBackNeverSeesIsHeldAsUnconfirmedAt100MsAndNotAt99() {
        stoppedWithAWriteNotSeenAtOnce()
        val before = host.frames.size
        val reads = field.reads
        scheduler.advance(99)
        assertEquals("nothing is decided before the second look", before, host.frames.size)
        assertEquals("the field is not read before the second look", reads, field.reads)
        scheduler.advance(1)
        val frame = host.frames.last()
        assertTrue(frame.held)
        assertTrue("Insertion uncertain, not \"not inserted\"", frame.uncertain)
        assertEquals(R.string.status_unconfirmed, frame.status)
        assertEquals("word", frame.preview)
        assertEquals(0, host.returns)
        assertTrue(historyRows().single().delivery != HistoryDelivery.ACCEPTED)
        // The held text is the take's: Copy hands exactly it over and returns to the keyboard.
        controller.onSafeAction()
        assertEquals(listOf("word"), host.copied)
        assertEquals(1, host.returns)
        assertEquals(listOf("word"), field.written)
    }

    /** Shown at the second look only: counts as inserted, the keyboard is handed back, nothing is held. */
    @Test fun aWriteSeenAtTheSecondLookCountsAsInsertedAt100MsAndNotAt99() {
        stoppedWithAWriteNotSeenAtOnce()
        assertTrue(historyRows().single().delivery != HistoryDelivery.ACCEPTED)
        field.shows = true
        val before = host.frames.size
        val reads = field.reads
        scheduler.advance(99)
        assertEquals("nothing is decided before the second look", before, host.frames.size)
        assertEquals("the field is not read before the second look", reads, field.reads)
        assertEquals(0, host.returns)
        scheduler.advance(1)
        assertEquals(1, host.returns)
        assertFalse(host.frames.last().held)
        assertEquals(R.string.status_idle, host.frames.last().status)
        assertTrue(host.frames.last().microphoneEnabled)
        assertEquals("the receipt the rejected-for-now write left is corrected", HistoryDelivery.ACCEPTED, historyRows().single().delivery)
        assertEquals(listOf("word"), field.written)
    }

    /** A lock during the read-back wait locks the take like any other: nothing is held, at the second look too. */
    @Test fun screenOffDuringTheReadBackWaitHoldsNothing() {
        stoppedWithAWriteNotSeenAtOnce()
        controller.screenOff()
        scheduler.advance(100)
        val frame = host.frames.last()
        assertFalse(frame.held)
        assertEquals("", frame.preview)
        assertTrue(frame.unableToInsert)
        assertEquals(R.string.status_unable_insert, frame.status)
        assertEquals(0, host.returns)
    }

    /** A failed start after an ordinary take must not leave the next starts refused. */
    @Test fun aFailedStartAfterACompletedTakeDoesNotRefuseLaterStarts() {
        recordingWithSpeech()
        controller.microphonePressed()
        await { host.returns == 1 }
        awaitTakeEnded()
        recorderFails = true
        controller.microphonePressed()
        scheduler.drain()
        assertFalse(controller.takeActive)
        assertEquals(R.string.status_failed, host.frames.last().status)
        assertTrue(host.frames.last().microphoneEnabled)
        await { !MicrophoneOwner.state().occupied }
        recorderFails = false
        controller.microphonePressed()
        assertTrue(controller.takeActive)
        scheduler.drain()
        assertEquals(R.string.status_recording, host.frames.last().status)
        assertEquals(2, opened)
    }

    /** Guard: a callback belongs to the take whose record it holds; once the next take started it changes nothing. */
    @Test fun callbacksOfAnEarlierTakeChangeNothingOnceTheNextTakeStarted() {
        recordingWithSpeech()
        controller.microphonePressed()
        await { host.returns == 1 }
        awaitTakeEnded()
        controller.microphonePressed()
        scheduler.drain()
        assertEquals(2, opened)
        assertEquals(R.string.status_recording, host.frames.last().status)
        val shown = host.frames.last()
        val earlier = outputs.first()
        earlier.preview("earlier take")
        earlier.captureReleaseFailed()
        assertFalse("no authority for a take that is not the newest", earlier.commit("earlier take"))
        assertFalse(earlier.returnToKeyboard())
        earlier.finished(TakeResult(TakeOutcome.COMMIT_REJECTED, "earlier take", emptyMap()), ByteArray(0))
        scheduler.drain()
        assertEquals(shown, host.frames.last())
        assertTrue(controller.takeActive)
        assertEquals(listOf("word"), field.written)
        assertEquals(1, host.returns)
        // The newest take is untouched: it still previews, stops and inserts.
        input.say()
        await { host.frames.last().preview == "word" }
        controller.microphonePressed()
        await { host.returns == 2 }
        assertEquals(listOf("word", " word"), field.written)
    }

    /** Guard: the callback order of the ordinary take, as a table: what each input makes the controller do at its ports, in order. */
    @Test fun theOrdinaryTakeCallsItsPortsInTableOrder() {
        val table = listOf<Triple<String, () -> Unit, List<String>>>(
            Triple("input starts on the editor", { controller.inputStart(EditorRetention.ELIGIBLE, FIELD_PACKAGE, FIELD) },
                listOf("status:opening")),
            Triple("the panel is shown", { controller.inputViewStart(); controller.editorEvent(EditorEvent.INPUT_VIEW_START) },
                listOf("status:idle", "status:opening")),
            Triple("the platform recording started", { scheduler.drain() },
                listOf("status:recording")),
            Triple("speech", { input.say(); await { host.frames.last().preview == "word" } },
                emptyList()),
            Triple("Stop", { controller.microphonePressed() },
                listOf("status:finishing")),
            Triple("text prepared", { await { host.returns == 1 }; awaitTakeEnded() },
                listOf("write:word", "return", "status:idle")),
        )
        table.forEach { (step, input, expected) ->
            effects.clear()
            input()
            assertEquals(step, expected, effects.toList())
        }
    }

    /**
     * Guard: state is written before an effect. Handing back can make Android hide the panel and end
     * the input before the call returns; the take was already inserted and must not become an interruption.
     */
    @Test fun aHandBackThatEndsTheInputAtOnceDoesNotUndoTheInsertedTake() {
        recordingWithSpeech()
        host.duringReturn = {
            controller.inputViewHidden()
            controller.editorEvent(EditorEvent.INPUT_VIEW_FINISH)
            controller.inputFinish()
        }
        controller.microphonePressed()
        await { host.returns == 1 }
        awaitTakeEnded()
        assertEquals(listOf("word"), field.written)
        assertEquals(HistoryDelivery.ACCEPTED, historyRows().single().delivery)
        assertEquals(R.string.status_idle, host.frames.last().status)
        assertFalse(host.frames.last().unableToInsert)
        assertFalse(host.frames.last().held)
    }

    /**
     * Guard: state is read again after an effect. Reading the field can make the editor end the input
     * before it answers; the session the write was allowed for is gone, so nothing is written.
     */
    @Test fun anEditorReadThatEndsTheInputWritesNothing() {
        recordingWithSpeech()
        field.duringRead = { field.duringRead = {}; controller.inputFinish() }
        controller.microphonePressed()
        awaitTakeEnded()
        assertEquals(1, field.reads)
        assertEquals(emptyList<String>(), field.written)
        assertEquals(0, host.returns)
        assertTrue(historyRows().single().delivery != HistoryDelivery.ACCEPTED)
    }

    /** Guard: the commit asserts the take's thread before it touches the editor. */
    @Test fun aCommitOffTheTakesThreadWritesNothing() {
        recordingWithSpeech()
        controller.microphonePressed()
        scheduler.wrongThread = true
        awaitTakeEnded()
        scheduler.wrongThread = false
        assertEquals(0, field.reads)
        assertEquals(emptyList<String>(), field.written)
        assertEquals(0, host.returns)
        assertTrue("the text is not lost: the panel holds it", host.frames.last().held)
    }

    /** Guard: every input leaves the panel showing the take's state; rendering again changes nothing. */
    @Test fun afterEveryInputThePanelShowsTheTakesState() {
        val inputs = listOf<Pair<String, () -> Unit>>(
            "input starts on the editor" to { controller.inputStart(EditorRetention.ELIGIBLE, FIELD_PACKAGE, FIELD) },
            "the panel is shown" to { controller.inputViewStart(); controller.editorEvent(EditorEvent.INPUT_VIEW_START) },
            "the platform recording started" to { scheduler.drain() },
            "speech" to { input.say(); await { host.frames.last().preview == "word" } },
            "rotation" to { rotateAndSettle() },
            "Stop" to { field.shows = false; controller.microphonePressed() },
            "text prepared" to { awaitTakeEnded() },
            "one millisecond before the second look" to { scheduler.advance(99) },
            "the second look" to { scheduler.advance(1) },
        )
        inputs.forEach { (step, input) ->
            input()
            val shown = host.frames.last()
            controller.render()
            assertEquals(step, shown, host.frames.last())
        }
        assertTrue(host.frames.last().held)
    }

    private companion object {
        const val FIELD_PACKAGE = "org.example.editor"
        val FIELD = FieldIdentity(FIELD_PACKAGE, 7, 1, 0, null, 0)
        val STATUS = mapOf(
            R.string.status_opening to "opening", R.string.status_idle to "idle", R.string.status_recording to "recording",
            R.string.status_finishing to "finishing", R.string.status_failed to "failed",
            R.string.status_unable_insert to "unable_insert", R.string.status_unconfirmed to "unconfirmed",
            R.string.status_no_permission to "no_permission", R.string.status_stopping_recording to "stopping",
            R.string.status_unable_history to "unable_history", R.string.status_unable_unsaved to "unable_unsaved",
            R.string.status_release_failed to "release_failed",
        ).withDefault { "status $it" }
    }
}
