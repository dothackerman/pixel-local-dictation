package ch.og.dictation

import ch.og.dictation.diagnostics.GoldenTrace
import ch.og.dictation.diagnostics.RotationEvent
import ch.og.dictation.diagnostics.RotationTrace
import ch.og.dictation.take.ROTATION_HOLD_GAP_MS
import ch.og.dictation.take.ROTATION_SETTLE_MS
import android.view.Surface
import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/**
 * Golden rotation traces (#73, #84): each scripted rotation scenario runs through the real service
 * with the rotation probe on and must write exactly the rows of its golden file, in order. Recorded
 * before the take controller work, so every slice proves the trace unchanged.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RotationTraceGoldenTest {
    private val file = File(RuntimeEnvironment.getApplication().wiring.storage.labFolder, "rotation-probe.jsonl")
    private val lines = mutableListOf<String>()
    private var read = 0
    private lateinit var bench: TakeBench
    /** The trace writer is shared: flushing any trace waits for every row submitted so far. */
    private lateinit var flusher: RotationTrace

    @After fun tearDown() {
        if (::bench.isInitialized) bench.close()
        RotationTrace.abortProbe()
        file.delete()
    }

    private fun rows(): List<JSONObject> {
        flusher.flush()
        return if (file.isFile) file.readLines().map(::JSONObject) else emptyList()
    }

    private fun scenario(name: String, script: TakeBench.() -> Unit) {
        file.delete()
        RotationTrace.beginProbe("app-capture-rotation")
        flusher = RotationTrace(Files.createTempDirectory("rotation-flush").resolve("unused.jsonl").toFile())
        bench = TakeBench()
        bench.script()
        traced("service destroyed") { bench.close() }
        GoldenTrace.check(name, lines)
    }

    /** One step of the script and the rows the service wrote during it. */
    private fun traced(name: String, action: () -> Unit) {
        lines += "> $name"
        action()
        val all = rows()
        lines += all.drop(read).map(GoldenTrace::line)
        read = all.size
    }

    private fun TakeBench.trace(name: String, action: TakeBench.() -> Unit) = traced(name) { step(name, action) }

    /** The microphone's release is reported off the main thread; the step ends once the main thread has it. */
    private fun TakeBench.released() = trace("microphone released") {
        releaseMicrophone()
        await { rows().any { it.getString("event") == RotationEvent.CAPTURE_STOPPED.name } }
    }

    @Test fun rotateWhileRecording() = scenario("rotate-while-recording") {
        trace("open on a text field") { open() }
        trace("speech") { speak() }
        trace("rotate to landscape") { rotate(Surface.ROTATION_90) }
        trace("rotation settled") { advance(ROTATION_HOLD_GAP_MS * 2) }
        trace("more speech") { speak(second = true) }
        trace("rotate back to portrait") { rotate(Surface.ROTATION_0) }
        trace("rotation settled") { advance(ROTATION_HOLD_GAP_MS * 2) }
        trace("Stop") { press(R.id.microphone) }
        released()
        trace("text prepared") { finish() }
        assertEquals(1, commits())
    }

    @Test fun stopThenRotate() = scenario("stop-then-rotate") {
        trace("open on a text field") { open() }
        trace("speech") { speak() }
        trace("Stop") { press(R.id.microphone) }
        trace("rotate to landscape") { rotate(Surface.ROTATION_90) }
        released()
        trace("text prepared") { finish() }
        trace("rotation settled") { advance(ROTATION_SETTLE_MS); finish() }
        assertEquals(1, commits())
    }

    @Test fun panelDoesNotReturnAfterTheRotation() = scenario("panel-does-not-return") {
        trace("open on a text field") { open() }
        trace("speech") { speak() }
        trace("rotate to landscape, the editor hides the panel") { rotateAndLoseThePanel(Surface.ROTATION_90) }
        trace("one millisecond before the gap ends") { advance(ROTATION_HOLD_GAP_MS - 1) }
        trace("the gap ends") { advance(1) }
        released()
        trace("text prepared") { finish() }
        assertEquals(0, commits())
    }

    @Test fun writeSeenAtTheSecondLook() = scenario("write-seen-at-the-second-look") {
        trace("open on a text field") { open() }
        trace("speech") { speak() }
        trace("rotate to landscape") { rotate(Surface.ROTATION_90) }
        trace("rotation settled") { advance(ROTATION_HOLD_GAP_MS * 2) }
        shows = false
        trace("Stop") { press(R.id.microphone) }
        released()
        trace("text prepared, the write does not show") { finish() }
        shows = true
        trace("one millisecond before the second look") { advance(DictationImeService.CONFIRM_DELAY_MS - 1) }
        trace("the second look") { advance(1) }
        assertEquals(1, commits())
    }

    @Test fun writeNeverSeen() = scenario("write-never-seen") {
        trace("open on a text field") { open() }
        trace("speech") { speak() }
        trace("rotate to landscape") { rotate(Surface.ROTATION_90) }
        trace("rotation settled") { advance(ROTATION_HOLD_GAP_MS * 2) }
        shows = false
        trace("Stop") { press(R.id.microphone) }
        released()
        trace("text prepared, the write does not show") { finish() }
        trace("one millisecond before the second look") { advance(DictationImeService.CONFIRM_DELAY_MS - 1) }
        trace("the second look") { advance(1) }
        assertEquals(1, commits())
    }
}
