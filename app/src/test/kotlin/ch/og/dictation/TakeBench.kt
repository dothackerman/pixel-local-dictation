package ch.og.dictation

import ch.og.dictation.audio.MicrophoneOwner
import ch.og.dictation.audio.Recorder
import ch.og.dictation.history.HistoryIo
import ch.og.dictation.history.HistoryNotice
import ch.og.dictation.history.historyNotices
import ch.og.dictation.panel.FrameText
import ch.og.dictation.panel.onPanelFrame
import ch.og.dictation.recognition.Chunk
import ch.og.dictation.recognition.DictationRuntime
import ch.og.dictation.recognition.Engine
import ch.og.dictation.recognition.Recognizer
import ch.og.dictation.recognition.Segmenter
import ch.og.dictation.recognition.Variant
import ch.og.dictation.storage.lastCopy
import android.Manifest
import android.content.Intent
import android.os.Binder
import android.os.Looper
import android.text.InputType
import android.view.KeyEvent
import android.view.Surface
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputBinding
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController

/**
 * One scripted take through the real service on the host (#77): audio, the recognizer and the
 * editor are substituted, everything between is the app's. Each step is written to [lines] as
 * `> step`, followed by every frame a panel showed during it, so a script's lines are its
 * panel-frame sequence. Decoding, the microphone's release and the main thread run only when a
 * step runs them, so the sequence is the same on every run.
 */
class TakeBench : AutoCloseable {
    private class DecodeQueue : Executor {
        private val pending = LinkedBlockingQueue<Runnable>()
        val submitted = AtomicInteger()
        override fun execute(command: Runnable) { pending.add(command); submitted.incrementAndGet() }
        fun drain() { while (true) (pending.poll() ?: return).run() }
    }

    /** The microphone: frames are captured only while started; its release waits for [releaseMicrophone]. */
    private class Input(private val releaseGate: CountDownLatch) : Recorder.AudioInput {
        override val bufferSize = 8
        val releases = AtomicInteger()
        private val frames = LinkedBlockingQueue<ShortArray>()
        @Volatile var running = false
        override fun start() { frames.clear(); running = true }
        override fun read(buffer: ShortArray): Int {
            val frame = frames.take()
            frame.copyInto(buffer)
            return if (frame.isEmpty()) -1 else frame.size
        }
        override fun stop() { running = false; frames.offer(shortArrayOf()) }
        override fun release() { releaseGate.await(5, TimeUnit.SECONDS); releases.incrementAndGet() }
        fun feed(sample: Short) { if (running) frames.add(shortArrayOf(sample)) }
    }

