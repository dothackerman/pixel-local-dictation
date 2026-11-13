package ch.og.dictation

import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #52 automated Pixel demo: the app-owned editor rotates itself while our panel records, in both
 * #56 modes (keeps focus and shows the keyboard again; recreated with the keyboard hidden, like
 * Signal). The panel is opened with FUTO's own voice key. Speech is the synthetic #54 fixture fed
 * through the take's own recorder seam, so no room audio is used and nothing is saved: the field
 * disables personalized learning. Asserts one take, one capture start, speech from before and
 * after the rotation inserted exactly once, the panel back recording, and FUTO restored by Stop.
 * Writes sanitized trace rows and a summary (events, booleans, counts, ms) to files/lab only.
 */
@RunWith(AndroidJUnit4::class)
class RotationContinuityDemoTest {
    private val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val traceFile = File(context.filesDir, "lab/rotation-probe.jsonl")

    @Test fun keptEditorKeepsTakeAndStopInsertsOnce() = demo("demo52-keep", keep = true)
    @Test fun recreatedHiddenEditorGetsPanelBackAndStopInsertsOnce() = demo("demo52-hidden", keep = false)
    /** Signal's 2026-11-13 order: recreated editor takes input, the keyboard shows, the app hides it. */
    @Test fun signalOrderHideAfterRecreateStopInsertsOnce() =
        demo("demo52-signal-hide", keep = false, kind = SelfRotationProbeActivity.SignalOrder::class.java)
    /**
     * As above, and the app swaps the focused field, leaving the keyboard a detached view's connection.
     * Text cannot reach a dead connection: Stop must refuse the commit, report no insert, keep the
     * text out of the field (it goes to History) and show "Unable to insert text" without handing back.
     */
    @Test fun signalOrderDetachedFieldStopRefusesAndShowsUnableToInsert() =
        demo("demo52-signal-detach", keep = false, kind = SelfRotationProbeActivity.SignalOrder::class.java, detach = true)

    private fun status(text: String) = instrumentation.sendStatus(0, Bundle().apply { putString("stream", text + "\n") })

    private val keys = KeyboardHandoff(instrumentation, ::status)

    private fun until(timeoutMs: Long, what: String, condition: () -> Boolean) {
        if (!keys.waitFor(timeoutMs, condition)) throw AssertionError("timed out: $what")
    }

    private fun currentIme(): String? = keys.currentIme()
    private fun ours(id: Int): AccessibilityNodeInfo? = keys.ourNode(id)

    /** In memory only; never logged or written. */
    private fun previewText(): String = ours(R.id.preview)?.text?.toString().orEmpty()
    private fun statusText(): String = ours(R.id.status)?.text?.toString().orEmpty()

    private fun rows(run: String): List<JSONObject> = if (!traceFile.exists()) emptyList() else
        traceFile.readLines().filter { it.isNotBlank() }.map { JSONObject(it) }.filter { it.optString("run") == run }
    private fun count(run: String, event: String) = rows(run).count { it.getString("event") == event }

    private fun activity(): SelfRotationProbeActivity = SelfRotationProbeActivity.current ?: error("probe editor gone")

    /** Plays the synthetic fixture at real time through the recorder seam, then silence until stopped. */
    private class FixtureInput(private val audio: FloatArray, val starts: AtomicInteger) : Recorder.AudioInput {
        override val bufferSize = 1280
        private val stopped = AtomicBoolean(false)
        private var offset = 0
        private var startedNs = 0L
        override fun start() { starts.incrementAndGet(); startedNs = System.nanoTime() }
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

