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
 * The take controller at its interface, without Android (#85, #86, #87, #88, #89, #90): the service's events go in,
 * the panel frame and the effects come out through the ports. The scheduler is a fake with its own
 * clock; the microphone owner is the real one, observed as the service observes it, and is released
 * when each test ends.
 */
class TakeControllerTest {
    /** What the controller did at its ports, in order: `status:…` (a changed status line), `write:…`, `return`, `request_panel`. */
    private val effects = mutableListOf<String>()

    private inner class Host : TakeHost {
        val frames = mutableListOf<PanelFrame>()
        val copied = mutableListOf<String>()
        val notices = mutableListOf<HistoryNotice>()
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
        override fun requestPanel() { panelRequests++; effects += "request_panel" }
        override fun copy(text: String) { copied += text; effects += "copy:$text" }
        override fun askPermission() { asked++ }
        override fun historyNotice(notice: HistoryNotice) { notices += notice }
        override fun hasMicPermission() = permission
        /** Android silences this app's capture (a client whose window is hidden). */
        var silenced = false
        override fun captureSilenced() = silenced
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
        /** The editor answers the write with false and takes nothing. */
        var accepts = true
        /** The editor fails inside the write. */
        var writeThrows = false
        /** The editor deactivated this connection: reads answer null, a write would be dropped and answered true (Pixel probe, 2026-11-13). */
        var dead = false
        private val content = StringBuilder()
        override fun write(text: String): Boolean {
            written += text; effects += "write:$text"
            if (writeThrows) error("synthetic editor failure")
            if (!accepts) return false
            if (!dead) content.append(text)
            duringWrite()
            return true
        }
        override fun textBeforeCursor(chars: Int): CharSequence? {
            reads++; duringRead()
            return if (dead) null else if (shows) content.takeLast(chars) else ""
        }
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
    /** The lab build traces startup: each take begun in the startup trace gets the next id. */
    private var startupTrace = false
    private var startupTakes = 0
    /** The take ids the panel's geometry was observed for. */
    private val geometryTakes = mutableListOf<String?>()
    private val lab = object : TakeLab {
        override val startup: StartupTrace? = null
        override fun beginStartupTake(): String? = if (startupTrace) "startup-${++startupTakes}" else null
        override fun observeGeometry(take: String?) { geometryTakes += take }
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
    /** The connection Android hands out now: the starting field's, until another editor starts input. */
    private var live: EditorConnection? = field
    private val rotations = Rotations()
    private val diagnostics = TakeDiagnostics()
    private val flushes = AtomicInteger()
    /** While set, decoding waits in [heldDecodes]: its output is late, whatever happened to the take meanwhile. */
    @Volatile private var holdDecode = false
    private val heldDecodes = LinkedBlockingQueue<Runnable>()
    private fun runHeldDecodes() { holdDecode = false; while (true) (heldDecodes.poll() ?: return).run() }
    /** While set, History's own work (its saves and their acknowledgement) waits in [heldHistory]. */
    @Volatile private var holdHistory = false
    private val heldHistory = LinkedBlockingQueue<Runnable>()
    private fun runHeldHistory() { holdHistory = false; while (true) (heldHistory.poll() ?: return).run() }
    /** Recognition fails: no text comes out of the take. */
    @Volatile private var decodeFails = false
    private val runtime = DictationRuntime(Variant.HISTORICAL_INT8, Executor { if (holdDecode) heldDecodes.add(it) else it.run() },
        { Recognizer { if (decodeFails) error("synthetic decode failure") else "word" } }, {
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
                val history = HistoryTakeOutput(output, { HistoryStore(File(folder, "history")) },
                    Executor { if (holdHistory) heldHistory.add(it) else it.run() },
                    { notice(HistoryNotice.SAVE) }, delivery = executor)
                attached(history) { null }
                return runtime.open(capture, history, executor, observer, retentionEligible = retentionEligible).also { it.start() }
            }
        }
    }
    /** Every controller of this test and whether it was destroyed already; each is destroyed when the test ends. */
    private val controllers = mutableMapOf<TakeController, Boolean>()
    private fun controllerOn(host: Host) =
        TakeController(host, scheduler, opener, EditorPort { live }, diagnostics, rotations, lab, log).also { controllers[it] = false }
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
        runHeldHistory()
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

    /** The startup trace's take id is the controller's (#91): one per take, from the take's start to its end. */
    @Test fun eachTakeHasItsOwnStartupTraceIdFromItsStartToItsEnd() {
        startupTrace = true
        assertEquals(null, controller.startupTake)
        openPanel()
        assertEquals("startup-1", controller.startupTake)
        assertEquals("the panel's geometry is observed for the take it opened", listOf<String?>("startup-1"), geometryTakes)
        scheduler.drain()
        input.say()
        await { host.frames.last().preview == "word" }
        controller.microphonePressed()
        await { !controller.takeActive && !MicrophoneOwner.state().occupied }
        assertEquals("an ended take leaves no id behind", null, controller.startupTake)
        controller.microphonePressed()
        assertTrue(controller.takeActive)
        assertEquals("startup-2", controller.startupTake)
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

    // --- Held text (#89): text that could not be inserted, its Copy, Discard text and Back, and the pending outcome. ---

    /** Stop wrote into an editor that refused the text: the take ended and the panel holds its text. */
    private fun heldAfterARefusedWrite(retention: EditorRetention = EditorRetention.ELIGIBLE) {
        field.accepts = false
        controller.inputStart(retention, FIELD_PACKAGE, FIELD)
        controller.inputViewStart()
        scheduler.drain()
        input.say()
        await { frame().preview == "word" }
        controller.microphonePressed()
        awaitTakeEnded()
        await { frame().held }
        assertEquals(listOf("word"), field.written)
        assertEquals(0, host.returns)
    }

    /**
     * Held text on the panel: its own status, the text in the preview, Copy and Discard text instead of the
     * microphone's take. (A held frame always draws the status mark: RenderPanelTest.)
     */
    private fun assertHeld(status: String, text: String = "word") {
        val frame = frame()
        assertTrue(frame.held)
        assertEquals(status, status())
        assertEquals(text, frame.preview)
        assertEquals("no Cancel choice: the two actions are Copy text and Discard text", null, frame.choice)
        assertFalse(frame.stopGlyph || frame.preparing || frame.ringLive)
        assertFalse(controller.takeActive)
    }

    /** Copy text hands exactly the take's text to the host's clipboard, drops it from the panel and hands the keyboard back. */
    private fun assertCopyHandsOverTheTextAndLeaves(text: String = "word") {
        val returns = host.returns
        controller.onSafeAction()
        assertEquals(listOf(text), host.copied)
        assertEquals(returns + 1, host.returns)
        assertIdlePanel()
        assertFalse("the next panel records by itself", MicrophoneOwner.state().explicitStartRequired)
        // The text is gone: a second Copy has nothing to copy.
        controller.onSafeAction()
        assertEquals(listOf(text), host.copied)
    }

    /** A write the editor answers with false: not inserted, held for Copy, and the status says History keeps it. */
    @Test fun aWriteTheEditorRefusesHoldsTheTextAndSaysHistoryKeepsIt() {
        heldAfterARefusedWrite()
        assertHeld("unable_history")
        assertFalse(frame().uncertain)
        assertEquals(R.string.back_to_keyboard, frame().backLabel)
        assertEquals("word", historyRows().single().text)
        assertEquals(HistoryDelivery.REJECTED, historyRows().single().delivery)
        assertEquals(emptyList<HistoryNotice>(), host.notices)
        assertCopyHandsOverTheTextAndLeaves()
        assertEquals("Copy writes nothing into the field", listOf("word"), field.written)
        assertEquals(1, opened)
    }

    @Test fun aWriteThatThrowsHoldsTheText() {
        field.writeThrows = true
        recordingWithSpeech()
        controller.microphonePressed()
        awaitTakeEnded()
        await { frame().held }
        assertHeld("unable_history")
        assertEquals(0, host.returns)
        assertCopyHandsOverTheTextAndLeaves()
    }

    /** No text, no held state: a failed recognition says so and offers Retry. */
    @Test fun aDecodeFailureWithoutTextSaysRecognitionFailedAndHoldsNothing() {
        decodeFails = true
        openPanel()
        scheduler.drain()
        input.say()
        controller.microphonePressed()
        awaitTakeEnded()
        await { status() == "failed" }
        assertFalse(frame().held || frame().unableToInsert)
        assertEquals("", frame().preview)
        assertTrue(frame().microphoneEnabled)
        assertEquals(R.string.retry_recording, frame().microphoneLabel)
        assertNothingWritten()
        controller.onSafeAction()
        assertEquals(emptyList<String>(), host.copied)
    }

    /** A private field: History has nothing, the status says the text is not saved, and Copy still hands it over. */
    @Test fun heldTextOfAPrivateFieldIsNotSavedAndCopyStillHandsItOver() {
        heldAfterARefusedWrite(EditorRetention.PASSWORD)
        assertHeld("unable_unsaved")
        assertEquals(R.string.retention_field_off, frame().notice)
        assertTrue(historyRows().isEmpty())
        assertCopyHandsOverTheTextAndLeaves()
    }

    /** An eligible field whose History save failed must not promise History; the History page gets the notice. */
    @Test fun heldTextWhoseHistorySaveFailedIsNotPromisedToHistory() {
        File(folder, "history").writeText("not a folder")
        heldAfterARefusedWrite()
        assertHeld("unable_unsaved")
        assertEquals(null, frame().notice)
        assertEquals(listOf(HistoryNotice.SAVE), host.notices.distinct())
        assertCopyHandsOverTheTextAndLeaves()
    }

    /**
     * After a rotation hold the editor deactivated the connection (reads answer null; a write would be
     * dropped and answered true): nothing is written, no insert is reported, the text is held.
     */
    @Test fun aConnectionDeactivatedAfterARotationIsNeverWrittenAndTheTextIsHeld() {
        recordingWithSpeech()
        rotateAndSettle()
        field.dead = true
        controller.microphonePressed()
        awaitTakeEnded()
        await { frame().held }
        assertNothingWritten()
        assertHeld("unable_history")
        assertFalse("nothing was written: not an uncertain insert", frame().uncertain)
        assertTrue(historyRows().single().delivery != HistoryDelivery.ACCEPTED)
        assertEquals("word", historyRows().single().text)
        assertCopyHandsOverTheTextAndLeaves()
    }

    /** Discard text: gone at once without a copy or a hand-back; the idle panel stays and starts nothing by itself. */
    @Test fun discardTextDropsTheHeldTextCopiesNothingAndLeavesTheIdlePanel() {
        heldAfterARefusedWrite()
        controller.onDiscardAction()
        assertIdlePanel()
        assertEquals(emptyList<String>(), host.copied)
        assertEquals(0, host.returns)
        assertFalse(MicrophoneOwner.state().explicitStartRequired)
        assertEquals(1, opened)
        // Nothing is left to copy or to discard.
        controller.onSafeAction()
        controller.onDiscardAction()
        assertEquals(emptyList<String>(), host.copied)
        assertIdlePanel()
        assertEquals("History keeps the take; only the panel's copy is gone", "word", historyRows().single().text)
    }

    /** Back never copies: on uncopied text the first Back only warns and says what the next does; the second discards and leaves. */
    @Test fun theFirstBackOnHeldTextOnlyWarnsAndTheSecondDiscardsAndLeaves() {
        heldAfterARefusedWrite()
        controller.cancelAndReturn()
        assertHeld("back_warning")
        assertEquals("the arrow says what the next press does", R.string.back_discard_text, frame().backLabel)
        assertEquals(0, host.returns)
        controller.cancelAndReturn()
        assertIdlePanel()
        assertEquals(1, host.returns)
        assertEquals("Back never copies", emptyList<String>(), host.copied)
        assertFalse(MicrophoneOwner.state().explicitStartRequired)
        assertEquals(1, opened)
    }

    @Test fun theSystemBackKeyOnHeldTextWarnsThenDiscardsLikeTheArrow() {
        heldAfterARefusedWrite()
        assertTrue(controller.backKey())
        assertHeld("back_warning")
        assertEquals(0, host.returns)
        assertTrue(controller.backKey())
        assertIdlePanel()
        assertEquals(1, host.returns)
        assertEquals(emptyList<String>(), host.copied)
        assertFalse("without held text or a take the key is Android's again", controller.backKey())
    }

    @Test fun copyAfterTheBackWarningStillCopiesAndHandsBack() {
        heldAfterARefusedWrite()
        controller.cancelAndReturn()
        assertHeld("back_warning")
        assertCopyHandsOverTheTextAndLeaves()
    }

    /** However the held text left the panel, no explicit start is required: the next panel records by itself. */
    private fun theNextPanelRecordsAfter(leave: () -> Unit) {
        heldAfterARefusedWrite()
        leave()
        assertFalse(MicrophoneOwner.state().explicitStartRequired)
        assertEquals(1, opened)
        hidePanel()
        controller.inputFinish()
        openPanel()
        assertEquals("the next open records", 2, opened)
        assertTrue(controller.takeActive)
    }

    @Test fun afterCopyTheNextPanelRecordsByItself() = theNextPanelRecordsAfter { controller.onSafeAction() }

    @Test fun afterTheSecondBackTheNextPanelRecordsByItself() = theNextPanelRecordsAfter { controller.cancelAndReturn(); controller.cancelAndReturn() }

    @Test fun afterDiscardTextTheNextPanelRecordsByItself() = theNextPanelRecordsAfter { controller.onDiscardAction() }

    /**
     * Held text is never dropped by starting a take: not by the panel shown again, on the same input or a
     * restarted one, and (guard) not by a microphone press the panel does not offer.
     */
    @Test fun aPanelShownAgainOverHeldTextStartsNoTakeAndKeepsTheText() {
        heldAfterARefusedWrite(EditorRetention.PASSWORD) // The text exists nowhere else.
        controller.inputViewStart() // A tap on the field.
        assertHeld("unable_unsaved")
        controller.inputStart(EditorRetention.PASSWORD, FIELD_PACKAGE, FIELD) // The same editor restarts input.
        controller.inputViewStart()
        assertHeld("unable_unsaved")
        hidePanel()
        controller.inputFinish()
        controller.inputStart(EditorRetention.PASSWORD, FIELD_PACKAGE, OTHER_FIELD) // Another field of the same app.
        controller.inputViewStart()
        assertHeld("unable_unsaved")
        controller.microphonePressed()
        scheduler.drain()
        assertHeld("unable_unsaved")
        assertEquals(1, opened)
        assertCopyHandsOverTheTextAndLeaves()
    }

    /** A rotation recreates the panel and restarts input: the new panel shows the same held text, turned back too. */
    @Test fun heldTextSurvivesARotationAndRotatingBack() {
        heldAfterARefusedWrite()
        repeat(2) {
            rotations.changes++
            controller.panelCreated()
            controller.render()
            controller.settleAfterRotation()
            controller.inputStart(EditorRetention.ELIGIBLE, FIELD_PACKAGE, FIELD)
            controller.inputViewStart()
            scheduler.advance(ROTATION_HOLD_GAP_MS * 2)
            assertHeld("unable_history")
        }
        assertEquals(1, opened)
        assertEquals(0, host.panelRequests)
        assertCopyHandsOverTheTextAndLeaves()
    }

    /** The warning belongs to the panel it was given on: a panel created again, or a new input start, takes it back. */
    @Test fun aPanelCreatedAgainOrANewInputStartTakesTheBackWarningBack() {
        heldAfterARefusedWrite()
        controller.cancelAndReturn()
        assertHeld("back_warning")
        controller.panelCreated()
        controller.render()
        assertHeld("unable_history")
        assertEquals(R.string.back_to_keyboard, frame().backLabel)
        controller.cancelAndReturn()
        assertHeld("back_warning") // Starts over: this Back only warns again.
        assertEquals(0, host.returns)
        restartInput()
        assertHeld("unable_history")
        controller.inputViewStart()
        controller.cancelAndReturn()
        assertHeld("back_warning")
        assertEquals(0, host.returns)
    }

    /** A lock never keeps a take's text on the panel. */
    @Test fun screenOffDropsTheHeldText() {
        heldAfterARefusedWrite()
        controller.screenOff()
        scheduler.drain()
        assertIdlePanel()
        controller.onSafeAction()
        assertEquals(emptyList<String>(), host.copied)
        assertEquals(0, host.returns)
        assertEquals("word", historyRows().single().text)
    }

    /** Held text is only ever shown for the app the take started in. */
    @Test fun anotherAppsEditorDropsTheHeldText() {
        heldAfterARefusedWrite()
        hidePanel()
        controller.inputFinish()
        assertTrue("hiding the panel keeps it", frame().held)
        controller.inputStart(EditorRetention.ELIGIBLE, OTHER_APP, OTHER_APP_FIELD)
        assertFalse(frame().held || frame().unableToInsert)
        assertEquals("idle", status())
        assertEquals("", frame().preview)
        controller.onSafeAction()
        assertEquals(emptyList<String>(), host.copied)
    }

    /** The other app's panel after a take of the first app ended there: "Unable to insert text" and nothing of the text. */
    private fun assertNothingHeldOrShownInTheOtherApp() {
        awaitInterruptedInHistory() // History keeps it where eligible.
        await { frame().unableToInsert && frame().microphoneEnabled }
        assertFalse(frame().held)
        assertEquals("", frame().preview)
        assertEquals("unable_insert", status())
        assertEquals(null, frame().choice)
        assertNothingWritten()
        controller.onSafeAction()
        assertEquals(emptyList<String>(), host.copied)
    }

    private fun anotherAppStartsInput() {
        hidePanel()
        controller.inputFinish()
        controller.inputStart(EditorRetention.ELIGIBLE, OTHER_APP, OTHER_APP_FIELD)
        controller.inputViewStart()
    }

    @Test fun anInterruptionByAnotherAppsInputHoldsAndShowsNothingThere() {
        recordingWithSpeech()
        anotherAppStartsInput()
        assertNothingHeldOrShownInTheOtherApp()
        assertEquals(1, opened)
    }

    @Test fun stopThenAnotherAppsInputWhileTheTextIsPreparedHoldsAndShowsNothingThere() {
        recordingWithSpeech()
        holdDecode = true
        controller.microphonePressed() // Stop: the text is still being decoded.
        anotherAppStartsInput()
        runHeldDecodes()
        assertNothingHeldOrShownInTheOtherApp()
    }

    /** The text is decoded and its write is queued on the take's thread when the other app's input arrives. */
    @Test fun anotherAppsInputBetweenTheDecodeEndAndTheWriteHoldsAndShowsNothingThere() {
        recordingWithSpeech()
        holdDecode = true
        controller.microphonePressed()
        await { flushes.get() == 1 && heldDecodes.isNotEmpty() }
        runHeldDecodes() // On the take's thread: what the decoded text brings is queued there and has not run.
        assertEquals("nothing is written yet", emptyList<String>(), field.written)
        anotherAppStartsInput()
        assertNothingHeldOrShownInTheOtherApp()
    }

    /**
     * The gap: an interrupted take's microphone is released, and its outcome (the late decode and the
     * History save) is not in yet. The panel says "Unable to insert text" and offers no new take.
     */
    private fun interruptedIntoTheGap(retention: EditorRetention = EditorRetention.ELIGIBLE) {
        controller.inputStart(retention, FIELD_PACKAGE, FIELD)
        controller.inputViewStart()
        scheduler.drain()
        input.say()
        await { frame().preview == "word" }
        holdDecode = true
        hidePanel()
        controller.windowHidden()
        await { !MicrophoneOwner.state().occupied && frame().unableToInsert }
        assertFalse(controller.takeActive)
        assertEquals("unable_insert", status())
        assertFalse("the microphone is not offered until the outcome is in", frame().microphoneEnabled)
        assertEquals(R.string.status_stopping_recording, frame().microphoneLabel)
        assertFalse(frame().held)
    }

    /** Any path that shows the panel again in the gap must not offer a start that does nothing. */
    @Test fun aPanelShownAgainInTheGapOffersNoStart() {
        interruptedIntoTheGap()
        restartInput()
        controller.inputViewStart()
        assertFalse(frame().microphoneEnabled)
        hidePanel()
        controller.windowHidden()
        assertFalse(frame().microphoneEnabled)
        controller.cancelAndReturn() // The Back arrow only leaves.
        assertFalse(frame().microphoneEnabled)
        assertEquals(1, opened)
        runHeldDecodes()
        awaitInterruptedInHistory()
        await { frame().microphoneEnabled }
        assertNothingWritten()
    }

    /** Guard: a microphone press the gap's panel does not offer starts nothing, and the take's text still arrives as held text. */
    @Test fun aMicrophonePressInTheGapStartsNothingAndTheOutcomeStillArrives() {
        interruptedIntoTheGap()
        controller.inputStart(EditorRetention.ELIGIBLE, FIELD_PACKAGE, FIELD)
        controller.inputViewStart()
        controller.microphonePressed()
        scheduler.drain()
        assertEquals(1, opened)
        assertFalse(controller.takeActive)
        runHeldDecodes()
        awaitInterruptedInHistory()
        await { frame().held }
        assertHeld("unable_history")
        assertEquals(1, opened)
        assertNothingWritten()
    }

    @Test fun onceTheOutcomeIsInTheMicrophoneStartsATakeAgain() {
        interruptedIntoTheGap()
        controller.inputStart(EditorRetention.ELIGIBLE, OTHER_APP, OTHER_APP_FIELD)
        controller.inputViewStart()
        assertFalse(frame().microphoneEnabled)
        runHeldDecodes()
        assertNothingHeldOrShownInTheOtherApp()
        assertEquals(R.string.start_recording, frame().microphoneLabel)
        controller.microphonePressed()
        assertEquals(2, opened)
        assertTrue(controller.takeActive)
    }

    /** A lock in the gap still refuses the text: nothing is held or shown after unlock, and History keeps it. */
    @Test fun aLockInTheGapHoldsNothingAfterUnlock() {
        interruptedIntoTheGap()
        controller.screenOff()
        runHeldDecodes()
        awaitInterruptedInHistory()
        restartInput() // Unlock: the same app restarts input.
        controller.inputViewStart()
        scheduler.drain()
        assertFalse(frame().held)
        assertEquals("", frame().preview)
        assertNothingWritten()
        assertEquals(1, opened)
    }

    /** The Stop path: the write would be refused and the text held, but the screen went off while the microphone was still being released. */
    @Test fun aLockAfterStopWhileTheMicrophoneIsStillReleasedHoldsNothingAfterUnlock() {
        field.accepts = false
        val gate = CountDownLatch(1)
        input.releaseGate = gate
        controller.inputStart(EditorRetention.PASSWORD, FIELD_PACKAGE, FIELD)
        controller.inputViewStart()
        scheduler.drain()
        input.say()
        await { frame().preview == "word" }
        controller.microphonePressed()
        controller.screenOff()
        gate.countDown()
        awaitTakeEnded()
        await { frame().microphoneEnabled }
        restartInput(EditorRetention.PASSWORD) // Unlock: the same app restarts input.
        controller.inputViewStart()
        scheduler.drain()
        assertFalse(frame().held)
        assertEquals("", frame().preview)
        assertTrue(historyRows().isEmpty())
        controller.onSafeAction()
        assertEquals(emptyList<String>(), host.copied)
    }

    /**
     * An interrupted take must not leave a silent idle panel (#36, #61): it says "Unable to insert text",
     * holds the text for Copy, and Back warns first, then discards and leaves without starting a take.
     */
    @Test fun anInterruptedTakeHoldsItsTextAndBackWarnsThenLeaves() {
        recordingWithSpeech()
        restartInput() // The same field restarts input: an interruption.
        controller.inputViewStart()
        awaitInterruptedInHistory()
        await { frame().held }
        assertHeld("unable_history")
        assertEquals(R.string.back_to_keyboard, frame().backLabel)
        controller.cancelAndReturn()
        assertHeld("back_warning")
        assertEquals(0, host.returns)
        controller.cancelAndReturn()
        assertEquals(1, host.returns)
        assertIdlePanel()
        assertEquals(1, opened)
        assertEquals(emptyList<String>(), field.written)
        assertEquals(emptyList<String>(), host.copied)
    }

    /**
     * Pinned as it is: a panel shown again inside the read-back wait is at rest under a pending outcome and
     * says "Stopping"; the second look brings the outcome in and changes nothing else on it. The write was
     * seen, so the keyboard is handed back, and the next panel records.
     */
    @Test fun aPanelShownAgainInsideTheReadBackWaitSaysStoppingAndStartsNoTake() {
        stoppedWithAWriteNotSeenAtOnce()
        controller.inputViewStart() // A tap on the field: the same input session.
        assertEquals("stopping", status())
        assertFalse(frame().microphoneEnabled)
        assertEquals(1, opened)
        controller.microphonePressed()
        assertEquals("no take starts before the second look", 1, opened)
        field.shows = true
        scheduler.advance(100)
        assertEquals(1, host.returns)
        assertFalse(frame().held)
        assertEquals("stopping", status())
        assertEquals(HistoryDelivery.ACCEPTED, historyRows().single().delivery)
        hidePanel()
        controller.inputFinish()
        openPanel()
        assertEquals(2, opened)
    }

    /** Guard: held text, the Back warning and the pending outcome call the ports in table order. */
    @Test fun heldTextCallsItsPortsInTableOrder() {
        field.accepts = false
        val table = listOf<Triple<String, () -> Unit, List<String>>>(
            Triple("open and speak", { recordingWithSpeech() },
                listOf("status:opening", "status:idle", "status:opening", "status:recording")),
            Triple("Stop", { controller.microphonePressed() },
                listOf("status:finishing")),
            Triple("text prepared and refused", { awaitTakeEnded(); await { frame().held } },
                listOf("write:word", "status:idle", "status:unable_history")),
            Triple("Back arrow", { controller.cancelAndReturn() },
                listOf("status:back_warning")),
            Triple("Copy text", { controller.onSafeAction() },
                listOf("copy:word", "status:unable_insert", "status:idle", "return")),
        )
        table.forEach { (step, input, expected) ->
            effects.clear()
            input()
            assertEquals(step, expected, effects.toList())
        }
    }

    // --- Rotation hold, authority move, retention latch (#90): hold, gap, settle, field judgement, panel request. ---

    /** The display turns: the service counts it, recreates the panel for the new layout and lets a visible panel settle. */
    private fun turn() {
        rotations.changes++
        controller.panelCreated()
        controller.render()
        controller.settleAfterRotation()
    }

    /** The service's onFinishInputView, onWindowHidden and onFinishInput: the editor and the keyboard go away. */
    private fun editorLeaves() {
        hidePanel()
        controller.windowHidden()
        controller.inputFinish()
    }

    /** An editor starts input on the connection [next]; what the controller posted for it has run. */
    private fun inputStartsOn(next: Field, retention: EditorRetention = EditorRetention.ELIGIBLE, app: String? = FIELD_PACKAGE,
                              identity: FieldIdentity? = FIELD) {
        live = next
        controller.inputStart(retention, app, identity)
        scheduler.drain()
    }

    /**
     * A Signal-like editor: the display turns, its activity is recreated in the new orientation and does not
     * show the keyboard again. The recreated field starts input on a new connection; the panel stays hidden.
     */
    private fun rotateHidden(retention: EditorRetention = EditorRetention.ELIGIBLE, identity: FieldIdentity? = FIELD): Field {
        turn()
        editorLeaves()
        return Field().also { inputStartsOn(it, retention, identity = identity) }
    }

    /** Android shows our panel on the live input (after the app or we asked). */
    private fun showPanel() {
        controller.inputViewStart()
        scheduler.drain()
    }

    /** Pixel order (#56 trace): inside the configuration change Android restarts input on the old editor and shows the recreated panel. */
    private fun platformReset() {
        turn()
        controller.inputStart(EditorRetention.ELIGIBLE, FIELD_PACKAGE, FIELD)
        showPanel()
    }

    /** The rest of the Pixel order: 95 ms later the old editor hides the panel and a recreated one starts input. */
    private fun recreatedEditorTakesInput(): Field {
        scheduler.advance(95)
        editorLeaves()
        return Field().also { inputStartsOn(it) }
    }

    private fun assertHeldForTheRotation() {
        assertTrue("the take is kept", controller.takeActive)
        assertTrue(MicrophoneOwner.state().occupied)
        assertEquals("the microphone was not stopped", 0, input.stops.get())
        assertEquals("no text shows while the panel is away", "", frame().preview)
        assertFalse(frame().ringLive)
    }

    private fun stopAndAwaitTheHandBack() {
        controller.microphonePressed()
        await { host.returns == 1 }
        awaitTakeEnded()
    }

    /** The microphone is being released after an interruption, at once: no clock has to move for it. */
    private fun assertInterruptedAtOnce() {
        assertTrue("interrupted at once", status() == "stopping" || frame().unableToInsert)
        assertFalse(frame().stopGlyph)
    }

    @Test fun aRotationThatHidesThePanelKeepsTheTakeAndStopInsertsOnceIntoTheReturnedField() {
        recordingWithSpeech()
        val next = rotateHidden()
        assertHeldForTheRotation()
        assertEquals("the controller asks for its own panel, once", 1, host.panelRequests)
        input.say()
        await { historyRows().single().text == "word word" }
        scheduler.drain()
        assertEquals("speech in the hold is kept and not shown", "", frame().preview)
        assertEquals(emptyList<String>(), field.written + next.written)

        showPanel()
        assertEquals("recording", status())
        assertEquals("word word", frame().preview)
        assertTrue(frame().microphoneEnabled && frame().stopGlyph && frame().ringLive)
        scheduler.advance(ROTATION_HOLD_GAP_MS * 2)
        assertTrue("the hold ended with the panel: no timer stops this take now", controller.takeActive)
        input.say()
        await { frame().preview == "word word word" }
        stopAndAwaitTheHandBack()
        assertEquals(listOf("word word word"), next.written)
        assertEquals("never into the connection the take started on", emptyList<String>(), field.written)
        assertEquals(1, input.starts.get())
        assertEquals(1, opened)
        assertEquals(HistoryDelivery.ACCEPTED, historyRows().single().delivery)
        assertEquals(1, host.panelRequests)
    }

    @Test fun aHoldWhosePanelNeverReturnsEndsTheTakeIntoHistoryAt3000MsAndNotAt2999() {
        recordingWithSpeech()
        val next = rotateHidden()
        scheduler.advance(2999)
        assertHeldForTheRotation()
        assertEquals("recording", status())
        scheduler.advance(1)
        assertEquals("stopping", status())
        awaitInterruptedInHistory()
        assertNothingWritten()
        assertEquals(emptyList<String>(), next.written)
        // A late panel cannot revive the take or start another microphone.
        showPanel()
        scheduler.advance(ROTATION_HOLD_GAP_MS)
        assertEquals(1, opened)
        assertEquals(1, input.starts.get())
        assertFalse(controller.takeActive)
    }

    /** The gap is one for the whole rotation: a panel that came back and was hidden again before it settled does not extend it. */
    @Test fun aHideAt999MsAfterThePanelReturnedIsStillTheRotationAndTheOneGapEndsIt() {
        recordingWithSpeech()
        rotateHidden()
        showPanel()
        scheduler.advance(999)
        hidePanel()
        assertHeldForTheRotation()
        scheduler.advance(2000)
        assertTrue("2999 ms after the first hold", controller.takeActive)
        assertEquals("recording", status())
        scheduler.advance(1)
        assertEquals("3000 ms after the first hold, not after the second", "stopping", status())
        awaitInterruptedInHistory()
        assertNothingWritten()
    }

    @Test fun aHideOnceTheReturnedPanelSettledAt1000MsIsARealHideAndInterruptsAtOnce() {
        recordingWithSpeech()
        rotateHidden()
        showPanel()
        scheduler.advance(1000)
        val requests = host.panelRequests
        hidePanel()
        assertEquals("no clock moved: no hold after a plain hide", "stopping", status())
        scheduler.drain()
        assertEquals("and no panel is asked for", requests, host.panelRequests)
        awaitInterruptedInHistory()
        assertNothingWritten()
    }

    /** An editor that keeps focus sends no event: the visible panel settles the rotation by itself. */
    @Test fun aKeptEditorsRotationSettlesAt1000MsAndNotAt999() {
        recordingWithSpeech()
        turn()
        scheduler.advance(999)
        hidePanel()
        assertHeldForTheRotation()
        showPanel()
        assertEquals("recording", status())
        scheduler.advance(999)
        hidePanel()
        assertHeldForTheRotation()
        showPanel()
        scheduler.advance(1000)
        hidePanel()
        assertEquals("settled 1000 ms after the panel came back: a real hide", "stopping", status())
        awaitInterruptedInHistory()
        assertNothingWritten()
    }

    /** A hide right after a rotation cannot be told apart from the editor reacting to it: held, and only until the gap ends. */
    @Test fun aHideRightAfterARotationIsHeldOnlyUntilTheGapEnds() {
        recordingWithSpeech()
        turn()
        scheduler.advance(500)
        hidePanel()
        assertHeldForTheRotation()
        scheduler.advance(2999)
        assertTrue("the gap counts from the hide, the first hold", controller.takeActive)
        assertEquals("recording", status())
        scheduler.advance(1)
        assertEquals("stopping", status())
        awaitInterruptedInHistory()
        assertNothingWritten()
    }

    @Test fun pixelOrderAKeptEditorKeepsTheTakeThroughThePlatformResetAndStopInsertsOnce() {
        recordingWithSpeech()
        platformReset()
        assertEquals("recording", status())
        scheduler.advance(ROTATION_HOLD_GAP_MS * 2)
        assertTrue("the panel stayed visible: no gap applies", controller.takeActive)
        assertEquals("recording", status())
        input.say()
        await { frame().preview == "word word" }
        stopAndAwaitTheHandBack()
        assertEquals(listOf("word word"), field.written)
        assertEquals(1, input.starts.get())
        assertEquals(0, host.panelRequests)
        assertEquals("idle", status())
    }

    @Test fun pixelOrderARecreatedEditorsLateHideIsHeldAndOnePanelRequestBringsTheTakeBack() {
        recordingWithSpeech()
        platformReset()
        val next = recreatedEditorTakesInput()
        assertHeldForTheRotation()
        assertEquals(1, host.panelRequests)
        showPanel()
        assertEquals("recording", status())
        scheduler.advance(ROTATION_HOLD_GAP_MS)
        assertTrue(controller.takeActive)
        input.say()
        await { frame().preview == "word word" }
        stopAndAwaitTheHandBack()
        assertEquals(listOf("word word"), next.written)
        assertEquals(emptyList<String>(), field.written)
        assertEquals(1, input.starts.get())
    }

    /**
     * Signal order (owner log 2026-11-13): platform reset, the old editor finishes, the recreated one starts
     * input with the panel shown, then Signal hides the keyboard and starts no further input; our own panel
     * request shows it again on that input. [pumped]: the first request ran between the view start and the
     * hide, when the hold had briefly ended (4 in 12 device runs did not get the panel back).
     */
    private fun signalOrderHideAfterTheRecreatedEditorStarted(pumped: Boolean) {
        recordingWithSpeech()
        platformReset()
        scheduler.advance(200)
        hidePanel()
        controller.inputFinish()
        val next = Field()
        live = next
        controller.inputStart(EditorRetention.ELIGIBLE, FIELD_PACKAGE, FIELD)
        controller.inputViewStart() // The window is still shown: the view starts with the input.
        if (pumped) {
            scheduler.drain()
            assertEquals("the first request finds the panel visible and asks for nothing", 0, host.panelRequests)
        }
        hidePanel() // Signal hides the keyboard; the input stays started.
        controller.windowHidden()
        scheduler.drain()
        assertHeldForTheRotation()
        assertEquals(1, host.panelRequests)
        showPanel()
        assertEquals("recording", status())
        scheduler.advance(ROTATION_HOLD_GAP_MS)
        assertTrue(controller.takeActive)
        input.say()
        await { frame().preview == "word word" }
        stopAndAwaitTheHandBack()
        assertEquals(listOf("word word"), next.written)
        assertEquals(emptyList<String>(), field.written)
        assertEquals(1, host.panelRequests)
    }

    @Test fun signalOrderAHideAfterTheRecreatedEditorStartedGetsThePanelBackAndStopInsertsOnceThere() =
        signalOrderHideAfterTheRecreatedEditorStarted(pumped = false)

    @Test fun signalOrderAHideAfterAViewStartWhoseRequestAlreadyRanAsksForThePanelAgain() =
        signalOrderHideAfterTheRecreatedEditorStarted(pumped = true)

    /** Once the panel is back and settled, nothing is asked for again. */
    @Test fun noPanelIsAskedForOnceTheHoldEnded() {
        recordingWithSpeech()
        platformReset()
        scheduler.advance(ROTATION_HOLD_GAP_MS + 100)
        hidePanel()
        controller.windowHidden()
        scheduler.drain()
        assertEquals(0, host.panelRequests)
        assertInterruptedAtOnce()
    }

    /** Home inside the rotation window: no input is started, so no panel is asked for; the take ends at the gap. */
    @Test fun homeInsideTheRotationWindowAsksForNoPanelAndTheTakeEndsAtTheGap() {
        recordingWithSpeech()
        platformReset()
        editorLeaves()
        scheduler.drain()
        assertEquals(0, host.panelRequests)
        scheduler.advance(2999)
        assertTrue(controller.takeActive)
        assertEquals(0, host.panelRequests)
        scheduler.advance(1)
        assertEquals("stopping", status())
        awaitInterruptedInHistory()
        assertNothingWritten()
        assertEquals(0, host.panelRequests)
    }

    /** A rotation never moves a take to another app: its input start ends the take at once, not at the gap. */
    @Test fun anotherAppsInputStartInsideTheRotationWindowEndsTheTakeAtOnceAndGetsNothing() {
        recordingWithSpeech()
        platformReset()
        editorLeaves()
        val other = Field()
        inputStartsOn(other, app = OTHER_APP, identity = OTHER_APP_FIELD)
        assertInterruptedAtOnce()
        assertEquals("no panel is asked for in the other app", 0, host.panelRequests)
        controller.inputViewStart()
        scheduler.advance(ROTATION_HOLD_GAP_MS)
        assertNothingHeldOrShownInTheOtherApp()
        assertEquals(emptyList<String>(), other.written)
        assertEquals(1, opened)
        assertEquals(0, host.panelRequests)
    }

    @Test fun repeatedRotationsKeepOneTakeWithAllItsSpeech() {
        recordingWithSpeech()
        rotateHidden()
        showPanel()
        input.say()
        await { frame().preview == "word word" }
        // Three more turns before the editor reacts.
        turn(); turn(); turn()
        editorLeaves()
        val last = Field()
        inputStartsOn(last)
        assertHeldForTheRotation()
        showPanel()
        input.say()
        await { frame().preview == "word word word" }
        stopAndAwaitTheHandBack()
        assertEquals(listOf("word word word"), last.written)
        assertEquals(1, input.starts.get())
        assertEquals(1, opened)
        assertEquals(1, historyRows().size)
    }

    @Test fun aLockDuringARotationHoldStopsAtOnceAndHoldsNothing() {
        recordingWithSpeech()
        rotateHidden()
        controller.screenOff()
        assertEquals("stopping", status())
        awaitInterruptedInHistory()
        assertNothingWritten()
        assertFalse("a lock never keeps the text", frame().held)
        scheduler.advance(ROTATION_HOLD_GAP_MS)
        assertEquals(1, opened)
    }

    @Test fun losingTheMicrophoneDuringARotationHoldEndsTheTakeIntoHistory() {
        recordingWithSpeech()
        val next = rotateHidden()
        input.endUnexpectedly()
        awaitInterruptedInHistory()
        showPanel()
        scheduler.advance(ROTATION_HOLD_GAP_MS)
        assertNothingWritten()
        assertEquals(emptyList<String>(), next.written)
        assertEquals(1, opened)
        assertEquals(1, input.starts.get())
    }

    /** Android may silence a client whose window is hidden: a silenced hold cannot keep listening. */
    @Test fun aCaptureSilencedDuringARotationHoldEndsTheTakeAtOnce() {
        recordingWithSpeech()
        controller.recordingConfigChanged { true }
        assertTrue("without a hold a silencing report changes nothing", controller.takeActive)
        assertEquals("recording", status())
        rotateHidden()
        assertHeldForTheRotation()
        controller.recordingConfigChanged { false }
        assertTrue(controller.takeActive)
        controller.recordingConfigChanged { true }
        assertEquals("no clock moved: at once, not at the gap", "stopping", status())
        awaitInterruptedInHistory()
        assertNothingWritten()
    }

    @Test fun aCaptureAlreadySilencedWhenTheHoldBeginsEndsTheTakeAtOnceAndAsksForNoPanel() {
        recordingWithSpeech()
        host.silenced = true
        turn()
        hidePanel()
        assertEquals("stopping", status())
        scheduler.drain()
        assertEquals(0, host.panelRequests)
        awaitInterruptedInHistory()
        assertNothingWritten()
    }

    // Field judgement: only the take's own field keeps the take's insert authority (ADR-010).

    /**
     * Signal order into another input of the same app: reset, the old editor finishes, the recreated activity
     * starts [identity] on a new connection and hides the keyboard; our panel returns there and settles.
     */
    private fun retargetedTo(identity: FieldIdentity, retention: EditorRetention): Field {
        recordingWithSpeech()
        platformReset()
        scheduler.advance(150)
        hidePanel()
        controller.inputFinish()
        val next = Field()
        live = next
        controller.inputStart(retention, FIELD_PACKAGE, identity)
        controller.inputViewStart()
        hidePanel()
        controller.windowHidden()
        scheduler.drain()
        showPanel()
        scheduler.advance(ROTATION_SETTLE_MS)
        return next
    }

    /** A view that is no text editor: TYPE_NULL, no selection (Signal after rotation, diagnostics 2026-11-13). */
    private fun retargetedToNoEditor() = retargetedTo(NO_EDITOR_INPUT, EditorRetention.NO_EDITOR)

    /** The user taps the take's own field: its input starts again, on a new connection, and the panel shows there. */
    private fun tapTheTakesField(): Field {
        hidePanel()
        controller.inputFinish()
        val back = Field()
        inputStartsOn(back)
        controller.inputViewStart()
        scheduler.advance(ROTATION_SETTLE_MS)
        return back
    }

    private fun stopAndAwaitHeld() {
        controller.microphonePressed()
        awaitTakeEnded()
        await { frame().held }
    }

    @Test fun anInputThatIsNoEditorAfterARotationIsNeverWrittenAndTheTextIsHeld() {
        val next = retargetedToNoEditor()
        stopAndAwaitHeld()
        assertEquals("nothing is written into a view that is no editor", emptyList<String>(), next.written)
        assertNothingWritten()
        assertTrue(historyRows().single().delivery != HistoryDelivery.ACCEPTED)
        assertHeld("unable_history")
        assertCopyHandsOverTheTextAndLeaves()
    }

    @Test fun anotherFieldOfTheSameAppAfterARotationIsNeverWrittenAndTheTextIsHeld() {
        val other = retargetedTo(OTHER_FIELD, EditorRetention.UNCLASSIFIABLE)
        stopAndAwaitHeld()
        assertEquals("never into a different field", emptyList<String>(), other.written)
        assertNothingWritten()
        // The take settled on an unclassifiable field: History does not keep it and the status says so.
        assertTrue(historyRows().isEmpty())
        assertHeld("unable_unsaved")
        assertEquals(R.string.retention_field_off, frame().notice)
    }

    @Test fun aTakeWithoutAuthorityKeepsRecordingAndStopHoldsAllItsText() {
        retargetedToNoEditor()
        assertEquals("recording", status())
        assertEquals("no helper line: only the private-field line ever shows", null, frame().notice)
        assertEquals("not an interruption", 0, input.stops.get())
        input.say() // Speech after the rotation is not lost.
        await { frame().preview == "word word" }
        scheduler.advance(ROTATION_HOLD_GAP_MS)
        assertTrue(controller.takeActive)
        stopAndAwaitHeld()
        assertEquals(null, frame().notice)
        assertHeld("unable_history", "word word")
    }

    /** Authority never comes back once the hold settled (owner 2026-11-13): the tap on the take's own field ends the take, its text is held. */
    @Test fun aTapOnTheTakesFieldAfterTheHoldSettledEndsTheTakeAndItsTextIsHeld() {
        retargetedToNoEditor()
        input.say()
        await { frame().preview == "word word" }
        val back = tapTheTakesField()
        awaitTakeEnded()
        await { frame().held }
        assertEquals(emptyList<String>(), back.written)
        assertNothingWritten()
        assertEquals("word word", frame().preview)
    }

    @Test fun theTakesFieldReturningDuringTheStopWaitAfterTheHoldSettledInsertsNothingAndTheTextIsHeld() {
        retargetedToNoEditor()
        holdDecode = true
        controller.microphonePressed() // Stopped without authority; the text is not prepared yet.
        await { !MicrophoneOwner.state().occupied }
        val back = tapTheTakesField()
        runHeldDecodes()
        awaitTakeEnded()
        await { frame().held }
        assertEquals(emptyList<String>(), back.written)
        assertNothingWritten()
        assertEquals("word", frame().preview)
    }

    @Test fun theTakesFieldStartingInputAfterTheTextWasHeldInsertsNothing() {
        retargetedToNoEditor()
        stopAndAwaitHeld()
        val back = tapTheTakesField()
        scheduler.advance(ROTATION_HOLD_GAP_MS)
        assertEquals(emptyList<String>(), back.written)
        assertNothingWritten()
        assertHeld("unable_history")
        assertEquals(1, opened)
    }

    /**
     * The hold settled on a mismatch: final. The take's field restarting input afterwards (no finish first) is
     * held like a rotation and judged, and gets no authority: Stop holds the text.
     */
    @Test fun aRestartOnTheTakesFieldAfterTheHoldSettledOnAMismatchGetsNoAuthorityAndStopHolds() {
        retargetedToNoEditor()
        val back = Field()
        inputStartsOn(back)
        showPanel()
        scheduler.advance(ROTATION_SETTLE_MS)
        assertTrue("not an interruption", controller.takeActive)
        assertEquals("recording", status())
        stopAndAwaitHeld()
        assertEquals(emptyList<String>(), back.written)
        assertNothingWritten()
        assertHeld("unable_history")
    }

    /** Android finishes the old input before the take's field starts again: a plain leave once the hold settled, at once. */
    @Test fun theTakesFieldStartingOnceItsInputFinishedAfterASettledMismatchEndsTheTakeAndTheTextIsHeld() {
        retargetedToNoEditor()
        controller.inputFinish()
        assertEquals("stopping", status())
        val back = Field()
        inputStartsOn(back)
        controller.inputViewStart()
        scheduler.advance(1000)
        awaitTakeEnded()
        await { frame().held }
        assertEquals(emptyList<String>(), back.written)
        assertNothingWritten()
        assertEquals(1, opened)
    }

    /** Stop's authority moves only to the take's own field: the panel returning on another field of the app gets none. */
    @Test fun stopThenARotationIntoAnotherFieldOfTheAppInsertsNothingAndTheTextIsHeld() {
        stoppedWaitingAt(StopWait.BEFORE_FINAL_DECODE)
        turn()
        editorLeaves()
        val other = Field()
        inputStartsOn(other, identity = OTHER_FIELD)
        showPanel()
        assertEquals("finishing", status())
        scheduler.advance(1000)
        runHeldDecodes()
        awaitTakeEnded()
        await { frame().held }
        assertEquals("never into a different field", emptyList<String>(), other.written)
        assertNothingWritten()
        assertHeld("unable_history")
    }

    /** What a rotation decided belongs to its take: the next take has its own authority. */
    @Test fun theTakeAfterOneThatLostItsAuthorityInsertsAgain() {
        val next = retargetedToNoEditor()
        stopAndAwaitHeld()
        controller.onDiscardAction()
        controller.microphonePressed()
        assertEquals(2, opened)
        scheduler.drain()
        input.say()
        await { frame().preview == "word" }
        stopAndAwaitTheHandBack()
        assertEquals(listOf("word"), next.written)
        assertEquals(emptyList<String>(), field.written)
    }

    /** No timer after a plain hide: the take without authority ends at once, as ADR-010 says, and asks for no panel. */
    @Test fun aPlainHideWhileWithoutAuthorityInterruptsAtOnceAndAnotherAppGetsNothing() {
        retargetedToNoEditor()
        val requests = host.panelRequests
        hidePanel()
        assertEquals("stopping", status())
        controller.windowHidden()
        controller.inputFinish()
        val other = Field()
        inputStartsOn(other, app = OTHER_APP, identity = OTHER_APP_FIELD)
        assertEquals(requests, host.panelRequests)
        awaitTakeEnded()
        assertEquals(emptyList<String>(), other.written)
        assertNothingWritten()
    }

    /** Inside the hold the automatic rotation sequence can pass through a view that is no editor: the take's field starting again returns the authority. */
    @Test fun signalOrderATransientInputWithoutAnEditorInsideTheHoldKeepsAuthorityAndTheTakeEligible() {
        recordingWithSpeech()
        assertEquals(1, historyRows().size)
        val none = Field()
        var last = Field()
        repeat(2) { // There, and back the same way.
            platformReset()
            scheduler.advance(130)
            hidePanel()
            controller.inputFinish()
            live = none
            controller.inputStart(EditorRetention.NO_EDITOR, null, NO_EDITOR_INPUT)
            controller.inputViewStart()
            hidePanel()
            controller.windowHidden()
            controller.inputFinish()
            last = Field()
            inputStartsOn(last)
            showPanel()
            assertEquals("the checkpoint is kept through the editor-less start", 1, historyRows().size)
            assertEquals("no editor is not a private editor", null, frame().notice)
            assertEquals("recording", status())
            scheduler.advance(ROTATION_SETTLE_MS)
            assertEquals(null, frame().notice)
        }
        input.say()
        await { frame().preview == "word word" }
        stopAndAwaitTheHandBack()
        assertEquals(listOf("word word"), last.written)
        assertEquals(emptyList<String>(), none.written + field.written)
        assertEquals(HistoryDelivery.ACCEPTED, historyRows().single().delivery)
        assertEquals("word word", historyRows().single().text)
    }

    // Retention latch: latched from the starting editor, and it only tightens.

    @Test fun aPrivateEditorTheTakeReboundToExcludesItFromHistoryAtOnceAndForGood() {
        recordingWithSpeech()
        assertEquals("checkpointed while the starting editor allowed it", 1, historyRows().size)
        val next = rotateHidden(EditorRetention.PASSWORD, OTHER_FIELD)
        assertTrue("excluded as soon as the private editor starts input", historyRows().isEmpty())
        showPanel()
        // A different (private) field: the take has no authority there, but the "not kept" line is never hidden.
        assertEquals(R.string.retention_field_off, frame().notice)
        input.say()
        await { frame().preview == "word word" }
        assertTrue(historyRows().isEmpty())
        stopAndAwaitHeld()
        assertEquals("never into a different field", emptyList<String>(), next.written)
        assertNothingWritten()
        assertTrue(historyRows().isEmpty())
        assertHeld("unable_unsaved", "word word")
    }

    /** How a rotation hold whose rebound editor is private ends. */
    private enum class HoldExit { GAP, SCREEN_OFF, MICROPHONE_LOSS, SILENCED, HIDE_IN_HOLD, HIDE_AFTER_PANEL }

    /**
     * A rotation rebinds the take to a private editor and the hold ends without the take continuing: the take
     * is excluded from the moment the private editor starts input, so neither the saved checkpoint nor the
     * interrupted result is kept, whatever ends the hold.
     */
    private fun aPrivateEditorInTheHoldKeepsNothing(exit: HoldExit, stopFirst: Boolean = false) {
        recordingWithSpeech()
        assertEquals(1, historyRows().size)
        if (stopFirst) {
            holdDecode = true
            controller.microphonePressed()
            await { !MicrophoneOwner.state().occupied }
        }
        val next = rotateHidden(EditorRetention.PASSWORD, OTHER_FIELD)
        assertTrue("excluded as soon as the private editor starts input", historyRows().isEmpty())
        when (exit) {
            HoldExit.GAP -> scheduler.advance(ROTATION_HOLD_GAP_MS)
            HoldExit.SCREEN_OFF -> controller.screenOff()
            HoldExit.MICROPHONE_LOSS -> input.endUnexpectedly()
            HoldExit.SILENCED -> controller.recordingConfigChanged { true }
            HoldExit.HIDE_IN_HOLD -> { hidePanel(); scheduler.advance(ROTATION_HOLD_GAP_MS) }
            HoldExit.HIDE_AFTER_PANEL -> {
                showPanel()
                scheduler.advance(ROTATION_SETTLE_MS)
                hidePanel()
                controller.windowHidden()
            }
        }
        runHeldDecodes()
        awaitTakeEnded()
        await { flushes.get() == 1 }
        scheduler.advance(ROTATION_HOLD_GAP_MS)
        assertTrue(historyRows().isEmpty())
        assertNothingWritten()
        assertEquals(emptyList<String>(), next.written)
        assertFalse(controller.takeActive)
    }

    @Test fun aPrivateEditorInTheHoldThenTheGapKeepsNothing() = aPrivateEditorInTheHoldKeepsNothing(HoldExit.GAP)
    @Test fun aPrivateEditorInTheHoldThenALockKeepsNothing() = aPrivateEditorInTheHoldKeepsNothing(HoldExit.SCREEN_OFF)
    @Test fun aPrivateEditorInTheHoldThenTheMicrophoneLostKeepsNothing() = aPrivateEditorInTheHoldKeepsNothing(HoldExit.MICROPHONE_LOSS)
    @Test fun aPrivateEditorInTheHoldThenSilencingKeepsNothing() = aPrivateEditorInTheHoldKeepsNothing(HoldExit.SILENCED)
    @Test fun aPrivateEditorInTheHoldThenAHideInTheHoldKeepsNothing() = aPrivateEditorInTheHoldKeepsNothing(HoldExit.HIDE_IN_HOLD)
    @Test fun aPrivateEditorInTheHoldThenARealHideKeepsNothing() = aPrivateEditorInTheHoldKeepsNothing(HoldExit.HIDE_AFTER_PANEL)
    @Test fun aPrivateEditorInTheStopWaitsHoldThenTheGapKeepsNothing() = aPrivateEditorInTheHoldKeepsNothing(HoldExit.GAP, stopFirst = true)
    @Test fun aPrivateEditorInTheStopWaitsHoldThenALockKeepsNothing() = aPrivateEditorInTheHoldKeepsNothing(HoldExit.SCREEN_OFF, stopFirst = true)
    @Test fun aPrivateEditorInTheStopWaitsHoldThenAHideInTheHoldKeepsNothing() =
        aPrivateEditorInTheHoldKeepsNothing(HoldExit.HIDE_IN_HOLD, stopFirst = true)
    @Test fun aPrivateEditorInTheStopWaitsHoldThenARealHideKeepsNothing() =
        aPrivateEditorInTheHoldKeepsNothing(HoldExit.HIDE_AFTER_PANEL, stopFirst = true)

    /** A no-learning editor during the hold is positively private: excluded at once, like a password, also without the panel. */
    @Test fun aNoLearningEditorInTheHoldExcludesTheTakeAlsoWithoutTheReturningPanel() {
        recordingWithSpeech()
        assertEquals(1, historyRows().size)
        rotateHidden(EditorRetention.NO_LEARNING, OTHER_FIELD)
        assertTrue(historyRows().isEmpty())
        scheduler.advance(ROTATION_HOLD_GAP_MS)
        awaitTakeEnded()
        await { flushes.get() == 1 }
        scheduler.drain()
        assertTrue(historyRows().isEmpty())
    }

    /** An unclassifiable editor excludes the take only once the panel settled on it; an input start alone does not. */
    @Test fun anUnclassifiableEditorExcludesTheTakeWhenThePanelSettledOnItAt1000MsAndNotAt999() {
        recordingWithSpeech()
        rotateHidden(EditorRetention.UNCLASSIFIABLE, OTHER_FIELD)
        assertEquals("not private by itself during the hold", 1, historyRows().size)
        showPanel()
        scheduler.advance(999)
        assertEquals(1, historyRows().size)
        assertEquals(null, frame().notice)
        scheduler.advance(1)
        assertTrue(historyRows().isEmpty())
        assertEquals(R.string.retention_field_off, frame().notice)
        assertTrue("the take goes on", controller.takeActive)
    }

    /** The latch never loosens: a take that started in a private field stays excluded on an ordinary one. */
    @Test fun aTakeStartedInAPrivateFieldStaysExcludedAfterARotationToAnOrdinaryField() {
        controller.inputStart(EditorRetention.PASSWORD, FIELD_PACKAGE, FIELD)
        controller.inputViewStart()
        scheduler.drain()
        input.say()
        await { frame().preview == "word" }
        val next = rotateHidden(EditorRetention.ELIGIBLE, OTHER_FIELD)
        showPanel()
        assertEquals(R.string.retention_field_off, frame().notice)
        scheduler.advance(ROTATION_SETTLE_MS)
        assertEquals(R.string.retention_field_off, frame().notice)
        input.say()
        await { frame().preview == "word word" }
        stopAndAwaitHeld()
        assertEquals("a different field: held, never written", emptyList<String>(), next.written)
        assertTrue(historyRows().isEmpty())
        assertHeld("unable_unsaved", "word word")
    }

    // Authority move: Stop's authority moves with the identified rotation to the session the panel returns to (#53).

    /** Where the Stop wait is when the phone rotates. */
    private enum class StopWait { BEFORE_FINAL_DECODE, BEFORE_SAVE_ACK }

    /** Stop was pressed and the microphone is released; the text is not out yet: [boundary] holds it. */
    private fun stoppedWaitingAt(boundary: StopWait) {
        recordingWithSpeech()
        if (boundary == StopWait.BEFORE_FINAL_DECODE) holdDecode = true else holdHistory = true
        controller.microphonePressed()
        await { !MicrophoneOwner.state().occupied }
        if (boundary == StopWait.BEFORE_SAVE_ACK) await { flushes.get() == 1 }
        assertEquals("finishing", status())
        assertEquals(emptyList<String>(), field.written)
    }

    /** What [stoppedWaitingAt] held is let go: the text is prepared and saved; it is written only if nothing else holds it. */
    private fun textPrepared() {
        runHeldDecodes()
        runHeldHistory()
        awaitTextPrepared()
    }

    /**
     * Stop, then a Pixel-order rotation into a recreated editor that does not show the keyboard: the panel comes
     * back finishing the same take, and once it settled the result is inserted there, once.
     */
    private fun aRotationInTheStopWaitInsertsOnceIntoTheReturnedField(boundary: StopWait) {
        stoppedWaitingAt(boundary)
        platformReset()
        val next = recreatedEditorTakesInput()
        assertEquals(1, host.panelRequests)
        textPrepared()
        assertEquals("the result waits for the panel", emptyList<String>(), field.written + next.written)
        showPanel()
        assertEquals("finishing", status())
        assertFalse(frame().microphoneEnabled)
        scheduler.advance(999)
        scheduler.drain()
        assertEquals("and for the panel to settle: not at 999 ms", emptyList<String>(), field.written + next.written)
        scheduler.advance(1)
        await { host.returns == 1 }
        awaitTakeEnded()
        assertEquals(listOf("word"), next.written)
        assertEquals(emptyList<String>(), field.written)
        assertEquals(1, input.starts.get())
        assertEquals(HistoryDelivery.ACCEPTED, historyRows().single().delivery)
        scheduler.advance(ROTATION_HOLD_GAP_MS * 2)
        assertEquals("no timer repeats it", listOf("word"), next.written)
        assertEquals(1, host.returns)
    }

    @Test fun aRotationBeforeTheFinalDecodeInsertsOnceIntoTheReturnedFieldAt1000MsAndNotAt999() =
        aRotationInTheStopWaitInsertsOnceIntoTheReturnedField(StopWait.BEFORE_FINAL_DECODE)

    @Test fun aRotationBeforeTheHistorySaveIsAcknowledgedInsertsOnceIntoTheReturnedField() =
        aRotationInTheStopWaitInsertsOnceIntoTheReturnedField(StopWait.BEFORE_SAVE_ACK)

    /** An editor that keeps focus: the result waits until the visible panel settled, then goes into the same field once. */
    @Test fun aKeptEditorsRotationInTheStopWaitInsertsOnceWhenThePanelSettled() {
        stoppedWaitingAt(StopWait.BEFORE_FINAL_DECODE)
        platformReset()
        assertEquals("finishing", status())
        textPrepared()
        scheduler.advance(999)
        scheduler.drain()
        assertEquals(emptyList<String>(), field.written)
        scheduler.advance(1)
        await { host.returns == 1 }
        awaitTakeEnded()
        assertEquals(listOf("word"), field.written)
        assertEquals(0, host.panelRequests)
        assertEquals(HistoryDelivery.ACCEPTED, historyRows().single().delivery)
    }

    /** Stop on the panel Android showed inside the configuration change, before the old editor's late hide. */
    @Test fun stopOnThePlatformResetPanelInsertsOnceIntoTheRecreatedEditor() {
        recordingWithSpeech()
        platformReset()
        holdDecode = true
        controller.microphonePressed()
        await { !MicrophoneOwner.state().occupied }
        val next = recreatedEditorTakesInput()
        textPrepared()
        assertEquals(emptyList<String>(), field.written + next.written)
        showPanel()
        scheduler.advance(ROTATION_SETTLE_MS)
        await { host.returns == 1 }
        awaitTakeEnded()
        assertEquals(listOf("word"), next.written)
        assertEquals(emptyList<String>(), field.written)
    }

    /** The take ended with its text inserted and the keyboard handed back: a rotation afterwards finds no take. */
    @Test fun aRotationAfterTheAcceptedInsertionNeitherUndoesNorRepeatsIt() {
        recordingWithSpeech()
        stopAndAwaitTheHandBack()
        editorLeaves() // The hand-back took the keyboard away.
        turn()
        controller.inputStart(EditorRetention.ELIGIBLE, FIELD_PACKAGE, FIELD)
        scheduler.advance(ROTATION_HOLD_GAP_MS * 2)
        assertEquals(listOf("word"), field.written)
        assertEquals(1, host.returns)
        assertEquals(HistoryDelivery.ACCEPTED, historyRows().single().delivery)
        assertEquals(1, opened)
        assertEquals(0, host.panelRequests)
    }

    @Test fun aRotationInTheStopWaitWhosePanelNeverReturnsSavesToHistoryOnlyAt3000MsAndNotAt2999() {
        stoppedWaitingAt(StopWait.BEFORE_FINAL_DECODE)
        val next = rotateHidden()
        textPrepared()
        scheduler.advance(2999)
        assertTrue("the result is held for the panel", controller.takeActive)
        assertEquals("finishing", status())
        scheduler.advance(1)
        await { !controller.takeActive && historyRows().single().delivery == HistoryDelivery.NOT_ATTEMPTED }
        assertEquals("word", historyRows().single().text)
        showPanel()
        scheduler.advance(ROTATION_SETTLE_MS)
        assertNothingWritten()
        assertEquals(emptyList<String>(), next.written)
        assertEquals(1, opened)
    }

    @Test fun aRealHideInTheStopWaitAfterTheReturnedPanelSettledSendsTheResultToHistoryOnly() {
        stoppedWaitingAt(StopWait.BEFORE_FINAL_DECODE)
        val next = rotateHidden()
        showPanel()
        scheduler.advance(ROTATION_SETTLE_MS)
        hidePanel() // A real hide: no rotation since the panel settled.
        runHeldDecodes()
        awaitInterruptedInHistory()
        scheduler.advance(ROTATION_HOLD_GAP_MS)
        assertNothingWritten()
        assertEquals(emptyList<String>(), next.written)
    }

    // The Cancel choice is take state: the panel that returns after a rotation shows it again; a rotation never chooses.

    /** Pixel-order rotation into a recreated editor with the panel back on it. */
    private fun rotateToARecreatedEditor(): Field {
        platformReset()
        return recreatedEditorTakesInput().also { showPanel() }
    }

    @Test fun aRecordingChoiceOpenThroughARotationIsShownAgainAndContinueKeepsTheTake() {
        recordingWithSpeech()
        controller.cancelAndReturn()
        assertEquals(PanelChoice.RECORDING, frame().choice)
        val next = rotateToARecreatedEditor()
        assertEquals("the returned panel shows the same choice", PanelChoice.RECORDING, frame().choice)
        assertEquals("paused", status())
        assertFalse(frame().ringLive)
        scheduler.advance(ROTATION_SETTLE_MS)
        assertEquals("a rotation never chooses", PanelChoice.RECORDING, frame().choice)
        assertTrue(controller.takeActive)
        assertEquals("still paused: no second platform start", 1, input.starts.get())
        controller.onSafeAction()
        assertEquals(2, input.starts.get())
        assertEquals("recording", status())
        input.say()
        await { frame().preview == "word word" }
        stopAndAwaitTheHandBack()
        assertEquals(listOf("word word"), next.written)
        assertEquals(emptyList<String>(), field.written)
        assertEquals(1, opened)
    }

    @Test fun stopAndInsertOnARecordingChoiceShownAgainAfterARotationInsertsOnceThere() {
        recordingWithSpeech()
        controller.cancelAndReturn()
        val next = rotateToARecreatedEditor()
        scheduler.advance(ROTATION_SETTLE_MS)
        controller.chooseStop()
        await { host.returns == 1 }
        awaitTakeEnded()
        scheduler.advance(ROTATION_HOLD_GAP_MS)
        assertEquals(listOf("word"), next.written)
        assertEquals(emptyList<String>(), field.written)
        assertEquals(HistoryDelivery.ACCEPTED, historyRows().single().delivery)
    }

    @Test fun discardOnARecordingChoiceShownAgainAfterARotationRemovesTheSavedText() {
        recordingWithSpeech()
        assertEquals(1, historyRows().size)
        controller.cancelAndReturn()
        val next = rotateToARecreatedEditor()
        assertEquals("a rotation never selects Discard", 1, historyRows().size)
        scheduler.advance(ROTATION_SETTLE_MS)
        assertEquals(1, historyRows().size)
        controller.onDiscardAction()
        awaitDiscardFinal()
        assertEquals(emptyList<String>(), next.written)
    }

    /**
     * Stop, Cancel (the finishing choice opens), rotate into a recreated editor; the panel is back and settled
     * with the choice open. [preparedFirst]: the text was prepared and saved before the rotation, else under it.
     */
    private fun finishingChoiceThroughARotation(preparedFirst: Boolean): Field {
        stoppedWaitingAt(StopWait.BEFORE_FINAL_DECODE)
        controller.cancelAndReturn()
        assertEquals(PanelChoice.FINISHING, frame().choice)
        if (preparedFirst) textPrepared()
        val next = rotateToARecreatedEditor()
        assertEquals(PanelChoice.FINISHING, frame().choice)
        assertEquals("finishing_choice", status())
        if (!preparedFirst) textPrepared()
        scheduler.advance(ROTATION_SETTLE_MS)
        scheduler.drain()
        assertEquals("a rotation never chooses", PanelChoice.FINISHING, frame().choice)
        assertEquals("the prepared text waits for the choice", emptyList<String>(), field.written + next.written)
        return next
    }

    private fun keepFinishingInsertsOnceInto(next: Field) {
        controller.onSafeAction()
        await { host.returns == 1 }
        awaitTakeEnded()
        assertEquals(listOf("word"), next.written)
        assertEquals(emptyList<String>(), field.written)
        assertEquals(HistoryDelivery.ACCEPTED, historyRows().single().delivery)
    }

    @Test fun aFinishingChoiceOpenThroughARotationHoldsTheTextPreparedUnderItUntilKept() =
        keepFinishingInsertsOnceInto(finishingChoiceThroughARotation(preparedFirst = false))

    @Test fun aFinishingChoiceOpenThroughARotationHoldsTheTextPreparedBeforeItUntilKept() =
        keepFinishingInsertsOnceInto(finishingChoiceThroughARotation(preparedFirst = true))

    @Test fun discardOnAFinishingChoiceShownAgainAfterARotationRemovesTheSavedText() {
        val next = finishingChoiceThroughARotation(preparedFirst = false)
        controller.onDiscardAction()
        scheduler.advance(ROTATION_HOLD_GAP_MS)
        awaitDiscardFinal()
        assertEquals(emptyList<String>(), next.written)
    }

    @Test fun aLockDuringARotationOverridesAnOpenChoiceAndTheTakeGoesToHistory() {
        stoppedWaitingAt(StopWait.BEFORE_FINAL_DECODE)
        controller.cancelAndReturn()
        assertEquals(PanelChoice.FINISHING, frame().choice)
        val next = rotateHidden()
        controller.screenOff()
        runHeldDecodes()
        awaitInterruptedInHistory()
        showPanel()
        scheduler.advance(ROTATION_SETTLE_MS)
        assertEquals("no choice comes back after a lock", null, frame().choice)
        assertNothingWritten()
        assertEquals(emptyList<String>(), next.written)
    }

    /** Rotation is read where it always was: once at each editor event and once at a take's start; no timer and no panel input reads it. */
    @Test fun theRotationIsReadAtEachEditorEventAndAtTheTakesStartAndNowhereElse() {
        openPanel() // Two editor events (input start, view start) and the take's start.
        assertEquals(3, rotations.observed)
        scheduler.drain()
        input.say()
        await { frame().preview == "word" }
        turn()
        controller.recordingConfigChanged { false }
        scheduler.advance(ROTATION_SETTLE_MS)
        controller.cancelAndReturn()
        controller.onSafeAction()
        assertEquals("the settle timer, the panel and the choice read nothing", 3, rotations.observed)
        turn()
        hidePanel()
        assertEquals(4, rotations.observed)
        scheduler.advance(ROTATION_HOLD_GAP_MS)
        assertEquals("the gap timer and the interruption read nothing", 4, rotations.observed)
        awaitTakeEnded()
    }

    @Test fun aRotationHoldCallsItsPortsInTableOrder() {
        var next = Field()
        val table = listOf<Triple<String, () -> Unit, List<String>>>(
            Triple("open and speak", { recordingWithSpeech() },
                listOf("status:opening", "status:idle", "status:opening", "status:recording")),
            Triple("the display turns, the editor leaves, a recreated one starts input", { next = rotateHidden() },
                listOf("request_panel")),
            Triple("one millisecond before the gap ends", { scheduler.advance(2999) },
                emptyList()),
            Triple("the panel returns", { showPanel() },
                emptyList()),
            Triple("it settles", { scheduler.advance(ROTATION_SETTLE_MS) },
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
        assertEquals(listOf("word"), next.written)
    }

    // --- The service as a pure adapter (#91): what the service's reflective lifecycle test and three of its
    // Robolectric cases checked through the service, at the controller's interface. ---

    /**
     * A loss of the input clears the preview at once; what is decoded afterwards is not shown, and the
     * panel shown again, also on a private field, starts no capture, also once the take's outcome is in.
     */
    private fun aLossClearsThePreviewAndTheNextPanelStartsNoCapture(loss: () -> Unit) {
        recordingWithSpeech()
        assertEquals("word", frame().preview)
        loss()
        assertEquals("the preview is cleared at once", "", frame().preview)
        outputs.single().preview("late words")
        assertEquals("a preview decoded after the loss is not shown", "", frame().preview)
        restartInput(EditorRetention.PASSWORD)
        controller.inputViewStart()
        assertEquals(1, input.starts.get())
        awaitTakeEnded()
        outputs.single().preview("later words")
        restartInput(EditorRetention.PASSWORD)
        controller.inputViewStart()
        scheduler.drain()
        assertFalse(frame().preview.contains("late"))
        assertEquals("the panel shown again started no capture", 1, input.starts.get())
        assertEquals(1, opened)
        assertNothingWritten()
    }

    @Test fun anInputRestartClearsThePreviewAndTheNextPanelStartsNoCapture() =
        aLossClearsThePreviewAndTheNextPanelStartsNoCapture { restartInput() }

    @Test fun aHideClearsThePreviewAndTheNextPanelStartsNoCapture() =
        aLossClearsThePreviewAndTheNextPanelStartsNoCapture { hidePanel() }

    @Test fun theInputsEndClearsThePreviewAndTheNextPanelStartsNoCapture() =
        aLossClearsThePreviewAndTheNextPanelStartsNoCapture { controller.inputFinish() }

    /** The service's destruction interrupts the take; its capture stays owned until the platform acknowledges the release. */
    @Test fun destructionClearsThePreviewAndKeepsTheCaptureUntilItsReleaseIsAcknowledged() {
        val gate = CountDownLatch(1)
        input.releaseGate = gate
        recordingWithSpeech()
        destroy(controller)
        assertEquals("", frame().preview)
        outputs.single().preview("late words")
        assertEquals("", frame().preview)
        assertTrue("the capture is kept until its release is acknowledged", MicrophoneOwner.state().occupied)
        assertEquals(1, input.starts.get())
        gate.countDown()
        await { !MicrophoneOwner.state().occupied }
        assertFalse(controller.takeActive)
        assertEquals(1, input.starts.get())
        assertNothingWritten()
    }

    /** The owner's recording switch is read once per take, at its start: the banner follows the take, not the switch. */
    @Test fun eachTakeReadsTheRecordingSwitchAtItsStart() {
        recordings = true
        recordingWithSpeech()
        assertTrue(frame().banner)
        recordings = false
        controller.render()
        assertTrue("a take keeps the switch it started with", frame().banner)
        input.endUnexpectedly()
        awaitInterruptedInHistory()
        awaitOutcomeThenDiscardHeld()
        restartInput()
        controller.inputViewStart()
        controller.microphonePressed()
        assertTrue(controller.takeActive)
        assertEquals(2, opened)
        assertFalse("the next take started with the switch off", frame().banner)
        assertEquals(0, recordingSaves)
    }

    private companion object {
        const val FIELD_PACKAGE = "org.example.editor"
        val FIELD = FieldIdentity(FIELD_PACKAGE, 7, 1, 0, null, 0)
        val OTHER_FIELD = FieldIdentity(FIELD_PACKAGE, 8, 1, 0, null, 0)
        /** An input start on a view that is no text editor: no type, no selection. */
        val NO_EDITOR_INPUT = FieldIdentity(FIELD_PACKAGE, 0, 0, 0, null, -1)
        const val OTHER_APP = "org.example.other"
        val OTHER_APP_FIELD = FieldIdentity(OTHER_APP, 3, 1, 0, null, 0)
        val STATUS = mapOf(
            R.string.status_opening to "opening", R.string.status_idle to "idle", R.string.status_recording to "recording",
            R.string.status_finishing to "finishing", R.string.status_failed to "failed",
            R.string.status_unable_insert to "unable_insert", R.string.status_unconfirmed to "unconfirmed",
            R.string.status_no_permission to "no_permission", R.string.status_stopping_recording to "stopping",
            R.string.status_unable_history to "unable_history", R.string.status_unable_unsaved to "unable_unsaved",
            R.string.status_release_failed to "release_failed", R.string.status_paused to "paused",
            R.string.status_finishing_choice to "finishing_choice", R.string.status_back_warning to "back_warning",
        ).withDefault { "status $it" }
    }
}
