package ch.og.dictation

import ch.og.dictation.diagnostics.LabReceipts
import ch.og.dictation.audio.Recorder
import ch.og.dictation.diagnostics.RotationEvent
import ch.og.dictation.diagnostics.RotationTrace
import ch.og.dictation.probe.SelfRotationProbeActivity
import ch.og.dictation.probe.SyntheticFixtureAudio
import ch.og.dictation.recognition.SAMPLE_RATE
import ch.og.dictation.take.ROTATION_HOLD_GAP_MS
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #53 automated Pixel demo, the one rotation-finishing case the phone keeps (#90): the app-owned
 * editor rotates itself right after Stop, while the final decode is still running, and is recreated
 * with the keyboard hidden. The panel is opened with FUTO's own voice key through [KeyboardHandoff];
 * speech is the synthetic #54 fixture fed through the take's recorder seam (no room audio, nothing
 * saved: the field disables personalized learning). Asserts one take, one capture start (no
 * restarted microphone), exactly one insertion, the panel back finishing, and FUTO restored.
 * Sanitized trace rows and a summary go to files/lab only.
 *
 * Every other boundary of the Stop wait and every Cancel choice through a rotation is decided by the
 * take controller and tested on the host: TakeControllerTest.
 *
 * Owner preconditions: phone unlocked, screen on, FUTO the current keyboard, the fixture pushed
 * to files/lab/issue36-inputs (#54). Run: `phone run ch.og.dictation.RotationFinishingDemoTest`.
 */
@RunWith(AndroidJUnit4::class)
class RotationFinishingDemoTest {
    private val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val traceFile = File(context.wiring.storage.labFolder, "rotation-probe.jsonl")

    /** Rotate right after Stop, while the final decode is still running. */
    @Test fun recreatedEditorRotationBeforeFinalDecodeInsertsOnce() = demo("demo53-hidden-before-decode") {
        stop()
        rotate()
        awaitPanelBack(R.string.status_finishing)
        bound("rotatedBeforeFinalReady", before("PROBE_ROTATE_REQUESTED", "FINAL_READY"))
        expectInsertion()
    }

    // ---- one run ----

    private inner class Run(val case: String, val run: String, val marks: RotationTrace) {
        val bounds = JSONObject()
        var inserted = ""
        var expectsInsertion = false
        var rotatedAt = 0L
        var panelBackMs = -1L
    }
    private var current: Run? = null
    private val starts = AtomicInteger()

    private fun status(text: String) = instrumentation.sendStatus(0, Bundle().apply { putString("stream", text + "\n") })
    private val keys = KeyboardHandoff(instrumentation, ::status)

    private fun until(timeoutMs: Long, what: String, condition: () -> Boolean) {
        if (!keys.waitFor(timeoutMs, condition)) throw AssertionError("timed out: $what")
    }
    private fun currentIme(): String? = keys.currentIme()
    private fun statusText(): String = keys.ourNode(R.id.status)?.text?.toString().orEmpty()
    private fun rows(): List<JSONObject> = LabReceipts.lines(traceFile)
        .map { JSONObject(it) }.filter { it.optString("run") == current!!.run }
    private fun count(event: String) = rows().count { it.getString("event") == event }
    /** First [a] row precedes first [b] row (a missing [b] counts as later). */
    private fun before(a: String, b: String): Boolean {
        val r = rows().map { it.getString("event") }
        val i = r.indexOf(a)
        val j = r.indexOf(b)
        return i >= 0 && (j < 0 || i < j)
    }
    private fun bound(name: String, hit: Boolean) { current!!.bounds.put(name, hit); assertTrue("boundary not reached: $name", hit) }
    private fun activity(): SelfRotationProbeActivity = SelfRotationProbeActivity.current ?: error("probe editor gone")

    private fun stop() {
        until(5_000, "Stop enabled") { keys.ourNode(R.id.microphone)?.isEnabled == true }
        keys.clickOurs(R.id.microphone)
    }
    private fun rotate() {
        current!!.marks.event(RotationEvent.PROBE_ROTATE_REQUESTED, flag = true)
        current!!.rotatedAt = SystemClock.uptimeMillis()
        instrumentation.runOnMainSync { activity().rotate(landscape = true) }
    }
    private fun awaitPanelBack(statusLabel: Int) {
        until(ROTATION_HOLD_GAP_MS + 2_000, "panel back after rotation") { statusText() == context.getString(statusLabel) }
        current!!.panelBackMs = SystemClock.uptimeMillis() - current!!.rotatedAt
    }
    private fun expectInsertion() {
        current!!.expectsInsertion = true
        until(30_000, "take terminal") { rows().any { it.getString("event").startsWith("TERMINAL_") } }
        until(5_000, "FUTO restored by Stop") { keys.futoSelected() }
        Thread.sleep(500)
        instrumentation.runOnMainSync { current!!.inserted = activity().editor.text.toString().trim() }
    }

    /** Plays the synthetic fixture at real time through the recorder seam, then silence until stopped. */
    private class FixtureInput(private val audio: FloatArray, val starts: AtomicInteger) : Recorder.AudioInput {
        override val bufferSize = 1280
        private val stopped = AtomicBoolean(false)
        private var offset = 0
        private var startedNs = 0L
        // A restart after the inline choice's pause continues the fixture where it stopped.
        override fun start() { starts.incrementAndGet(); stopped.set(false); startedNs = System.nanoTime() - offset * 1_000_000_000L / SAMPLE_RATE }
        override fun read(buffer: ShortArray): Int {
            if (stopped.get()) return -1
            val due = startedNs + (offset + buffer.size) * 1_000_000_000L / SAMPLE_RATE
            val waitMs = (due - System.nanoTime()) / 1_000_000L
            if (waitMs > 0) SystemClock.sleep(waitMs)
            if (stopped.get()) return -1
            for (i in buffer.indices) {
                val at = offset + i
                buffer[i] = if (at < audio.size) (audio[at] * 32767f).toInt().coerceIn(-32768, 32767).toShort() else 0
            }
            offset += buffer.size
            return buffer.size
        }
        override fun stop() { stopped.set(true) }
        override fun release() {}
    }

    /** Events after the (first) rotation request, as ms offsets; names and counts only. */
    private fun afterRotation(): Pair<JSONArray, Long?> {
        val all = rows()
        val at = all.firstOrNull { it.getString("event") == "PROBE_ROTATE_REQUESTED" && it.optBoolean("flag") }?.getLong("ns")
            ?: return JSONArray() to null
        val list = JSONArray()
        all.filter { it.getLong("ns") >= at }.take(60).forEach {
            list.put("${it.getString("event")}${if (it.has("flag")) "(" + it.get("flag") + ")" else ""}@${(it.getLong("ns") - at) / 1_000_000}")
        }
        return list to at
    }
    private fun msAfter(at: Long?, event: String, last: Boolean = false): Long? {
        if (at == null) return null
        val hits = rows().filter { it.getString("event") == event && it.getLong("ns") >= at }
        return (if (last) hits.lastOrNull() else hits.firstOrNull())?.let { (it.getLong("ns") - at) / 1_000_000 }
    }

    private fun demo(case: String, steps: () -> Unit) {
        val power = context.getSystemService(android.os.PowerManager::class.java)
        val keyguard = context.getSystemService(android.app.KeyguardManager::class.java)
        assertTrue("phone must be unlocked with the screen on (owner step); the demo never unlocks it",
            power.isInteractive && !keyguard.isKeyguardLocked)
        val showKeyboard = { SelfRotationProbeActivity.current?.showKeyboard(); Unit }
        if (!keys.futoSelected()) {
            // An earlier test may have left our keyboard selected: hand back with our own controls.
            instrumentation.startActivitySync(Intent(context, SelfRotationProbeActivity.Keep::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Thread.sleep(600)
            status("$case: our keyboard still selected, handing back")
            keys.handBackToFuto(showKeyboard)
            instrumentation.runOnMainSync { SelfRotationProbeActivity.current?.finish() }
            Thread.sleep(500)
        }
        val futo = currentIme()
        assertTrue("current keyboard must be FUTO before the demo (${keys.selection()})", isFuto(futo))
        val fixture = SyntheticFixtureAudio.file(context.wiring.storage.labFolder, "continuous")
        assertTrue("synthetic fixture missing: push lab/issue36-inputs (see #54)", fixture.isFile)
        val audio = SyntheticFixtureAudio.read(fixture)
        starts.set(0)
        val wiring = context.overrideWiring { it.with(recorderFactory = { Recorder { FixtureInput(audio, starts) } }) }
        val run = UUID.randomUUID().toString()
        RotationTrace.beginProbe(case, run)
        val state = Run(case, run, RotationTrace(traceFile))
        current = state
        var passed = false
        try {
            instrumentation.startActivitySync(Intent(context, SelfRotationProbeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Thread.sleep(600)
            keys.openFuto({ activity().showKeyboard() })
            Thread.sleep(400)
            keys.tapFutoVoiceKey()
            if (keys.awaitOurPanelRecording({ count("CAPTURE_STARTED") == 1 })) status("$case: explicit start was needed")
            until(5_000, "our panel recording") { statusText() == context.getString(R.string.status_recording) }
            Thread.sleep(6_000) // Some synthetic speech; the final decode transcribes it at Stop.
            steps()
            Thread.sleep(1_000)
            val terminal = rows().lastOrNull { it.getString("event").startsWith("TERMINAL_") }?.getString("event")
            val commits = count("COMMIT_ACCEPTED")
            val head = state.inserted.take(20)
            val insertedOnce = head.isNotEmpty() && state.inserted.indexOf(head, 1) < 0
            val (events, rotatedNs) = afterRotation()
            state.marks.event(RotationEvent.PROBE_ROTATE_REQUESTED, flag = false)
            instrumentation.runOnMainSync { activity().rotate(landscape = false) }
            Thread.sleep(1_500)
            instrumentation.runOnMainSync { SelfRotationProbeActivity.current?.hideKeyboard() }
            Thread.sleep(500)
            state.marks.close()
            RotationTrace.finishProbe(10_000)
            val summary = JSONObject()
                .put("case", case)
                .put("terminal", terminal ?: JSONObject.NULL)
                .put("commitAccepted", commits)
                .put("commitAttempt", count("COMMIT_ATTEMPT"))
                .put("captureStart", count("CAPTURE_START"))
                .put("captureStarted", count("CAPTURE_STARTED"))
                .put("recorderStarts", starts.get())
                .put("insertedNonEmpty", state.inserted.isNotEmpty())
                .put("insertedOnce", insertedOnce)
                .put("panelBackMs", state.panelBackMs)
                .put("lastShownAfterRotationMs", msAfter(rotatedNs, "WINDOW_SHOWN", last = true) ?: JSONObject.NULL)
                .put("finalReadyAfterRotationMs", msAfter(rotatedNs, "FINAL_READY") ?: JSONObject.NULL)
                .put("commitAfterRotationMs", msAfter(rotatedNs, "COMMIT_ACCEPTED") ?: JSONObject.NULL)
                .put("futoRestored", currentIme() == futo)
                .put("serviceDestroyed", rows().any { it.getString("event") == "SERVICE_DESTROY" })
                .put("boundaries", state.bounds)
                .put("afterRotation", events)
            File(context.wiring.storage.labFolder, "issue53").mkdirs()
            File(context.wiring.storage.labFolder, "issue53/demo-$case-$run.json").writeText(summary.toString(2))
            status("$case summary: $summary")
            assertEquals("one take", 1, count("CAPTURE_START"))
            assertEquals("microphone starts", 1, starts.get())
            assertTrue("more than one insertion", commits <= 1)
            if (state.expectsInsertion) {
                assertEquals("TERMINAL_COMPLETED", terminal)
                assertEquals(1, commits)
                assertTrue("text not inserted exactly once", insertedOnce)
            }
            assertTrue("keyboard not restored to FUTO (${keys.selection()})", currentIme() == futo)
            passed = true
        } finally {
            RotationTrace.abortProbe()
            if (!passed && !keys.futoSelected() && !keys.handBackToFuto(showKeyboard)) status("$case: hand-back failed")
            instrumentation.runOnMainSync { SelfRotationProbeActivity.current?.finish() }
            wiring.close()
            if (!passed && currentIme() != futo) status("WARNING: keyboard not restored (${keys.selection()})")
        }
    }
}