    private fun demo(case: String, keep: Boolean, kind: Class<out SelfRotationProbeActivity>? = null, detach: Boolean = false) {
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
        val fixture = SyntheticFixtureAudio.file(context.filesDir, "continuous")
        assertTrue("synthetic fixture missing: push lab/issue36-inputs (see #54)", fixture.isFile)
        val audio = SyntheticFixtureAudio.read(fixture)
        val starts = AtomicInteger()
        val previousRecorder = DictationImeService.recorderFactory
        DictationImeService.recorderFactory = { Recorder { FixtureInput(audio, starts) } }
        val run = UUID.randomUUID().toString()
        makeRoomForRotationTrace(context)
        RotationTrace.beginProbe(case, run)
        val marks = RotationTrace(traceFile)
        val editorKind = kind ?: if (keep) SelfRotationProbeActivity.Keep::class.java else SelfRotationProbeActivity::class.java
        var passed = false
        try {
            instrumentation.startActivitySync(Intent(context, editorKind).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(SelfRotationProbeActivity.DETACH_AFTER_HIDE, detach))
            Thread.sleep(600)
            keys.openFuto({ activity().showKeyboard() })
            Thread.sleep(400)
            keys.tapFutoVoiceKey()
            val explicitStart = keys.awaitOurPanelRecording({ count(run, "CAPTURE_STARTED") == 1 })
            if (explicitStart) status("$case: explicit start was needed")
            // Continuous speech yields its first chunk after about 26 s (#54).
            until(45_000, "speech before rotation in the preview") { previewText().isNotBlank() }
            Thread.sleep(1_000)
            val before = previewText().trim()

            marks.event(RotationEvent.PROBE_ROTATE_REQUESTED, flag = true)
            val rotatedAt = SystemClock.uptimeMillis()
            instrumentation.runOnMainSync { activity().rotate(landscape = true) }
            Thread.sleep(500)
            until(ROTATION_HOLD_GAP_MS + 2_000, "panel back recording after rotation") {
                statusText() == context.getString(R.string.status_recording) && ours(R.id.microphone)?.isEnabled == true
            }
            val panelBackMs = SystemClock.uptimeMillis() - rotatedAt
            assertTrue("take ended during rotation", rows(run).none { it.getString("event").startsWith("TERMINAL_") })
            until(45_000, "speech after rotation in the preview") {
                // The preview keeps only the newest ~240 characters, so a long second chunk can push the
                // first one out (Pixel run 20261111T131042): any new text after the restored preview counts.
                previewText().trim().let { it.isNotBlank() && it != before }
            }
            Thread.sleep(1_500)
            assertTrue("panel lost before Stop", ours(R.id.microphone) != null)
            assertTrue(ours(R.id.microphone)!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            until(30_000, "take terminal") { rows(run).any { it.getString("event").startsWith("TERMINAL_") } }
            if (!detach) until(5_000, "FUTO restored by Stop") { currentIme() == futo }
            else until(5_000, "unable to insert shown") { statusText() == context.getString(R.string.status_unable_insert) }
            Thread.sleep(500)
            // Read the panel now: the rotation back and the hide below take it off screen.
            val unableShown = statusText()
            val copyReady = ours(R.id.copy_text)?.let { it.isEnabled && it.isVisibleToUser } == true
            val handedBack = currentIme() == futo
            var inserted = ""
            instrumentation.runOnMainSync { inserted = activity().editor.text.toString().trim() }

            val terminal = rows(run).last { it.getString("event").startsWith("TERMINAL_") }.getString("event")
            val commits = count(run, "COMMIT_ACCEPTED")
            val captures = count(run, "CAPTURE_STARTED")
            val refusals = count(run, "COMMIT_REFUSED_INACTIVE")
            // The preview shows at most the newest 240 characters; compare with its tail.
            val tail = before.takeLast(40)
            val at = inserted.indexOf(tail)
            val beforeInserted = tail.isNotEmpty() && at >= 0
            val afterInserted = beforeInserted && inserted.length - (at + tail.length) >= 10
            val insertedOnce = beforeInserted && inserted.indexOf(tail, at + 1) < 0
            marks.event(RotationEvent.PROBE_ROTATE_REQUESTED, flag = false)
            instrumentation.runOnMainSync { activity().rotate(landscape = false) }
            Thread.sleep(1_500)
            instrumentation.runOnMainSync { activity().hideKeyboard() }
            Thread.sleep(500)
            marks.close()
            RotationTrace.finishProbe(10_000)
            val summary = JSONObject()
                .put("case", case)
                .put("terminal", terminal)
                .put("commitAccepted", commits)
                .put("commitRefusedInactive", refusals)
                .put("captureStarted", captures)
                .put("recorderStarts", starts.get())
                .put("panelBackRecordingMs", panelBackMs)
                .put("speechBeforeInserted", beforeInserted)
                .put("speechAfterInserted", afterInserted)
                .put("insertedOnce", insertedOnce)
                .put("futoRestored", currentIme() == futo)
                .put("serviceDestroyed", rows(run).any { it.getString("event") == "SERVICE_DESTROY" })
            File(context.filesDir, "lab/issue52").mkdirs()
            File(context.filesDir, "lab/issue52/demo-$case-$run.json").writeText(summary.toString(2))
            status("$case summary: $summary")
            assertEquals(1, captures)
            assertEquals(1, starts.get())
            if (detach) {
                assertEquals("TERMINAL_COMMIT_REJECTED", terminal)
                assertEquals(0, commits)
                assertEquals(1, refusals)
                assertEquals("nothing may reach the editor", "", inserted)
                assertEquals(context.getString(R.string.status_unable_insert), unableShown)
                assertFalse("must not hand back to FUTO as if inserted", handedBack)
                assertTrue("Copy control missing or disabled", copyReady)
                passed = true
                return
            }
            assertEquals("TERMINAL_COMPLETED", terminal)
            assertEquals(1, commits)
            assertTrue("speech before rotation not inserted", beforeInserted)
            assertTrue("speech after rotation not inserted", afterInserted)
            assertTrue("text not inserted exactly once", insertedOnce)
            assertTrue("keyboard not restored to FUTO (${keys.selection()})", currentIme() == futo)
            passed = true
        } finally {
            RotationTrace.abortProbe()
            if (!passed && !keys.futoSelected() && !keys.handBackToFuto(showKeyboard)) status("$case: hand-back failed")
            DictationImeService.recorderFactory = previousRecorder
            instrumentation.runOnMainSync { SelfRotationProbeActivity.current?.finish() }
            if (!passed && currentIme() != futo) status("WARNING: keyboard not restored (${keys.selection()})")
        }
    }
}