    /** One editor field; [accepts] is what its write answers, [shows] whether the written text can be read back. */
    private inner class Field(service: DictationImeService) : BaseInputConnection(View(service), false) {
        private val content = StringBuilder()
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            commits++
            val accepted = accepts
            if (accepted) content.append(text)
            return accepted
        }
        override fun getTextBeforeCursor(length: Int, flags: Int): CharSequence = if (shows) content.takeLast(length).toString() else ""
        override fun getExtractedText(request: android.view.inputmethod.ExtractedTextRequest?, flags: Int) = null
    }

    private val app = RuntimeEnvironment.getApplication()
    private val decode = DecodeQueue()
    private val accepted = AtomicInteger()
    private val releaseGate = CountDownLatch(1)
    private val input = Input(releaseGate)
    private val runtime: DictationRuntime
    private val wiring: WiringOverride
    private var controller: ServiceController<DictationImeService>? = null
    private var commits = 0
    private var closed = false
    private var fed = 0

    /** What the editor's write answers. */
    var accepts = true
    /** The written text shows in the field; false: the editor answers the write and shows nothing (yet). */
    var shows = true
    /** Steps and the frames each one showed. */
    val lines = mutableListOf<String>()
    /** The panel shown now. */
    lateinit var panel: View
        private set
    private val service get() = controller!!.get()

    init {
        assertFalse(MicrophoneOwner.state().occupied)
        MicrophoneOwner.acceptExplicitStart()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        runtime = DictationRuntime(Variant.HISTORICAL_INT8, decode,
            { Recognizer { samples -> if (samples[0] > 0.4f) "second words" else "first words" } }, {
                object : Segmenter {
                    override fun accept(samples: FloatArray): List<Chunk> { accepted.incrementAndGet(); return listOf(Chunk(samples, true)) }
                    override fun flush(): List<Chunk> = emptyList()
                    override fun reset() {}
                }
            })
        wiring = app.overrideWiring {
            it.with(engine = Engine.prepared(runtime), recorderFactory = { Recorder { input } }, requestPanel = {})
        }
        val root = app.wiring.storage.historyRoot
        HistoryIo.executor.submit { val store = HistoryIo.store(root); store.clear(store.clearableIds()) }.get(2, TimeUnit.SECONDS)
        HistoryNotice.entries.forEach { historyNotices(app).dismiss(it) }
        onPanelFrame = { frame, fresh -> lines += FrameText.line(frame, fresh, app.resources) }
    }

    /** Puts [text] on the build's clipboard, as an earlier Copy did. */
    fun copy(text: String) = app.wiring.storage.clipboard.copy(text)

    /** What the build's clipboard holds: the service's Copy goes through Storage (ADR-028). */
    fun copied(): String? = lastCopy(app)

    /** Runs [action] as the named step. */
    fun step(name: String, action: TakeBench.() -> Unit) {
        lines += "> $name"
        action()
        pump()
        // What History acknowledged during the step belongs to it.
        HistoryIo.executor.submit {}.get(2, TimeUnit.SECONDS)
        pump()
    }

    fun editor(privateField: Boolean = false, pkg: String? = null) = EditorInfo().apply {
        packageName = pkg
        initialSelStart = 0; initialSelEnd = 0 // A real editor reports its selection; the default is -1.
        inputType = InputType.TYPE_CLASS_TEXT or
            if (privateField) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_VARIATION_NORMAL
    }

    /** FUTO's microphone key: our service starts on [info] and shows its panel. */
    fun open(info: EditorInfo = editor()) {
        val created = Robolectric.buildService(DictationImeService::class.java).create()
        controller = created
        val connection = Field(service)
        val inputMethod = service.onCreateInputMethodInterface()
        inputMethod.bindInput(InputBinding(connection, Binder(), 1000, 1000))
        inputMethod.startInput(connection, info)
        panel = service.onCreateInputView()
        Robolectric.buildActivity(android.app.Activity::class.java).setup().get().setContentView(panel)
        service.onStartInputView(info, false)
        decode.drain() // Warm-up.
        pump()
    }

    /** One chunk of speech reaches the recognizer and its text the panel. [second]: other words. */
    fun speak(second: Boolean = false) {
        val before = decode.submitted.get()
        fed++
        input.feed(if (second) 20000 else 1000)
        await { accepted.get() >= fed && decode.submitted.get() > before }
        decode.drain()
        pump()
    }

    fun press(id: Int) { panel.findViewById<View>(id).performClick(); pump() }

    fun systemBack() { service.onKeyDown(KeyEvent.KEYCODE_BACK, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)); pump() }

    /** The microphone's release, which waits for this step, completes. */
    fun releaseMicrophone() {
        releaseGate.countDown()
        await { input.releases.get() == 1 }
    }

    /** The recognizer finishes and History acknowledges, until nothing is left to do. */
    fun finish() {
        repeat(3) {
            decode.drain()
            pump()
            HistoryIo.executor.submit {}.get(2, TimeUnit.SECONDS)
            pump()
        }
    }

    fun commits() = commits

    fun advance(ms: Long) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(ms)) }

    /** The display turns and Android recreates our panel inside the configuration change. */
    fun turn(rotation: Int) {
        val display = app.getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(android.view.Display.DEFAULT_DISPLAY)
        shadowOf(display).setRotation(rotation)
        // Robolectric does not load the android.view.im meta-data, so hand the framework's own
        // configuration tracker what Android reads from method.xml (InputMethodInfo.getConfigChanges).
        val tracker = android.inputmethodservice.InputMethodService::class.java.getDeclaredField("mConfigTracker")
            .apply { isAccessible = true }.get(service)
        tracker.javaClass.getMethod("setHandledConfigChanges", Int::class.java).invoke(tracker, declaredConfigChanges())
        RuntimeEnvironment.setQualifiers(if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) "+land" else "+port")
        service.onConfigurationChanged(android.content.res.Configuration(service.resources.configuration))
        pump()
        panel = service.window.findViewById(R.id.panel)
    }

    private fun declaredConfigChanges(): Int {
        val parser = app.resources.getXml(R.xml.method)
        while (parser.next() != org.xmlpull.v1.XmlPullParser.START_TAG || parser.name != "input-method") Unit
        return parser.getAttributeIntValue("http://schemas.android.com/apk/res/android", "configChanges", 0)
    }

    /** Pixel order (#56 trace): inside the turn Android restarts input on the kept editor and shows the recreated panel. */
    fun rotate(rotation: Int, info: EditorInfo = editor()) {
        turn(rotation)
        service.onStartInput(info, true)
        service.onStartInputView(info, false)
        service.onWindowShown()
        pump()
        panel = service.window.findViewById(R.id.panel)
    }

    /** The display turns, the editor restarts input and hides our panel; the panel does not come back. */
    fun rotateAndLoseThePanel(rotation: Int, info: EditorInfo = editor()) {
        turn(rotation)
        service.onStartInput(info, true)
        service.onFinishInputView(false)
        pump()
    }

    /** The user taps another field: its input starts and our panel is shown on it. */
    fun leaveTheField(info: EditorInfo = editor()) {
        service.onFinishInputView(false)
        service.onCreateInputMethodInterface().startInput(Field(service), info)
        service.onStartInputView(info, false)
        service.onWindowShown()
        pump()
    }

    fun lock() {
        val intent = Intent(Intent.ACTION_SCREEN_OFF)
        shadowOf(app).getReceiversForIntent(intent).single().onReceive(app, intent)
        pump()
    }

    fun unlock(info: EditorInfo = editor()) {
        app.sendBroadcast(Intent(Intent.ACTION_USER_PRESENT))
        service.onStartInput(info, true)
        service.onStartInputView(info, true)
        pump()
    }

    private fun pump() { shadowOf(Looper.getMainLooper()).idle() }

    fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!condition() && System.nanoTime() < deadline) { pump(); Thread.sleep(5) }
        pump()
        assertTrue("timed out waiting for the take", condition())
    }

    /** Ends the take and puts the process back as it was; the recorded [lines] stay. */
    override fun close() {
        if (closed) return
        closed = true
        onPanelFrame = null
        try {
            releaseGate.countDown()
            controller?.let { runCatching { it.destroy() } }
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
}
