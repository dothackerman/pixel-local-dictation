package ch.og.dictation

import ch.og.dictation.audio.MicrophoneCapture
import ch.og.dictation.audio.MicrophoneOwner
import ch.og.dictation.audio.Recorder
import ch.og.dictation.ime.InputSession
import ch.og.dictation.recognition.Chunk
import ch.og.dictation.recognition.DictationRuntime
import ch.og.dictation.recognition.Engine
import ch.og.dictation.recognition.Recognizer
import ch.og.dictation.recognition.Segmenter
import ch.og.dictation.recognition.Variant
import ch.og.dictation.take.TakeCapture
import ch.og.dictation.take.TakeOutput
import ch.og.dictation.take.TakeResult
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
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/** Exercises the service lifecycle with a controlled platform capture boundary. */
@RunWith(RobolectricTestRunner::class)
class DictationImeLifecycleTest {
    private lateinit var wiring: WiringOverride
    private lateinit var warmupRuntime: DictationRuntime

    @Before fun isolateEngineWarmup() {
        warmupRuntime = DictationRuntime(Variant.HISTORICAL_INT8, Executor { it.run() }, { Recognizer { "" } }, {
            object : Segmenter {
                override fun accept(samples: FloatArray) = emptyList<Chunk>()
                override fun flush() = emptyList<Chunk>()
                override fun reset() {}
            }
        })
        wiring = RuntimeEnvironment.getApplication().overrideWiring { it.with(engine = Engine.prepared(warmupRuntime)) }
    }

    @After fun restoreEngineWarmup() {
        wiring.close()
        warmupRuntime.close().join()
        MicrophoneOwner.acceptExplicitStart()
    }

    /** Take state lives in the service's take controller (#85); these reach it by name. */
    private fun controller(service: DictationImeService): Any =
        service.javaClass.getDeclaredField("controller").apply { isAccessible = true }.get(service)!!
    private fun set(service: DictationImeService, name: String, value: Any?) {
        val controller = controller(service)
        controller.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(controller, value)
    }
    private fun get(service: DictationImeService, name: String): Any? {
        val controller = controller(service)
        return controller.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(controller)
    }
    private fun publish(service: DictationImeService, text: String) {
        val controller = controller(service)
        controller.javaClass.getDeclaredMethod("publishPreview", TAKE_RECORD, String::class.java)
            .apply { isAccessible = true }.invoke(controller, get(service, "latest"), text)
    }
    /** The planted take becomes the newest one: the controller gets a per-take record for it (#86). */
    private fun plantRecord(service: DictationImeService) =
        set(service, "latest", TAKE_RECORD.getDeclaredConstructor(java.lang.Long.TYPE).apply { isAccessible = true }.newInstance(1L))

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
        plantRecord(old)
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
        plantRecord(service)
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

    private companion object {
        val TAKE_RECORD: Class<*> = Class.forName("ch.og.dictation.take.TakeController\$Take")
    }
}
