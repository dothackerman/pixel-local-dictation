package ch.og.dictation

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #53 automated Pixel demo: the app-owned editor rotates itself at each boundary of the Stop
 * wait and with each Cancel choice open. The panel is opened with FUTO's own voice key; speech is
 * the synthetic #54 fixture fed through the take's recorder seam (no room audio, nothing saved as
 * audio). Each case asserts one take, one capture start (no restarted microphone), at most one
 * insertion (exactly one where Stop or Keep finishing was chosen), the panel or choice back, and
 * FUTO restored. Cases that reach the History save acknowledgement use an ordinary field and
 * delete the History rows they created. Sanitized trace rows and a summary go to files/lab only.
 *
 * Owner preconditions: phone unlocked, screen on, FUTO the current keyboard, the fixture pushed
 * to files/lab/issue36-inputs (#54). Run: `phone run ch.og.dictation.RotationFinishingDemoTest`.
 */
@RunWith(AndroidJUnit4::class)
class RotationFinishingDemoTest {
    private val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val ui = instrumentation.uiAutomation.also {
        it.serviceInfo = it.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
    }
    private val traceFile = File(context.filesDir, "lab/rotation-probe.jsonl")
    private val historyRoot = File(context.noBackupFilesDir, "history")

    /** Rotate right after Stop, while the final decode is still running. */
    @Test fun recreatedEditorRotationBeforeFinalDecodeInsertsOnce() = demo("demo53-hidden-before-decode") {
        stop()
        rotate()
        awaitPanelBack(R.string.status_finishing)
        bound("rotatedBeforeFinalReady", before("PROBE_ROTATE_REQUESTED", "FINAL_READY"))
        expectInsertion()
    }

    @Test fun keptEditorRotationBeforeFinalDecodeInsertsOnce() = demo("demo53-keep-before-decode", keep = true) {
        stop()
        rotate()
        bound("rotatedBeforeFinalReady", before("PROBE_ROTATE_REQUESTED", "FINAL_READY"))
        expectInsertion()
    }

    /** Decoded, History save not yet acknowledged (its executor is held by the test). */
    @Test fun rotationAfterDecodeBeforeTheHistorySaveInsertsOnce() = demo("demo53-hidden-before-save", eligible = true) {
        val gate = holdHistory()
        stop()
        until(30_000, "final decode") { count("FINAL_READY") == 1 }
        rotate()
        awaitPanelBack(R.string.status_finishing)
        bound("noCommitBeforeSave", count("COMMIT_ATTEMPT") == 0)
        gate.countDown()
        expectInsertion()
    }

    /** Saved and acknowledged, dispatched: a later rotation neither undoes nor repeats the insertion. */
    @Test fun rotationAfterTheAcceptedInsertionChangesNothing() = demo("demo53-hidden-after-commit", eligible = true) {
        stop()
        expectInsertion()
        rotate()
        Thread.sleep(ROTATION_HOLD_GAP_MS + 1_000)
        bound("stillOneCommit", count("COMMIT_ACCEPTED") == 1)
    }

    /** Back during recording, rotate: the choice comes back; Continue keeps the same take. */
    @Test fun recordingChoiceOpenThroughRotationContinues() = demo("demo53-hidden-recording-choice") {
        tap(R.id.back)
        until(5_000, "recording choice") { choice(R.string.continue_recording) != null }
        rotate()
        awaitPanelBack(R.string.status_recording)
        until(ROTATION_SETTLE_MS + 3_000, "recording choice shown again") { choice(R.string.continue_recording) != null }
        bound("choiceNotMade", rows().none { it.getString("event") in setOf("STOP_REQUESTED", "CANCEL_REQUESTED") })
        assertTrue(choice(R.string.continue_recording)!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        Thread.sleep(3_000)
        stop()
        expectInsertion()
    }

    /** Back after Stop before the result, rotate: Keep finishing comes back; nothing dispatched before it. */
    @Test fun finishingChoiceOpenBeforeFinalDecodeHoldsTheResult() = demo("demo53-hidden-finishing-choice-before-decode") {
        stop()
        tap(R.id.back)
        until(5_000, "finishing choice") { choice(R.string.keep_finishing) != null }
        rotate()
        keepFinishingAfterRotation()
    }

    /** The result was decoded and its History save acknowledged under the open choice; then rotate. */
    @Test fun finishingChoiceOpenAfterTheHistorySaveHoldsTheResult() = demo("demo53-hidden-finishing-choice-after-save", eligible = true) {
        stop()
        tap(R.id.back)
        until(5_000, "finishing choice") { choice(R.string.keep_finishing) != null }
        until(30_000, "final decode") { count("FINAL_READY") == 1 }
        HistoryIo.executor.submit {}.get(10, TimeUnit.SECONDS) // The save acknowledgement has run.
        rotate()
        keepFinishingAfterRotation()
    }

    // ---- one run ----

    private inner class Run(val case: String, val run: String, val marks: RotationTrace) {
        val bounds = JSONObject()
        var inserted = ""
        var expectsInsertion = false
    }
    private var current: Run? = null
    private val starts = AtomicInteger()
    private var futo: String? = null

    private fun status(text: String) = instrumentation.sendStatus(0, Bundle().apply { putString("stream", text + "\n") })
    private fun until(timeoutMs: Long, what: String, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end) { if (condition()) return; Thread.sleep(50) }
        throw AssertionError("timed out: $what")
    }
    private fun imeWindow(): AccessibilityWindowInfo? = ui.windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
    private fun imePackage(): String? = imeWindow()?.root?.packageName?.toString()
    private fun currentIme(): String? = context.getSystemService(InputMethodManager::class.java).currentInputMethodInfo?.id
    private fun nodes(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
        if (root == null) return emptyList()
        return listOf(root) + (0 until root.childCount).flatMap { nodes(root.getChild(it)) }
    }
    private fun ours(id: Int): AccessibilityNodeInfo? {
        if (imePackage() != context.packageName) return null
        val name = context.resources.getResourceName(id)
        return nodes(imeWindow()?.root).singleOrNull { it.viewIdResourceName == name }
    }
    private fun statusText(): String = ours(R.id.status)?.text?.toString().orEmpty()
    /** A Cancel choice button in any window (the prompt is attached to our panel's window). */
    private fun choice(label: Int): AccessibilityNodeInfo? = ui.windows.flatMap { nodes(it.root) }
        .firstOrNull { it.isClickable && it.isVisibleToUser && it.text?.toString().equals(context.getString(label), ignoreCase = true) }
    private fun tap(id: Int) = assertTrue(ours(id)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
    private fun rows(): List<JSONObject> = if (!traceFile.exists()) emptyList() else traceFile.readLines()
        .filter { it.isNotBlank() }.map { JSONObject(it) }.filter { it.optString("run") == current!!.run }
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
        until(5_000, "Stop enabled") { ours(R.id.microphone)?.isEnabled == true }
        tap(R.id.microphone)
    }
    private fun rotate() {
        current!!.marks.event(RotationEvent.PROBE_ROTATE_REQUESTED, flag = true)
        instrumentation.runOnMainSync { activity().rotate(landscape = true) }
    }
    private fun awaitPanelBack(statusLabel: Int) = until(ROTATION_HOLD_GAP_MS + 2_000, "panel back after rotation") {
        statusText() == context.getString(statusLabel)
    }
    private fun keepFinishingAfterRotation() {
        awaitPanelBack(R.string.status_finishing)
        until(ROTATION_SETTLE_MS + 3_000, "finishing choice shown again") { choice(R.string.keep_finishing) != null }
        until(30_000, "final decode") { count("FINAL_READY") == 1 }
        Thread.sleep(1_000)
        bound("nothingDispatchedUnderChoice", count("COMMIT_ATTEMPT") == 0)
        assertTrue(choice(R.string.keep_finishing)!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        expectInsertion()
    }
    private fun holdHistory(): CountDownLatch {
        val gate = CountDownLatch(1)
        HistoryIo.executor.execute { gate.await(30, TimeUnit.SECONDS) }
        heldHistory = gate
        return gate
    }
    private var heldHistory: CountDownLatch? = null
    private fun expectInsertion() {
        current!!.expectsInsertion = true
        until(30_000, "take terminal") { rows().any { it.getString("event").startsWith("TERMINAL_") } }
        until(5_000, "FUTO restored by Stop") { currentIme() == futo }
        Thread.sleep(500)
        instrumentation.runOnMainSync { current!!.inserted = activity().editor.text.toString().trim() }
    }

    /** FUTO's voice key, identified by its accessibility label only; refuses if ambiguous. */
    private fun clickFutoVoiceKey() {
        val all = nodes(imeWindow()?.root ?: error("no keyboard window"))
        val labelled = all.filter { n -> n.isVisibleToUser &&
            Regex("(?i)voice|micro|speech|dictat").containsMatchIn(n.contentDescription?.toString() ?: "") }
        val voice = labelled.filter { it.isClickable }.ifEmpty { labelled }
        if (voice.size != 1) error("FUTO voice key not identified; labels: " +
            all.mapNotNull { it.contentDescription?.toString() }.distinct().joinToString())
        val key = voice.single()
        if (key.isClickable && key.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
        val bounds = android.graphics.Rect().also { key.getBoundsInScreen(it) }
        check(!bounds.isEmpty) { "FUTO voice key has no bounds" }
        val at = SystemClock.uptimeMillis()
        for (action in intArrayOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
            val event = android.view.MotionEvent.obtain(at, SystemClock.uptimeMillis(), action,
                bounds.exactCenterX(), bounds.exactCenterY(), 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            check(ui.injectInputEvent(event, true)) { "tap on FUTO voice key refused" }
            event.recycle()
        }
    }

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

    private fun historyIds(): Set<String> = HistoryIo.executor.submit<Set<String>> {
        HistoryIo.store(historyRoot).list().map { it.id }.toSet()
    }.get(10, TimeUnit.SECONDS)

    private fun demo(case: String, keep: Boolean = false, eligible: Boolean = false, steps: () -> Unit) {
        val power = context.getSystemService(android.os.PowerManager::class.java)
        val keyguard = context.getSystemService(android.app.KeyguardManager::class.java)
        assertTrue("phone must be unlocked with the screen on (owner step); the demo never unlocks it",
            power.isInteractive && !keyguard.isKeyguardLocked)
        futo = currentIme()
        assertTrue("current keyboard must be FUTO before the demo, was $futo", futo?.startsWith("org.futo.") == true)
        val fixture = SyntheticFixtureAudio.file(context.filesDir, "continuous")
        assertTrue("synthetic fixture missing: push lab/issue36-inputs (see #54)", fixture.isFile)
        val audio = SyntheticFixtureAudio.read(fixture)
        starts.set(0)
        val previousRecorder = DictationImeService.recorderFactory
        DictationImeService.recorderFactory = { Recorder { FixtureInput(audio, starts) } }
        val keptBefore = if (eligible) historyIds() else emptySet()
        val run = UUID.randomUUID().toString()
        RotationTrace.beginProbe(case, run)
        val state = Run(case, run, RotationTrace(traceFile))
        current = state
        val kind = if (keep) SelfRotationProbeActivity.Keep::class.java else SelfRotationProbeActivity::class.java
        var passed = false
        try {
            instrumentation.startActivitySync(Intent(context, kind).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(SelfRotationProbeActivity.HISTORY_ELIGIBLE, eligible))
            Thread.sleep(600)
            instrumentation.runOnMainSync { activity().showKeyboard() }
            until(5_000, "FUTO keyboard") { imePackage()?.startsWith("org.futo.") == true }
            Thread.sleep(400)
            clickFutoVoiceKey()
            until(10_000, "our panel recording") { count("CAPTURE_STARTED") == 1 && statusText() == context.getString(R.string.status_recording) }
            Thread.sleep(6_000) // Some synthetic speech; the final decode transcribes it at Stop.
            steps()
            Thread.sleep(1_000)
            val terminal = rows().lastOrNull { it.getString("event").startsWith("TERMINAL_") }?.getString("event")
            val commits = count("COMMIT_ACCEPTED")
            val head = state.inserted.take(20)
            val insertedOnce = head.isNotEmpty() && state.inserted.indexOf(head, 1) < 0
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
                .put("captureStart", count("CAPTURE_START"))
                .put("captureStarted", count("CAPTURE_STARTED"))
                .put("recorderStarts", starts.get())
                .put("insertedNonEmpty", state.inserted.isNotEmpty())
                .put("insertedOnce", insertedOnce)
                .put("futoRestored", currentIme() == futo)
                .put("serviceDestroyed", rows().any { it.getString("event") == "SERVICE_DESTROY" })
                .put("boundaries", state.bounds)
            File(context.filesDir, "lab/issue53").mkdirs()
            File(context.filesDir, "lab/issue53/demo-$case-$run.json").writeText(summary.toString(2))
            status("$case summary: $summary")
            assertEquals("one take", 1, count("CAPTURE_START"))
            assertEquals("no restarted microphone", 1, starts.get())
            assertTrue("more than one insertion", commits <= 1)
            if (state.expectsInsertion) {
                assertEquals("TERMINAL_COMPLETED", terminal)
                assertEquals(1, commits)
                assertTrue("text not inserted exactly once", insertedOnce)
            }
            assertEquals("keyboard not restored to FUTO", futo, currentIme())
            passed = true
        } finally {
            heldHistory?.countDown()
            heldHistory = null
            DictationImeService.recorderFactory = previousRecorder
            RotationTrace.abortProbe()
            instrumentation.runOnMainSync { SelfRotationProbeActivity.current?.finish() }
            if (eligible) runCatching {
                // Synthetic-speech rows this run created; the owner's own History stays untouched.
                Thread.sleep(1_000)
                val created = historyIds() - keptBefore
                HistoryIo.executor.submit { created.forEach { HistoryIo.store(historyRoot).delete(it) } }.get(10, TimeUnit.SECONDS)
            }
            if (!passed && currentIme() != futo) status("WARNING: keyboard left on ${currentIme()}")
        }
    }
}
