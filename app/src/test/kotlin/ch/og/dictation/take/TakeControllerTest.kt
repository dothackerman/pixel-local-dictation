package ch.og.dictation.take

import ch.og.dictation.R
import ch.og.dictation.audio.MicrophoneCapture
import ch.og.dictation.audio.MicrophoneOwner
import ch.og.dictation.audio.Recorder
import ch.og.dictation.audio.RecordingFeedback
import ch.og.dictation.diagnostics.RotationEvent
import ch.og.dictation.diagnostics.RotationTrace
import ch.og.dictation.diagnostics.StartupTrace
import ch.og.dictation.history.HistoryCompletion
import ch.og.dictation.history.HistoryDelivery
import ch.og.dictation.history.HistoryNotice
import ch.og.dictation.history.HistoryStore
import ch.og.dictation.history.HistoryTakeOutput
import ch.og.dictation.panel.PanelChoice
import ch.og.dictation.panel.PanelFrame
import ch.og.dictation.recognition.Chunk
import ch.og.dictation.recognition.DictationRuntime
import ch.og.dictation.recognition.Recognizer
import ch.og.dictation.recognition.Segmenter
import ch.og.dictation.recognition.Variant
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The take controller at its interface, without Android (#85, #86, #87, #88): the service's events go in,
 * the panel frame and the effects come out through the ports. The scheduler is a fake with its own
 * clock; the microphone owner is the real one, observed as the service observes it, and is released
 * when each test ends.
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
        /** While set, what another thread posts is kept in [heldPosts] and runs only when the test runs it. */
        @Volatile var holdForeignPosts = false
        val heldPosts = LinkedBlockingQueue<Runnable>()
        private fun add(delayMs: Long, action: Runnable) {
            if (holdForeignPosts && Thread.currentThread() !== thread) { heldPosts.add(action); return }
            synchronized(queue) { queue += Entry(now + delayMs, posted++, action) }
        }
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

    /** The microphone: it reads what [say] feeds; its end is the recorder's own stop, or [endUnexpectedly]. */
    private class Input : Recorder.AudioInput {
        override val bufferSize = 8
        private val frames = LinkedBlockingQueue<ShortArray>()
        /** The platform recording was started (by the take's start and by each resume) and stopped (by each pause and by Stop). */
        val starts = AtomicInteger()
        val stops = AtomicInteger()
        val releases = AtomicInteger()
        /** The platform cannot stop the recording: a pause fails. */
        @Volatile var stopFails = false
        /** The platform cannot start the recording again: a resume fails. */
        @Volatile var restartFails = false
        /** The release waits for it: the microphone stays owned meanwhile. */
        @Volatile var releaseGate: CountDownLatch? = null
        /** So many releases fail first: the platform does not confirm them. */
        @Volatile var failedReleases = 0
        @Volatile var reader: Thread? = null
        override fun start() {
            if (starts.incrementAndGet() > 1 && restartFails) error("synthetic restart failure")
            frames.clear()
        }
        override fun read(buffer: ShortArray): Int {
            reader = Thread.currentThread()
            val frame = frames.take()
            frame.copyInto(buffer)
            return if (frame.isEmpty()) -1 else frame.size
        }
        override fun stop() {
            stops.incrementAndGet()
            frames.offer(shortArrayOf())
            if (stopFails) error("synthetic stop failure")
        }
        override fun release() {
            releaseGate?.await(5, TimeUnit.SECONDS)
            if (releases.incrementAndGet() <= failedReleases) error("synthetic release failure")
        }
        fun say() { frames.add(shortArrayOf(1000)) }
        /** The platform ends the recording by itself; the recorder's read thread has ended when this returns. */
        fun endUnexpectedly() {
            frames.add(shortArrayOf())
            reader!!.join(2000)
            assertFalse(reader!!.isAlive)
        }
    }

    /** The editor's field. [shows]: what was written can be read back; false is an editor that answers the write and shows nothing (yet). */
    private inner class Field : EditorConnection {
        val written = mutableListOf<String>()
        var shows = true
        var reads = 0
        /** What the editor does inside a read, before it answers. */
        var duringRead: () -> Unit = {}
        /** What happens inside a write, after the editor took the text and before it answers. */
        var duringWrite: () -> Unit = {}
        private val content = StringBuilder()
        override fun write(text: String): Boolean { written += text; effects += "write:$text"; content.append(text); duringWrite(); return true }
        override fun textBeforeCursor(chars: Int): CharSequence { reads++; duringRead(); return if (shows) content.takeLast(chars) else "" }
        override fun extractedLength(): Int? = null
    }

    private class Rotations : RotationCounter {
        var observed = 0
        override fun observe(): Int { observed++; return 0 }
        override var changes = 0L
        override val view: Long? = null
    }

    /** The owner's recording switch (lab build). */
    private var recordings = false
    /** How often a take's audio was offered for saving. */
    private var recordingSaves = 0
    private val lab = object : TakeLab {
        override val startup: StartupTrace? = null
        override var startupTake: String? = null
        override fun beginStartupTake(): String? = null
        override fun observeGeometry(take: String?) {}
        override fun recordingEnabled(liveCheck: Boolean) = recordings
        override fun saveRecording(enabled: Boolean, pcm: ByteArray, result: TakeResult) { recordingSaves++ }
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
    private val flushes = AtomicInteger()
    /** While set, decoding waits in [heldDecodes]: its output is late, whatever happened to the take meanwhile. */
    @Volatile private var holdDecode = false
    private val heldDecodes = LinkedBlockingQueue<Runnable>()
    private fun runHeldDecodes() { holdDecode = false; while (true) (heldDecodes.poll() ?: return).run() }
    private val runtime = DictationRuntime(Variant.HISTORICAL_INT8, Executor { if (holdDecode) heldDecodes.add(it) else it.run() }, { Recognizer { "word" } }, {
        object : Segmenter {
            override fun accept(samples: FloatArray) = listOf(Chunk(samples, true))
            override fun flush(): List<Chunk> { flushes.incrementAndGet(); return emptyList() }
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
    /** Every controller of this test and whether it was destroyed already; each is destroyed when the test ends. */
    private val controllers = mutableMapOf<TakeController, Boolean>()
    private fun controllerOn(host: Host) =
        TakeController(host, scheduler, opener, EditorPort { field }, diagnostics, rotations, lab, log).also { controllers[it] = false }
    private val controller = controllerOn(host)

    @Before fun freeMicrophone() {
        assertFalse(MicrophoneOwner.state().occupied)
        MicrophoneOwner.acceptExplicitStart()
        MicrophoneOwner.observe(controller.microphoneOwnerListener)
    }

    /** The service's onDestroy, in its order. */
    private fun destroy(controller: TakeController) {
        if (controllers.put(controller, true) == true) return
        controller.beginDestroy()
        MicrophoneOwner.remove(controller.microphoneOwnerListener)
        controller.finishDestroy()
    }

    @After fun releaseMicrophone() {
        scheduler.holdForeignPosts = false
        input.releaseGate?.countDown()
        runHeldDecodes()
        controllers.keys.toList().forEach { destroy(it) }
        await {
            if (MicrophoneOwner.state().releaseFailed) MicrophoneOwner.retryRelease()
            !MicrophoneOwner.state().occupied
        }
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

    // --- Interruptions (#87): an input restart, a hide, a lock and the loss of the microphone. ---

    private fun status(on: Host = host) = STATUS.getValue(on.frames.last().status)

    /** The service's onFinishInputView. */
    private fun hidePanel() {
        controller.inputViewHidden()
        controller.editorEvent(EditorEvent.INPUT_VIEW_FINISH)
    }

    private fun restartInput(retention: EditorRetention = EditorRetention.ELIGIBLE, field: FieldIdentity = FIELD) =
        controller.inputStart(retention, FIELD_PACKAGE, field)

    /** The interrupted take ended: its microphone is free and its text is in History, marked interrupted. */
    private fun awaitInterruptedInHistory() {
        awaitTakeEnded()
        await { historyRows().singleOrNull()?.completion == HistoryCompletion.INTERRUPTED }
        assertEquals("word", historyRows().single().text)
    }

    private fun assertNothingWritten() {
        assertEquals(emptyList<String>(), field.written)
        assertEquals(0, host.returns)
    }

    /** The interrupted take's outcome is in; text the panel holds is discarded, so the next take may start. */
    private fun awaitOutcomeThenDiscardHeld() {
        await { !MicrophoneOwner.state().occupied && host.frames.last().microphoneEnabled }
        if (host.frames.last().held) controller.onDiscardAction()
    }

    /** The same field restarting input without a rotation is a session change: the take ends at once, into History only. */
    @Test fun anInputRestartWithoutARotationInterruptsAtOnceIntoHistoryOnly() {
        recordingWithSpeech()
        restartInput()
        assertEquals("at once, while the microphone is released", "stopping", status())
        assertFalse(host.frames.last().stopGlyph)
        assertFalse(host.frames.last().microphoneEnabled)
        assertEquals("", host.frames.last().preview)
        awaitInterruptedInHistory()
        assertNothingWritten()
        assertTrue("the text is not lost: the panel holds it", host.frames.last().held)
        assertEquals("unable_history", status())
        assertEquals("word", host.frames.last().preview)
        assertEquals(1, opened)
        // Guard: the interruption itself latches the automatic start, also once no explicit start is required any more.
        controller.onDiscardAction()
        assertFalse(MicrophoneOwner.state().explicitStartRequired)
        hidePanel()
        controller.inputViewStart()
        assertEquals("the panel shown again starts nothing until the microphone is pressed", 1, opened)
        controller.microphonePressed()
        assertEquals(2, opened)
    }

    @Test fun aHideAndAnotherFieldWithoutARotationInterruptAtOnceAndThePanelThereStartsNoTake() {
        recordingWithSpeech()
        hidePanel()
        assertEquals("stopping", status())
        restartInput(field = OTHER_FIELD)
        controller.inputViewStart()
        awaitInterruptedInHistory()
        scheduler.drain()
        assertNothingWritten()
        assertEquals("an interruption latches the automatic start", 1, opened)
        assertFalse(controller.takeActive)
    }

    /** An editor that restarts input by itself right after Stop, without a rotation: the result goes to History only (spec #36). */
    @Test fun anEditorRestartingInputRightAfterStopSendsTheResultToHistoryOnly() {
        recordingWithSpeech()
        controller.cancelAndReturn()
        controller.chooseStop()
        assertEquals("finishing", status())
        hidePanel()
        controller.inputFinish()
        restartInput()
        controller.inputViewStart()
        awaitInterruptedInHistory()
        assertNothingWritten()
        assertTrue(host.frames.last().held || host.frames.last().unableToInsert)
    }

    /** A system interruption overrides an open choice; audio captured before the pause goes to History. */
    @Test fun aHideOverridesAnOpenRecordingChoiceAndKeepsThePausedAudioInHistory() {
        recordingWithSpeech()
        controller.cancelAndReturn()
        assertEquals(PanelChoice.RECORDING, host.frames.last().choice)
        hidePanel()
        assertEquals(null, host.frames.last().choice)
        awaitInterruptedInHistory()
        assertNothingWritten()
    }

    @Test fun aLockWhilePausedStopsAndUnlockResumesNothing() {
        recordingWithSpeech()
        controller.cancelAndReturn()
        controller.screenOff()
        awaitInterruptedInHistory()
        restartInput() // Unlock: the same app restarts input.
        controller.inputViewStart()
        scheduler.drain()
        assertEquals(1, opened)
        assertFalse(controller.takeActive)
        assertEquals(null, host.frames.last().choice)
        assertNothingWritten()
    }

    @Test fun aLockInterruptsIntoHistoryAndUnlockDoesNotResumeCapture() {
        val gate = CountDownLatch(1)
        input.releaseGate = gate
        recordingWithSpeech()
        controller.screenOff()
        assertEquals("stopping", status())
        assertTrue("until the platform released it", MicrophoneOwner.state().occupied)
        assertNothingWritten()
        gate.countDown()
        awaitInterruptedInHistory()
        await { host.frames.last().unableToInsert }
        assertFalse("a lock never keeps the text in the panel", host.frames.last().held)
        assertEquals("", host.frames.last().preview)
        val saved = historyRows().single()
        restartInput() // Unlock: the same app restarts input.
        controller.inputViewStart()
        scheduler.drain()
        assertEquals(1, opened)
        assertFalse(MicrophoneOwner.state().occupied)
        assertEquals(listOf(saved.id), historyRows().map { it.id })
        assertNothingWritten()
    }

    /** A take in a private field, active at the lock: nothing is held or shown after unlock, and History has nothing. */
    @Test fun aTakeActiveAtTheLockHoldsAndShowsNothingAfterUnlock() {
        controller.inputStart(EditorRetention.PASSWORD, FIELD_PACKAGE, FIELD)
        controller.inputViewStart()
        scheduler.drain()
        input.say()
        await { host.frames.last().preview == "word" }
        controller.screenOff()
        awaitTakeEnded()
        await { host.frames.last().microphoneEnabled }
        restartInput(EditorRetention.PASSWORD) // Unlock: the same app restarts input.
        controller.inputViewStart()
        scheduler.drain()
        val frame = host.frames.last()
        assertFalse(frame.held)
        assertEquals("", frame.preview)
        assertEquals(null, frame.choice)
        assertTrue(historyRows().isEmpty())
        assertEquals(1, opened)
        assertNothingWritten()
    }

    @Test fun aLockDuringTheStopWaitTurnsThePendingInsertIntoOneHistorySave() {
        val gate = CountDownLatch(1)
        input.releaseGate = gate
        recordingWithSpeech()
        controller.microphonePressed()
        assertEquals("finishing", status())
        controller.screenOff()
        assertEquals("stopping", status())
        assertNothingWritten()
        gate.countDown()
        awaitInterruptedInHistory()
        await { host.frames.last().unableToInsert }
        assertEquals(1, flushes.get())
        assertNothingWritten()
    }

    /** The screen goes off inside the editor's write: the accepted insert is neither undone nor repeated. */
    @Test fun aLockAfterTheEditorAcceptedTheWriteNeitherUndoesNorRepeatsIt() {
        recordingWithSpeech()
        field.duringWrite = { field.duringWrite = {}; controller.screenOff() }
        controller.microphonePressed()
        awaitTakeEnded()
        await { historyRows().singleOrNull()?.delivery == HistoryDelivery.ACCEPTED }
        assertEquals(listOf("word"), field.written)
        assertEquals(1, flushes.get())
        assertEquals("word", historyRows().single().text)
        assertFalse(host.frames.last().held)
        controller.screenOff()
        scheduler.drain()
        assertEquals(listOf("word"), field.written)
    }

    /** The platform ends the recording by itself: what was accepted is finished into History only. */
    @Test fun anUnexpectedCaptureEndFinishesTheAcceptedSpeechIntoHistoryOnly() {
        val gate = CountDownLatch(1)
        input.releaseGate = gate
        recordingWithSpeech()
        input.endUnexpectedly()
        assertEquals("the end is delivered on the take's thread, not on the recorder's", "recording", status())
        scheduler.drain()
        assertEquals("stopping", status())
        assertTrue(MicrophoneOwner.state().occupied)
        assertNothingWritten()
        gate.countDown()
        awaitInterruptedInHistory()
        assertEquals(1, flushes.get())
        assertTrue(host.frames.last().held)
        assertEquals(1, opened)
        assertNothingWritten()
    }

    /** The end is queued behind Stop: Stop shows first, then the interruption wins; no recording is saved for the lab. */
    @Test fun anUnexpectedEndQueuedBehindStopStillInterruptsIntoHistory() {
        recordings = true
        val gate = CountDownLatch(1)
        input.releaseGate = gate
        recordingWithSpeech()
        assertTrue("this take's audio would be saved", host.frames.last().banner)
        input.endUnexpectedly()
        controller.microphonePressed()
        assertEquals("finishing", status())
        scheduler.drain()
        assertEquals("stopping", status())
        gate.countDown()
        awaitInterruptedInHistory()
        assertEquals(1, flushes.get())
        assertNothingWritten()
        assertEquals("an interrupted take's audio is never offered for saving", 0, recordingSaves)
    }

    /** Cancel after the capture already ended is a system interruption, not a choice; the microphone then starts a new take. */
    @Test fun anUnexpectedEndOverridesTheCancelChoiceAndTheMicrophoneStartsANewTake() {
        recordingWithSpeech()
        input.endUnexpectedly()
        controller.cancelAndReturn()
        assertEquals(null, host.frames.last().choice)
        assertEquals("stopping", status())
        awaitOutcomeThenDiscardHeld()
        assertEquals(HistoryCompletion.INTERRUPTED, historyRows().single().completion)
        controller.microphonePressed()
        assertEquals(2, opened)
        scheduler.drain()
        assertEquals("recording", status())
        input.say()
        await { host.frames.last().preview == "word" }
        assertTrue(controller.takeActive)
        assertNothingWritten()
    }

    /**
     * Guard: a capture end belongs to the take whose record it holds. Delivered after the next take
     * was started by the microphone, it does not interrupt that take.
     */
    @Test fun anEarlierTakesCaptureEndDeliveredAfterANewTakeStartedDoesNotInterruptIt() {
        recordingWithSpeech()
        scheduler.holdForeignPosts = true
        input.endUnexpectedly()
        val earlierEnd = scheduler.heldPosts.poll(2, TimeUnit.SECONDS)
        assertTrue("the capture end is delivered through the take scheduler", earlierEnd != null)
        scheduler.holdForeignPosts = false
        assertTrue(scheduler.heldPosts.isEmpty())
        controller.cancelAndReturn() // The capture already ended: a system interruption.
        assertEquals(null, host.frames.last().choice)
        awaitOutcomeThenDiscardHeld()
        MicrophoneOwner.requireExplicitStart()
        restartInput(EditorRetention.PASSWORD)
        controller.inputViewStart()
        assertEquals("an explicit start is required: the panel shown again starts nothing", 1, opened)
        controller.microphonePressed()
        assertEquals(2, opened)
        earlierEnd!!.run()
        scheduler.drain()
        input.say()
        await { host.frames.last().preview == "word" }
        assertEquals("recording", status())
        assertTrue(controller.takeActive)
        assertNothingWritten()
    }

    /** The platform does not confirm the release: the microphone stays owned and only a retry frees it, before any new take. */
    @Test fun anUnexpectedEndWithAnUnconfirmedReleaseNeedsTheRetryBeforeANewTake() {
        input.failedReleases = 1
        recordingWithSpeech()
        input.endUnexpectedly()
        await { MicrophoneOwner.state().releaseFailed && status() == "release_failed" }
        assertTrue(MicrophoneOwner.state().occupied)
        assertEquals(R.string.retry_release, host.frames.last().microphoneLabel)
        assertTrue(host.frames.last().microphoneEnabled)
        controller.microphonePressed() // Retry the release.
        assertEquals(1, opened)
        await { !MicrophoneOwner.state().occupied }
        await { historyRows().singleOrNull()?.completion == HistoryCompletion.INTERRUPTED }
        assertEquals("word", historyRows().single().text)
        assertEquals(1, opened)
        assertNothingWritten()
        await { host.frames.last().microphoneEnabled }
        controller.microphonePressed()
        assertEquals(2, opened)
        assertTrue(controller.takeActive)
    }

    /**
     * The service is replaced while its take's microphone is not released yet (ADR-010: ownership survives
     * the service). The new controller finds the microphone busy: it shows that, offers the retry, returns
     * to idle once the microphone is free, and starts a take only on the next press.
     */
    @Test fun aBusyMicrophoneLeftByADestroyedControllerIsShownRetriedAndNeverStartsATakeByItself() {
        val gate = CountDownLatch(1)
        input.releaseGate = gate
        input.failedReleases = 1
        recordingWithSpeech()
        val saved = historyRows().single()
        hidePanel()
        assertEquals("", host.frames.last().preview)
        destroy(controller)
        gate.countDown()
        await { MicrophoneOwner.state().releaseFailed }
        assertEquals(1, opened)

        val replacementHost = Host()
        val replacement = controllerOn(replacementHost)
        MicrophoneOwner.observe(replacement.microphoneOwnerListener)
        replacement.inputStart(EditorRetention.PASSWORD, FIELD_PACKAGE, FIELD)
        replacement.inputViewStart()
        scheduler.drain()
        assertEquals("release_failed", status(replacementHost))
        assertEquals(R.string.retry_release, replacementHost.frames.last().microphoneLabel)
        assertEquals(1, opened)
        replacement.microphonePressed() // Retry the earlier take's release.
        assertEquals("stopping", status(replacementHost))
        await { !MicrophoneOwner.state().occupied && status(replacementHost) == "idle" }
        assertEquals("the freed microphone starts nothing by itself", 1, opened)
        replacement.inputViewStart()
        assertEquals("nor does the panel shown again", 1, opened)
        assertEquals(emptyList<String>(), field.written)
        replacement.microphonePressed()
        assertEquals(2, opened)
        scheduler.drain()
        assertEquals("recording", status(replacementHost))
        input.say()
        await { replacementHost.frames.last().preview == "word" }
        assertEquals("a private field's take is not kept", listOf(saved.id), historyRows().map { it.id })
        assertEquals(emptyList<String>(), field.written)
        assertEquals(0, host.returns + replacementHost.returns)
    }

    /**
     * Guard: a required explicit start (left by an interruption or a discard, also in an earlier service)
     * latches the automatic start: the panel shows idle and records only on the press.
     */
    @Test fun aRequiredExplicitStartLatchesTheAutomaticStartUntilTheMicrophoneIsPressed() {
        MicrophoneOwner.requireExplicitStart()
        openPanel()
        assertEquals(0, opened)
        assertEquals("idle", status())
        assertTrue(host.frames.last().microphoneEnabled)
        hidePanel()
        MicrophoneOwner.acceptExplicitStart()
        controller.inputViewStart()
        assertEquals("latched for this controller until the press", 0, opened)
        controller.microphonePressed()
        assertEquals(1, opened)
        assertFalse("the press accepted the explicit start", MicrophoneOwner.state().explicitStartRequired)
    }

    /** Guard: the callback order of an interruption, as a table: what each input makes the controller do at its ports, in order. */
    @Test fun anInterruptionCallsItsPortsInTableOrder() {
        val table = listOf<Triple<String, () -> Unit, List<String>>>(
            Triple("open and speak", { recordingWithSpeech() },
                listOf("status:opening", "status:idle", "status:opening", "status:recording")),
            Triple("the panel is hidden", { hidePanel() },
                listOf("status:stopping")),
            Triple("the window is hidden and the input ends", { controller.windowHidden(); controller.inputFinish() },
                emptyList()),
            Triple("the microphone is released and the text saved", { awaitInterruptedInHistory(); scheduler.drain() },
                listOf("status:unable_insert", "status:unable_history")),
            Triple("the panel is shown again", { restartInput(); controller.inputViewStart(); scheduler.drain() },
                emptyList()),
            Triple("Discard text", { controller.onDiscardAction() },
                listOf("status:idle")),
            Triple("the microphone", { controller.microphonePressed(); scheduler.drain() },
                listOf("status:opening", "status:recording")),
        )
        table.forEach { (step, input, expected) ->
            effects.clear()
            input()
            assertEquals(step, expected, effects.toList())
        }
        assertNothingWritten()
        assertEquals(2, opened)
    }

    // --- Cancel choice and Back (#88): pause, Continue, Stop and insert, Discard; Keep and Discard in the Stop wait. ---

    private fun frame() = host.frames.last()

    /** The idle panel, microphone ready, on the panel itself: nothing of the discarded take is left on it. */
    private fun assertIdlePanel() {
        val frame = frame()
        assertEquals("no choice after Discard", null, frame.choice)
        assertEquals("idle", status())
        assertEquals("", frame.preview)
        assertTrue("the microphone is ready for a new take", frame.microphoneEnabled)
        assertEquals(R.string.start_recording, frame.microphoneLabel)
        assertEquals(R.string.back_to_keyboard, frame.backLabel)
        assertFalse(frame.stopGlyph || frame.preparing || frame.held || frame.unableToInsert || frame.ringLive)
    }

    /** The discard is final: the microphone released, the saved text gone, nothing inserted, no hand-back. */
    private fun awaitDiscardFinal() {
        awaitTakeEnded()
        runHeldDecodes()
        await { historyRows().isEmpty() }
        scheduler.drain()
        assertTrue("a discard leaves nothing in History", historyRows().isEmpty())
        assertNothingWritten()
        assertEquals("no new take by itself", 1, opened)
    }

    /** Stop was pressed and the text is prepared; with a choice open it waits there. */
    private fun awaitTextPrepared() {
        await { historyRows().singleOrNull()?.completion == HistoryCompletion.COMPLETE }
        scheduler.drain()
    }

    /**
     * Cancel while recording pauses the take (#61): the platform recording stops, the microphone stays
     * owned by this take, and Continue resumes the same take: one take, speech before and after.
     */
    @Test fun cancelWhileRecordingPausesTheTakeAndContinueResumesTheSameTake() {
        recordingWithSpeech()
        await { historyRows().size == 1 }
        val saved = historyRows().single().id
        controller.cancelAndReturn()
        assertEquals(PanelChoice.RECORDING, frame().choice)
        assertEquals("paused", status())
        assertEquals("Back is the safe action", R.string.continue_recording, frame().backLabel)
        assertFalse(frame().microphoneEnabled)
        assertFalse(frame().ringLive)
        assertEquals(1, input.stops.get())
        assertEquals(0, input.releases.get())
        assertTrue("paused, the microphone stays this take's", MicrophoneOwner.state().occupied)
        input.say() // Speech while paused is not captured.
        scheduler.drain()
        assertEquals("word", frame().preview)
        controller.onSafeAction()
        assertEquals(null, frame().choice)
        assertEquals(2, input.starts.get())
        assertEquals("recording", status())
        assertTrue(frame().microphoneEnabled && frame().stopGlyph && frame().ringLive)
        input.say()
        await { frame().preview == "word word" }
        assertEquals(saved, historyRows().single().id)
        controller.microphonePressed()
        await { host.returns == 1 }
        awaitTakeEnded()
        assertEquals(listOf("word word"), field.written)
        assertEquals(saved, historyRows().single().id)
        assertEquals(1, input.releases.get())
        assertEquals(1, opened)
        assertEquals("the choice is the panel's own: nothing else is asked for", 0, host.panelRequests)
    }

    @Test fun systemBackAndTheBackArrowOnAnOpenChoiceContinueRecording() {
        recordingWithSpeech()
        assertTrue(controller.backKey())
        assertEquals(PanelChoice.RECORDING, frame().choice)
        assertEquals(R.string.continue_recording, frame().backLabel)
        assertTrue(controller.backKey())
        assertEquals("Back is the safe default", null, frame().choice)
        assertEquals(2, input.starts.get())
        controller.cancelAndReturn()
        assertEquals(PanelChoice.RECORDING, frame().choice)
        controller.cancelAndReturn()
        assertEquals(null, frame().choice)
        assertEquals(3, input.starts.get())
        assertEquals(0, input.releases.get())
        input.say()
        await { frame().preview == "word word" }
        assertTrue(controller.takeActive)
        assertEquals("recording", status())
        assertNothingWritten()
        assertEquals(1, historyRows().size)
    }

    /** The choice is the take's state: a panel created again (a rotation) shows it, and the pause stays. */
    @Test fun aPanelCreatedAgainShowsTheOpenChoiceAndThePauseStays() {
        recordingWithSpeech()
        controller.cancelAndReturn()
        val shown = frame()
        controller.panelCreated()
        controller.render()
        assertEquals(shown, frame())
        assertEquals(PanelChoice.RECORDING, frame().choice)
        assertEquals("paused", status())
        assertEquals(1, input.starts.get())
        controller.onSafeAction()
        assertEquals(2, input.starts.get())
        assertEquals(0, input.releases.get())
        assertEquals("recording", status())
    }

    /**
     * Stop and insert on the choice is exactly Stop (#61): one insertion, then the keyboard. The choice is
     * inline, so no window opens and the editor restarts nothing (Signal order, #61 visit A7): until the
     * hand-back the take asks the input method for nothing.
     */
    @Test fun stopAndInsertOnTheChoiceInsertsOnceLikeStop() {
        recordingWithSpeech()
        controller.cancelAndReturn()
        effects.clear()
        controller.chooseStop()
        assertEquals(null, frame().choice)
        assertEquals("finishing", status())
        assertTrue(frame().preparing)
        await { host.returns == 1 }
        awaitTakeEnded()
        assertEquals(listOf("status:finishing", "write:word", "return", "status:idle"), effects.toList())
        assertEquals(listOf("word"), field.written)
        assertEquals(HistoryDelivery.ACCEPTED, historyRows().single().delivery)
        assertEquals(1, input.releases.get())
        assertEquals(0, host.panelRequests)
    }

    /**
     * Discard take is final at once (#36): insertion revoked, the saved text removed, the capture released;
     * what is decoded late changes nothing. The panel stays, idle, and records again only on the press.
     */
    @Test fun discardOnThePausedChoiceIsFinalAtOnceAndLeavesTheIdlePanel() {
        val gate = CountDownLatch(1)
        input.releaseGate = gate
        recordingWithSpeech()
        await { historyRows().size == 1 }
        holdDecode = true
        input.say() // Still being decoded when the take is discarded.
        await { heldDecodes.isNotEmpty() }
        controller.cancelAndReturn()
        controller.onDiscardAction()
        assertEquals("at once", null, frame().choice)
        assertEquals("", frame().preview)
        assertEquals("until the platform released the microphone", "stopping", status())
        assertFalse(frame().microphoneEnabled)
        assertTrue(controller.takeActive)
        gate.countDown()
        awaitDiscardFinal()
        assertIdlePanel()
        assertEquals(1, input.releases.get())
        assertEquals("the panel stays", 0, host.returns)
        // The panel shown again starts nothing; the microphone does.
        hidePanel()
        controller.inputViewStart()
        assertEquals(1, opened)
        controller.microphonePressed()
        assertEquals(2, opened)
    }

    /** Discard during the Stop wait, before the text is prepared: the late result is neither inserted nor kept. */
    @Test fun discardOnTheFinishingChoiceIsFinalAtOnceAndLeavesTheIdlePanel() {
        recordingWithSpeech()
        holdDecode = true
        controller.microphonePressed()
        controller.cancelAndReturn()
        assertEquals(PanelChoice.FINISHING, frame().choice)
        assertEquals("finishing_choice", status())
        controller.onDiscardAction()
        assertEquals(null, frame().choice)
        awaitDiscardFinal()
        assertIdlePanel()
        scheduler.advance(10_000)
        assertIdlePanel()
        assertNothingWritten()
        assertTrue(historyRows().isEmpty())
    }

    /** Discard during the Stop wait, with the prepared text waiting on the choice: it is dropped, never inserted. */
    @Test fun discardOnTheFinishingChoiceDropsTheTextThatWaitedForIt() {
        recordingWithSpeech()
        controller.microphonePressed()
        controller.cancelAndReturn()
        awaitTextPrepared()
        assertNothingWritten()
        controller.onDiscardAction()
        awaitDiscardFinal()
        assertIdlePanel()
    }

    /** A rotation right after a discard recreates the panel on a recreated editor: it is idle and starts nothing. */
    @Test fun aPanelShownOnARecreatedEditorRightAfterADiscardIsIdleAndStartsNoTake() {
        recordingWithSpeech()
        controller.cancelAndReturn()
        controller.onDiscardAction()
        rotations.changes++
        controller.panelCreated()
        controller.render()
        controller.inputStart(EditorRetention.ELIGIBLE, FIELD_PACKAGE, OTHER_FIELD)
        controller.inputViewStart()
        scheduler.advance(ROTATION_SETTLE_MS)
        awaitDiscardFinal()
        assertIdlePanel()
        assertEquals(0, host.panelRequests)
    }

    @Test fun discardInAPrivateFieldIsFinalAtOnceAndKeepsThePrivateLine() {
        controller.inputStart(EditorRetention.PASSWORD, FIELD_PACKAGE, FIELD)
        controller.inputViewStart()
        scheduler.drain()
        input.say()
        await { frame().preview == "word" }
        controller.cancelAndReturn()
        controller.onDiscardAction()
        awaitDiscardFinal()
        assertIdlePanel()
        assertEquals(R.string.retention_field_off, frame().notice)
    }

    /** Cancel during the Stop wait holds the result before it is written; Keep finishing releases it: one insertion. */
    @Test fun cancelDuringTheStopWaitHoldsThePreparedTextUntilKept() {
        recordingWithSpeech()
        controller.microphonePressed()
        controller.cancelAndReturn()
        assertEquals(PanelChoice.FINISHING, frame().choice)
        assertEquals("finishing_choice", status())
        assertEquals("Back is the safe action", R.string.keep_finishing, frame().backLabel)
        assertFalse("the choice replaces the Preparing arc", frame().preparing)
        awaitTextPrepared()
        assertNothingWritten()
        controller.onSafeAction()
        assertEquals(null, frame().choice)
        await { host.returns == 1 }
        awaitTakeEnded()
        assertEquals(listOf("word"), field.written)
        assertEquals(HistoryDelivery.ACCEPTED, historyRows().single().delivery)
        assertEquals("idle", status())
        assertEquals("the Stop wait is not paused: only Stop stopped the recording", 1, input.stops.get())
    }

    @Test fun systemBackOnTheFinishingChoiceKeepsFinishing() {
        recordingWithSpeech()
        controller.microphonePressed()
        assertTrue(controller.backKey())
        assertEquals(PanelChoice.FINISHING, frame().choice)
        awaitTextPrepared()
        assertNothingWritten()
        assertTrue(controller.backKey())
        assertEquals(null, frame().choice)
        await { host.returns == 1 }
        assertEquals(listOf("word"), field.written)
    }

    /**
     * Without a take the Back arrow hands back to the previous keyboard (device trace 2026-11-11: it left
     * this input method selected); the system Back key is not the take's then, and Android hides the panel.
     */
    @Test fun withoutATakeTheBackArrowHandsBackAndSystemBackIsLeftToAndroid() {
        host.permission = false
        openPanel()
        assertEquals(0, opened)
        assertFalse(controller.backKey())
        assertEquals(0, host.returns)
        controller.cancelAndReturn()
        assertEquals(1, host.returns)
        // Also once a take ended on the panel (a discard): Back belongs to the live editor session.
        host.permission = true
        controller.microphonePressed()
        scheduler.drain()
        controller.cancelAndReturn()
        controller.onDiscardAction()
        awaitTakeEnded()
        assertFalse(controller.backKey())
        controller.cancelAndReturn()
        assertEquals(2, host.returns)
        assertEquals(1, opened)
    }

    /**
     * An interrupted take keeps finishing into History and has no choice: the system Back key is taken
     * and does nothing, the Back arrow only leaves.
     */
    @Test fun onAnInterruptedTakeSystemBackDoesNothingAndTheBackArrowOnlyLeaves() {
        val gate = CountDownLatch(1)
        input.releaseGate = gate
        recordingWithSpeech()
        hidePanel()
        assertTrue(controller.takeActive)
        val shown = frame()
        assertTrue(controller.backKey())
        assertEquals(shown, frame())
        assertEquals(0, host.returns)
        controller.cancelAndReturn()
        assertEquals(1, host.returns)
        assertEquals(shown, frame())
        assertEquals(null, frame().choice)
        gate.countDown()
        awaitInterruptedInHistory()
        assertEquals(emptyList<String>(), field.written)
        assertEquals(1, input.stops.get())
    }

    /** Guard: Continue, Stop and insert, Keep and Discard act only on the choice that offers them. */
    @Test fun theChoiceActionsDoNothingWithoutTheChoiceThatOffersThem() {
        recordingWithSpeech()
        val recording = frame()
        controller.onSafeAction()
        controller.chooseStop()
        controller.onDiscardAction()
        assertEquals(recording, frame())
        assertTrue(controller.takeActive)
        assertEquals(0, input.stops.get())
        // The Stop wait offers Keep and Discard only: Stop and insert does nothing there.
        controller.microphonePressed()
        controller.cancelAndReturn()
        val choice = frame()
        controller.chooseStop()
        assertEquals(choice, frame())
        awaitTextPrepared()
        assertNothingWritten()
        controller.onSafeAction()
        await { host.returns == 1 }
        assertEquals(listOf("word"), field.written)
    }

    /** Guard: one choice at a time, and Stop is pressed once: the microphone disk does nothing while the take is paused or finishing. */
    @Test fun aSecondStopDuringTheStopWaitOrItsChoiceChangesNothing() {
        val gate = CountDownLatch(1)
        input.releaseGate = gate
        recordingWithSpeech()
        controller.microphonePressed()
        val finishing = frame()
        controller.microphonePressed()
        assertEquals(finishing, frame())
        controller.cancelAndReturn()
        val choice = frame()
        controller.microphonePressed()
        assertEquals(choice, frame())
        gate.countDown()
        awaitTextPrepared()
        assertNothingWritten()
        controller.cancelAndReturn()
        await { host.returns == 1 }
        assertEquals(listOf("word"), field.written)
        assertEquals(1, input.stops.get())
    }

    /** A recording the platform cannot stop cannot pause: Cancel is then a system interruption, and the audio so far goes to History. */
    @Test fun aTakeThatCannotPauseIsInterruptedInsteadOfPaused() {
        recordingWithSpeech()
        input.stopFails = true
        controller.cancelAndReturn()
        assertEquals(null, frame().choice)
        assertEquals("stopping", status())
        awaitInterruptedInHistory()
        assertNothingWritten()
    }

    /** A paused recording the platform cannot start again cannot continue: Continue is then a system interruption. */
    @Test fun aPausedTakeThatCannotResumeIsInterruptedIntoHistory() {
        recordingWithSpeech()
        controller.cancelAndReturn()
        input.restartFails = true
        controller.onSafeAction()
        assertEquals(null, frame().choice)
        assertEquals("stopping", status())
        awaitInterruptedInHistory()
        assertNothingWritten()
        assertEquals(1, opened)
    }

    /**
     * Back while a discarded take's microphone is still being released: the take is not in the Stop wait
     * any more and cannot pause, so Back opens no choice and the discard stays final. (As before the
     * controller, Back counts as an interruption here and ends the input session: the Back arrow hands
     * back again only after the next input start.)
     */
    @Test fun backWhileADiscardedTakesMicrophoneIsStillReleasedOpensNoChoice() {
        val gate = CountDownLatch(1)
        input.releaseGate = gate
        recordingWithSpeech()
        controller.microphonePressed()
        controller.cancelAndReturn()
        controller.onDiscardAction()
        assertTrue(controller.takeActive)
        assertEquals("stopping", status())
        assertTrue(controller.backKey())
        assertEquals(null, frame().choice)
        assertEquals("stopping", status())
        assertFalse(frame().preparing)
        gate.countDown()
        awaitDiscardFinal()
        assertIdlePanel()
        controller.cancelAndReturn()
        assertEquals(0, host.returns)
        restartInput()
        controller.inputViewStart()
        controller.cancelAndReturn()
        assertEquals(1, host.returns)
        assertEquals(1, opened)
    }

    /** Guard: the callback order of the Cancel choices, as a table: what each input makes the controller do at its ports, in order. */
    @Test fun theCancelChoicesCallTheirPortsInTableOrder() {
        val gate = CountDownLatch(1)
        input.releaseGate = gate
        val table = listOf<Triple<String, () -> Unit, List<String>>>(
            Triple("open and speak", { recordingWithSpeech() },
                listOf("status:opening", "status:idle", "status:opening", "status:recording")),
            Triple("Back arrow", { controller.cancelAndReturn() },
                listOf("status:paused")),
            Triple("Continue recording", { controller.onSafeAction() },
                listOf("status:recording")),
            Triple("system Back", { controller.backKey() },
                listOf("status:paused")),
            Triple("Stop and insert", { controller.chooseStop() },
                listOf("status:finishing")),
            Triple("system Back in the Stop wait", { controller.backKey() },
                listOf("status:finishing_choice")),
            Triple("text prepared", { gate.countDown(); awaitTextPrepared() },
                emptyList()),
            Triple("Keep finishing", { controller.onSafeAction(); awaitTakeEnded() },
                listOf("status:finishing", "write:word", "return", "status:idle")),
        )
        table.forEach { (step, input, expected) ->
            effects.clear()
            input()
            assertEquals(step, expected, effects.toList())
        }
        assertEquals(1, host.returns)
    }

    private companion object {
        const val FIELD_PACKAGE = "org.example.editor"
        val FIELD = FieldIdentity(FIELD_PACKAGE, 7, 1, 0, null, 0)
        val OTHER_FIELD = FieldIdentity(FIELD_PACKAGE, 8, 1, 0, null, 0)
        val STATUS = mapOf(
            R.string.status_opening to "opening", R.string.status_idle to "idle", R.string.status_recording to "recording",
            R.string.status_finishing to "finishing", R.string.status_failed to "failed",
            R.string.status_unable_insert to "unable_insert", R.string.status_unconfirmed to "unconfirmed",
            R.string.status_no_permission to "no_permission", R.string.status_stopping_recording to "stopping",
            R.string.status_unable_history to "unable_history", R.string.status_unable_unsaved to "unable_unsaved",
            R.string.status_release_failed to "release_failed", R.string.status_paused to "paused",
            R.string.status_finishing_choice to "finishing_choice",
        ).withDefault { "status $it" }
    }
}
