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
        override fun start() { starts.incrementAndGet(); if (startFails) error("synthetic start failure") }
        override fun read(buffer: ShortArray): Int {
            readerThread = Thread.currentThread()
            val frame = frames.take()
            if (frame.isEmpty() && readFails) throw SecurityException("synthetic permission loss")
            frame.copyInto(buffer)
            return if (frame.isEmpty()) -1 else frame.size
        }
        override fun stop() { frames.offer(shortArrayOf()) }
        override fun release() {
            releaseGate?.await(2, TimeUnit.SECONDS)
            if (releases.incrementAndGet() <= failedReleases) error("synthetic release failure")
        }
        fun feed(sample: Short) { frames.add(shortArrayOf(sample)) }
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
    private fun editor(privateField: Boolean = false) = EditorInfo().apply {
        inputType = InputType.TYPE_CLASS_TEXT or if (privateField) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_VARIATION_NORMAL
    }
    /** One editor field; counts what reaches it. */
    private inner class Field(service: DictationImeService) : BaseInputConnection(View(service), false) {
        var inserted = mutableListOf<String>()
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            commits++
            inserted.add(text.toString())
            onCommit?.also { onCommit = null; it() }
            return true
        }
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
        val notice = returned.findViewById<TextView>(R.id.retention_notice)
        assertEquals(View.VISIBLE, notice.visibility)
        assertEquals(service.getString(R.string.retention_field_off), notice.text.toString())
        feed(input, 20000, 2)
        decode.drain(); settleHistory()
        assertTrue(rows().isEmpty())
        button(returned).performClick()
        await { input.releases.get() == 1 }
        await { decode.drain(); commits == 1 }
        settleHistory()
        assertEquals(listOf("old speech private speech"), next.inserted)
        assertTrue(rows().isEmpty())
    }

    @Test fun privateStartingEditorStaysExcludedAfterReboundToOrdinaryField() {
        DictationImeService.requestPanel = {}
        val input = Input()
        devices.add(input)
        val (service, _) = service(editor(privateField = true))
        feed(input, 1000, 1)
        val next = rotateHidden(service, android.view.Surface.ROTATION_90, editor())
        val returned = showPanel(service, editor())!!
        assertEquals(service.getString(R.string.retention_field_off), returned.findViewById<TextView>(R.id.retention_notice).text.toString())
        feed(input, 20000, 2)
        button(returned).performClick()
        await { input.releases.get() == 1 }
        await { decode.drain(); commits == 1 }
        settleHistory()
        assertEquals(1, next.inserted.size)
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

    private fun latestPrompt() = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()

    /**
     * Pixel-order rotation into a recreated editor; returns the new field and the returned panel.
     * [attached]: the returned panel's window is attached, so a prompt can be attached to it.
     */
    private fun rotateToRecreatedEditor(service: DictationImeService, attached: Boolean = true): Pair<Field, View> {
        platformResetOnRotation(service, android.view.Surface.ROTATION_90)
        val next = recreatedEditorTakesInput(service)
        val returned = showPanel(service)!!
        // Robolectric cannot attach the input method window; host the panel in an activity window
        // as service() does, so it has a window token a prompt can attach to.
        if (attached) {
            (returned.parent as android.view.ViewGroup).removeView(returned)
            Robolectric.buildActivity(android.app.Activity::class.java).setup().get().setContentView(returned)
            pump()
        }
        assertEquals(attached, returned.windowToken != null)
        return next to returned
    }

    @Test fun recordingChoiceOpenThroughARotationIsShownAgainAndContinueKeepsTheTake() {
        DictationImeService.requestPanel = {}
        val input = Input()
        devices.add(input)
        val (service, view) = service(editor())
        feed(input, 1000, 1)
        view.findViewById<ImageButton>(R.id.back).performClick()
        val before = latestPrompt()
        assertTrue(before.isShowing)
        val (next, returned) = rotateToRecreatedEditor(service)
        assertEquals(0, input.releases.get())
        advance(ROTATION_SETTLE_MS)
        val again = latestPrompt()
        assertNotSame(before, again)
        assertTrue("the choice is shown again, not made", again.isShowing)
        assertEquals(service.getString(R.string.status_recording), status(returned))
        again.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).performClick()
        assertEquals(0, input.releases.get())
        feed(input, 20000, 2)
        button(returned).performClick()
        await { input.releases.get() == 1 }
        await { decode.drain(); settleHistory(); commits == 1 }
        assertEquals(listOf("old speech private speech"), next.inserted)
        assertEquals(1, input.starts.get())
    }

    @Test fun recordingChoiceShownAgainAfterARotationFinishStopsOnceAndInsertsThere() {
        DictationImeService.requestPanel = {}
        val input = Input()
        devices.add(input)
        val (service, view) = service(editor())
        val first = firstField.getValue(service)
        feed(input, 1000, 1)
        view.findViewById<ImageButton>(R.id.back).performClick()
        val (next, _) = rotateToRecreatedEditor(service)
        assertFalse("rotation never selects Finish", latestPrompt().isShowing)
        assertEquals(0, input.releases.get())
        advance(ROTATION_SETTLE_MS)
        latestPrompt().getButton(android.app.AlertDialog.BUTTON_NEUTRAL).performClick()
        await { input.releases.get() == 1 }
        await { decode.drain(); settleHistory(); commits == 1 }
        advance(ROTATION_HOLD_GAP_MS)
        settleHistory()
        assertEquals(listOf("old speech"), next.inserted)
        assertTrue(first.inserted.isEmpty())
        assertEquals(1, input.releases.get())
        assertEquals(HistoryDelivery.ACCEPTED, rows().single().delivery)
    }

    @Test fun recordingChoiceShownAgainAfterARotationDiscardRemovesSavedTextOnce() {
        DictationImeService.requestPanel = {}
        val input = Input()
        devices.add(input)
        val (service, view) = service(editor())
        feed(input, 1000, 1)
        decode.drain(); settleHistory()
        assertEquals(1, rows().size)
        view.findViewById<ImageButton>(R.id.back).performClick()
        rotateToRecreatedEditor(service)
        settleHistory()
        assertEquals("rotation never selects Discard", 1, rows().size)
        advance(ROTATION_SETTLE_MS)
        latestPrompt().getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        await { input.releases.get() == 1 }
        decode.drain(); settleHistory()
        assertTrue(rows().isEmpty())
        assertEquals(0, commits)
    }

    /** Stop, Back (finishing choice open), rotate; the result decodes under the moved choice. */
    private fun finishingChoiceThroughARotation(attached: Boolean = true): Triple<DictationImeService, Field, Input> {
        DictationImeService.requestPanel = {}
        val input = Input()
        devices.add(input)
        val (service, view) = service(editor())
        feed(input, 1000, 1)
        button(view).performClick()
        await { input.releases.get() == 1 }
        view.findViewById<ImageButton>(R.id.back).performClick()
        assertTrue(latestPrompt().isShowing)
        val (next, returned) = rotateToRecreatedEditor(service, attached)
        assertEquals(service.getString(R.string.status_finishing), status(returned))
        decode.drain(); settleHistory(); decode.drain(); settleHistory()
        assertEquals(0, commits)
        advance(ROTATION_SETTLE_MS)
        settleHistory()
        return Triple(service, next, input)
    }

    @Test fun finishingChoiceOpenThroughARotationHoldsTheResultUntilKept() {
        val (_, next, _) = finishingChoiceThroughARotation()
        val again = latestPrompt()
        assertTrue("the choice is shown again, not made", again.isShowing)
        assertEquals("a result decoded under the choice waits for it", 0, commits)
        again.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).performClick()
        await { settleHistory(); commits == 1 }
        assertEquals(listOf("old speech"), next.inserted)
        assertEquals(HistoryDelivery.ACCEPTED, rows().single().delivery)
    }

    @Test fun finishingChoiceShownAgainAfterARotationDiscardRemovesSavedTextOnce() {
        val (_, next, _) = finishingChoiceThroughARotation()
        latestPrompt().getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        settleHistory()
        advance(ROTATION_HOLD_GAP_MS)
        decode.drain(); settleHistory()
        assertEquals(0, commits)
        assertTrue(next.inserted.isEmpty())
        assertTrue(rows().isEmpty())
    }

    @Test fun finishingChoiceThatCannotBeAttachedAfterARotationKeepsFinishing() {
        val (_, next, _) = finishingChoiceThroughARotation(attached = false)
        await { settleHistory(); commits == 1 }
        assertEquals(listOf("old speech"), next.inserted)
        assertEquals(HistoryDelivery.ACCEPTED, rows().single().delivery)
    }

    @Test fun recordingChoiceThatCannotBeAttachedAfterARotationContinuesRecording() {
        DictationImeService.requestPanel = {}
        val input = Input()
        devices.add(input)
        val (service, view) = service(editor())
        feed(input, 1000, 1)
        view.findViewById<ImageButton>(R.id.back).performClick()
        val before = latestPrompt()
        val (next, returned) = rotateToRecreatedEditor(service, attached = false)
        advance(ROTATION_SETTLE_MS)
        advance(ROTATION_HOLD_GAP_MS)
        assertSame(before, latestPrompt())
        assertFalse(before.isShowing)
        assertEquals(0, input.releases.get())
        assertEquals(service.getString(R.string.status_recording), status(returned))
        feed(input, 20000, 2)
        button(returned).performClick()
        await { input.releases.get() == 1 }
        await { decode.drain(); settleHistory(); commits == 1 }
        assertEquals(listOf("old speech private speech"), next.inserted)
    }

    @Test fun lockDuringARotationOverridesAMovedChoiceAndGoesToHistory() {
        DictationImeService.requestPanel = {}
        val input = Input()
        devices.add(input)
        val (service, view) = service(editor())
        feed(input, 1000, 1)
        button(view).performClick()
        await { input.releases.get() == 1 }
        view.findViewById<ImageButton>(R.id.back).performClick()
        val before = latestPrompt()
        rotateHidden(service, android.view.Surface.ROTATION_90)
        screenOff()
        awaitInterruptedInHistory()
        showPanel(service)
        advance(ROTATION_SETTLE_MS)
        decode.drain(); settleHistory()
        assertSame("no choice comes back after a lock", before, latestPrompt())
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
            assertTrue("a finishing microphone looks like a live one", mic.alpha < 1f)
            assertEquals(service.getString(R.string.status_finishing), mic.contentDescription.toString())
            assertTrue("recording ring not drawn", recordingRing > 200)
            assertTrue("finishing ring looks like recording", ringAlpha(returned) < recordingRing * 0.6)
            val whole = android.graphics.Rect(0, 0, returned.width, returned.height)
            for (target in listOf(returned.findViewById<View>(R.id.back), mic)) {
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
                "active", "authority", "flag", "view", "take", "rotation", "rotations", "configDiff", "handledConfig")
            assertTrue(rows.all { allowed.containsAll(it.keys().asSequence().toSet()) })
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

    @Test fun recordingCancelOffersContinueWithoutStoppingTheTake() {
        val input = Input()
        devices.add(input)
        val (service, view) = service(editor())
        feed(input, 1000, 1)
        decode.drain(); settleHistory()
        val originalId = rows().single().id
        view.findViewById<ImageButton>(R.id.back).performClick()
        val dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
        assertNotNull(dialog)
        assertTrue(dialog.isShowing)
        dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).performClick()
        assertEquals(0, input.releases.get())
        feed(input, 20000, 2)
        decode.drain(); settleHistory()
        assertEquals(originalId, rows().single().id)
        assertEquals(0, commits)
    }

    @Test fun promptCancellationAndPanelRecreationKeepRecording() {
        val input = Input()
        devices.add(input)
        val (service, view) = service(editor())
        feed(input, 1000, 1)
        decode.drain(); settleHistory()
        val id = rows().single().id
        view.findViewById<ImageButton>(R.id.back).performClick()
        val dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
        assertTrue(dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).isFocusable)
        service.onCreateInputView()
        view.findViewById<ImageButton>(R.id.back).performClick()
        assertSame(dialog, org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog())
        dialog.cancel()
        assertEquals(0, input.releases.get())
        feed(input, 20000, 2)
        decode.drain(); settleHistory()
        assertEquals(id, rows().single().id)
        assertEquals(0, commits)
    }

    @Test fun recordingPromptFinishStopsOnceAndInserts() {
        val input = Input()
        devices.add(input)
        val (service, view) = service(editor())
        feed(input, 1000, 1)
        decode.drain(); settleHistory()
        view.findViewById<ImageButton>(R.id.back).performClick()
        val dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
        dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).performClick()
        await { input.releases.get() == 1 }
        decode.drain(); settleHistory()
        assertEquals(1, commits)
        assertEquals(1, input.releases.get())
    }

    @Test fun recordingPromptDiscardRemovesCheckpointsAndNeverInserts() {
        val input = Input()
        devices.add(input)
        val (service, view) = service(editor())
        feed(input, 1000, 1)
        decode.drain(); settleHistory()
        assertEquals(1, rows().size)
        view.findViewById<ImageButton>(R.id.back).performClick()
        val dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        await { input.releases.get() == 1 }
        decode.drain(); settleHistory()
        assertTrue(rows().isEmpty())
        assertEquals(0, commits)
    }

    @Test fun inputLossDismissesRecordingPromptAndKeepsInterruptedText() {
        val input = Input()
        devices.add(input)
        val (service, view) = service(editor())
        feed(input, 1000, 1)
        decode.drain(); settleHistory()
        view.findViewById<ImageButton>(R.id.back).performClick()
        val dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
        service.onFinishInputView(true)
        assertFalse(dialog.isShowing)
        await { input.releases.get() == 1 }
        awaitInterruptedInHistory()
        assertEquals(0, commits)
    }

    @Test fun finishingPromptHoldsDecodedTextUntilKept() {
        val input = Input()
        devices.add(input)
        val (service, view) = service(editor())
        feed(input, 1000, 1)
        button(view).performClick()
        await { input.releases.get() == 1 }
        view.findViewById<ImageButton>(R.id.back).performClick()
        val dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
        assertTrue(dialog.isShowing)
        assertTrue(dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).isFocusable)
        decode.drain(); settleHistory()
        assertEquals(0, commits)
        dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).performClick()
        settleHistory()
        assertEquals(1, commits)
    }

    @Test @Config(qualifiers = "w320dp-h320dp-land")
    fun attachedPromptsKeepSafeFocusAtLargeTextInLandscape() = attachedPromptsKeepSafeFocusAtLargeText()

    @Test @Config(qualifiers = "w320dp-h320dp-land-night")
    fun attachedPromptsKeepSafeFocusAtLargeTextInLandscapeDark() = attachedPromptsKeepSafeFocusAtLargeText()

    private fun attachedPromptsKeepSafeFocusAtLargeText() {
        val app = RuntimeEnvironment.getApplication()
        val config = android.content.res.Configuration(app.resources.configuration)
        config.fontScale = 2f
        app.resources.updateConfiguration(config, app.resources.displayMetrics)
        val input = Input()
        devices.add(input)
        val (service, view) = service(editor())
        feed(input, 1000, 1)
        view.findViewById<ImageButton>(R.id.back).performClick()
        val recording = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
        assertEquals(android.view.WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG, recording.window!!.attributes.type)
        val root = recording.window!!.decorView
        val width = (320 * app.resources.displayMetrics.density).toInt()
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        root.layout(0, 0, width, root.measuredHeight)
        assertTrue(recording.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).hasFocus())
        for (which in listOf(android.app.AlertDialog.BUTTON_NEGATIVE, android.app.AlertDialog.BUTTON_NEUTRAL, android.app.AlertDialog.BUTTON_POSITIVE)) {
            val action = recording.getButton(which)
            assertTrue(action.measuredWidth > 0 && action.measuredHeight >= 48 * app.resources.displayMetrics.density)
        }
        assertTrue(recording.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).text.isNotEmpty())
        recording.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).performClick()
        await { input.releases.get() == 1 }
        view.findViewById<ImageButton>(R.id.back).performClick()
        val finishing = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
        assertEquals(android.view.WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG, finishing.window!!.attributes.type)
        val finishRoot = finishing.window!!.decorView
        finishRoot.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        finishRoot.layout(0, 0, width, finishRoot.measuredHeight)
        assertTrue(finishing.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).hasFocus())
        for (which in listOf(android.app.AlertDialog.BUTTON_NEGATIVE, android.app.AlertDialog.BUTTON_POSITIVE)) {
            val action = finishing.getButton(which)
            assertTrue(action.measuredWidth > 0 && action.measuredHeight >= 48 * app.resources.displayMetrics.density)
        }
        assertTrue(finishing.getButton(android.app.AlertDialog.BUTTON_POSITIVE).text.isNotEmpty())
        val safe = finishing.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)
        safe.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ENTER))
        safe.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_ENTER))
        // Robolectric does not synthesize Button clicks from Enter; ensure it did not discard,
        // then exercise the focused safe action explicitly.
        assertEquals(0, commits)
        if (finishing.isShowing) safe.performClick()
        decode.drain(); settleHistory()
        assertEquals(1, commits)
    }

    @Test fun finishingPromptDiscardSuppressesLateOutput() {
        val input = Input()
        devices.add(input)
        val (_, view) = service(editor())
        feed(input, 1000, 1)
        button(view).performClick()
        await { input.releases.get() == 1 }
        view.findViewById<ImageButton>(R.id.back).performClick()
        val dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
        decode.drain(); settleHistory()
        assertEquals(0, commits)
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        settleHistory()
        assertEquals(0, commits)
        assertTrue(rows().isEmpty())
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
        await { status(oldView) == old.getString(R.string.status_recording_stopped) }
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
        await { status(view) == service.getString(R.string.status_stopped_finishing_history) }
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
        await { status(view) == service.getString(R.string.status_stopped_finishing_history) }
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
            await { status(view) == service.getString(R.string.status_saved_history) }
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
            await { status(view) == service.getString(R.string.status_stopped_recovery_unavailable) }
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
        view.findViewById<ImageButton>(R.id.back).performClick()
        original.endUnexpectedly()
        original.readerThread!!.join(2000)
        assertFalse(original.readerThread!!.isAlive)
        val oldEnd = heldEnd.poll(2, TimeUnit.SECONDS)
        assertNotNull("real capture-end delivery must be held", oldEnd)
        org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
            .getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
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
            assertEquals(service.getString(R.string.status_transcription_incomplete), status(view))
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
        fun maxAlpha(): Int {
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            return try {
                ring.draw(Canvas(bitmap))
                val pixels = IntArray(size * size)
                bitmap.getPixels(pixels, 0, size, 0, 0, size, size)
                pixels.maxOf { Color.alpha(it) }
            } finally { bitmap.recycle() }
        }
        val listeningAlpha = maxAlpha()
        input.endUnexpectedly()
        try {
            await { status(view) == service.getString(R.string.status_stopping_recording) }
            assertTrue("active ring was not drawn", listeningAlpha > 200)
            assertTrue("ring still looks active after capture ended", maxAlpha() < listeningAlpha)
            assertFalse(button(view).isEnabled)
            assertEquals(service.getString(R.string.status_stopping_recording), button(view).contentDescription.toString())
            assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, view.findViewById<TextView>(R.id.status).accessibilityLiveRegion)
        } finally { gate.countDown() }
    }
}
