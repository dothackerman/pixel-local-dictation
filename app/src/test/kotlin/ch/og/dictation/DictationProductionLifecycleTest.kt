package ch.og.dictation

import ch.og.dictation.audio.MicrophoneOwner
import ch.og.dictation.audio.Recorder
import ch.og.dictation.diagnostics.BenchmarkStore
import ch.og.dictation.diagnostics.RotationEvent
import ch.og.dictation.diagnostics.RotationTrace
import ch.og.dictation.history.HistoryCompletion
import ch.og.dictation.history.HistoryDelivery
import ch.og.dictation.history.HistoryEntry
import ch.og.dictation.history.HistoryIo
import ch.og.dictation.history.HistoryNotice
import ch.og.dictation.history.historyNotices
import ch.og.dictation.panel.PreparingArcView
import ch.og.dictation.panel.RecordingRingView
import ch.og.dictation.recognition.Chunk
import ch.og.dictation.recognition.DictationRuntime
import ch.og.dictation.recognition.Engine
import ch.og.dictation.storage.Storage
import ch.og.dictation.recognition.Recognizer
import ch.og.dictation.recognition.Segmenter
import ch.og.dictation.recognition.Variant
import ch.og.dictation.take.EditorRetention
import ch.og.dictation.take.ROTATION_SETTLE_MS
import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Binder
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
    /** This test's Wiring: an engine around [runtime], a fake recorder; restored after each test. */
    private lateinit var wiring: WiringOverride
    private lateinit var runtime: DictationRuntime
    private lateinit var root: File
    private var commits = 0
    private var onCommit: (() -> Unit)? = null

    @Before fun setUp() {
        assertFalse(MicrophoneOwner.state().occupied)
        MicrophoneOwner.acceptExplicitStart()
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO)
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
        wiring = RuntimeEnvironment.getApplication().overrideWiring {
            it.with(engine = Engine.prepared(runtime), recorderFactory = { Recorder { devices.remove() } })
        }
        root = RuntimeEnvironment.getApplication().wiring.storage.historyRoot
        HistoryIo.executor.submit { val store = HistoryIo.store(root); store.clear(store.clearableIds()) }.get(2, TimeUnit.SECONDS)
        HistoryNotice.entries.forEach { historyNotices(RuntimeEnvironment.getApplication()).dismiss(it) }
    }

    @After fun tearDown() {
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
            wiring.close()
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
        private val content = StringBuilder()
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            commits++
            inserted.add(text.toString())
            onCommit?.also { onCommit = null; it() }
            if (!swallow) content.append(text)
            return true
        }
        override fun getTextBeforeCursor(length: Int, flags: Int): CharSequence = content.takeLast(length).toString()
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

    /** The old take's outcome is in (decoded, saved, delivered); text it holds is discarded so the next take may start. */
    private fun awaitOutcomeThenDiscardHeld(view: View) {
        await { decode.drain(); settleHistory(); !MicrophoneOwner.state().occupied && button(view).isEnabled }
        if (view.findViewById<View>(R.id.choice).visibility == View.VISIBLE) discardAction(view).performClick()
    }

    private fun advance(ms: Long) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(ms)) }

    /** Device demos keep synthetic takes out of the owner's History (same isolation as the History UI tests). */
    @Test fun testHistoryRootKeepsTheTakeOutOfTheRealHistory() {
        val storage = Storage.under(File(RuntimeEnvironment.getApplication().cacheDir, "history-ui-test-hook"))
        val isolated = storage.historyRoot
        wiring.set { it.with(storage = storage) }
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

    // What a rotation does to a take is the take controller's (TakeControllerTest, #90). Here: what only the service
    // does with a rotation: it counts it, recreates the panel from the take's state, and forwards nothing else.

    /** A take that kept authority through a rotation: a cursor move in the same field changes nothing, Stop inserts. */
    @Test fun aCursorMoveInTheTakesFieldAfterARotationStillInsertsOnStop() {
        val input = Input()
        devices.add(input)
        val (service, _) = service(editor())
        val first = firstField.getValue(service)
        feed(input, 1000, 1)
        val panel = platformResetOnRotation(service, android.view.Surface.ROTATION_90)!!
        advance(ROTATION_SETTLE_MS * 2)
        service.onUpdateSelection(0, 0, 5, 5, -1, -1)
        pump()
        assertEquals(0, input.releases.get())
        button(panel).performClick()
        await { input.releases.get() == 1 }
        await { decode.drain(); commits == 1 }
        assertEquals(listOf("old speech"), first.inserted)
    }

    /** The service counts every display change: turning there and back is still a rotation, though the display ends where it started. */
    @Test fun repeatedRotationsAndRotatingBackKeepOneTakeWithAllSpeech() {
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

    private fun discardAction(panel: View) = panel.findViewById<ImageButton>(R.id.choice_discard)

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

    @Test fun regressionPermissionReadFailureInPrivateFieldDoesNotRetainOrInsert() {
        val input = Input(readFails = true)
        devices.add(input)
        val app = RuntimeEnvironment.getApplication()
        BenchmarkStore.setEnabled(app, true)
        val benchmarkDir = app.wiring.storage.recordingsRoot
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
            awaitOutcomeThenDiscardHeld(view)
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
            awaitOutcomeThenDiscardHeld(view)
            BenchmarkStore.setEnabled(app, false)
            service.onStartInputView(editor(), true)
            button(view).performClick()
            await { next.starts.get() == 1 }
            assertEquals(View.GONE, view.findViewById<View>(R.id.benchmark_banner).visibility)
        } finally { BenchmarkStore.setEnabled(app, false) }
    }

    @Test fun regressionLockDecodeFailureKeepsPrefixAndWarnsWithoutBenchmarkFallback() {
        val input = Input()
        devices.add(input)
        val app = RuntimeEnvironment.getApplication()
        BenchmarkStore.setEnabled(app, true)
        val benchmarkDir = app.wiring.storage.recordingsRoot
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
        val benchmarkDir = app.wiring.storage.recordingsRoot
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
