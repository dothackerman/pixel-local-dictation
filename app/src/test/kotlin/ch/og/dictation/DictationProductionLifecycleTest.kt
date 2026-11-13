package ch.og.dictation

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Binder
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputBinding
import android.widget.ImageButton
import android.widget.TextView
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.GraphicsMode

/** Real service, output and History wiring; only audio/model system boundaries are substituted. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@LooperMode(LooperMode.Mode.PAUSED)
class DictationProductionLifecycleTest {
    private class DecodeQueue : Executor {
        private val pending = LinkedBlockingQueue<Runnable>()
        val submitted = AtomicInteger()
        override fun execute(command: Runnable) { pending.add(command); submitted.incrementAndGet() }
        fun drain() { while (true) (pending.poll() ?: return).run() }
        fun runOne() { pending.poll()?.run() }
    }
    private class Input(
        private val startFails: Boolean = false,
        private val failedReleases: Int = 0,
        private val releaseGate: CountDownLatch? = null,
        private val readFails: Boolean = false,
    ) : Recorder.AudioInput {
        override val bufferSize = 8
        val starts = AtomicInteger()
        val releases = AtomicInteger()
        @Volatile var readerThread: Thread? = null
        private val frames = LinkedBlockingQueue<ShortArray>()
        val stops = AtomicInteger()
        /** Like the platform recorder: frames are captured only while started; a restart starts empty. */
        @Volatile var running = false
        override fun start() { starts.incrementAndGet(); if (startFails) error("synthetic start failure"); frames.clear(); running = true }
        override fun read(buffer: ShortArray): Int {
            readerThread = Thread.currentThread()
            val frame = frames.take()
            if (frame.isEmpty() && readFails) throw SecurityException("synthetic permission loss")
            frame.copyInto(buffer)
            return if (frame.isEmpty()) -1 else frame.size
        }
        override fun stop() { stops.incrementAndGet(); running = false; frames.offer(shortArrayOf()) }
        override fun release() {
            releaseGate?.await(2, TimeUnit.SECONDS)
            if (releases.incrementAndGet() <= failedReleases) error("synthetic release failure")
        }
        fun feed(sample: Short) { if (running) frames.add(shortArrayOf(sample)) }
        fun endUnexpectedly() { frames.add(shortArrayOf()) }
    }
    private val decode = DecodeQueue()
    private val accepted = AtomicInteger()
    private val flushes = AtomicInteger()
    @Volatile private var failLoudDecode = false
    private val devices = LinkedBlockingQueue<Input>()
    private val opened = mutableListOf<ServiceController<DictationImeService>>()
    private val engineRuntime = Engine::class.java.getDeclaredField("runtime").apply { isAccessible = true }
    private val modelReady = Engine::class.java.getDeclaredField("modelReadyNs").apply { isAccessible = true }
    private var originalRuntime: Any? = null
    private var originalModelReady: Any? = null
    private lateinit var runtime: DictationRuntime
    private lateinit var root: File
    private var commits = 0
    private var onCommit: (() -> Unit)? = null
    /** What an editor's commitText answers (or throws) for the next writes. */
    private var writeResult: () -> Boolean = { true }

    @Before fun setUp() {
        assertFalse(MicrophoneOwner.state().occupied)
        MicrophoneOwner.acceptExplicitStart()
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        originalRuntime = engineRuntime.get(Engine)
        originalModelReady = modelReady.get(Engine)
        runtime = DictationRuntime(Variant.HISTORICAL_INT8, decode,
            { Recognizer { samples ->
                if (failLoudDecode && samples[0] > 0.4f) error("synthetic decoder failure")
                if (samples[0] > 0.4f) "private speech" else "old speech"
            } }, {
                object : Segmenter {
                    override fun accept(samples: FloatArray): List<Chunk> {
                        accepted.incrementAndGet()
                        return listOf(Chunk(samples, true))
                    }
                    override fun flush(): List<Chunk> { flushes.incrementAndGet(); return emptyList() }
                    override fun reset() {}
                }
            })
        engineRuntime.set(Engine, runtime)
        DictationImeService.recorderFactory = { Recorder { devices.remove() } }
        root = File(RuntimeEnvironment.getApplication().noBackupFilesDir, "history")
        HistoryIo.executor.submit { val store = HistoryIo.store(root); store.clear(store.clearableIds()) }.get(2, TimeUnit.SECONDS)
        HistoryNotice.entries.forEach { historyNotices(RuntimeEnvironment.getApplication()).dismiss(it) }
    }

    @After fun tearDown() {
        DictationImeService.postCaptureEnd = { handler, callback -> handler.post(callback) }
        DictationImeService.requestPanel = { it.requestShowSelf(0) }
        try {
            opened.asReversed().forEach { runCatching { it.destroy() } }
            await {
                if (MicrophoneOwner.state().releaseFailed) MicrophoneOwner.retryRelease()
                !MicrophoneOwner.state().occupied
            }
            decode.drain()
            pump()
            HistoryIo.executor.submit {}.get(2, TimeUnit.SECONDS)
            runCatching { runtime.close() }
            decode.drain()
        } finally {
            engineRuntime.set(Engine, originalRuntime)
            modelReady.set(Engine, originalModelReady)
            DictationImeService.recorderFactory = ::Recorder
            DictationImeService.historyRootFor = ::historyRoot
            MicrophoneOwner.acceptExplicitStart()
        }
    }

    private fun pump() { shadowOf(Looper.getMainLooper()).idle() }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!condition() && System.nanoTime() < deadline) { pump(); Thread.sleep(5) }
        pump()
        assertTrue("timed out waiting for lifecycle transition", condition())
    }
    private fun rows(): List<HistoryEntry> = HistoryIo.executor.submit<List<HistoryEntry>> { HistoryIo.store(root).list() }.get(2, TimeUnit.SECONDS)
    private fun panel(service: DictationImeService): View = service.onCreateInputView()
    private fun preview(view: View) = view.findViewById<TextView>(R.id.preview).text.toString()
    private fun status(view: View) = view.findViewById<TextView>(R.id.status).text.toString()
    private fun button(view: View) = view.findViewById<ImageButton>(R.id.microphone)
    private fun editor(privateField: Boolean = false, pkg: String? = null) = EditorInfo().apply {
        packageName = pkg
        initialSelStart = 0; initialSelEnd = 0 // A real editor reports its selection; the default is -1.
        inputType = InputType.TYPE_CLASS_TEXT or if (privateField) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_VARIATION_NORMAL
    }
    /** One editor field; counts what reaches it. */
    private inner class Field(service: DictationImeService) : BaseInputConnection(View(service), false) {
        var inserted = mutableListOf<String>()
        /** A placeholder connection: the write answers true and changes nothing (Signal, 2026-11-13). */
        var swallow = false
        /** A slow editor: what was written shows before the cursor only from this many reads after the write. */
        var visibleAfterReads = 0
        private val content = StringBuilder()
        private var readsSinceWrite = 0
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            commits++
            inserted.add(text.toString())
            onCommit?.also { onCommit = null; it() }
            val accepted = writeResult()
            if (accepted && !swallow) { content.append(text); readsSinceWrite = 0 }
            return accepted
        }
        override fun getTextBeforeCursor(length: Int, flags: Int): CharSequence {
            if (content.isNotEmpty()) readsSinceWrite++
            return if (readsSinceWrite < visibleAfterReads) "" else content.takeLast(length).toString()
        }
        override fun getExtractedText(request: android.view.inputmethod.ExtractedTextRequest?, flags: Int) = null
    }
    private val firstField = mutableMapOf<DictationImeService, Field>()
    private fun service(info: EditorInfo): Pair<DictationImeService, View> {
        val controller = Robolectric.buildService(DictationImeService::class.java).create()
        opened.add(controller)
        val service = controller.get()
        val connection = Field(service)
        firstField[service] = connection
        val inputMethod = service.onCreateInputMethodInterface()
        inputMethod.bindInput(InputBinding(connection, Binder(), 1000, 1000))
        inputMethod.startInput(connection, info)
        val view = panel(service)
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        activity.setContentView(view)
        service.onStartInputView(info, false)
        if (accepted.get() == 0) decode.drain() // Initial warm-up only.
        pump()
        return service to view
    }
    private fun feed(input: Input, sample: Short, count: Int) {
        val before = decode.submitted.get()
        input.feed(sample)
        await { accepted.get() >= count && decode.submitted.get() > before }
    }
    private fun settleHistory() {
        pump()
        HistoryIo.executor.submit {}.get(2, TimeUnit.SECONDS)
        pump()
    }
    private fun screenOff() {
        val app = RuntimeEnvironment.getApplication()
        val intent = Intent(Intent.ACTION_SCREEN_OFF)
        shadowOf(app).getReceiversForIntent(intent).single().onReceive(app, intent)
    }

    /** Rotates the service's own configuration the way Android does when the method handles it. */
    private fun rotate(service: DictationImeService, qualifiers: String): View {
        // Robolectric does not load the android.view.im meta-data, so hand the framework's own
        // configuration tracker what Android reads from method.xml (InputMethodInfo.getConfigChanges).
        val tracker = android.inputmethodservice.InputMethodService::class.java.getDeclaredField("mConfigTracker")
            .apply { isAccessible = true }.get(service)
        tracker.javaClass.getMethod("setHandledConfigChanges", Int::class.java).invoke(tracker, declaredConfigChanges())
        RuntimeEnvironment.setQualifiers(qualifiers)
        service.onConfigurationChanged(android.content.res.Configuration(service.resources.configuration))
        pump()
        return service.window.findViewById(R.id.panel)
    }

    private fun declaredConfigChanges(): Int {
        val parser = RuntimeEnvironment.getApplication().resources.getXml(R.xml.method)
        while (parser.next() != org.xmlpull.v1.XmlPullParser.START_TAG || parser.name != "input-method") Unit
        return parser.getAttributeIntValue("http://schemas.android.com/apk/res/android", "configChanges", 0)
    }

    private fun setDisplayRotation(rotation: Int) {
        val display = RuntimeEnvironment.getApplication().getSystemService(android.hardware.display.DisplayManager::class.java)
            .getDisplay(android.view.Display.DEFAULT_DISPLAY)
        shadowOf(display).setRotation(rotation)
    }

    /** The display turns and the input method's own configuration follows (Android's order). */
    private fun turn(service: DictationImeService, rotation: Int): View {
        setDisplayRotation(rotation)
        val odd = rotation == android.view.Surface.ROTATION_90 || rotation == android.view.Surface.ROTATION_270
        return rotate(service, if (odd) "+land" else "+port")
    }

    /**
     * Signal-like editor: its activity is recreated in the new orientation and does not show the
     * keyboard again. The new field starts input; the panel stays hidden unless someone shows it.
     */
    private fun rotateHidden(service: DictationImeService, rotation: Int, info: EditorInfo = editor()): Field {
        turn(service, rotation)
        service.onFinishInputView(true)
        service.onWindowHidden()
        service.onFinishInput()
        val next = Field(service)
        service.onCreateInputMethodInterface().startInput(next, info)
        pump()
        return next
    }

    /** The framework shows our panel on the live session (after the app or editor asked). */
    private fun showPanel(service: DictationImeService, info: EditorInfo = editor()): View? {
        service.onStartInputView(info, false)
        service.onWindowShown()
        pump()
        return service.window.findViewById<View?>(R.id.panel)
    }

    /**
     * Pixel order (#56 trace, recreated editor): inside the configuration change Android still
     * restarts input and shows a recreated panel; the old editor hides it ~95 ms later.
     */
    private fun platformResetOnRotation(service: DictationImeService, rotation: Int, info: EditorInfo = editor()): View? {
        turn(service, rotation) // Recreates the panel for the new orientation.
        service.onStartInput(info, true)
        return showPanel(service, info)
    }

    /** Late decode and the History save run after the release; keep draining while waiting. */
    private fun awaitInterruptedInHistory() =
        await { decode.drain(); settleHistory(); rows().singleOrNull()?.completion == HistoryCompletion.INTERRUPTED }

    private fun advance(ms: Long) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(ms)) }

    /** Device demos keep synthetic takes out of the owner's History (same isolation as the History UI tests). */
    @Test fun testHistoryRootKeepsTheTakeOutOfTheRealHistory() {
        val isolated = File(RuntimeEnvironment.getApplication().noBackupFilesDir, "history-ui-test-hook")
        DictationImeService.historyRootFor = { isolated }
        val input = Input()
        devices.add(input)
        val (service, view) = service(editor())
        feed(input, 1000, 1)
        button(view).performClick()
        await { input.releases.get() == 1 }
        await { decode.drain(); commits == 1 }
        settleHistory()
        assertTrue(rows().isEmpty())
        val kept = HistoryIo.executor.submit<List<HistoryEntry>> { HistoryIo.store(isolated).list() }.get(2, TimeUnit.SECONDS)
        assertEquals(HistoryDelivery.ACCEPTED, kept.single().delivery)
        assertEquals(listOf("old speech"), firstField.getValue(service).inserted)
    }

    @Test fun rotationWithHiddenKeyboardKeepsTheTakeAndStopInsertsIntoTheReturnedField() {
        val panelRequests = AtomicInteger()
        DictationImeService.requestPanel = { panelRequests.incrementAndGet() }
        val input = Input()
        devices.add(input)
        val (service, _) = service(editor())
        val first = firstField.getValue(service)
        feed(input, 1000, 1)
        decode.drain(); pump()

        val next = rotateHidden(service, android.view.Surface.ROTATION_90)
        // Rotation hold: capture continues, nothing written, the app asks for its own panel.
        assertEquals(0, input.releases.get())
        assertTrue(MicrophoneOwner.state().occupied)
        assertTrue(panelRequests.get() >= 1)
        feed(input, 20000, 2)
        decode.drain(); pump()
        assertEquals(0, commits)
        assertEquals("", preview(service.window.findViewById(R.id.panel)))

        val returned = showPanel(service)!!
        assertEquals(service.getString(R.string.status_recording), status(returned))
        assertEquals("old speech private speech", preview(returned))
        assertTrue(button(returned).isEnabled)
        assertEquals(service.getString(R.string.stop_recording), button(returned).contentDescription.toString())
        advance(ROTATION_HOLD_GAP_MS * 2) // The hold ended; no timer may stop this take now.
        assertEquals(0, input.releases.get())
        feed(input, 1000, 3)
        button(returned).performClick()
        await { input.releases.get() == 1 }
        await { decode.drain(); commits == 1 }
        settleHistory()
        assertEquals(listOf("old speech private speech old speech"), next.inserted)
        assertTrue(first.inserted.isEmpty())
        assertEquals(1, input.starts.get())
        assertEquals(HistoryDelivery.ACCEPTED, rows().single().delivery)
    }

    @Test fun rotationWherePanelNeverReturnsEndsInHistoryWithinTheGap() {
        DictationImeService.requestPanel = {}
        val input = Input()
        devices.add(input)
        val (service, _) = service(editor())
        feed(input, 1000, 1)
        rotateHidden(service, android.view.Surface.ROTATION_90)
        advance(ROTATION_HOLD_GAP_MS - 100)
        assertEquals(0, input.releases.get())
        advance(100)
        await { input.releases.get() == 1 }
        awaitInterruptedInHistory()
        assertEquals("old speech", rows().single().text)
        assertEquals(0, commits)
        // A late panel cannot revive the take or start another microphone.
        showPanel(service)
        assertEquals(1, input.starts.get())
        assertEquals(0, commits)
    }

    @Test fun realHideAfterTheReturnedPanelStopsAtOnce() {
        DictationImeService.requestPanel = {}
        val input = Input()
        devices.add(input)
        val (service, _) = service(editor())
        feed(input, 1000, 1)
        rotateHidden(service, android.view.Surface.ROTATION_90)
        showPanel(service)
        advance(ROTATION_SETTLE_MS)
        service.onFinishInputView(true)
        await { input.releases.get() == 1 } // No further clock advance: no hold after a plain hide.
        awaitInterruptedInHistory()
        assertEquals(0, commits)
    }

    @Test fun realHideAfterAKeptRotationStopsAtOnce() {
        val input = Input()
        devices.add(input)
        val (service, _) = service(editor())
        feed(input, 1000, 1)
        turn(service, android.view.Surface.ROTATION_90) // Editor keeps focus; panel stays visible.
        advance(ROTATION_SETTLE_MS)
        service.onFinishInputView(true)
        await { input.releases.get() == 1 }
        awaitInterruptedInHistory()
        assertEquals(0, commits)
    }

    @Test fun pixelOrderRecreatedEditorKeepsTheTakeThroughThePlatformResetAndLateHide() {
        val panelRequests = AtomicInteger()
        DictationImeService.requestPanel = { panelRequests.incrementAndGet() }
        val input = Input()
        devices.add(input)
        val (service, _) = service(editor())
        feed(input, 1000, 1)
        platformResetOnRotation(service, android.view.Surface.ROTATION_90)
        advance(95)
        service.onFinishInputView(false)
        service.onWindowHidden()
        service.onFinishInput()
        val next = Field(service)
        service.onCreateInputMethodInterface().startInput(next, editor())
        pump()
        assertEquals(0, input.releases.get())
        assertEquals(1, panelRequests.get())
        val returned = showPanel(service)!!
        assertEquals(service.getString(R.string.status_recording), status(returned))
        advance(ROTATION_HOLD_GAP_MS)
        assertEquals(0, input.releases.get())
        feed(input, 20000, 2)
        button(returned).performClick()
        await { input.releases.get() == 1 }
        await { decode.drain(); commits == 1 }
        settleHistory()
        assertEquals(listOf("old speech private speech"), next.inserted)
        assertEquals(1, input.starts.get())
    }

    /**
     * Signal order (owner log 2026-11-13): platform reset, the old editor finishes, the recreated
     * one starts input with the panel shown, then Signal hides the keyboard and starts no further
     * input; our own panel request shows it again on that input. Stop must insert there once.
     */
    @Test fun signalOrderHideAfterRecreatedEditorStartStopInsertsOnceIntoIt() {
        val panelRequests = AtomicInteger()
        DictationImeService.requestPanel = { panelRequests.incrementAndGet() }
        val input = Input()
        devices.add(input)
        val (service, _) = service(editor())
        val first = firstField.getValue(service)
        feed(input, 1000, 1)
        platformResetOnRotation(service, android.view.Surface.ROTATION_90)
        advance(200)
        service.onFinishInputView(true)
        service.onFinishInput()
        val next = Field(service)
        service.onCreateInputMethodInterface().startInput(next, editor())
        service.onStartInputView(editor(), false) // Window still shown: the view starts with the input.
        service.onFinishInputView(false) // Signal hides the keyboard; the input stays started.
        service.onWindowHidden()
        pump() // Our request for the panel runs here.
        assertEquals(0, input.releases.get())
        assertEquals(1, panelRequests.get())
        val returned = showPanel(service)!!
        assertEquals(service.getString(R.string.status_recording), status(returned))
        advance(ROTATION_HOLD_GAP_MS)
        assertEquals(0, input.releases.get())
        feed(input, 20000, 2)
        button(returned).performClick()
        await { input.releases.get() == 1 }
        await { decode.drain(); commits == 1 }
        settleHistory()
        assertEquals(listOf("old speech private speech"), next.inserted)
        assertTrue(first.inserted.isEmpty())
    }

    /**
     * The client deactivated this connection (its view was detached) without a new input start:
     * Android answers reads with null and drops writes while reporting success (Pixel probe
     * demo52-signal-detach, 2026-11-13: "commitText on inactive InputConnection").
     */
    private inner class InactiveField(service: DictationImeService) : BaseInputConnection(View(service), false) {
        var dropped = 0
        override fun getTextBeforeCursor(length: Int, flags: Int): CharSequence? = null
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean { dropped++; return true }
    }

    /**
     * Signal order with the recreated editor's connection deactivated by Signal before Stop: no
     * text can reach the field, so Stop must not report an insert; the take stays in History.
     */
    @Test fun signalOrderInactiveConnectionAtStopIsNotReportedInsertedAndStaysInHistory() {
        val (service, returned, next) = deadConnectionAtStop(privateField = false)
        assertEquals(0, next.dropped) // Before the fix: 1, the text was written into the dead connection.
        assertTrue(rows().single().delivery != HistoryDelivery.ACCEPTED)
        assertEquals("old speech", rows().single().text)
        assertHeldState(service, returned)
        assertCopyPutsTakeTextOnClipboard(service, returned)
    }

    /** A retention-ineligible editor: nothing in History, but the panel holds the text for Copy. */
    @Test fun signalOrderInactiveConnectionInPrivateFieldKeepsTextForCopyOnly() {
        val (service, returned, next) = deadConnectionAtStop(privateField = true)
        assertEquals(0, next.dropped)
        assertTrue(rows().isEmpty())
        assertHeldState(service, returned, privateField = true)
        assertCopyPutsTakeTextOnClipboard(service, returned)
    }

    /** Discard text: the text is gone, the panel is idle and only the microphone tap starts a take. */
    @Test fun discardTextEndsTheHeldStateAndNeedsTheMicrophoneTap() {
        val (service, returned, _) = deadConnectionAtStop(privateField = false)
        val clipboard = service.getSystemService(android.content.ClipboardManager::class.java)
        clipboard.clearPrimaryClip()
        assertEquals(service.getString(R.string.discard_text), discardAction(returned).contentDescription.toString())
        discardAction(returned).performClick()
        assertEquals(service.getString(R.string.status_idle), status(returned))
        assertEquals("", preview(returned))
        assertEquals(View.GONE, returned.findViewById<View>(R.id.choice).visibility)
        assertEquals(View.VISIBLE, button(returned).visibility)
        assertNull(clipboard.primaryClip) // Discard never copies.
        // A panel shown again (a rotation) neither starts a take nor brings the text back.
        val rebuilt = platformResetOnRotation(service, android.view.Surface.ROTATION_0)!!
        assertEquals(service.getString(R.string.status_idle), status(rebuilt))
        assertEquals("", preview(rebuilt))
        assertEquals(View.GONE, rebuilt.findViewById<View>(R.id.choice).visibility)
    }

    /** Back never writes to the clipboard: uncopied text gets a warning first, the second Back discards. */
    @Test fun firstBackOnUncopiedTextOnlyWarnsAndTheSecondDiscards() {
        val (service, returned, _) = deadConnectionAtStop(privateField = false)
        val clipboard = service.getSystemService(android.content.ClipboardManager::class.java)
        clipboard.clearPrimaryClip()
        assertEquals(service.getString(R.string.back_to_keyboard), back(returned).contentDescription.toString())
        back(returned).performClick()
        assertEquals(service.getString(R.string.status_back_warning), status(returned))
        // The arrow says what the next press does.
        assertEquals(service.getString(R.string.back_discard_text), back(returned).contentDescription.toString())
        assertEquals("old speech", preview(returned))
        assertNull("Back never copies", clipboard.primaryClip)
        assertEquals(View.VISIBLE, returned.findViewById<View>(R.id.status_marker).visibility)
        back(returned).performClick()
        assertEquals(service.getString(R.string.status_idle), status(returned))
        assertEquals("", preview(returned))
        assertNull(clipboard.primaryClip)
    }

    @Test fun hardwareBackFollowsTheArrowWarnThenDiscard() {
        val (service, returned, _) = deadConnectionAtStop(privateField = false)
        assertTrue(systemBack(service))
        assertEquals(service.getString(R.string.status_back_warning), status(returned))
        assertTrue(systemBack(service))
        assertEquals(service.getString(R.string.status_idle), status(returned))
    }

    @Test fun backAfterACopyLeavesAtOnce() {
        val (service, returned, _) = deadConnectionAtStop(privateField = false)
        returned.findViewById<View>(R.id.choice_safe).performClick()
        back(returned).performClick()
        assertEquals(service.getString(R.string.status_idle), status(returned))
        assertEquals("", preview(returned))
    }

    @Test fun copyCancelsTheBackWarning() {
        val (service, returned, _) = deadConnectionAtStop(privateField = false)
        back(returned).performClick()
        assertEquals(service.getString(R.string.status_back_warning), status(returned))
        returned.findViewById<View>(R.id.choice_safe).performClick()
        assertEquals(service.getString(R.string.status_copied), status(returned))
        assertEquals(service.getString(R.string.back_to_keyboard), back(returned).contentDescription.toString())
    }

    @Test fun aRotationCancelsTheBackWarning() {
        val (service, returned, _) = deadConnectionAtStop(privateField = false)
        back(returned).performClick()
        val rebuilt = platformResetOnRotation(service, android.view.Surface.ROTATION_0)!!
        assertHeldState(service, rebuilt)
        assertEquals(service.getString(R.string.back_to_keyboard), back(rebuilt).contentDescription.toString())
        back(rebuilt).performClick()
        assertEquals(service.getString(R.string.status_back_warning), status(rebuilt)) // Starts over.
    }

    /** The status says what to do, and whether History keeps the text, by the take's editor. */
    @Test fun statusLineTellsWhereTheTextIsSaved() {
        val (service, eligible, _) = deadConnectionAtStop(privateField = false)
        assertEquals("Unable to insert text. Copy it, or find it in History.", status(eligible))
        assertEquals(service.getString(R.string.status_unable_history), status(eligible))
    }

    @Test fun statusLineSaysAPrivateFieldTextIsNotSaved() {
        val (_, view, _) = deadConnectionAtStop(privateField = true)
        assertEquals("Unable to insert text. Copy it now. It is not saved.", status(view))
    }

    /** A rotation keeps the state and the text, copied or not, on the new panel view. */
    @Test fun rotationKeepsTheHeldStateAndTheCopiedStatus() {
        val (service, returned, _) = deadConnectionAtStop(privateField = false)
        val rebuilt = platformResetOnRotation(service, android.view.Surface.ROTATION_0)!!
        assertHeldState(service, rebuilt)
        rebuilt.findViewById<View>(R.id.choice_safe).performClick()
        val again = platformResetOnRotation(service, android.view.Surface.ROTATION_90)!!
        assertEquals(service.getString(R.string.status_copied), status(again))
        assertEquals("old speech", preview(again))
        assertEquals(View.VISIBLE, again.findViewById<View>(R.id.choice).visibility)
        assertEquals(View.VISIBLE, again.findViewById<View>(R.id.status_marker).visibility)
    }

    @Test fun screenOffClearsTheHeldText() {
        val (service, returned, _) = deadConnectionAtStop(privateField = false)
        screenOff()
        pump()
        assertEquals(service.getString(R.string.status_idle), status(returned))
        assertEquals("", preview(returned))
    }

    @Test fun anotherAppsEditorClearsTheHeldText() {
        val (service, returned, _) = deadConnectionAtStop(privateField = false)
        service.onFinishInputView(true)
        service.onFinishInput()
        service.onCreateInputMethodInterface().startInput(Field(service), editor().apply { packageName = "other.app" })
        pump()
        assertEquals(service.getString(R.string.status_idle), status(returned))
        assertEquals("", preview(returned))
        assertEquals(View.GONE, returned.findViewById<View>(R.id.choice).visibility)
    }

    /** Held text: the status keeps its mark, the stage has Copy and Discard text and no microphone or ring. */
    private fun assertHeldState(service: DictationImeService, panel: View, privateField: Boolean = false, text: String = "old speech",
                                statusRes: Int? = null) {
        assertEquals(service.getString(statusRes ?: if (privateField) R.string.status_unable_unsaved else R.string.status_unable_history), status(panel))
        assertEquals(text, preview(panel))
        assertEquals(View.VISIBLE, panel.findViewById<View>(R.id.status_marker).visibility)
        assertEquals(View.VISIBLE, panel.findViewById<View>(R.id.choice).visibility)
        assertEquals(service.getString(R.string.copy_text), panel.findViewById<View>(R.id.choice_safe).contentDescription.toString())
        assertEquals(View.GONE, panel.findViewById<View>(R.id.choice_stop).visibility)
        assertEquals(service.getString(R.string.discard_text), discardAction(panel).contentDescription.toString())
        assertTrue(panel.findViewById<View>(R.id.choice_safe).isEnabled && discardAction(panel).isEnabled)
        // No control starts a take or drops the text implicitly.
        assertNotEquals(View.VISIBLE, button(panel).visibility)
        assertNotEquals(View.VISIBLE, panel.findViewById<View>(R.id.recording_ring).visibility)
    }

    /**
     * Signal order with the pump BETWEEN the view start and the hide: the hold has briefly ended
     * when the first request ran, so the hide must ask for our panel again (4 in 12 device runs
     * did not get it back).
     */
    @Test fun signalOrderHideAfterAPumpedViewStartStillGetsOurPanelBack() {
        val panelRequests = AtomicInteger()
        DictationImeService.requestPanel = { panelRequests.incrementAndGet() }
        val input = Input()
        devices.add(input)
        val (service, _) = service(editor())
        feed(input, 1000, 1)
        platformResetOnRotation(service, android.view.Surface.ROTATION_90)
        advance(200)
        service.onFinishInputView(true)
        service.onFinishInput()
        val next = Field(service)
        service.onCreateInputMethodInterface().startInput(next, editor())
        service.onStartInputView(editor(), false)
        pump() // The first request runs here: it finds the view visible and asks for nothing.
        assertEquals(0, panelRequests.get())
        service.onFinishInputView(false) // Signal hides the keyboard; the input stays started.
        service.onWindowHidden()
        pump()
        assertEquals(1, panelRequests.get())
        val returned = showPanel(service)!!
        assertEquals(service.getString(R.string.status_recording), status(returned))
        advance(ROTATION_HOLD_GAP_MS)
        assertEquals(0, input.releases.get())
        feed(input, 20000, 2)
        button(returned).performClick()
        await { input.releases.get() == 1 }
        await { decode.drain(); commits == 1 }
        settleHistory()
        assertEquals(listOf("old speech private speech"), next.inserted)
    }

    /** After the gap, or once the panel is back, nothing is asked for again. */
    @Test fun noPanelRequestAfterTheHoldEnds() {
        val panelRequests = AtomicInteger()
        DictationImeService.requestPanel = { panelRequests.incrementAndGet() }
        val input = Input()
        devices.add(input)
        val (service, _) = service(editor())
        feed(input, 1000, 1)
        platformResetOnRotation(service, android.view.Surface.ROTATION_90)
        advance(ROTATION_HOLD_GAP_MS + 100) // The panel stays visible and settles: the hold is over.
        val before = panelRequests.get()
        service.onFinishInputView(false)
        service.onWindowHidden()
        pump()
        assertEquals(before, panelRequests.get())
    }

    // --- Held text is for the app the take started in, and never survives a lock (round 2, A and B). ---

    private fun assertNothingHeldOrShown(service: DictationImeService, view: View) {
        assertEquals("", preview(view))
        assertEquals(View.GONE, view.findViewById<View>(R.id.choice).visibility)
        assertEquals(View.VISIBLE, button(view).visibility)
        assertFalse(status(view).contains("Copy"))
    }

    @Test fun stopThenSwitchingAppsDuringDecodeHoldsAndShowsNothingInTheOtherApp() {
        val input = Input()
        devices.add(input)
        val (service, view) = service(editor(pkg = "app.a"))
        feed(input, 1000, 1)
        button(view).performClick() // Stop: the text is still being decoded.
        service.onFinishInputView(true)
        service.onFinishInput()
        service.onCreateInputMethodInterface().startInput(Field(service), editor(pkg = "app.b"))
        service.onStartInputView(editor(pkg = "app.b"), false)
        await { input.releases.get() == 1 }
        awaitInterruptedInHistory()
        await { status(view).startsWith(service.getString(R.string.status_unable_insert)) }
        assertNothingHeldOrShown(service, view)
        assertEquals("old speech", rows().single().text) // History keeps it where eligible.
        assertEquals(0, commits)
    }

    @Test fun interruptedByAnotherAppsInputHoldsAndShowsNothingThere() {
        val input = Input()
        devices.add(input)
        val (service, view) = service(editor(pkg = "app.a"))
        feed(input, 1000, 1)
        service.onFinishInputView(true)
        service.onFinishInput()
        service.onCreateInputMethodInterface().startInput(Field(service), editor(pkg = "app.b"))
        service.onStartInputView(editor(pkg = "app.b"), false)
        await { input.releases.get() == 1 }
        awaitInterruptedInHistory()
        await { status(view).startsWith(service.getString(R.string.status_unable_insert)) }
        assertNothingHeldOrShown(service, view)
        assertEquals("old speech", rows().single().text)
        assertEquals(0, commits)
    }

    /** The screen goes off after the take ended (Stop's result is out) but before its capture release is acknowledged. */
    @Test fun aLockBetweenTheTakeEndingAndItsFinishHoldsNothingAfterUnlock() {
        writeResult = { false } // The commit fails, so the take would otherwise hold its text.
        val gate = CountDownLatch(1)
        val input = Input(releaseGate = gate)
        devices.add(input)
        val (service, view) = service(editor(privateField = true))
        feed(input, 1000, 1)
        button(view).performClick()
        decode.drain()
        screenOff() // The release is held: finished() has not run yet.
        gate.countDown()
        await { input.releases.get() == 1 }
        decode.drain(); settleHistory(); pump()
        service.onStartInput(editor(privateField = true), true) // Unlock: the same app restarts input.
        service.onStartInputView(editor(privateField = true), true)
        pump()
        assertNothingHeldOrShown(service, view)
    }

    /** Stop, then another app's input arrives while the result is on its way to the commit: nothing held or shown there. */
    @Test fun anotherAppsInputBetweenDecodeEndAndCommitHoldsAndShowsNothingThere() {
        val input = Input()
        devices.add(input)
        val (service, view) = service(editor(pkg = "app.a"))
        feed(input, 1000, 1)
        button(view).performClick()
        decode.drain() // The text is decoded; the commit is still queued on the main thread.
        service.onFinishInputView(true)
        service.onFinishInput()
        val other = Field(service)
        service.onCreateInputMethodInterface().startInput(other, editor(pkg = "app.b"))
        service.onStartInputView(editor(pkg = "app.b"), false)
        await { input.releases.get() == 1 }
        decode.drain(); settleHistory(); pump()
        assertNothingHeldOrShown(service, view)
        assertEquals(0, commits)
        assertTrue(other.inserted.isEmpty())
    }

    @Test fun aTakeActiveAtLockHoldsNothingAfterUnlock() {
        val input = Input()
        devices.add(input)
        val (service, view) = service(editor(privateField = true))
        feed(input, 1000, 1)
        screenOff()
        await { input.releases.get() == 1 }
        decode.drain(); settleHistory(); pump()
        service.onStartInput(editor(privateField = true), true) // Unlock: the same app restarts input.
        service.onStartInputView(editor(privateField = true), true)
        pump()
        assertNothingHeldOrShown(service, view)
        assertEquals(0, commits)
    }

    // --- A rotation never moves a take to another app (round 2, C). ---

    /**
     * Before the fix: Home inside the rotation window requested our panel with no input started
     * (the hide re-entered the hold), and a different app's input start was just another hold entry.
     */
    @Test fun homeInsideTheRotationWindowAsksForNoPanelAndEndsTheTakeAtTheGap() {
        val panelRequests = AtomicInteger()
        DictationImeService.requestPanel = { panelRequests.incrementAndGet() }
        val input = Input()
        devices.add(input)
        val (service, _) = service(editor(pkg = "app.a"))
        feed(input, 1000, 1)
        platformResetOnRotation(service, android.view.Surface.ROTATION_90, editor(pkg = "app.a"))
        val before = panelRequests.get()
        service.onFinishInputView(true) // Home: the editor and the keyboard go away.
        service.onWindowHidden()
        service.onFinishInput()
        pump()
        assertEquals(before, panelRequests.get())
        advance(ROTATION_HOLD_GAP_MS + 100)
        await { input.releases.get() == 1 }
        awaitInterruptedInHistory()
        assertEquals(0, commits)
    }

    @Test fun anotherAppsInputStartInsideTheRotationWindowEndsTheHoldAndGetsNothing() {
        val panelRequests = AtomicInteger()
        DictationImeService.requestPanel = { panelRequests.incrementAndGet() }
        val input = Input()
        devices.add(input)
        val (service, _) = service(editor(pkg = "app.a"))
        feed(input, 1000, 1)
        platformResetOnRotation(service, android.view.Surface.ROTATION_90, editor(pkg = "app.a"))
        val before = panelRequests.get()
        service.onFinishInputView(true)
        service.onWindowHidden()
        service.onFinishInput()
        val other = Field(service)
        service.onCreateInputMethodInterface().startInput(other, editor(pkg = "app.b"))
        pump()
        assertEquals(before, panelRequests.get()) // No request for the other app.
        await { input.releases.get() == 1 } // The take ended at once, not at the gap.
        val shown = showPanel(service, editor(pkg = "app.b"))
        advance(ROTATION_HOLD_GAP_MS)
        awaitInterruptedInHistory()
        assertEquals(0, commits)
        assertTrue(other.inserted.isEmpty())
        if (shown != null) assertNotEquals(service.getString(R.string.status_recording), status(shown))
    }

    // --- Commit outcomes with text that was not inserted: one held-text state for all (finding 3). ---

    private fun stopWithWrites(write: () -> Boolean): Triple<DictationImeService, View, Field> {
        writeResult = write
        val input = Input()
        devices.add(input)
        val (service, view) = service(editor())
        feed(input, 1000, 1)
        button(view).performClick()
        await { input.releases.get() == 1 }
        await { decode.drain(); status(view).startsWith(service.getString(R.string.status_unable_insert)) }
        settleHistory()
        return Triple(service, view, firstField.getValue(service))
    }

    @Test fun aWriteThatReturnsFalseHoldsTheText() {
        val (service, view, _) = stopWithWrites { false }
        assertHeldState(service, view)
        assertCopyPutsTakeTextOnClipboard(service, view)
    }

    @Test fun aWriteThatThrowsHoldsTheText() {
        val (service, view, _) = stopWithWrites { error("synthetic editor failure") }
        assertHeldState(service, view)
    }

    @Test fun anInsertedTakeHoldsNothing() {
        val input = Input()
        devices.add(input)
        val (service, view) = service(editor())
        feed(input, 1000, 1)
        button(view).performClick()
        await { input.releases.get() == 1 }
        await { decode.drain(); commits == 1 }
        settleHistory()
        assertFalse(status(view).startsWith(service.getString(R.string.status_unable_insert)))
        assertEquals(View.GONE, view.findViewById<View>(R.id.choice).visibility)
    }

    /** No text, no held state: a decode failure is still "Recognition failed" with Retry. */
    @Test fun aDecodeFailureWithoutTextStaysRecognitionFailed() {
        failLoudDecode = true
        val input = Input()
        devices.add(input)
        val (service, view) = service(editor())
        feed(input, 20000, 1)
        button(view).performClick()
        await { input.releases.get() == 1 }
        await { decode.drain(); status(view) == service.getString(R.string.status_failed) }
        assertEquals(View.GONE, view.findViewById<View>(R.id.choice).visibility)
    }

    // --- The held state survives what a rotation or a tap does to the input (finding 2). ---

    private fun assertHeldSurvives(trigger: (DictationImeService) -> Unit) {
        val (service, returned, _) = deadConnectionAtStop(privateField = true) // The text exists nowhere else.
        trigger(service)
        pump()
        val view = service.window.findViewById<View>(R.id.panel) ?: returned
        assertHeldState(service, view, privateField = true)
        assertEquals(View.VISIBLE, view.findViewById<View>(R.id.choice).visibility)
        assertCopyPutsTakeTextOnClipboard(service, view)
    }

    @Test fun heldTextSurvivesRotatingBack() = assertHeldSurvives {
        platformResetOnRotation(it, android.view.Surface.ROTATION_0)
    }

    @Test fun heldTextSurvivesTheSameEditorRestartingInput() = assertHeldSurvives {
        it.onStartInput(editor(true), true)
        it.onStartInputView(editor(true), true)
    }

    @Test fun heldTextSurvivesATapOnTheField() = assertHeldSurvives {
        it.onStartInputView(editor(true), false)
    }

    private fun deadConnectionAtStop(privateField: Boolean): Triple<DictationImeService, View, InactiveField> {
        DictationImeService.requestPanel = {}
        val input = Input()
        devices.add(input)
        val (service, _) = service(editor(privateField))
        feed(input, 1000, 1)
        platformResetOnRotation(service, android.view.Surface.ROTATION_90)
        advance(200)
        service.onFinishInputView(true)
        service.onFinishInput()
        val next = InactiveField(service)
        service.onCreateInputMethodInterface().startInput(next, editor(privateField))
        service.onStartInputView(editor(privateField), false)
        service.onFinishInputView(false)
        service.onWindowHidden()
        pump()
        val returned = showPanel(service)!!
        advance(ROTATION_SETTLE_MS)
        button(returned).performClick()
        await { input.releases.get() == 1 }
        await { decode.drain(); status(returned).startsWith(service.getString(R.string.status_unable_insert)) }
        settleHistory()
        return Triple(service, returned, next)
    }

    /** The take's field: a chat compose box with an id, in the editor's app. */
    private fun composeField() = EditorInfo().apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        imeOptions = EditorInfo.IME_ACTION_NONE or EditorInfo.IME_FLAG_NO_ENTER_ACTION
        fieldId = 0x7f0a0101
        packageName = "org.example.chat"
        hintText = "Message"
        initialSelStart = 0; initialSelEnd = 0
    }

    /**
     * The platform's fallback connection for a focused view that is not a text editor: reads give
     * "", there is no extracted text, a write returns true and reaches nothing visible (Signal
     * after rotation, diagnostics 2026-11-13: beforeLen 0 before and after, extracted null).
     */
    private inner class NonEditorConnection(service: DictationImeService) : BaseInputConnection(View(service), false) {
        var swallowed = 0
        override fun getTextBeforeCursor(length: Int, flags: Int): CharSequence = ""
        override fun getExtractedText(request: android.view.inputmethod.ExtractedTextRequest?, flags: Int) = null
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean { swallowed++; return true }
    }

    /** Signal order: reset, old editor finishes, the recreated activity starts [next] with [info], hides. */
    private fun recreatedActivityStartsInput(service: DictationImeService, next: android.view.inputmethod.InputConnection, info: EditorInfo): View {
        platformResetOnRotation(service, android.view.Surface.ROTATION_90, composeField())
        advance(150)
        service.onFinishInputView(true)
        service.onFinishInput()
        service.onCreateInputMethodInterface().startInput(next, info)
        service.onStartInputView(info, false)
        service.onFinishInputView(false)
        service.onWindowHidden()
        pump()
        val returned = showPanel(service, info)!! // Our own panel request, on that input.
        advance(ROTATION_SETTLE_MS)
        return returned
    }

    /**
     * Signal 2026-11-13: after the rotation the input that starts is not a text editor (TYPE_NULL,
     * no selection). Stop must not write into it nor report an insert; the take is held for Copy.
     */
    @Test fun signalOrderNonEditorInputAfterRotationIsNeverWrittenAndHoldsTheText() {
        DictationImeService.requestPanel = {}
        val input = Input()
        devices.add(input)
        val (service, _) = service(composeField())
        feed(input, 1000, 1)
        val next = NonEditorConnection(service)
        val returned = recreatedActivityStartsInput(service, next, EditorInfo().apply {
            inputType = InputType.TYPE_NULL; packageName = "org.example.chat"; initialSelStart = -1; initialSelEnd = -1
        })
        button(returned).performClick()
        await { input.releases.get() == 1 }
        await { decode.drain(); status(returned).startsWith(service.getString(R.string.status_unable_insert)) }
        settleHistory()
        assertEquals(0, next.swallowed)
        assertEquals(0, commits)
        assertTrue(rows().single().delivery != HistoryDelivery.ACCEPTED)
        assertHeldState(service, returned)
    }

    /**
     * ADR-010: a different real field of the same app that takes focus after the recreation (a
     * search box) never receives the take's text; the take is held for Copy.
     */
    @Test fun otherFieldOfTheSameAppAfterRotationIsNeverWrittenAndHoldsTheText() {
        DictationImeService.requestPanel = {}
        val input = Input()
        devices.add(input)
        val (service, _) = service(composeField())
        feed(input, 1000, 1)
        val other = Field(service)
        val returned = recreatedActivityStartsInput(service, other, EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            fieldId = 0x7f0a0202
            packageName = "org.example.chat"
            hintText = "Search"
            initialSelStart = 0; initialSelEnd = 0
        })
        button(returned).performClick()
        await { input.releases.get() == 1 }
        await { decode.drain(); status(returned).startsWith(service.getString(R.string.status_unable_insert)) }
        settleHistory()
        assertTrue(other.inserted.isEmpty())
        assertEquals(0, commits)
        // A URI field is unclassifiable: the take settled on it, so History does not keep it and the status says so.
        assertTrue(rows().isEmpty())
        assertHeldState(service, returned, privateField = true)
    }

    private fun nonEditorInfo() = EditorInfo().apply {
        inputType = InputType.TYPE_NULL; packageName = "org.example.chat"; initialSelStart = -1; initialSelEnd = -1
    }
    private fun notice(panel: View) = panel.findViewById<TextView>(R.id.retention_notice)

    /** Signal order into a view that is no editor; returns the take's panel, recording without authority. */
    private fun retargetedToNonEditor(): Triple<DictationImeService, View, Input> {
        DictationImeService.requestPanel = {}
        val input = Input()
        devices.add(input)
        val (service, _) = service(composeField())
        feed(input, 1000, 1)
        val returned = recreatedActivityStartsInput(service, NonEditorConnection(service), nonEditorInfo())
        return Triple(service, returned, input)
    }

    /** The user taps the take's own text field: input starts there again (a new connection). */
    private fun tapOriginalField(service: DictationImeService): Field {
        val back = Field(service)
        service.onFinishInputView(true)
        service.onFinishInput()
        service.onCreateInputMethodInterface().startInput(back, composeField())
        service.onStartInputView(composeField(), false)
        pump()
        advance(ROTATION_SETTLE_MS)
        return back
    }

    @Test fun aTakeRecordingWithoutAuthorityKeepsRecordingShowsTheTapLineAndStopHoldsTheText() {
        val (service, returned, input) = retargetedToNonEditor()
        assertEquals(service.getString(R.string.status_recording), status(returned))
        assertEquals(View.VISIBLE, notice(returned).visibility)
        assertEquals(service.getString(R.string.notice_tap_field), notice(returned).text.toString())
        assertEquals(0, input.releases.get()) // Not an interruption.
        feed(input, 20000, 2) // Speech after the rotation is not lost.
        advance(ROTATION_HOLD_GAP_MS)
        assertEquals(0, input.releases.get())
        button(returned).performClick()
        await { input.releases.get() == 1 }
        await { decode.drain(); status(returned).startsWith(service.getString(R.string.status_unable_insert)) }
        settleHistory()
        assertEquals(View.GONE, notice(returned).visibility)
        assertEquals(0, commits)
        assertHeldState(service, returned, text = "old speech private speech")
    }

    @Test fun theTakesFieldStartingInputAgainRestoresAuthorityAndStopInsertsOnce() {
        val (service, returned, input) = retargetedToNonEditor()
        val back = tapOriginalField(service)
        assertEquals(View.GONE, notice(returned).visibility) // The line goes when authority returns.
        assertEquals(service.getString(R.string.status_recording), status(returned))
        assertEquals(0, input.releases.get()) // A matching start never interrupts.
        feed(input, 20000, 2)
        button(returned).performClick()
        await { input.releases.get() == 1 }
        await { decode.drain(); commits == 1 }
        settleHistory()
        assertEquals(listOf("old speech private speech"), back.inserted)
        assertEquals(HistoryDelivery.ACCEPTED, rows().single().delivery)
    }

    @Test fun theTakesFieldReturningDuringTheStopWaitGetsTheResultOnce() {
        val (service, returned, input) = retargetedToNonEditor()
        button(returned).performClick()
        await { input.releases.get() == 1 } // Stopped without authority; the result is not decoded yet.
        val back = tapOriginalField(service)
        await { decode.drain(); commits == 1 }
        settleHistory()
        assertEquals(listOf("old speech"), back.inserted)
    }

    @Test fun theTakesFieldStartingInputAfterTheResultWasDispatchedInsertsNothing() {
        val (service, returned, input) = retargetedToNonEditor()
        button(returned).performClick()
        await { input.releases.get() == 1 }
        await { decode.drain(); status(returned).startsWith(service.getString(R.string.status_unable_insert)) }
        settleHistory()
        val back = tapOriginalField(service)
        decode.drain(); settleHistory()
        assertEquals(0, commits)
        assertTrue(back.inserted.isEmpty())
        assertHeldState(service, returned)
    }

    @Test fun anotherAppStartingInputWhileWithoutAuthorityStillInterrupts() {
        val (service, _, input) = retargetedToNonEditor()
        service.onFinishInputView(true)
        service.onFinishInput()
        val other = Field(service)
        service.onCreateInputMethodInterface().startInput(other, composeField().apply { packageName = "org.example.other" })
        pump()
        await { input.releases.get() == 1 }
        assertTrue(other.inserted.isEmpty())
    }

    /** A rotation into [connection] with the take's own field attributes, then Stop and the write. */
    private fun stopAfterRotationInto(connection: (DictationImeService) -> Field): Triple<DictationImeService, View, Field> {
        DictationImeService.requestPanel = {}
        val input = Input()
        devices.add(input)
        val (service, _) = service(composeField())
        feed(input, 1000, 1)
        val next = connection(service)
        val returned = recreatedActivityStartsInput(service, next, composeField())
        button(returned).performClick()
        await { input.releases.get() == 1 }
        await { decode.drain(); pump(); commits == 1 }
        return Triple(service, returned, next)
    }

    /** The write answered true and nothing is visible: not inserted, held with a status that admits the doubt. */
    @Test fun aWriteTheReadBackNeverSeesIsHeldAsUnconfirmed() {
        val (service, returned, _) = stopAfterRotationInto { Field(it).apply { swallow = true } }
        assertEquals(View.GONE, returned.findViewById<View>(R.id.choice).visibility) // Not decided before the second look.
        advance(DictationImeService.CONFIRM_DELAY_MS + 10)
        settleHistory()
        assertHeldState(service, returned, statusRes = R.string.status_unconfirmed)
        assertTrue(rows().single().delivery != HistoryDelivery.ACCEPTED)
        assertCopyPutsTakeTextOnClipboard(service, returned)
    }

    /** Shown at the second look only: counts as inserted, the keyboard is handed back, nothing is held. */
    @Test fun aWriteSeenAtTheSecondLookCountsAsInserted() {
        org.robolectric.shadows.ShadowLog.clear()
        val (service, returned, _) = stopAfterRotationInto { Field(it).apply { visibleAfterReads = 2 } }
        advance(DictationImeService.CONFIRM_DELAY_MS + 10)
        settleHistory()
        assertEquals(View.GONE, returned.findViewById<View>(R.id.choice).visibility)
        assertEquals(service.getString(R.string.status_idle), status(returned))
        assertTrue(org.robolectric.shadows.ShadowLog.getLogsForTag("DictationIme").any { it.msg.contains("no previous IME") })
    }

    /** The read-back belongs to takes that went through a rotation hold: others are untouched. */
    @Test fun aTakeWithoutARotationIsNotReadBack() {
        org.robolectric.shadows.ShadowLog.clear()
        val input = Input()
        devices.add(input)
        val (service, view) = service(composeField())
        firstField.getValue(service).swallow = true
        feed(input, 1000, 1)
        button(view).performClick()
        await { input.releases.get() == 1 }
        await { decode.drain(); pump(); commits == 1 }
        settleHistory()
        assertEquals(HistoryDelivery.ACCEPTED, rows().single().delivery)
        assertEquals(View.GONE, view.findViewById<View>(R.id.choice).visibility)
        assertTrue(org.robolectric.shadows.ShadowLog.getLogsForTag("DictationIme").any { it.msg.contains("no previous IME") })
    }

    /** Copy puts exactly the take text on the clipboard as plain text (no sensitive flag), repeatably, and says so. */
    private fun assertCopyPutsTakeTextOnClipboard(service: DictationImeService, panel: View) {
        val clipboard = service.getSystemService(android.content.ClipboardManager::class.java)
        repeat(2) {
            clipboard.clearPrimaryClip()
            panel.findViewById<View>(R.id.choice_safe).performClick()
            val clip = clipboard.primaryClip!!
            assertEquals("old speech", clip.getItemAt(0).text.toString())
            assertEquals(false, clip.description.extras?.getBoolean("android.content.extra.IS_SENSITIVE") ?: false)
            assertEquals(service.getString(R.string.status_copied), status(panel))
            assertEquals("old speech", preview(panel)) // The text stays held and visible.
            assertEquals(View.VISIBLE, panel.findViewById<View>(R.id.status_marker).visibility)
        }
    }

    @Test fun pixelOrderKeptEditorKeepsTheTakeAfterThePlatformReset() {
        DictationImeService.requestPanel = {}
        val input = Input()
        devices.add(input)
        val (service, _) = service(editor())
        val first = firstField.getValue(service)
        feed(input, 1000, 1)
        val panel = platformResetOnRotation(service, android.view.Surface.ROTATION_90)!!
        advance(ROTATION_HOLD_GAP_MS * 2) // Panel stays visible: no gap applies.
        assertEquals(0, input.releases.get())
        assertEquals(service.getString(R.string.status_recording), status(panel))
        feed(input, 20000, 2)
        decode.drain(); pump()
        assertEquals("old speech private speech", preview(panel))
        button(panel).performClick()
        await { input.releases.get() == 1 }
        await { decode.drain(); commits == 1 }
        assertEquals(listOf("old speech private speech"), first.inserted)
    }

    @Test fun hideRightAfterARotationIsHeldOnlyUntilTheGapEnds() {
        DictationImeService.requestPanel = {}
        val input = Input()
        devices.add(input)
        val (service, _) = service(editor())
        feed(input, 1000, 1)
        turn(service, android.view.Surface.ROTATION_90)
        advance(ROTATION_SETTLE_MS / 2)
        service.onFinishInputView(true) // Cannot be told apart from the editor reacting to rotation.
        advance(ROTATION_HOLD_GAP_MS)
        await { input.releases.get() == 1 }
        awaitInterruptedInHistory()
        assertEquals(0, commits)
    }

    @Test fun repeatedRotationsAndRotatingBackKeepOneTakeWithAllSpeech() {
        DictationImeService.requestPanel = {}
        val input = Input()
        devices.add(input)
        val (service, _) = service(editor())
        feed(input, 1000, 1)
        rotateHidden(service, android.view.Surface.ROTATION_90)
        showPanel(service)
        feed(input, 20000, 2)
        // There and back before the editor reacts: the display ends where it started.
        turn(service, android.view.Surface.ROTATION_0)
        turn(service, android.view.Surface.ROTATION_90)
        turn(service, android.view.Surface.ROTATION_0)
        service.onFinishInputView(true)
        service.onWindowHidden()
        service.onFinishInput()
        val last = Field(service)
        service.onCreateInputMethodInterface().startInput(last, editor())
        pump()
        assertEquals(0, input.releases.get())
        val returned = showPanel(service)!!
        feed(input, 1000, 3)
        decode.drain(); pump()
        button(returned).performClick()
        await { input.releases.get() == 1 }
        await { decode.drain(); commits == 1 }
        settleHistory()
        assertEquals(listOf("old speech private speech old speech"), last.inserted)
        assertEquals(1, commits)
        assertEquals(1, input.starts.get())
        assertEquals(1, rows().size)
    }

    @Test fun screenOffDuringRotationHoldStopsAtOnce() {
        DictationImeService.requestPanel = {}
        val input = Input()
        devices.add(input)
        val (service, _) = service(editor())
        feed(input, 1000, 1)
        rotateHidden(service, android.view.Surface.ROTATION_90)
        screenOff()
        await { input.releases.get() == 1 }
        awaitInterruptedInHistory()
        assertEquals(0, commits)
    }

    @Test fun microphoneLossDuringRotationHoldEndsInHistory() {
        DictationImeService.requestPanel = {}
        val input = Input()
        devices.add(input)
        val (service, _) = service(editor())
        feed(input, 1000, 1)
        rotateHidden(service, android.view.Surface.ROTATION_90)
        input.endUnexpectedly()
        await { input.releases.get() == 1 }
        await { flushes.get() == 1 }
        awaitInterruptedInHistory()
        showPanel(service)
        assertEquals(0, commits)
        assertEquals(1, input.starts.get())
    }

    @Test fun reboundPrivateEditorExcludesTheTakeFromHistory() {
        DictationImeService.requestPanel = {}
        val input = Input()
        devices.add(input)
        val (service, _) = service(editor())
        feed(input, 1000, 1)
        decode.drain(); settleHistory()
        assertEquals(1, rows().size) // Checkpointed while the starting editor allowed it.
        val next = rotateHidden(service, android.view.Surface.ROTATION_90, editor(privateField = true))
        val returned = showPanel(service, editor(privateField = true))!!
        settleHistory()
        assertTrue(rows().isEmpty())
        // A different (private) field: the take has no authority there, so the notice line says how to get it back.
        val notice = returned.findViewById<TextView>(R.id.retention_notice)
        assertEquals(View.VISIBLE, notice.visibility)
        assertEquals(service.getString(R.string.notice_tap_field), notice.text.toString())
        feed(input, 20000, 2)
        decode.drain(); settleHistory()
        assertTrue(rows().isEmpty())
        button(returned).performClick()
        await { input.releases.get() == 1 }
        await { decode.drain(); status(returned).startsWith(service.getString(R.string.status_unable_insert)) }
        settleHistory()
        assertTrue(next.inserted.isEmpty()) // Never into a different field.
        assertTrue(rows().isEmpty())
        assertHeldState(service, returned, privateField = true, text = "old speech private speech")
    }

    /** How a rotation hold whose rebound editor is private ends. */
    private enum class HoldExit { GAP, SCREEN_OFF, MICROPHONE_LOSS, SILENCED, HIDE_IN_HOLD, HIDE_AFTER_PANEL }

    /**
     * A rotation rebinds the take to a private editor and the hold ends without the take
     * continuing: the take is excluded from the moment the private editor starts input, so
     * neither the saved checkpoint nor the interrupted result is kept, whatever ends the hold.
     */
    private fun reboundPrivateEditorKeepsNothing(exit: HoldExit, stopFirst: Boolean = false) {
        DictationImeService.requestPanel = {}
        val input = Input()
        devices.add(input)
        val (service, view) = service(editor())
        feed(input, 1000, 1)
        decode.drain(); settleHistory()
        assertEquals(1, rows().size) // Checkpointed while the starting editor allowed it.
        if (exit == HoldExit.SILENCED) reportSilenced(false)
        if (stopFirst) { button(view).performClick(); await { input.releases.get() == 1 } }
        rotateHidden(service, android.view.Surface.ROTATION_90, editor(privateField = true))
        settleHistory()
        assertTrue("excluded as soon as the private editor starts input", rows().isEmpty())
        when (exit) {
            HoldExit.GAP -> advance(ROTATION_HOLD_GAP_MS)
            HoldExit.SCREEN_OFF -> screenOff()
            HoldExit.MICROPHONE_LOSS -> input.endUnexpectedly()
            HoldExit.SILENCED -> reportSilenced(true)
            HoldExit.HIDE_IN_HOLD -> { service.onFinishInputView(true); advance(ROTATION_HOLD_GAP_MS) }
            HoldExit.HIDE_AFTER_PANEL -> {
                showPanel(service, editor(privateField = true))
                advance(ROTATION_SETTLE_MS)
                service.onFinishInputView(true)
                // The private editor is not the take's field (no authority): a leave is held for the gap, as a tap on
                // the field would otherwise end it (ADR-010 same-field rule).
                advance(ROTATION_HOLD_GAP_MS)
            }
        }
        await { input.releases.get() == 1 }
        // The final decode ran; the interrupted save (if any) follows on main and History I/O.
        await { decode.drain(); settleHistory(); flushes.get() == 1 }
        repeat(3) { decode.drain(); settleHistory() }
        assertTrue(rows().isEmpty())
        assertEquals(0, commits)
    }

    @Test fun reboundPrivateEditorThenGapKeepsNothing() = reboundPrivateEditorKeepsNothing(HoldExit.GAP)
    @Test fun reboundPrivateEditorThenLockKeepsNothing() = reboundPrivateEditorKeepsNothing(HoldExit.SCREEN_OFF)
    @Test fun reboundPrivateEditorThenMicrophoneLossKeepsNothing() = reboundPrivateEditorKeepsNothing(HoldExit.MICROPHONE_LOSS)
    @Test fun reboundPrivateEditorThenSilencingKeepsNothing() = reboundPrivateEditorKeepsNothing(HoldExit.SILENCED)
    @Test fun reboundPrivateEditorThenHideInTheHoldKeepsNothing() = reboundPrivateEditorKeepsNothing(HoldExit.HIDE_IN_HOLD)
    @Test fun reboundPrivateEditorThenRealHideKeepsNothing() = reboundPrivateEditorKeepsNothing(HoldExit.HIDE_AFTER_PANEL)
    @Test fun stopWaitReboundPrivateEditorThenGapKeepsNothing() = reboundPrivateEditorKeepsNothing(HoldExit.GAP, stopFirst = true)
    @Test fun stopWaitReboundPrivateEditorThenLockKeepsNothing() = reboundPrivateEditorKeepsNothing(HoldExit.SCREEN_OFF, stopFirst = true)
    @Test fun stopWaitReboundPrivateEditorThenHideInTheHoldKeepsNothing() = reboundPrivateEditorKeepsNothing(HoldExit.HIDE_IN_HOLD, stopFirst = true)
    @Test fun stopWaitReboundPrivateEditorThenRealHideKeepsNothing() = reboundPrivateEditorKeepsNothing(HoldExit.HIDE_AFTER_PANEL, stopFirst = true)

    /**
     * Signal order (#61 visit trace): the platform reset inside the configuration change, then the
     * recreated activity briefly starts input with no editor (TYPE_NULL) before its message field
     * starts input again. No editor is not a private editor: the take stays eligible.
     */
    @Test fun signalOrderTransientInputStartWithoutEditorKeepsTheTakeEligible() {
        val panelRequests = AtomicInteger()
        DictationImeService.requestPanel = { panelRequests.incrementAndGet() }
        val input = Input()
        devices.add(input)
        val (service, _) = service(editor())
        feed(input, 1000, 1)
        decode.drain(); settleHistory()
        assertEquals(1, rows().size)
        platformResetOnRotation(service, android.view.Surface.ROTATION_90)
        advance(130)
        service.onFinishInputView(true)
        service.onFinishInput()
        val none = Field(service)
        service.onCreateInputMethodInterface().startInput(none, EditorInfo().apply { inputType = InputType.TYPE_NULL })
        service.onStartInputView(EditorInfo().apply { inputType = InputType.TYPE_NULL }, false)
        service.onFinishInputView(false)
        service.onWindowHidden()
        service.onFinishInput()
        val next = Field(service)
        service.onCreateInputMethodInterface().startInput(next, editor())
        pump()
        val returned = showPanel(service)!!
        settleHistory()
        assertEquals("checkpoint kept through the editor-less start", 1, rows().size)
        assertEquals(View.GONE, returned.findViewById<TextView>(R.id.retention_notice).visibility)
        advance(ROTATION_SETTLE_MS)
        // Rotate back the same way: still eligible.
        platformResetOnRotation(service, android.view.Surface.ROTATION_0)
        advance(130)
        service.onFinishInputView(true)
        service.onFinishInput()
        service.onCreateInputMethodInterface().startInput(Field(service), EditorInfo())
        service.onFinishInput()
        val last = Field(service)
        service.onCreateInputMethodInterface().startInput(last, editor())
        pump()
        val back = showPanel(service)!!
        advance(ROTATION_SETTLE_MS)
        assertEquals(View.GONE, back.findViewById<TextView>(R.id.retention_notice).visibility)
        feed(input, 20000, 2)
        button(back).performClick()
        await { input.releases.get() == 1 }
        await { decode.drain(); settleHistory(); commits == 1 }
        settleHistory()
        assertEquals(listOf("old speech private speech"), last.inserted)
        assertTrue(none.inserted.isEmpty())
        assertEquals(HistoryDelivery.ACCEPTED, rows().single().delivery)
        assertEquals("old speech private speech", rows().single().text)
    }

    /** A no-learning editor during the hold is positively private: excluded at once, like a password. */
    @Test fun reboundNoLearningEditorExcludesTheTakeEvenWithoutTheReturningPanel() {
        DictationImeService.requestPanel = {}
        val input = Input()
        devices.add(input)
        val (service, _) = service(editor())
        feed(input, 1000, 1)
        decode.drain(); settleHistory()
        assertEquals(1, rows().size)
        rotateHidden(service, android.view.Surface.ROTATION_90, editor().apply { imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING })
        settleHistory()
        assertTrue(rows().isEmpty())
        advance(ROTATION_HOLD_GAP_MS)
        await { input.releases.get() == 1 }
        repeat(3) { decode.drain(); settleHistory() }
        assertTrue(rows().isEmpty())
    }

    /** An unclassifiable editor the panel settles on excludes the take; an editor-less start alone does not. */
    @Test fun panelSettlingOnAnUnclassifiableEditorExcludesTheTake() {
        DictationImeService.requestPanel = {}
        val input = Input()
        devices.add(input)
        val (service, _) = service(editor())
        feed(input, 1000, 1)
        decode.drain(); settleHistory()
        val odd = EditorInfo().apply { inputType = InputType.TYPE_CLASS_NUMBER }
        rotateHidden(service, android.view.Surface.ROTATION_90, odd)
        settleHistory()
        assertEquals("not private by itself during the hold", 1, rows().size)
        val returned = showPanel(service, odd)!!
        advance(ROTATION_SETTLE_MS)
        settleHistory()
        assertTrue(rows().isEmpty())
        assertEquals(View.VISIBLE, returned.findViewById<TextView>(R.id.retention_notice).visibility)
    }

    @Test fun privateStartingEditorStaysExcludedAfterReboundToOrdinaryField() {
        DictationImeService.requestPanel = {}
        val input = Input()
        devices.add(input)
        val (service, _) = service(editor(privateField = true))
        feed(input, 1000, 1)
        val next = rotateHidden(service, android.view.Surface.ROTATION_90, editor())
        val returned = showPanel(service, editor())!!
        assertEquals(service.getString(R.string.notice_tap_field), returned.findViewById<TextView>(R.id.retention_notice).text.toString())
        feed(input, 20000, 2)
        button(returned).performClick()
        await { input.releases.get() == 1 }
        await { decode.drain(); status(returned).startsWith(service.getString(R.string.status_unable_insert)) }
        settleHistory()
        assertTrue(next.inserted.isEmpty()) // A different field: held, never written.
        assertTrue(rows().isEmpty())
    }

    /** Android reports this app's recording as silenced (for example, a hidden client). */
    private fun reportSilenced(silenced: Boolean) {
        val audio = RuntimeEnvironment.getApplication().getSystemService(android.media.AudioManager::class.java)
        val shadow = shadowOf(audio)
        val config = shadow.createActiveRecordingConfiguration(1, android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION, "ch.og.dictation")
        android.media.AudioRecordingConfiguration::class.java.getDeclaredField("mClientSilenced")
            .apply { isAccessible = true }.setBoolean(config, silenced)
        shadow.setActiveRecordingConfigurations(listOf(config), true)
        pump()
    }

    @Test fun silencedCaptureDuringRotationHoldEndsInHistory() {
        DictationImeService.requestPanel = {}
        val input = Input()
        devices.add(input)
        val (service, _) = service(editor())
        feed(input, 1000, 1)
        reportSilenced(false)
        rotateHidden(service, android.view.Surface.ROTATION_90)
        assertEquals(0, input.releases.get())
        reportSilenced(true)
        await { input.releases.get() == 1 } // No clock advance: at once, not at the gap.
        await { decode.drain(); settleHistory(); rows().singleOrNull()?.completion == HistoryCompletion.INTERRUPTED }
        assertEquals(0, commits)
    }

    @Test fun focusChangeToAnotherFieldWithoutRotationStopsAtOnce() {
        val input = Input()
        devices.add(input)
        val (service, _) = service(editor())
        feed(input, 1000, 1)
        service.onFinishInputView(false)
        val other = Field(service)
        service.onCreateInputMethodInterface().startInput(other, editor())
        showPanel(service)
        await { input.releases.get() == 1 }
        awaitInterruptedInHistory()
        assertTrue(other.inserted.isEmpty())
        assertEquals(0, commits)
        assertEquals(1, input.starts.get())
    }

    @Test fun sameFieldRestartWithoutRotationStopsAtOnce() {
        val input = Input()
        devices.add(input)
        val (service, _) = service(editor())
        feed(input, 1000, 1)
        service.onStartInput(editor(), true)
        await { input.releases.get() == 1 }
        awaitInterruptedInHistory()
        assertEquals(0, commits)
    }

    @Test fun inputMethodDeclaresRotationAndResizeAsHandled() {
        val handled = declaredConfigChanges()
        for (change in listOf(android.content.pm.ActivityInfo.CONFIG_ORIENTATION, android.content.pm.ActivityInfo.CONFIG_SCREEN_SIZE,
                android.content.pm.ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE, android.content.pm.ActivityInfo.CONFIG_SCREEN_LAYOUT)) {
            assertEquals(change, handled and change)
        }
    }

    @Test @Config(qualifiers = "land")
    fun landscapeNeverUsesFullscreenExtractMode() {
        val (service, _) = service(editor())
        assertFalse(service.onEvaluateFullscreenMode())
        assertFalse(service.isFullscreenMode)
    }

    @Test fun rotationDuringRecordingRecreatesPanelFromTakeAndKeepsCapture() {
        val input = Input()
        devices.add(input)
        val (service, view) = service(editor(privateField = true))
        feed(input, 20000, 1)
        decode.drain(); pump()
        assertEquals("private speech", preview(view))

        val landscape = rotate(service, "+land")
        assertNotSame(view, landscape)
        assertEquals(service.getString(R.string.status_recording), status(landscape))
        assertEquals("private speech", preview(landscape))
        assertTrue(button(landscape).isEnabled)
        assertEquals(service.getString(R.string.stop_recording), button(landscape).contentDescription.toString())
        val notice = landscape.findViewById<TextView>(R.id.retention_notice)
        assertEquals(View.VISIBLE, notice.visibility)
        assertEquals(service.getString(R.string.retention_field_off), notice.text.toString())
        assertEquals(1, input.starts.get())
        assertEquals(0, input.releases.get())

        feed(input, 20000, 2)
        decode.drain(); pump()
        assertEquals("private speech private speech", preview(landscape))
        val portrait = rotate(service, "+port")
        assertEquals(service.getString(R.string.status_recording), status(portrait))
        assertEquals("private speech private speech", preview(portrait))
        assertEquals(0, input.releases.get())
        button(portrait).performClick()
        await { input.releases.get() == 1 }
        await { decode.drain(); commits == 1 }
        settleHistory()
        assertEquals(1, commits)
        assertEquals(1, input.starts.get())
    }

    @Test fun rotationDuringStopWaitKeepsFinishingAndInsertsOnce() {
        val gate = CountDownLatch(1)
        val input = Input(releaseGate = gate)
        devices.add(input)
        val (service, view) = service(editor())
        feed(input, 1000, 1)
        button(view).performClick()
        assertEquals(service.getString(R.string.status_finishing), status(view))

        val landscape = rotate(service, "+land")
        assertEquals(service.getString(R.string.status_finishing), status(landscape))
        assertFalse(button(landscape).isEnabled)
        gate.countDown()
        await { input.releases.get() == 1 }
        await { decode.drain(); commits == 1 }
        settleHistory()
        assertEquals(1, commits)
        assertEquals(HistoryCompletion.COMPLETE, rows().single().completion)
    }

    /** The rest of the Pixel order: the old editor hides the panel and a recreated one starts input. */
    private fun recreatedEditorTakesInput(service: DictationImeService, info: EditorInfo = editor()): Field {
        advance(95)
        service.onFinishInputView(false)
        service.onWindowHidden()
        service.onFinishInput()
        val next = Field(service)
        service.onCreateInputMethodInterface().startInput(next, info)
        pump()
        return next
    }

    /** Where the Stop wait is when the phone rotates (#53). */
    private enum class StopWait { BEFORE_FINAL_DECODE, BEFORE_SAVE_ACK }

    /**
     * Stop, then a Pixel-order rotation into a recreated editor that does not show the keyboard:
     * the panel comes back finishing the same take and the result is inserted there once.
     */
    private fun rotationDuringStopWaitInsertsOnceIntoTheReturnedField(boundary: StopWait) {
        val panelRequests = AtomicInteger()
        DictationImeService.requestPanel = { panelRequests.incrementAndGet() }
        val input = Input()
        devices.add(input)
        val (service, view) = service(editor())
        val first = firstField.getValue(service)
        feed(input, 1000, 1)
        button(view).performClick()
        await { input.releases.get() == 1 }
        val io = CountDownLatch(1)
        if (boundary == StopWait.BEFORE_SAVE_ACK) {
            HistoryIo.executor.execute { io.await(2, TimeUnit.SECONDS) }
            await { decode.drain(); flushes.get() == 1 }
        }
        platformResetOnRotation(service, android.view.Surface.ROTATION_90)
        val next = recreatedEditorTakesInput(service)
        assertEquals(1, panelRequests.get())
        io.countDown()
        decode.drain(); settleHistory(); decode.drain(); settleHistory()
        assertEquals("the result waits for the panel", 0, commits)

        val returned = showPanel(service)!!
        assertEquals(service.getString(R.string.status_finishing), status(returned))
        assertFalse(button(returned).isEnabled)
        advance(ROTATION_SETTLE_MS)
        await { decode.drain(); settleHistory(); commits == 1 }
        settleHistory()
        assertEquals(listOf("old speech"), next.inserted)
        assertTrue(first.inserted.isEmpty())
        assertEquals(1, input.starts.get())
        assertEquals(HistoryDelivery.ACCEPTED, rows().single().delivery)
        advance(ROTATION_HOLD_GAP_MS * 2)
        assertEquals(1, commits)
    }

    @Test fun rotationBeforeFinalDecodeInsertsOnceIntoTheReturnedField() =
        rotationDuringStopWaitInsertsOnceIntoTheReturnedField(StopWait.BEFORE_FINAL_DECODE)

    @Test fun rotationBeforeTheHistorySaveAcknowledgementInsertsOnceIntoTheReturnedField() =
        rotationDuringStopWaitInsertsOnceIntoTheReturnedField(StopWait.BEFORE_SAVE_ACK)

    /** Stop on the panel Android showed inside the configuration change, before the late hide. */
    @Test fun stopOnThePlatformResetPanelInsertsOnceIntoTheRecreatedEditor() {
        DictationImeService.requestPanel = {}
        val input = Input()
        devices.add(input)
        val (service, _) = service(editor())
        val first = firstField.getValue(service)
        feed(input, 1000, 1)
        val reset = platformResetOnRotation(service, android.view.Surface.ROTATION_90)!!
        button(reset).performClick()
        await { input.releases.get() == 1 }
        val next = recreatedEditorTakesInput(service)
        decode.drain(); settleHistory()
        assertEquals(0, commits)
        showPanel(service)
        advance(ROTATION_SETTLE_MS)
        await { decode.drain(); settleHistory(); commits == 1 }
        assertEquals(listOf("old speech"), next.inserted)
        assertTrue(first.inserted.isEmpty())
    }

    @Test fun rotationAfterTheAcceptedCommitNeitherUndoesNorRepeatsIt() {
        val input = Input()
        devices.add(input)
        val (service, view) = service(editor())
        val first = firstField.getValue(service)
        feed(input, 1000, 1)
        button(view).performClick()
        await { decode.drain(); settleHistory(); commits == 1 }
        platformResetOnRotation(service, android.view.Surface.ROTATION_90)
        recreatedEditorTakesInput(service)
        showPanel(service)
        advance(ROTATION_HOLD_GAP_MS * 2)
        decode.drain(); settleHistory()
        assertEquals(listOf("old speech"), first.inserted)
        assertEquals(1, commits)
        assertEquals(HistoryDelivery.ACCEPTED, rows().single().delivery)
        assertEquals(1, input.starts.get())
    }

    @Test fun rotationDuringStopWaitWithoutAReturningPanelSavesToHistoryOnlyAtTheGap() {
        DictationImeService.requestPanel = {}
        val input = Input()
        devices.add(input)
        val (service, view) = service(editor())
        feed(input, 1000, 1)
        button(view).performClick()
        await { input.releases.get() == 1 }
        rotateHidden(service, android.view.Surface.ROTATION_90)
        decode.drain(); settleHistory()
        assertEquals(0, commits)
        advance(ROTATION_HOLD_GAP_MS)
        await { decode.drain(); settleHistory(); rows().singleOrNull()?.delivery == HistoryDelivery.NOT_ATTEMPTED }
        assertEquals("old speech", rows().single().text)
        showPanel(service)
        advance(ROTATION_SETTLE_MS)
        settleHistory()
        assertEquals(0, commits)
        assertEquals(1, input.starts.get())
    }

    @Test fun realHideDuringStopWaitAfterTheReturnedPanelSettledGoesToHistory() {
        DictationImeService.requestPanel = {}
        val input = Input()
        devices.add(input)
        val (service, view) = service(editor())
        feed(input, 1000, 1)
        val io = CountDownLatch(1)
        button(view).performClick()
        await { input.releases.get() == 1 }
        HistoryIo.executor.execute { io.await(2, TimeUnit.SECONDS) }
        rotateHidden(service, android.view.Surface.ROTATION_90)
        showPanel(service)
        advance(ROTATION_SETTLE_MS)
        service.onFinishInputView(true) // A real hide: no rotation since the panel settled.
        io.countDown()
        awaitInterruptedInHistory()
        assertEquals(0, commits)
    }

    private fun choiceShown(panel: View) = panel.findViewById<View>(R.id.choice).visibility == View.VISIBLE
    private fun safe(panel: View) = panel.findViewById<ImageButton>(R.id.choice_safe)
    private fun stopAction(panel: View) = panel.findViewById<ImageButton>(R.id.choice_stop)
    private fun discardAction(panel: View) = panel.findViewById<ImageButton>(R.id.choice_discard)
    private fun back(panel: View) = panel.findViewById<ImageButton>(R.id.back)
    private fun systemBack(service: DictationImeService) =
        service.onKeyDown(android.view.KeyEvent.KEYCODE_BACK, android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_BACK))
    /** The choice is part of the panel: no dialog or other window is ever shown for it. */
    private fun assertNoWindow() = assertNull(org.robolectric.shadows.ShadowDialog.getLatestDialog())

    /** Pixel-order rotation into a recreated editor; returns the new field and the returned panel. */
    private fun rotateToRecreatedEditor(service: DictationImeService): Pair<Field, View> {
        platformResetOnRotation(service, android.view.Surface.ROTATION_90)
        val next = recreatedEditorTakesInput(service)
        return next to showPanel(service)!!
    }

    @Test fun recordingChoiceOpenThroughARotationIsRenderedAgainAndContinueKeepsTheTake() {
        DictationImeService.requestPanel = {}
        val input = Input()
        devices.add(input)
        val (service, view) = service(editor())
        feed(input, 1000, 1)
        back(view).performClick()
        assertTrue(choiceShown(view))
        val (next, returned) = rotateToRecreatedEditor(service)
        assertNotSame(view, returned)
        assertTrue("the recreated panel renders the same choice", choiceShown(returned))
        assertEquals(service.getString(R.string.status_paused), status(returned))
        advance(ROTATION_SETTLE_MS)
        assertTrue("rotation never chooses", choiceShown(returned))
        assertEquals(0, input.releases.get())
        assertEquals("still paused: no second platform start", 1, input.starts.get())
        safe(returned).performClick()
        assertEquals(2, input.starts.get())
        assertEquals(service.getString(R.string.status_recording), status(returned))
        feed(input, 20000, 2)
        button(returned).performClick()
        await { input.releases.get() == 1 }
        await { decode.drain(); settleHistory(); commits == 1 }
        assertEquals(listOf("old speech private speech"), next.inserted)
        assertNoWindow()
    }

    @Test fun recordingChoiceRenderedAgainAfterARotationStopInsertsOnceThere() {
        DictationImeService.requestPanel = {}
        val input = Input()
        devices.add(input)
        val (service, view) = service(editor())
        val first = firstField.getValue(service)
        feed(input, 1000, 1)
        back(view).performClick()
        val (next, returned) = rotateToRecreatedEditor(service)
        assertEquals(0, input.releases.get())
        advance(ROTATION_SETTLE_MS)
        stopAction(returned).performClick()
        await { input.releases.get() == 1 }
        await { decode.drain(); settleHistory(); commits == 1 }
        advance(ROTATION_HOLD_GAP_MS)
        settleHistory()
        assertEquals(listOf("old speech"), next.inserted)
        assertTrue(first.inserted.isEmpty())
        assertEquals(1, input.releases.get())
        assertEquals(HistoryDelivery.ACCEPTED, rows().single().delivery)
    }

    @Test fun recordingChoiceRenderedAgainAfterARotationDiscardRemovesSavedTextOnce() {
        DictationImeService.requestPanel = {}
        val input = Input()
        devices.add(input)
        val (service, view) = service(editor())
        feed(input, 1000, 1)
        decode.drain(); settleHistory()
        assertEquals(1, rows().size)
        back(view).performClick()
        val (_, returned) = rotateToRecreatedEditor(service)
        settleHistory()
        assertEquals("rotation never selects Discard", 1, rows().size)
        advance(ROTATION_SETTLE_MS)
        discardAction(returned).performClick()
        await { input.releases.get() == 1 }
        decode.drain(); settleHistory()
        assertTrue(rows().isEmpty())
        assertEquals(0, commits)
    }

    /** Stop, Back (finishing choice open), rotate; the result decodes under the open choice. */
    private fun finishingChoiceThroughARotation(): Triple<DictationImeService, Field, View> {
        DictationImeService.requestPanel = {}
        val input = Input()
        devices.add(input)
        val (service, view) = service(editor())
        feed(input, 1000, 1)
        button(view).performClick()
        await { input.releases.get() == 1 }
        back(view).performClick()
        assertTrue(choiceShown(view))
        assertEquals(View.GONE, stopAction(view).visibility)
        val (next, returned) = rotateToRecreatedEditor(service)
        assertTrue(choiceShown(returned))
        assertEquals(service.getString(R.string.status_finishing_choice), status(returned))
        decode.drain(); settleHistory(); decode.drain(); settleHistory()
        assertEquals(0, commits)
        advance(ROTATION_SETTLE_MS)
        settleHistory()
        return Triple(service, next, returned)
    }

    @Test fun finishingChoiceOpenThroughARotationHoldsTheResultUntilKept() {
        val (_, next, returned) = finishingChoiceThroughARotation()
        assertTrue("rotation never chooses", choiceShown(returned))
        assertEquals("a result decoded under the choice waits for it", 0, commits)
        safe(returned).performClick()
        await { settleHistory(); commits == 1 }
        assertEquals(listOf("old speech"), next.inserted)
        assertEquals(HistoryDelivery.ACCEPTED, rows().single().delivery)
        assertNoWindow()
    }

    @Test fun finishingChoiceRenderedAgainAfterARotationDiscardRemovesSavedTextOnce() {
        val (_, next, returned) = finishingChoiceThroughARotation()
        discardAction(returned).performClick()
        settleHistory()
        advance(ROTATION_HOLD_GAP_MS)
        decode.drain(); settleHistory()
        assertEquals(0, commits)
        assertTrue(next.inserted.isEmpty())
        assertTrue(rows().isEmpty())
    }

    @Test fun lockDuringARotationOverridesAnOpenChoiceAndGoesToHistory() {
        DictationImeService.requestPanel = {}
        val input = Input()
        devices.add(input)
        val (service, view) = service(editor())
        feed(input, 1000, 1)
        button(view).performClick()
        await { input.releases.get() == 1 }
        back(view).performClick()
        rotateHidden(service, android.view.Surface.ROTATION_90)
        screenOff()
        awaitInterruptedInHistory()
        val returned = showPanel(service)!!
        advance(ROTATION_SETTLE_MS)
        decode.drain(); settleHistory()
        assertFalse("no choice comes back after a lock", choiceShown(returned))
        assertEquals(0, commits)
    }

    /** Brightest ink the panel's ring draws now; the ring is laid out by the caller. */
    private fun ringAlpha(panel: View): Int {
        val ring = panel.findViewById<RecordingRingView>(R.id.recording_ring)
        val bitmap = Bitmap.createBitmap(ring.width, ring.height, Bitmap.Config.ARGB_8888)
        return try {
            ring.draw(Canvas(bitmap))
            val pixels = IntArray(ring.width * ring.height)
            bitmap.getPixels(pixels, 0, ring.width, 0, 0, ring.width, ring.height)
            pixels.maxOf { Color.alpha(it) }
        } finally { bitmap.recycle() }
    }

    private fun layOut(panel: View, widthDp: Int) {
        val width = (widthDp * panel.resources.displayMetrics.density).toInt()
        panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        panel.layout(0, 0, panel.measuredWidth, panel.measuredHeight)
    }

    /**
     * After a Stop-wait rotation the returned landscape panel reads as finishing, not recording,
     * in a still frame (animations off), at fontScale 2, with Back and the microphone reachable.
     */
    private fun returnedFinishingPanelIsDistinctWithoutAnimation() {
        RuntimeEnvironment.setFontScale(2f)
        val durationScale = android.animation.ValueAnimator::class.java.getMethod("setDurationScale", Float::class.javaPrimitiveType)
        durationScale.invoke(null, 0f)
        try {
            DictationImeService.requestPanel = {}
            val input = Input()
            devices.add(input)
            val (service, view) = service(editor())
            feed(input, 1000, 1)
            layOut(view, 891)
            val recordingRing = ringAlpha(view)
            button(view).performClick()
            await { input.releases.get() == 1 }
            platformResetOnRotation(service, android.view.Surface.ROTATION_90)
            recreatedEditorTakesInput(service)
            val returned = showPanel(service)!!
            assertEquals(android.content.res.Configuration.ORIENTATION_LANDSCAPE, returned.resources.configuration.orientation)
            assertEquals(2f, returned.resources.configuration.fontScale)
            layOut(returned, 891)
            val density = returned.resources.displayMetrics.density
            val status = returned.findViewById<TextView>(R.id.status)
            assertEquals(service.getString(R.string.status_finishing), status.text.toString())
            assertTrue("status clipped", status.height >= status.layout.height + status.compoundPaddingTop + status.compoundPaddingBottom)
            val mic = button(returned)
            assertFalse(mic.isEnabled)
            // Preparing text… (#35/#36): no disk and no ring; the arc says it, segmented when still.
            assertEquals("a preparing panel shows no microphone disk", View.INVISIBLE, mic.visibility)
            assertEquals(View.INVISIBLE, returned.findViewById<View>(R.id.recording_ring).visibility)
            val arc = returned.findViewById<PreparingArcView>(R.id.processing)
            assertEquals(View.VISIBLE, arc.visibility)
            arc.draw(Canvas(Bitmap.createBitmap(arc.width, arc.height, Bitmap.Config.ARGB_8888)))
            assertTrue("with animations off the arc is a static segmented ring", arc.segmented)
            assertEquals(service.getString(R.string.status_finishing), mic.contentDescription.toString())
            assertTrue("recording ring not drawn", recordingRing > 200)
            val whole = android.graphics.Rect(0, 0, returned.width, returned.height)
            for (target in listOf(returned.findViewById<View>(R.id.back), arc)) {
                assertTrue(target.width >= 48 * density && target.height >= 48 * density)
                val bounds = android.graphics.Rect(0, 0, target.width, target.height)
                (returned as android.view.ViewGroup).offsetDescendantRectToMyCoords(target, bounds)
                assertTrue("action outside the panel", whole.contains(bounds))
            }
            decode.drain(); settleHistory()
            advance(ROTATION_SETTLE_MS)
            await { decode.drain(); settleHistory(); commits == 1 }
        } finally { durationScale.invoke(null, 1f) }
    }

    @Test @Config(qualifiers = "w411dp-h891dp-port") @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun returnedFinishingPanelIsDistinctWithoutAnimationInLight() = returnedFinishingPanelIsDistinctWithoutAnimation()

    @Test @Config(qualifiers = "w411dp-h891dp-port-night") @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun returnedFinishingPanelIsDistinctWithoutAnimationInDark() = returnedFinishingPanelIsDistinctWithoutAnimation()

    /** A narrow landscape window (320dp, fontScale 2): finishing still reads and both actions fit. */
    @Test @Config(qualifiers = "w320dp-h320dp-land") @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun narrowLandscapePanelKeepsFinishingStatusAndActions() {
        RuntimeEnvironment.setFontScale(2f)
        val input = Input()
        devices.add(input)
        val (service, view) = service(editor())
        feed(input, 1000, 1)
        button(view).performClick()
        layOut(view, 320)
        val status = view.findViewById<TextView>(R.id.status)
        assertEquals(service.getString(R.string.status_finishing), status.text.toString())
        assertTrue("status squeezed to ${status.width}px", status.width >= status.paint.measureText("Finishing") / 2)
        assertTrue("status clipped", status.height >= status.layout.height + status.compoundPaddingTop + status.compoundPaddingBottom)
        val preview = view.findViewById<TextView>(R.id.preview)
        assertTrue("preview squeezed", preview.width >= 64 * view.resources.displayMetrics.density)
        val whole = android.graphics.Rect(0, 0, view.width, view.height)
        for (target in listOf(view.findViewById<View>(R.id.back), button(view), view.findViewById<View>(R.id.recording_ring))) {
            val bounds = android.graphics.Rect(0, 0, target.width, target.height)
            (view as android.view.ViewGroup).offsetDescendantRectToMyCoords(target, bounds)
            assertTrue("${target.id} outside the panel", whole.contains(bounds))
        }
        assertTrue(button(view).width >= 48 * view.resources.displayMetrics.density)
        await { decode.drain(); settleHistory(); commits == 1 }
    }

    @Test fun rotationTraceRecordsDisplayRotationAndChangesAtEveryCallback() {
        val app = RuntimeEnvironment.getApplication()
        val file = File(app.filesDir, "lab/rotation-probe.jsonl")
        file.delete()
        RotationTrace.beginProbe("app-capture-rotation")
        try {
            val input = Input()
            devices.add(input)
            val (service, view) = service(editor())
            feed(input, 1000, 1)
            val display = app.getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(android.view.Display.DEFAULT_DISPLAY)
            shadowOf(display).setRotation(android.view.Surface.ROTATION_90)
            val landscape = rotate(service, "+land")
            service.onWindowShown()
            button(landscape).performClick()
            await { input.releases.get() == 1 }
            await { decode.drain(); commits == 1 }
            settleHistory()
            service.onFinishInputView(true)
            service.onFinishInput()
            val controller = opened.single { it.get() === service }
            opened.remove(controller)
            controller.destroy()
            RotationTrace.abortProbe()
            val rows = file.readLines().map { org.json.JSONObject(it) }
            fun row(event: RotationEvent) = rows.first { it.getString("event") == event.name }
            val lifecycle = setOf(RotationEvent.SERVICE_CREATE, RotationEvent.SERVICE_DESTROY, RotationEvent.INPUT_START,
                RotationEvent.INPUT_VIEW_START, RotationEvent.INPUT_VIEW_FINISH, RotationEvent.INPUT_FINISH,
                RotationEvent.CONFIGURATION, RotationEvent.CONFIGURATION_DONE, RotationEvent.WINDOW_SHOWN,
                RotationEvent.VIEW_CREATE, RotationEvent.CAPTURE_START).map { it.name }.toSet()
            assertEquals(lifecycle, rows.map { it.getString("event") }.filter { it in lifecycle }.toSet())
            for (r in rows.filter { it.getString("event") in lifecycle }) {
                assertTrue("rotation missing on $r", r.has("rotation") && r.has("rotations"))
            }
            assertEquals(0, row(RotationEvent.CAPTURE_START).getInt("rotation"))
            assertEquals(0L, row(RotationEvent.CAPTURE_START).getLong("rotations"))
            assertEquals(1, row(RotationEvent.CONFIGURATION).getInt("rotation"))
            assertEquals(1L, row(RotationEvent.CONFIGURATION).getLong("rotations"))
            assertEquals(1L, row(RotationEvent.SERVICE_DESTROY).getLong("rotations"))
            val allowed = setOf("protocol", "process", "service", "sequence", "ns", "event", "case", "run",
                "active", "authority", "flag", "view", "take", "rotation", "rotations", "configDiff", "handledConfig", "retention",
                "sameField", "typeNull", "selValid", "connectionChanged", "verified", "check")
            assertTrue(rows.all { allowed.containsAll(it.keys().asSequence().toSet()) })
            // Only the fixed retention class, and only where input starts.
            assertEquals("ELIGIBLE", row(RotationEvent.INPUT_START_DONE).getString("retention"))
            assertTrue(rows.filter { it.has("retention") }.all { it.getString("event") == RotationEvent.INPUT_START_DONE.name &&
                it.getString("retention") in EditorRetention.entries.map { e -> e.name } })
        } finally { RotationTrace.abortProbe() }
    }

    /** Device trace 2026-11-11: after a take ended, the idle panel's Back left our IME selected. */
    @Test fun idleBackWithoutTakeHandsBackToKeyboard() {
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.RECORD_AUDIO)
        val (_, view) = service(editor())
        org.robolectric.shadows.ShadowLog.clear()
        view.findViewById<ImageButton>(R.id.back).performClick()
        pump()
        // Robolectric has no previous IME, so the hand-back attempt falls through to hiding.
        assertTrue(org.robolectric.shadows.ShadowLog.getLogsForTag("DictationIme").any { it.msg.contains("no previous IME") })
    }

    /**
     * #61 owner decision: Cancel while recording pauses the take. The platform recording stops
     * (no audio, no microphone indicator), the microphone stays owned by this take (never a
     * second one), and Continue resumes the same take: one take, speech before and after.
     */
    @Test fun recordingCancelPausesAndContinueResumesTheSameTake() {
        val input = Input()
        devices.add(input)
        val (service, view) = service(editor())
        feed(input, 1000, 1)
        decode.drain(); settleHistory()
        val originalId = rows().single().id
        back(view).performClick()
        assertTrue(choiceShown(view))
        assertEquals(service.getString(R.string.status_paused), status(view))
        assertTrue("the safe action has focus", safe(view).isFocused)
        assertEquals(service.getString(R.string.continue_recording), safe(view).contentDescription.toString())
        assertEquals(View.VISIBLE, stopAction(view).visibility)
        assertEquals(1, input.stops.get())
        assertEquals(0, input.releases.get())
        assertTrue(MicrophoneOwner.state().occupied)
        assertFalse(button(view).isEnabled)
        val acceptedWhilePaused = accepted.get()
        input.feed(20000) // Speech while paused is not captured.
        pump(); Thread.sleep(50); pump()
        assertEquals(acceptedWhilePaused, accepted.get())
        safe(view).performClick()
        assertFalse(choiceShown(view))
        assertEquals(2, input.starts.get())
        assertEquals(service.getString(R.string.status_recording), status(view))
        assertTrue(button(view).isEnabled)
        feed(input, 20000, 2)
        decode.drain(); settleHistory()
        assertEquals(originalId, rows().single().id)
        button(view).performClick()
        await { input.releases.get() == 1 }
        await { decode.drain(); settleHistory(); commits == 1 }
        assertEquals(listOf("old speech private speech"), firstField.getValue(service).inserted)
        assertEquals(originalId, rows().single().id)
        assertEquals(1, input.releases.get())
        assertNoWindow()
    }

    @Test fun systemBackAndTheBackArrowOnAnOpenChoiceContinueRecording() {
        val input = Input()
        devices.add(input)
        val (service, view) = service(editor())
        feed(input, 1000, 1)
        assertTrue(systemBack(service))
        assertTrue(choiceShown(view))
        assertEquals(service.getString(R.string.continue_recording), back(view).contentDescription.toString())
        assertTrue(systemBack(service))
        assertFalse("Back is the safe default", choiceShown(view))
        assertEquals(2, input.starts.get())
        back(view).performClick()
        assertTrue(choiceShown(view))
        back(view).performClick()
        assertFalse(choiceShown(view))
        assertEquals(3, input.starts.get())
        assertEquals(0, input.releases.get())
        feed(input, 20000, 2)
        decode.drain(); settleHistory()
        assertEquals(0, commits)
        assertEquals(1, rows().size)
    }

    @Test fun panelRecreationKeepsTheOpenChoiceAndThePause() {
        val input = Input()
        devices.add(input)
        val (service, view) = service(editor())
        feed(input, 1000, 1)
        back(view).performClick()
        val again = service.onCreateInputView()
        assertTrue(choiceShown(again))
        assertEquals(service.getString(R.string.status_paused), status(again))
        assertEquals(1, input.starts.get())
        safe(again).performClick()
        assertEquals(2, input.starts.get())
        assertEquals(0, input.releases.get())
    }

    /** #61 owner decision: Stop and insert on the choice is exactly Stop (one insertion, FUTO back). */
    @Test fun recordingChoiceStopInsertsOnceLikeStop() {
        val input = Input()
        devices.add(input)
        val (service, view) = service(editor())
        feed(input, 1000, 1)
        decode.drain(); settleHistory()
        back(view).performClick()
        stopAction(view).performClick()
        assertFalse(choiceShown(view))
        assertEquals(service.getString(R.string.status_finishing), status(view))
        await { input.releases.get() == 1 }
        await { decode.drain(); settleHistory(); commits == 1 }
        assertEquals(listOf("old speech"), firstField.getValue(service).inserted)
        assertEquals(HistoryDelivery.ACCEPTED, rows().single().delivery)
        assertEquals(1, input.releases.get())
    }

    /**
     * Signal order (#61 visit, A7): closing the choice *dialog* made Signal restart input within
     * 5 ms, which revoked Stop's authority. The inline choice opens no window, so nothing restarts
     * input: the Signal sequence after Stop is only our own hand-back, and the text is inserted.
     */
    @Test fun signalOrderStopFromTheChoiceInsertsBecauseNoWindowRestartsInput() {
        val input = Input()
        devices.add(input)
        val (service, view) = service(editor())
        feed(input, 1000, 1)
        back(view).performClick()
        assertNoWindow()
        stopAction(view).performClick()
        assertNoWindow()
        await { input.releases.get() == 1 }
        await { decode.drain(); settleHistory(); commits == 1 }
        assertEquals(listOf("old speech"), firstField.getValue(service).inserted)
    }

    /**
     * An editor that restarts input by itself right after Stop, without a rotation, is a session
     * change like any other: the result goes to History only, never inserted (spec #36).
     */
    @Test fun editorRestartingInputByItselfRightAfterStopGoesToHistoryOnly() {
        val input = Input()
        devices.add(input)
        val (service, view) = service(editor())
        feed(input, 1000, 1)
        back(view).performClick()
        stopAction(view).performClick()
        service.onFinishInputView(true)
        service.onFinishInput()
        service.onStartInput(editor(), true)
        service.onStartInputView(editor(), true)
        await { input.releases.get() == 1 }
        awaitInterruptedInHistory()
        assertEquals(0, commits)
        assertTrue(status(view).startsWith(service.getString(R.string.status_unable_insert)))
    }

    // --- Discard take (#36, owner 2026-11-11): final at once, from either choice; no Undo window. ---

    /** Records one checkpoint, opens the paused choice and discards. */
    private fun discardedWhilePaused(info: EditorInfo = editor(), drainFirst: Boolean = true): Triple<DictationImeService, View, Input> {
        val input = Input()
        devices.add(input)
        val (service, view) = service(info)
        feed(input, 1000, 1)
        if (drainFirst) { decode.drain(); settleHistory() }
        back(view).performClick()
        discardAction(view).performClick()
        return Triple(service, view, input)
    }

    /** The discard is final: capture released, saved text gone, nothing inserted. */
    private fun assertDiscardFinal(input: Input) {
        await { input.releases.get() == 1 }
        decode.drain(); settleHistory(); decode.drain(); settleHistory()
        assertTrue("a discard leaves nothing in History", rows().isEmpty())
        assertEquals(0, commits)
    }

    /** The idle panel, microphone ready, on the panel itself (no hand-back to the keyboard). */
    private fun assertIdlePanel(service: DictationImeService, view: View) {
        assertFalse("no choice after Discard", choiceShown(view))
        assertEquals(service.getString(R.string.status_idle), status(view))
        assertEquals("", preview(view))
        assertEquals(View.VISIBLE, button(view).visibility)
        assertTrue("the microphone is ready for a new take", button(view).isEnabled)
        assertEquals(service.getString(R.string.start_recording), button(view).contentDescription.toString())
        assertEquals(service.getString(R.string.back_to_keyboard), back(view).contentDescription.toString())
    }

    @Test fun discardFromThePausedChoiceIsFinalAtOnceAndLeavesTheIdlePanel() {
        org.robolectric.shadows.ShadowLog.clear()
        val (service, view, input) = discardedWhilePaused()
        assertDiscardFinal(input)
        assertIdlePanel(service, view)
        assertFalse("the panel stays", org.robolectric.shadows.ShadowLog.getLogsForTag("DictationIme").any { it.msg.contains("no previous IME") })
        assertEquals("no new take by itself", 1, input.starts.get())
        assertNoWindow()
    }

    @Test fun discardFromTheFinishingChoiceIsFinalAtOnceAndLeavesTheIdlePanel() {
        val input = Input()
        devices.add(input)
        val (service, view) = service(editor())
        feed(input, 1000, 1)
        button(view).performClick()
        await { input.releases.get() == 1 }
        back(view).performClick()
        assertEquals(service.getString(R.string.status_finishing_choice), status(view))
        discardAction(view).performClick()
        assertDiscardFinal(input)
        assertIdlePanel(service, view)
        advance(10_000)
        decode.drain(); settleHistory()
        assertEquals(0, commits)
        assertTrue(rows().isEmpty())
    }

    @Test fun lateDecoderOutputAfterADiscardChangesNothing() {
        val (service, view, input) = discardedWhilePaused(drainFirst = false)
        await { input.releases.get() == 1 }
        decode.drain(); settleHistory(); decode.drain(); settleHistory()
        assertTrue("late output cannot list it", rows().isEmpty())
        assertEquals("late output is never inserted", 0, commits)
        assertIdlePanel(service, view)
    }

    @Test fun rotationRightAfterADiscardLeavesTheIdlePanel() {
        DictationImeService.requestPanel = {}
        val (service, _, input) = discardedWhilePaused()
        val (next, returned) = rotateToRecreatedEditor(service)
        advance(ROTATION_SETTLE_MS)
        assertDiscardFinal(input)
        assertIdlePanel(service, returned)
        assertTrue(next.inserted.isEmpty())
        assertEquals("no new take by itself", 1, input.starts.get())
    }

    @Test fun discardInAPrivateFieldIsFinalAtOnceAndKeepsThePrivateLine() {
        val (service, view, input) = discardedWhilePaused(editor(privateField = true))
        assertDiscardFinal(input)
        assertIdlePanel(service, view)
        val notice = view.findViewById<TextView>(R.id.retention_notice)
        assertEquals(View.VISIBLE, notice.visibility)
        assertEquals(service.getString(R.string.retention_field_off), notice.text.toString())
    }

    @Test fun recordingChoiceDiscardRemovesCheckpointsAndNeverInserts() {
        val input = Input()
        devices.add(input)
        val (_, view) = service(editor())
        feed(input, 1000, 1)
        decode.drain(); settleHistory()
        assertEquals(1, rows().size)
        back(view).performClick()
        discardAction(view).performClick()
        await { input.releases.get() == 1 }
        decode.drain(); settleHistory()
        assertTrue(rows().isEmpty())
        assertEquals(0, commits)
        assertFalse(choiceShown(view))
    }

    /** A system interruption overrides an open choice; audio captured before the pause goes to History. */
    @Test fun hideOverridesAnOpenRecordingChoiceAndKeepsPausedAudioInHistory() {
        val input = Input()
        devices.add(input)
        val (service, view) = service(editor())
        feed(input, 1000, 1)
        back(view).performClick()
        service.onFinishInputView(true)
        assertFalse(choiceShown(view))
        await { input.releases.get() == 1 }
        awaitInterruptedInHistory()
        assertEquals("old speech", rows().single().text)
        assertEquals(0, commits)
    }

    @Test fun lockWhilePausedStopsAndDoesNotResumeOnUnlock() {
        val input = Input()
        devices.add(input)
        val (service, view) = service(editor())
        feed(input, 1000, 1)
        back(view).performClick()
        screenOff()
        await { input.releases.get() == 1 }
        awaitInterruptedInHistory()
        service.onStartInput(editor(), true)
        service.onStartInputView(editor(), true)
        pump()
        assertEquals(1, input.starts.get())
        assertFalse(choiceShown(view))
        assertEquals(0, commits)
    }

    /**
     * P1 (#61 visit), #36: an interrupted take must not leave a silent idle panel with old text. The
     * panel says "Unable to insert text" with its marker, shows the held text and offers Copy and
     * Discard text; the first Back warns and the second discards and leaves; Discard text returns to the microphone.
     */
    @Test fun historyOnlyEndSaysUnableToInsertWithItsMarkerAndBackLeaves() {
        val input = Input()
        devices.add(input)
        val (service, view) = service(editor())
        feed(input, 1000, 1)
        decode.drain(); pump()
        assertEquals("old speech", preview(view))
        service.onStartInput(editor(), true) // Same-field restart: interruption.
        service.onStartInputView(editor(), true)
        await { input.releases.get() == 1 }
        awaitInterruptedInHistory()
        await { status(view).startsWith(service.getString(R.string.status_unable_insert)) }
        assertEquals("old speech", preview(view))
        val marker = view.findViewById<View>(R.id.status_marker)
        assertEquals(View.VISIBLE, marker.visibility)
        // The mark reads with the words, directly after them; at the far edge it reads as a button.
        layOut(view, 411)
        val statusView = view.findViewById<TextView>(R.id.status)
        val statusBounds = android.graphics.Rect(0, 0, statusView.width, statusView.height)
        val markerBounds = android.graphics.Rect(0, 0, marker.width, marker.height)
        (view as android.view.ViewGroup).offsetDescendantRectToMyCoords(statusView, statusBounds)
        view.offsetDescendantRectToMyCoords(marker, markerBounds)
        val textEnd = statusBounds.left + statusView.totalPaddingLeft + statusView.layout.getLineWidth(0)
        assertTrue("mark ${markerBounds.left - textEnd}px after the words", markerBounds.left - textEnd <= 12 * view.resources.displayMetrics.density)
        assertTrue("mark beside the words", markerBounds.centerY() in statusBounds.top..statusBounds.bottom)
        assertNotEquals("no microphone while the text is held", View.VISIBLE, button(view).visibility)
        assertEquals(service.getString(R.string.back_to_keyboard), back(view).contentDescription.toString())
        back(view).performClick() // Uncopied text: the first Back only warns.
        assertEquals(service.getString(R.string.status_back_warning), status(view))
        org.robolectric.shadows.ShadowLog.clear()
        back(view).performClick() // The second Back discards the text and leaves.
        pump()
        assertTrue(org.robolectric.shadows.ShadowLog.getLogsForTag("DictationIme").any { it.msg.contains("no previous IME") })
        assertEquals(1, input.starts.get())
        assertEquals(0, commits)
    }

    @Test fun finishingChoiceHoldsDecodedTextUntilKept() {
        val input = Input()
        devices.add(input)
        val (service, view) = service(editor())
        feed(input, 1000, 1)
        button(view).performClick()
        await { input.releases.get() == 1 }
        back(view).performClick()
        assertTrue(choiceShown(view))
        assertEquals(service.getString(R.string.keep_finishing), safe(view).contentDescription.toString())
        assertTrue(safe(view).isFocused)
        decode.drain(); settleHistory()
        assertEquals(0, commits)
        safe(view).performClick()
        settleHistory()
        assertEquals(1, commits)
        assertNoWindow()
    }

    @Test fun systemBackOnTheFinishingChoiceKeepsFinishing() {
        val input = Input()
        devices.add(input)
        val (service, view) = service(editor())
        feed(input, 1000, 1)
        button(view).performClick()
        await { input.releases.get() == 1 }
        assertTrue(systemBack(service))
        decode.drain(); settleHistory()
        assertEquals(0, commits)
        assertTrue(systemBack(service))
        settleHistory()
        assertEquals(1, commits)
    }

    @Test fun finishingChoiceDiscardSuppressesLateOutput() {
        val input = Input()
        devices.add(input)
        val (_, view) = service(editor())
        feed(input, 1000, 1)
        button(view).performClick()
        await { input.releases.get() == 1 }
        back(view).performClick()
        decode.drain(); settleHistory()
        assertEquals(0, commits)
        discardAction(view).performClick()
        settleHistory()
        assertEquals(0, commits)
        assertTrue(rows().isEmpty())
    }

    /**
     * The choice at fontScale 2 in the recording panel: every action ≥ 48dp, inside the panel and
     * not truncated; the safe action first and focused; Discard destructive and apart. Portrait
     * keeps the panel height of the recording state.
     */
    private fun choiceFitsAtLargeText(widthDp: Int, finishingToo: Boolean = true) {
        RuntimeEnvironment.setFontScale(2f)
        val input = Input()
        devices.add(input)
        val (service, view) = service(editor())
        feed(input, 1000, 1)
        layOut(view, widthDp)
        val recordingHeight = view.height
        back(view).performClick()
        layOut(view, widthDp)
        val density = view.resources.displayMetrics.density
        fun check(actions: List<ImageButton>) {
            val whole = android.graphics.Rect(0, 0, view.width, view.height)
            val tops = mutableSetOf<Int>()
            for (action in actions) {
                val name = action.contentDescription
                assertTrue("$name below 52dp", action.height >= 52 * density && action.width >= 52 * density)
                val bounds = android.graphics.Rect(0, 0, action.width, action.height)
                (view as android.view.ViewGroup).offsetDescendantRectToMyCoords(action, bounds)
                assertTrue("$name outside the panel", whole.contains(bounds))
                assertFalse("icon-only buttons still carry their words", name.isNullOrBlank())
                assertEquals("$name tooltip", name.toString(), action.tooltipText?.toString())
                tops.add(bounds.top)
            }
            assertEquals("one row at one height", 1, tops.size)
            val safeBounds = android.graphics.Rect(0, 0, safe(view).width, safe(view).height)
            val discardBounds = android.graphics.Rect(0, 0, discardAction(view).width, discardAction(view).height)
            (view as android.view.ViewGroup).offsetDescendantRectToMyCoords(safe(view), safeBounds)
            view.offsetDescendantRectToMyCoords(discardAction(view), discardBounds)
            val apart = maxOf(discardBounds.left - safeBounds.right, discardBounds.top - safeBounds.bottom)
            assertTrue("Discard sits next to the safe action", apart >= 16 * density)
            assertTrue("Discard sits 40dp apart", apart >= 40 * density)
            assertNotSame("Discard has its own (dashed) edge", safe(view).background.constantState, discardAction(view).background.constantState)
            assertTrue(safe(view).isFocused)
        }
        check(listOf(safe(view), stopAction(view), discardAction(view)))
        assertEquals("Continue recording plays", R.drawable.ic_play_arrow, shadowOf(safe(view).drawable).createdFromResId)
        assertEquals("the choice keeps the panel height", recordingHeight, view.height)
        if (!finishingToo) return
        stopAction(view).performClick()
        await { input.releases.get() == 1 }
        back(view).performClick()
        layOut(view, widthDp)
        check(listOf(safe(view), discardAction(view)))
        assertEquals("Keep preparing text shows preparing, not play", R.drawable.ic_marker_preparing,
            shadowOf(safe(view).drawable).createdFromResId)
        safe(view).performClick()
        await { decode.drain(); settleHistory(); commits == 1 }
    }

    @Test @Config(qualifiers = "w411dp-h891dp-port") @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun choiceFitsAtLargeTextInPortrait() = choiceFitsAtLargeText(411)

    @Test @Config(qualifiers = "w411dp-h891dp-port-night") @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun choiceFitsAtLargeTextInPortraitDark() = choiceFitsAtLargeText(411)

    /** Pixel 10 Pro XL landscape window. */
    @Test @Config(qualifiers = "w960dp-h440dp-land") @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun choiceFitsAtLargeTextInLandscape() = choiceFitsAtLargeText(960)

    @Test @Config(qualifiers = "w960dp-h440dp-land-night") @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun choiceFitsAtLargeTextInLandscapeDark() = choiceFitsAtLargeText(960)

    /**
     * Private field, landscape, font 2× (#36 critique): the lock line costs the editor almost no
     * height (the preview yields a line to it), and the lock glyph grows with the text.
     */
    @Test @Config(qualifiers = "w960dp-h440dp-land") @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun privateFieldLineStaysCompactInLandscapeAtLargeText() {
        RuntimeEnvironment.setFontScale(2f)
        val input = Input()
        devices.add(input)
        val (_, view) = service(editor(privateField = true))
        feed(input, 1000, 1)
        decode.drain(); pump()
        val density = view.resources.displayMetrics.density
        val notice = view.findViewById<TextView>(R.id.retention_notice)
        layOut(view, 960)
        assertEquals(View.VISIBLE, notice.visibility)
        val withLine = view.height
        val lock = notice.compoundDrawablesRelative[0]
        assertTrue("lock ${lock.bounds.height() / density}dp does not scale with the text", lock.bounds.height() >= 24 * density)
        assertEquals("lock keeps the label's proportion", notice.textSize * 16 / 14, lock.bounds.height().toFloat(), 1.5f)
        PanelDisclosure.bind(notice, null)
        layOut(view, 960)
        assertTrue("private line adds ${(withLine - view.height) / density}dp", withLine - view.height <= 4 * density)
        PanelDisclosure.bind(notice, R.string.retention_field_off)
        button(view).performClick()
        await { decode.drain(); settleHistory(); commits == 1 }
    }

    private val longPreview = "zwei Hokkaido, eine Zwiebel, ein Stück Ingwer und Kokosmilch. And please bring fresh coriander on " +
        "Saturday, because the market closes early and we still need lime, garlic and a bunch of spring onions for the soup."

    /** Landscape preview (#36 critique P3): lines stop near 70 characters instead of running across the panel. */
    @Test @Config(qualifiers = "w960dp-h440dp-land") @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun landscapePreviewKeepsAReadableMeasure() {
        val input = Input()
        devices.add(input)
        val (_, view) = service(editor())
        feed(input, 1000, 1)
        decode.drain(); pump()
        val preview = view.findViewById<TextView>(R.id.preview)
        preview.text = longPreview
        layOut(view, 960)
        val layout = preview.layout
        val longest = (0 until layout.lineCount).maxOf { layout.getLineEnd(it) - layout.getLineStart(it) }
        assertTrue("longest preview line has $longest characters", longest <= 75)
        button(view).performClick()
        await { decode.drain(); settleHistory(); commits == 1 }
    }

    /** Landscape at font 2× (#36 critique P3): the last preview line keeps clear room above the panel's bottom edge. */
    @Test @Config(qualifiers = "w960dp-h440dp-land") @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun landscapePreviewKeepsRoomBelowAtLargeText() {
        RuntimeEnvironment.setFontScale(2f)
        val input = Input()
        devices.add(input)
        val (_, view) = service(editor())
        feed(input, 1000, 1)
        decode.drain(); pump()
        val preview = view.findViewById<TextView>(R.id.preview)
        preview.text = longPreview
        layOut(view, 960)
        val bounds = android.graphics.Rect(0, 0, preview.width, preview.height)
        (view as android.view.ViewGroup).offsetDescendantRectToMyCoords(preview, bounds)
        val lastLine = bounds.top + preview.totalPaddingTop + preview.layout.getLineBottom(preview.layout.lineCount - 1)
        val density = view.resources.displayMetrics.density
        assertTrue("last line ${(view.height - lastLine) / density}dp above the bottom edge", view.height - lastLine >= 16 * density)
        button(view).performClick()
        await { decode.drain(); settleHistory(); commits == 1 }
    }

    /**
     * Landscape on the phone (#36 critique P1): the choice sits in the stage under the
     * right thumb, where the microphone was; the preview stays readable while Stop or Discard is
     * decided, and the panel keeps its height in every state.
     */
    @Test @Config(qualifiers = "w960dp-h440dp-land") @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun landscapeChoiceTakesTheStageAndKeepsThePreview() {
        val input = Input()
        devices.add(input)
        val (_, view) = service(editor())
        feed(input, 1000, 1)
        decode.drain(); pump()
        layOut(view, 960)
        val height = view.height
        val words = preview(view)
        assertEquals("old speech", words)
        fun inStage(id: Int) {
            val stage = android.graphics.Rect(0, 0, view.findViewById<View>(R.id.stage).width, view.findViewById<View>(R.id.stage).height)
            (view as android.view.ViewGroup).offsetDescendantRectToMyCoords(view.findViewById(R.id.stage), stage)
            val target = view.findViewById<View>(id)
            val bounds = android.graphics.Rect(0, 0, target.width, target.height)
            view.offsetDescendantRectToMyCoords(target, bounds)
            assertTrue("${view.resources.getResourceEntryName(id)} $bounds not in the stage $stage", stage.contains(bounds))
        }
        back(view).performClick()
        layOut(view, 960)
        assertTrue(choiceShown(view))
        inStage(R.id.choice)
        assertEquals("the preview stays during the choice", View.VISIBLE, view.findViewById<View>(R.id.preview).visibility)
        assertEquals(words, preview(view))
        assertEquals("the choice keeps the panel height", height, view.height)
        stopAction(view).performClick()
        await { decode.drain(); settleHistory(); commits == 1 }
        layOut(view, 960)
        assertEquals("the idle panel keeps the panel height", height, view.height)
    }

    @Test fun destroyedServiceReleaseFailureCanBeRetriedBeforePrivateNewTake() {
        val gate = CountDownLatch(1)
        val original = Input(failedReleases = 1, releaseGate = gate)
        val next = Input()
        devices.add(original); devices.add(next)
        val (old, oldView) = service(editor())
        feed(original, 1000, 1)
        decode.drain(); settleHistory()
        assertEquals("old speech", preview(oldView))
        val saved = rows().single()
        old.onFinishInputView(true)
        assertEquals("", preview(oldView))
        opened.removeAt(0).destroy()
        gate.countDown()
        await { MicrophoneOwner.state().releaseFailed }
        assertEquals(1, original.starts.get())
        assertThrows(IllegalStateException::class.java) { runtime.close() }
        val (replacement, view) = service(editor(privateField = true))
        await { status(view) == replacement.getString(R.string.status_release_failed) }
        assertEquals(0, next.starts.get())
        button(view).performClick()
        await { !MicrophoneOwner.state().occupied }
        assertEquals(0, next.starts.get())
        assertEquals(0, commits)
        button(view).performClick()
        await { next.starts.get() == 1 }
        feed(next, 20000, 2)
        decode.drain(); settleHistory()
        assertEquals("private speech", preview(view))
        assertEquals(listOf(saved.id), rows().map { it.id })
        assertEquals(0, commits)
    }

    @Test fun oldHistoryFinishesAfterReplacementStartsWithoutChangingNewPanel() {
        val original = Input()
        val next = Input()
        devices.add(original); devices.add(next)
        val (old, oldView) = service(editor())
        feed(original, 1000, 1)
        decode.drain(); settleHistory()
        val saved = rows().single()
        feed(original, 1000, 2) // Leave this chunk queued while capture is interrupted.
        old.onFinishInputView(true)
        assertEquals("", preview(oldView))
        await { !MicrophoneOwner.state().occupied }
        await { status(oldView).startsWith(old.getString(R.string.status_unable_insert)) }
        opened.removeAt(0).destroy()
        val (replacement, view) = service(editor(privateField = true))
        assertEquals(0, next.starts.get())
        button(view).performClick()
        await { next.starts.get() == 1 }
        val recordingStatus = status(view)
        decode.drain(); settleHistory()
        val recovered = rows().single()
        assertEquals(saved.id, recovered.id)
        assertEquals("old speech old speech", recovered.text)
        assertEquals(HistoryCompletion.INTERRUPTED, recovered.completion)
        assertEquals(recordingStatus, status(view))
        assertEquals("", preview(view))
        assertEquals(0, commits)
        feed(next, 20000, 3)
        decode.drain(); settleHistory()
        assertEquals("private speech", preview(view))
        assertEquals(listOf(saved.id), rows().map { it.id })
        assertEquals(0, commits)
    }

    @Test fun failedStartWithAndWithoutRetainedInputHasTruthfulRelease() {
        val released = Input(startFails = true)
        val retained = Input(startFails = true, failedReleases = 2)
        devices.add(released); devices.add(retained)
        val (service, view) = service(editor())
        await { !MicrophoneOwner.state().occupied && button(view).isEnabled }
        assertEquals(1, released.releases.get())
        button(view).performClick()
        await { MicrophoneOwner.state().releaseFailed }
        assertEquals(1, retained.starts.get())
        assertThrows(IllegalStateException::class.java) { runtime.close() }
        button(view).performClick()
        await { !MicrophoneOwner.state().occupied }
        assertEquals(3, retained.releases.get())
        assertEquals(0, commits)
    }

    @Test fun screenOffInterruptsToHistoryAndUnlockDoesNotResumeCapture() {
        val gate = CountDownLatch(1)
        val input = Input(releaseGate = gate)
        val next = Input()
        devices.add(input); devices.add(next)
        val (service, view) = service(editor())
        feed(input, 1000, 1)

        RuntimeEnvironment.getApplication().sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        await { status(view) == service.getString(R.string.status_stopping_recording) }
        assertEquals(1, input.starts.get())
        assertEquals(0, commits)
        assertTrue(rows().isEmpty())

        gate.countDown()
        await { !MicrophoneOwner.state().occupied }
        await { status(view).startsWith(service.getString(R.string.status_unable_insert)) }
        decode.drain()
        settleHistory()
        val saved = rows().single()
        assertEquals("old speech", saved.text)
        assertEquals(HistoryCompletion.INTERRUPTED, saved.completion)
        assertEquals(0, commits)

        RuntimeEnvironment.getApplication().sendBroadcast(Intent(Intent.ACTION_USER_PRESENT))
        val unlockedEditor = editor()
        service.onStartInput(unlockedEditor, true)
        service.onStartInputView(unlockedEditor, true)
        pump()
        assertEquals(1, input.starts.get())
        assertEquals(0, next.starts.get())
        decode.drain()
        settleHistory()
        assertEquals(0, next.starts.get())
        assertEquals(listOf(saved.id), rows().map { it.id })
        assertEquals(0, commits)
    }

    @Test fun screenOffDuringStopWaitChangesPendingCommitToOneHistoryFlush() {
        val gate = CountDownLatch(1)
        val input = Input(releaseGate = gate)
        devices.add(input)
        val (service, view) = service(editor())
        feed(input, 1000, 1)

        button(view).performClick()
        await { status(view) == service.getString(R.string.status_finishing) }
        RuntimeEnvironment.getApplication().sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        await { status(view) == service.getString(R.string.status_stopping_recording) }
        assertEquals(0, commits)
        assertTrue(rows().isEmpty())

        gate.countDown()
        await { !MicrophoneOwner.state().occupied }
        await { status(view).startsWith(service.getString(R.string.status_unable_insert)) }
        decode.drain()
        settleHistory()
        val saved = rows().single()
        assertEquals("old speech", saved.text)
        assertEquals(HistoryCompletion.INTERRUPTED, saved.completion)
        assertEquals(1, flushes.get())
        assertEquals(0, commits)
    }

    @Test fun screenOffAfterEditorAcceptsStopCommitCannotUndoOrRepeatIt() {
        val input = Input()
        devices.add(input)
        val (service, view) = service(editor())
        feed(input, 1000, 1)
        button(view).performClick()
        await { !MicrophoneOwner.state().occupied }
        onCommit = {
            val app = RuntimeEnvironment.getApplication()
            val intent = Intent(Intent.ACTION_SCREEN_OFF)
            val receivers = shadowOf(app).getReceiversForIntent(intent)
            assertEquals(1, receivers.size)
            receivers.single().onReceive(app, intent)
        }
        await { flushes.get() == 1 }
        decode.drain()
        await { commits == 1 }
        settleHistory()

        assertEquals(1, commits)
        assertEquals(1, flushes.get())
        assertEquals("old speech", rows().single().text)
        assertEquals(HistoryDelivery.ACCEPTED, rows().single().delivery)
        RuntimeEnvironment.getApplication().sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        pump()
        assertEquals(1, commits)
    }

    @Test fun unexpectedCaptureEndFinishesAcceptedSpeechOnlyToHistory() {
        val releaseGate = CountDownLatch(1)
        val input = Input(releaseGate = releaseGate)
        val unused = Input()
        devices.add(input); devices.add(unused)
        val (service, view) = service(editor())
        feed(input, 1000, 1)
        input.endUnexpectedly()
        try {
            await { status(view) == service.getString(R.string.status_stopping_recording) }
            assertTrue(MicrophoneOwner.state().occupied)
            assertEquals(0, commits)
            releaseGate.countDown()
            await { !MicrophoneOwner.state().occupied }
            await { flushes.get() == 1 }
            decode.drain(); settleHistory()
            await { status(view).startsWith(service.getString(R.string.status_unable_insert)) }
            assertEquals("old speech", rows().single().text)
            assertEquals(HistoryCompletion.INTERRUPTED, rows().single().completion)
            assertEquals(0, commits)
            assertEquals(0, unused.starts.get())
        } finally {
            releaseGate.countDown()
        }
    }

    @Test fun queuedUnexpectedEndBeforeStopStillRecoversWithBenchmarkOff() = queuedUnexpectedEndBeforeStop(false)
    @Test fun queuedUnexpectedEndBeforeStopStillRecoversWithBenchmarkOn() = queuedUnexpectedEndBeforeStop(true)

    private fun queuedUnexpectedEndBeforeStop(benchmark: Boolean) {
        val gate = CountDownLatch(1)
        val input = Input(releaseGate = gate)
        devices.add(input)
        val app = RuntimeEnvironment.getApplication()
        BenchmarkStore.setEnabled(app, benchmark)
        val benchmarkDir = File(app.filesDir, "benchmark")
        benchmarkDir.deleteRecursively()
        val (service, view) = service(editor())
        feed(input, 1000, 1)
        input.endUnexpectedly()
        // The recorder invokes onEnded before this thread exits. The paused main
        // looper therefore holds the real callback while the user presses Stop.
        input.readerThread!!.join(2000)
        assertFalse(input.readerThread!!.isAlive)
        try {
            button(view).performClick()
            assertEquals(service.getString(R.string.status_finishing), status(view))
            pump()
            assertEquals(service.getString(R.string.status_stopping_recording), status(view))
            gate.countDown()
            await { !MicrophoneOwner.state().occupied }
            await { flushes.get() == 1 }
            decode.drain(); settleHistory()
            assertEquals("old speech", rows().single().text)
            assertEquals(HistoryCompletion.INTERRUPTED, rows().single().completion)
            assertEquals(0, commits)
            assertFalse(benchmarkDir.exists())
        } finally {
            gate.countDown()
            BenchmarkStore.setEnabled(app, false)
            benchmarkDir.deleteRecursively()
        }
    }

    @Test fun regressionPermissionReadFailureInPrivateFieldDoesNotRetainOrInsert() {
        val input = Input(readFails = true)
        devices.add(input)
        val app = RuntimeEnvironment.getApplication()
        BenchmarkStore.setEnabled(app, true)
        val benchmarkDir = File(app.filesDir, "benchmark")
        benchmarkDir.deleteRecursively()
        try {
            val (service, view) = service(editor(privateField = true))
            feed(input, 1000, 1)
            input.endUnexpectedly()
            await { !MicrophoneOwner.state().occupied }
            await { flushes.get() == 1 }
            decode.drain(); settleHistory()
            await { status(view).startsWith(service.getString(R.string.status_unable_insert)) }
            assertTrue(rows().isEmpty())
            assertFalse(benchmarkDir.exists())
            assertEquals(0, commits)
        } finally {
            BenchmarkStore.setEnabled(app, false)
            benchmarkDir.deleteRecursively()
        }
    }

    @Test fun regressionUnexpectedEndOverridesPromptAndAllowsNewTake() {
        val original = Input()
        val next = Input()
        devices.add(original); devices.add(next)
        val (_, view) = service(editor())
        feed(original, 1000, 1)
        original.endUnexpectedly()
        original.readerThread!!.join(2000)
        assertFalse(original.readerThread!!.isAlive)
        view.findViewById<ImageButton>(R.id.back).performClick()
        await { !MicrophoneOwner.state().occupied }
        button(view).performClick()
        await { next.starts.get() == 1 }
        feed(next, 20000, 2)
        decode.drain(); settleHistory()
        assertEquals("private speech", preview(view))
        assertEquals(0, commits)
        assertTrue(rows().any { it.text.contains("old speech") && it.completion == HistoryCompletion.INTERRUPTED })
    }

    @Test fun lateInterruptedTimingKeepsOriginalTakeAfterExplicitRestart() {
        val original = Input()
        val next = Input()
        devices.add(original); devices.add(next)
        val app = RuntimeEnvironment.getApplication()
        val timing = File(app.filesDir, "lab/recovery-timing.jsonl")
        fun receipts(): List<org.json.JSONObject> = if (timing.exists())
            timing.readLines().filter { it.isNotBlank() }.map { org.json.JSONObject(it) } else emptyList()
        val (_, view) = service(editor())
        feed(original, 1000, 1)
        decode.drain(); settleHistory()
        val prefix = rows().single()
        val blocked = File(root, "${prefix.id}.tmp")
        assertTrue(blocked.mkdir())
        File(blocked, "block").writeText("synthetic")
        try {
            feed(original, 20000, 2)
            original.endUnexpectedly()
            await { !MicrophoneOwner.state().occupied && button(view).isEnabled }
            button(view).performClick()
            await { next.starts.get() == 1 }
            await { receipts().count { it.optString("phase") == "captureStart" } >= 2 }
            val starts = receipts().filter { it.optString("phase") == "captureStart" }
            val oldId = starts[starts.size - 2].getString("takeId")
            val newId = starts.last().getString("takeId")
            assertNotEquals(oldId, newId)
            // Decode the late chunk alone and let its checkpoint run (and fail) first: queued
            // together with the interruption's final save, History keeps only the newer one.
            decode.runOne(); settleHistory()
            decode.drain(); settleHistory()
            // Late decode work may be queued after the first drain; keep draining while waiting.
            await { decode.drain(); receipts().any { it.optString("phase") == "checkpointFailed" } &&
                receipts().any { it.optString("phase") == "takeEnded" && it.optString("takeId") == oldId } }
            val late = receipts().filter { it.optString("phase") in setOf("checkpointFailed", "takeEnded") }
            assertTrue(late.any { it.optString("phase") == "checkpointFailed" })
            assertTrue(late.all { !it.isNull("takeId") && it.getString("takeId") == oldId })
        } finally { blocked.deleteRecursively() }
    }

    @Test fun explicitRestartLatchesCurrentBenchmarkOptIn() {
        val original = Input()
        val next = Input()
        devices.add(original); devices.add(next)
        val app = RuntimeEnvironment.getApplication()
        BenchmarkStore.setEnabled(app, true)
        try {
            val (service, view) = service(editor())
            assertEquals(View.VISIBLE, view.findViewById<View>(R.id.benchmark_banner).visibility)
            original.endUnexpectedly()
            await { !MicrophoneOwner.state().occupied }
            BenchmarkStore.setEnabled(app, false)
            service.onStartInputView(editor(), true)
            button(view).performClick()
            await { next.starts.get() == 1 }
            assertEquals(View.GONE, view.findViewById<View>(R.id.benchmark_banner).visibility)
        } finally { BenchmarkStore.setEnabled(app, false) }
    }

    @Test fun regressionOldEndDeliveredAfterExplicitNewTakeDoesNotInterruptIt() {
        val original = Input()
        val next = Input()
        val heldEnd = LinkedBlockingQueue<Runnable>()
        DictationImeService.postCaptureEnd = { _, callback -> heldEnd.add(callback) }
        devices.add(original); devices.add(next)
        val (service, view) = service(editor())
        feed(original, 1000, 1)
        original.endUnexpectedly()
        original.readerThread!!.join(2000)
        assertFalse(original.readerThread!!.isAlive)
        val oldEnd = heldEnd.poll(2, TimeUnit.SECONDS)
        assertNotNull("real capture-end delivery must be held", oldEnd)
        // Cancel after the capture already ended is a system interruption, not a choice.
        view.findViewById<ImageButton>(R.id.back).performClick()
        assertEquals(View.GONE, view.findViewById<View>(R.id.choice).visibility)
        // Process interruption and the separate release acknowledgement, keeping only
        // the old capture-end event out of the main queue.
        await { !MicrophoneOwner.state().occupied && button(view).isEnabled }
        MicrophoneOwner.requireExplicitStart()
        val newEditor = editor(privateField = true)
        service.onStartInput(newEditor, true)
        service.onStartInputView(newEditor, true)
        assertEquals(0, next.starts.get())
        button(view).performClick()
        assertEquals(1, next.starts.get())
        Handler(Looper.getMainLooper()).post(oldEnd!!)
        pump() // Delivers the old end after the explicit new start.
        feed(next, 20000, 2)
        decode.drain(); settleHistory()
        assertEquals(service.getString(R.string.status_recording), status(view))
        assertEquals("private speech", preview(view))
        assertEquals(0, commits)
    }

    @Test fun regressionLockDecodeFailureKeepsPrefixAndWarnsWithoutBenchmarkFallback() {
        val input = Input()
        devices.add(input)
        val app = RuntimeEnvironment.getApplication()
        BenchmarkStore.setEnabled(app, true)
        val benchmarkDir = File(app.filesDir, "benchmark")
        benchmarkDir.deleteRecursively()
        try {
            val (service, view) = service(editor())
            feed(input, 1000, 1)
            decode.drain(); settleHistory()
            val prefix = rows().single()
            failLoudDecode = true
            feed(input, 20000, 2)
            screenOff()
            await { !MicrophoneOwner.state().occupied }
            await { flushes.get() == 1 }
            decode.drain(); settleHistory()
            await { HistoryNotice.INCOMPLETE in historyNotices(app).pending() }
            assertEquals(prefix.id, rows().single().id)
            assertEquals("old speech", rows().single().text)
            assertEquals(HistoryCompletion.INTERRUPTED, rows().single().completion)
            assertTrue(status(view).startsWith(service.getString(R.string.status_unable_insert)))
            assertFalse(benchmarkDir.exists())
            assertEquals(0, commits)
        } finally { BenchmarkStore.setEnabled(app, false); benchmarkDir.deleteRecursively() }
    }

    @Test fun regressionUnexpectedEndSaveFailureKeepsPrefixAndWarnsWithoutBenchmarkFallback() {
        val input = Input()
        devices.add(input)
        val app = RuntimeEnvironment.getApplication()
        BenchmarkStore.setEnabled(app, true)
        val benchmarkDir = File(app.filesDir, "benchmark")
        benchmarkDir.deleteRecursively()
        var blocked: File? = null
        try {
            val (_, view) = service(editor())
            feed(input, 1000, 1)
            decode.drain(); settleHistory()
            val prefix = rows().single()
            // Block only the next atomic checkpoint's temporary file; keep the durable prefix.
            blocked = File(root, "${prefix.id}.tmp")
            assertTrue(blocked.mkdir())
            File(blocked, "block").writeText("synthetic")
            feed(input, 20000, 2)
            input.endUnexpectedly()
            await { !MicrophoneOwner.state().occupied }
            await { flushes.get() == 1 }
            decode.drain(); settleHistory()
            await { HistoryNotice.SAVE in historyNotices(app).pending() }
            assertEquals(prefix.id, rows().single().id)
            assertEquals("old speech", rows().single().text)
            assertEquals(HistoryCompletion.INTERRUPTED, rows().single().completion)
            // The warning is pending for the History page, and the panel is unchanged by it.
            val panelNotice = view.findViewById<TextView>(R.id.retention_notice)
            assertEquals(android.view.View.GONE, panelNotice.visibility)
            assertEquals("", panelNotice.text.toString())
            assertFalse(panelNotice.text.contains(app.getString(R.string.history_save_error)))
            assertFalse(benchmarkDir.exists())
            assertEquals(0, commits)
        } finally { blocked?.deleteRecursively(); BenchmarkStore.setEnabled(app, false); benchmarkDir.deleteRecursively() }
    }

    @Test fun regressionUnexpectedEndUnconfirmedReleaseRequiresRetryBeforeNewCapture() {
        val input = Input(failedReleases = 1)
        val next = Input()
        devices.add(input); devices.add(next)
        val (service, view) = service(editor())
        feed(input, 1000, 1)
        decode.drain(); settleHistory()
        val prefix = rows().single()
        input.endUnexpectedly()
        await { MicrophoneOwner.state().releaseFailed && status(view) == service.getString(R.string.status_release_failed) }
        assertTrue(MicrophoneOwner.state().occupied)
        assertEquals(service.getString(R.string.retry_release), button(view).contentDescription.toString())
        assertEquals(0, next.starts.get())
        button(view).performClick()
        await { !MicrophoneOwner.state().occupied }
        decode.drain(); settleHistory()
        assertEquals(prefix.id, rows().single().id)
        assertEquals("old speech", rows().single().text)
        assertEquals(HistoryCompletion.INTERRUPTED, rows().single().completion)
        assertEquals(0, next.starts.get())
        assertEquals(0, commits)
        button(view).performClick()
        await { next.starts.get() == 1 }
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun regressionUnexpectedEndPanelShowsInactiveRingAndStopSemantics() {
        val gate = CountDownLatch(1)
        val input = Input(releaseGate = gate)
        devices.add(input)
        val (service, view) = service(editor())
        val ring = view.findViewById<RecordingRingView>(R.id.recording_ring)
        val size = (200 * service.resources.displayMetrics.density).toInt()
        ring.layout(0, 0, size, size)
        val primary = view.context.getColor(R.color.b2_primary)
        /** Pixels in the recording ink (red-orange); the resting ring is an outline hairline. */
        fun inkPixels(): Int {
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            return try {
                ring.draw(Canvas(bitmap))
                val pixels = IntArray(size * size)
                bitmap.getPixels(pixels, 0, size, 0, 0, size, size)
                pixels.count { it == primary }
            } finally { bitmap.recycle() }
        }
        val listening = inkPixels()
        input.endUnexpectedly()
        try {
            await { status(view) == service.getString(R.string.status_stopping_recording) }
            assertTrue("active ring was not drawn", listening > 0)
            assertEquals("ring still looks active after capture ended", 0, inkPixels())
            assertFalse(ring.recording)
            assertFalse(button(view).isEnabled)
            assertEquals(service.getString(R.string.status_stopping_recording), button(view).contentDescription.toString())
            assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, view.findViewById<TextView>(R.id.status).accessibilityLiveRegion)
        } finally { gate.countDown() }
    }
}
