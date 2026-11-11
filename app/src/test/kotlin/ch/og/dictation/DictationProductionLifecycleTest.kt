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
    private fun service(info: EditorInfo): Pair<DictationImeService, View> {
        val controller = Robolectric.buildService(DictationImeService::class.java).create()
        opened.add(controller)
        val service = controller.get()
        val connection = object : BaseInputConnection(View(service), false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                commits++
                onCommit?.also { onCommit = null; it() }
                return true
            }
        }
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
        decode.drain(); settleHistory()
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
        decode.drain(); settleHistory()
        assertEquals(1, commits)
        assertEquals(HistoryCompletion.COMPLETE, rows().single().completion)
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
        decode.drain(); settleHistory()
        assertEquals(HistoryCompletion.INTERRUPTED, rows().single().completion)
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

    @Test
    @Config(qualifiers = "w320dp-h320dp-land")
    fun attachedPromptsKeepSafeFocusAtLargeTextInLandscape() {
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
            decode.drain(); settleHistory()
            await { receipts().any { it.optString("phase") == "checkpointFailed" } &&
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
            assertTrue(view.findViewById<TextView>(R.id.retention_notice).text.contains(app.getString(R.string.history_save_error)))
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
