package ch.og.dictation

import android.view.inputmethod.EditorInfo
import android.widget.TextView
import android.widget.ImageButton
import android.os.Looper
import java.util.concurrent.Executor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** Exercises the service lifecycle with a controlled platform capture boundary. */
@RunWith(RobolectricTestRunner::class)
class DictationImeLifecycleTest {
    private val engineRuntime = Engine::class.java.getDeclaredField("runtime").apply { isAccessible = true }
    private val modelReady = Engine::class.java.getDeclaredField("modelReadyNs").apply { isAccessible = true }
    private var previousRuntime: Any? = null
    private var previousModelReady: Any? = null
    private lateinit var warmupRuntime: DictationRuntime

    @Before fun isolateEngineWarmup() {
        previousRuntime = engineRuntime.get(Engine)
        previousModelReady = modelReady.get(Engine)
        warmupRuntime = DictationRuntime(Variant.HISTORICAL_INT8, Executor { it.run() }, { Recognizer { "" } }, {
            object : Segmenter {
                override fun accept(samples: FloatArray) = emptyList<Chunk>()
                override fun flush() = emptyList<Chunk>()
                override fun reset() {}
            }
        })
        engineRuntime.set(Engine, warmupRuntime)
    }

    @After fun restoreEngineWarmup() {
        engineRuntime.set(Engine, previousRuntime)
        modelReady.set(Engine, previousModelReady)
        warmupRuntime.close().join()
        MicrophoneOwner.acceptExplicitStart()
    }

    private fun set(service: DictationImeService, name: String, value: Any?) {
        service.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(service, value)
    }
    private fun get(service: DictationImeService, name: String): Any? =
        service.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(service)
    private fun publish(service: DictationImeService, text: String) {
        service.javaClass.getDeclaredMethod("publishPreview", java.lang.Long.TYPE, String::class.java)
            .apply { isAccessible = true }.invoke(service, 1L, text)
    }

    @Test fun replacementServiceOffersRetryForOldUnconfirmedMicrophone() {
        val oldController = Robolectric.buildService(DictationImeService::class.java).create()
        val old = oldController.get()
        val info = EditorInfo().apply { inputType = android.text.InputType.TYPE_CLASS_TEXT }
        old.onStartInput(info, false)
        val session = get(old, "session") as InputSession
        var starts = 0
        var releases = 0
        val capture = MicrophoneCapture(recorder = Recorder { object : Recorder.AudioInput {
            override val bufferSize = 8
            override fun start() { starts++ }
            override fun read(buffer: ShortArray) = -1
            override fun stop() {}
            override fun release() { if (++releases == 1) error("release") }
        } })
        val direct = Executor { it.run() }
        val runtime = DictationRuntime(Variant.HISTORICAL_INT8, direct, { Recognizer { "" } }, {
            object : Segmenter {
                override fun accept(samples: FloatArray) = emptyList<Chunk>()
                override fun flush() = emptyList<Chunk>()
                override fun reset() {}
            }
        })
        val finished = CountDownLatch(1)
        val failed = CountDownLatch(1)
        val listener: (MicrophoneOwner.State) -> Unit = { if (it.releaseFailed) failed.countDown() }
        MicrophoneOwner.observe(listener)
        val output = object : TakeOutput {
            override fun preview(text: String) {}
            override fun commit(text: String) = false
            override fun returnToKeyboard() = false
            override fun finished(result: TakeResult, pcm: ByteArray) { finished.countDown() }
        }
        val take = runtime.open(capture, output, direct)
        take.start()
        capture.setReleaseRetry { take.retryCaptureRelease() }
        set(old, "take", take)
        set(old, "recordingToken", session.token())
        set(old, "takeGeneration", 1L)
        oldController.destroy()
        assertTrue(failed.await(2, TimeUnit.SECONDS))
        assertTrue(take.active)

        val newController = Robolectric.buildService(DictationImeService::class.java).create()
        val replacement = newController.get()
        val panel = replacement.onCreateInputView()
        replacement.onStartInput(info, false)
        replacement.onStartInputView(info, false)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(replacement.getString(R.string.status_release_failed), panel.findViewById<TextView>(R.id.status).text.toString())
        assertEquals(1, starts)
        panel.findViewById<ImageButton>(R.id.microphone).performClick()
        assertTrue(finished.await(2, TimeUnit.SECONDS))
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(MicrophoneOwner.state().occupied)
        assertEquals(1, starts)
        assertFalse(take.active)
        replacement.onStartInputView(info, true)
        assertTrue(MicrophoneOwner.state().explicitStartRequired)
        assertEquals(1, starts)
        MicrophoneOwner.remove(listener)
        newController.destroy()
        runtime.close()
    }

