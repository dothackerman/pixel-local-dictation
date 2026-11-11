package ch.og.dictation

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Instrumentation
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #56: an app-owned editor rotates itself (requested orientation, no phone setting) while our
 * panel records or finishes. The keyboard is opened by FUTO's own voice key, exactly as the owner
 * does; the probe clicks only that key and our own panel's controls. Records sanitized trace
 * rows (event names, booleans, rotation integers, monotonic ns) and a summary in files/lab.
 * Room audio is captured in memory only; the field is excluded from History.
 */
@RunWith(AndroidJUnit4::class)
class SelfRotationProbeTest {
    private val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val ui = instrumentation.uiAutomation.also {
        it.serviceInfo = it.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
    }
    private val traceFile = File(context.filesDir, "lab/rotation-probe.jsonl")

    @Test fun keepFocusDuringCapture() = probe("self-keep-capture", keep = true, finishing = false)
    @Test fun keepFocusDuringStopWait() = probe("self-keep-finishing", keep = true, finishing = true)
    @Test fun recreatedHiddenDuringCapture() = probe("self-hidden-capture", keep = false, finishing = false)
    @Test fun recreatedHiddenDuringStopWait() = probe("self-hidden-finishing", keep = false, finishing = true)

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

    /** FUTO's voice key, identified by its accessibility label only; refuses if ambiguous. */
    private fun clickFutoVoiceKey() {
        val root = imeWindow()?.root ?: error("no keyboard window")
        val all = nodes(root)
        val labelled = all.filter { n -> n.isVisibleToUser &&
            Regex("(?i)voice|micro|speech|dictat").containsMatchIn(n.contentDescription?.toString() ?: "") }
        val voice = labelled.filter { it.isClickable }.ifEmpty { labelled }
        if (voice.size != 1) error("FUTO voice key not identified; matches: " +
            labelled.joinToString { "${it.contentDescription}/${it.className}/clickable=${it.isClickable}" } + "; labels: " +
            all.mapNotNull { it.contentDescription?.toString() }.distinct().joinToString())
        // FUTO's keys are virtual nodes that refuse ACTION_CLICK: tap the key's centre once instead.
        val key = voice.single()
        if (key.isClickable && key.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
        val bounds = android.graphics.Rect().also { key.getBoundsInScreen(it) }
        check(!bounds.isEmpty) { "FUTO voice key has no bounds" }
        val at = SystemClock.uptimeMillis()
        for (action in intArrayOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
            val event = android.view.MotionEvent.obtain(at, SystemClock.uptimeMillis(), action,
                bounds.exactCenterX(), bounds.exactCenterY(), 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            assertTrue(ui.injectInputEvent(event, true))
            event.recycle()
            Thread.sleep(40)
        }
    }

    private fun clickOurs(id: Int) {
        val name = context.resources.getResourceName(id)
        val node = nodes(imeWindow()?.root).singleOrNull { it.viewIdResourceName == name } ?: error("our $name not on screen")
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    /**
     * Shows our idle panel in the probe editor and taps its Back, which hands back to the previous
     * keyboard. Retries: the editor may be recreated or still settling after a rotation.
     */
    private fun handBackToFuto() {
        repeat(4) { attempt ->
            if (currentIme()?.startsWith("org.futo.") == true) return
            instrumentation.runOnMainSync { SelfRotationProbeActivity.current?.showKeyboard() }
            val end = SystemClock.uptimeMillis() + 5_000
            while (SystemClock.uptimeMillis() < end && imePackage() != context.packageName) Thread.sleep(50)
            if (imePackage() == context.packageName) {
                Thread.sleep(500)
                runCatching { clickOurs(R.id.back) }.onFailure { status("hand-back attempt $attempt: ${it.message}") }
                val back = SystemClock.uptimeMillis() + 4_000
                while (SystemClock.uptimeMillis() < back && currentIme()?.startsWith("org.futo.") != true) Thread.sleep(50)
            } else status("hand-back attempt $attempt: our panel not shown")
        }
        until(1_000, "FUTO restored") { currentIme()?.startsWith("org.futo.") == true }
    }

    private fun rows(run: String): List<JSONObject> = if (!traceFile.exists()) emptyList() else
        traceFile.readLines().filter { it.isNotBlank() }.map { JSONObject(it) }.filter { it.optString("run") == run }

    private fun activity(): SelfRotationProbeActivity = SelfRotationProbeActivity.current ?: error("probe editor gone")

    private fun probe(case: String, keep: Boolean, finishing: Boolean) {
        val power = context.getSystemService(android.os.PowerManager::class.java)
        val keyguard = context.getSystemService(android.app.KeyguardManager::class.java)
        status("$case: interactive=${power.isInteractive} keyguardLocked=${keyguard.isKeyguardLocked}")
        assertTrue("phone must be unlocked with the screen on (owner step); the probe never unlocks it",
            power.isInteractive && !keyguard.isKeyguardLocked)
        val kind = if (keep) SelfRotationProbeActivity.Keep::class.java else SelfRotationProbeActivity::class.java
        instrumentation.startActivitySync(Intent(context, kind).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Thread.sleep(600)
        // An earlier case may have left our IME selected: hand back through our own panel's Back.
        if (currentIme()?.startsWith(context.packageName + "/") == true) {
            status("$case: our keyboard still selected, handing back with our Back")
            handBackToFuto()
        }
        val futo = currentIme()
        assertTrue("current keyboard must be FUTO before the probe, was $futo", futo?.startsWith("org.futo.") == true)
        val run = UUID.randomUUID().toString()
        RotationTrace.beginProbe(case, run)
        val marks = RotationTrace(traceFile)
        val polling = AtomicBoolean(true)
        val audio = context.getSystemService(AudioManager::class.java)
        // Our own recordings only (the platform returns only this app's configurations).
        val poller = Thread {
            var last: String? = null
            while (polling.get()) {
                val configs = audio.activeRecordingConfigurations
                val state = if (configs.isEmpty()) "none" else if (configs.all { it.isClientSilenced }) "silenced" else "active"
                if (state != last) {
                    last = state
                    marks.event(RotationEvent.PROBE_RECORDING_STATE, active = state != "none", flag = state == "silenced")
                }
                Thread.sleep(25)
            }
        }.apply { isDaemon = true; start() }
        var passed = false
        try {
            var asked = 0L
            until(12_000, "FUTO keyboard") {
                if (SystemClock.uptimeMillis() - asked > 2_000) {
                    asked = SystemClock.uptimeMillis()
                    instrumentation.runOnMainSync { activity().showKeyboard() }
                }
                imePackage()?.startsWith("org.futo.") == true
            }
            Thread.sleep(400)
            clickFutoVoiceKey()
            until(10_000, "our panel recording") {
                imePackage() == context.packageName && rows(run).any { it.getString("event") == "CAPTURE_STARTED" }
            }
            Thread.sleep(1_500)
            if (finishing) clickOurs(R.id.microphone)
            marks.event(RotationEvent.PROBE_ROTATE_REQUESTED, flag = true)
            instrumentation.runOnMainSync { activity().rotate(landscape = true) }
            Thread.sleep(4_000)
            val panelBack = imePackage() == context.packageName
            var editorVisible = false
            instrumentation.runOnMainSync { editorVisible = SelfRotationProbeActivity.current?.editorVisibleAboveKeyboard() == true }
            status("$case: panelVisibleAfterRotation=$panelBack editorVisibleAbovePanel=$editorVisible")
            if (!finishing && panelBack && rows(run).none { it.getString("event").startsWith("TERMINAL_") }) {
                Thread.sleep(1_500)
                clickOurs(R.id.microphone)
            }
            until(20_000, "take terminal") { rows(run).any { it.getString("event").startsWith("TERMINAL_") } }
            Thread.sleep(800)
            marks.event(RotationEvent.PROBE_ROTATE_REQUESTED, flag = false)
            instrumentation.runOnMainSync { activity().rotate(landscape = false) }
            Thread.sleep(2_000)
            // Leave FUTO as the keyboard: our idle panel's Back hands back to it.
            if (currentIme() != futo) handBackToFuto()
            instrumentation.runOnMainSync { activity().hideKeyboard() }
            Thread.sleep(800)
            polling.set(false); poller.join(1_000)
            marks.close()
            val complete = RotationTrace.finishProbe(10_000)
            val summary = summarize(rows(run), panelBack, editorVisible)
            File(context.filesDir, "lab/issue56").mkdirs()
            File(context.filesDir, "lab/issue56/probe-$case-$run.json").writeText(summary.toString(2))
            status("$case summary: $summary")
            assertTrue("trace incomplete", complete)
            assertEquals("keyboard not restored to FUTO", futo, currentIme())
            passed = true
        } finally {
            polling.set(false)
            RotationTrace.abortProbe()
            if (!passed) runCatching { handBackToFuto() }.onFailure { status("$case: hand-back failed: ${it.message}") }
            instrumentation.runOnMainSync { SelfRotationProbeActivity.current?.finish() }
            if (!passed && currentIme() != futo) status("WARNING: keyboard left on ${currentIme()}")
        }
    }

    /** Sanitized: event names, booleans, rotation integers and millisecond differences only. */
    private fun summarize(rows: List<JSONObject>, panelBack: Boolean, editorVisible: Boolean): JSONObject {
        val ordered = rows.sortedBy { it.getLong("ns") }
        val rotateAt = ordered.first { it.getString("event") == "PROBE_ROTATE_REQUESTED" && it.optBoolean("flag") }
        val start = rotateAt.getLong("ns")
        val window = ordered.filter { it.getLong("ns") >= start && it.getLong("ns") <= start + 4_000_000_000 }
        fun ms(row: JSONObject?) = row?.let { (it.getLong("ns") - start) / 1_000_000 }
        val observed = window.firstOrNull { it.optLong("rotations", 0) > 0 && it.has("rotation") }
        val interrupting = setOf("INPUT_START", "INPUT_VIEW_FINISH", "INPUT_FINISH", "WINDOW_HIDDEN")
        val firstInterrupt = window.firstOrNull { it.getString("event") in interrupting }
        val drawn = window.firstOrNull { it.getString("event") == "PANEL_DRAWN" }
        val configuration = window.firstOrNull { it.getString("event") == "CONFIGURATION" }
        val silenced = ordered.any { it.getString("event") == "PROBE_RECORDING_STATE" && it.optBoolean("flag") }
        return JSONObject()
            .put("callbackOrder", org.json.JSONArray(window.map { it.getString("event") }.filter { !it.startsWith("PROBE_RECORDING") }))
            .put("rotationObservedMs", ms(observed) ?: JSONObject.NULL)
            .put("rotationObservedBy", observed?.getString("event") ?: JSONObject.NULL)
            .put("firstInterruptingEvent", firstInterrupt?.getString("event") ?: JSONObject.NULL)
            .put("firstInterruptingMs", ms(firstInterrupt) ?: JSONObject.NULL)
            .put("rotationObservableAtFirstInterrupt", firstInterrupt?.let { it.optLong("rotations", 0) > 0 } ?: JSONObject.NULL)
            .put("serviceDestroyed", ordered.any { it.getString("event") == "SERVICE_DESTROY" })
            .put("serviceInstances", ordered.filter { it.getString("event") != "PROBE_ROTATE_REQUESTED" && it.getString("event") != "PROBE_RECORDING_STATE" }
                .map { it.getLong("service") }.distinct().size)
            .put("panelDrawnAfterRotationMs", ms(drawn) ?: JSONObject.NULL)
            .put("panelDrawnAfterObservedRotationMs", if (drawn != null && observed != null) (drawn.getLong("ns") - observed.getLong("ns")) / 1_000_000 else JSONObject.NULL)
            .put("panelVisibleAfterRotation", panelBack)
            .put("editorVisibleAbovePanel", editorVisible)
            .put("terminal", ordered.lastOrNull { it.getString("event").startsWith("TERMINAL_") }?.getString("event") ?: JSONObject.NULL)
            .put("commitAccepted", ordered.any { it.getString("event") == "COMMIT_ACCEPTED" })
            .put("captureStarts", ordered.count { it.getString("event") == "CAPTURE_STARTED" })
            .put("recordingSilencedObserved", silenced)
            .put("configDiff", configuration?.opt("configDiff") ?: JSONObject.NULL)
            .put("handledConfig", configuration?.opt("handledConfig") ?: JSONObject.NULL)
            .put("restartInsideConfiguration", configuration?.let { c ->
                val done = window.firstOrNull { it.getString("event") == "CONFIGURATION_DONE" && it.getLong("ns") > c.getLong("ns") }
                window.any { it.getString("event") == "INPUT_START" && it.optBoolean("flag") &&
                    it.getLong("ns") > c.getLong("ns") && (done == null || it.getLong("ns") < done.getLong("ns")) }
            } ?: JSONObject.NULL)
            .put("rotations", org.json.JSONArray(window.mapNotNull { if (it.has("rotation")) it.getInt("rotation") else null }.distinct()))
    }
}