    @Test fun sameEditorRestartClearsPreviewAndReattachDoesNotStartCapture() = exerciseLoss("restart")
    @Test fun hidingInputViewClearsPreviewAndReattachDoesNotStartCapture() = exerciseLoss("hide")
    @Test fun finishingInputClearsPreviewAndReattachDoesNotStartCapture() = exerciseLoss("finish")
    @Test fun destructionInterruptsAndKeepsCaptureUntilAcknowledged() = exerciseLoss("destroy")

    private fun exerciseLoss(loss: String) {
        val controller = Robolectric.buildService(DictationImeService::class.java).create()
        val service = controller.get()
        val info = EditorInfo().apply { inputType = android.text.InputType.TYPE_CLASS_TEXT }
        service.onStartInput(info, false)
        val session = get(service, "session") as InputSession
        var starts = 0
        var drain: ((Result<ByteArray>) -> Unit)? = null
        val capture = object : TakeCapture {
            override fun start(accept: (FloatArray) -> Unit) { starts++ }
            override fun stop(done: (Result<ByteArray>) -> Unit) { drain = done }
            override fun cancel(done: () -> Unit) = done()
        }
        val direct = Executor { it.run() }
        val runtime = DictationRuntime(Variant.HISTORICAL_INT8, direct, { Recognizer { "" } }, {
            object : Segmenter {
                override fun accept(samples: FloatArray) = emptyList<Chunk>()
                override fun flush() = emptyList<Chunk>()
                override fun reset() {}
            }
        })
        val output = object : TakeOutput {
            override fun preview(text: String) {}
            override fun commit(text: String) = false
            override fun returnToKeyboard() = false
            override fun finished(result: TakeResult, pcm: ByteArray) {}
        }
        val take = runtime.open(capture, output, direct)
        take.start()
        set(service, "take", take)
        set(service, "recordingToken", session.token())
        set(service, "takeGeneration", 1L)
        set(service, "previewAllowed", true)
        var panel = service.onCreateInputView()
        publish(service, "synthetic old preview")
        assertEquals("synthetic old preview", panel.findViewById<TextView>(R.id.preview).text.toString())
        when (loss) {
            "restart" -> service.onStartInput(info, true)
            "hide" -> service.onFinishInputView(true)
            "finish" -> service.onFinishInput()
            "destroy" -> controller.destroy()
        }
        assertEquals("", panel.findViewById<TextView>(R.id.preview).text.toString())
        publish(service, "synthetic late preview")
        assertTrue(take.active)
        if (loss == "destroy") {
            assertEquals(1, starts)
            drain!!(Result.success(byteArrayOf()))
            assertFalse(take.active)
            return
        }
        panel = service.onCreateInputView()
        assertEquals("", panel.findViewById<TextView>(R.id.preview).text.toString())
        val privateInfo = EditorInfo().apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        service.onStartInputView(privateInfo, true)
        assertEquals(1, starts)
        drain!!(Result.success(byteArrayOf()))
        publish(service, "synthetic later preview")
        panel = service.onCreateInputView()
        assertEquals("", panel.findViewById<TextView>(R.id.preview).text.toString())
        service.onStartInputView(privateInfo, true)
        assertEquals(1, starts)
        controller.destroy()
    }
}
