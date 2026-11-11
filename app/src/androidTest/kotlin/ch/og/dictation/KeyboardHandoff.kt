package ch.og.dictation

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Instrumentation
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager

/**
 * FUTO <-> our panel plumbing shared by the #56 probe and the #52/#53 demos. Touches only our own
 * panel and prompt controls, plus FUTO's voice key once per take. Every wait is bounded.
 *
 * Pixel run 20261111T124640 showed why this exists: our keyboard was still selected, showing it
 * started a take, our Back then opened the "discard?" prompt instead of handing back; and after
 * that interrupted take every later panel opened idle (explicit start required), so "panel
 * recording" never came.
 *
 * Run output stays free of other apps' data (ADR-024): messages report booleans and counts
 * ("selected keyboard is FUTO: false", "voice-key matches: 2"), never FUTO's IME id, package or
 * key labels.
 */

/** Whether an IME id or package name belongs to FUTO Keyboard. */
fun isFuto(idOrPackage: CharSequence?): Boolean = idOrPackage?.startsWith("org.futo.") == true

class KeyboardHandoff(private val instrumentation: Instrumentation, private val status: (String) -> Unit) {
    private val context = instrumentation.targetContext
    private val ui = instrumentation.uiAutomation.also {
        it.serviceInfo = it.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
    }

    enum class Shown { FUTO, OURS, NONE }

    /**
     * The selected keyboard's id from the IME service only; null when unknown. Compare it or
     * pass it to [isFuto]; never print it.
     */
    fun currentIme(): String? =
        runCatching { context.getSystemService(InputMethodManager::class.java).currentInputMethodInfo?.id }.getOrNull()

    fun futoSelected() = isFuto(currentIme())
    fun oursSelected() = currentIme()?.startsWith(context.packageName + "/") == true

    /** Printable selection state: booleans only. */
    fun selection(): String {
        val id = currentIme() ?: return "selected keyboard unknown"
        return "selected keyboard is FUTO: ${isFuto(id)}, ours: ${id.startsWith(context.packageName + "/")}"
    }

    private fun windows(): List<AccessibilityWindowInfo> = runCatching { ui.windows }.getOrDefault(emptyList())

    private fun nodes(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
        if (root == null) return emptyList()
        return listOf(root) + (0 until root.childCount).flatMap { nodes(root.getChild(it)) }
    }

    private fun imeRoots(): List<AccessibilityNodeInfo> =
        windows().filter { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }.mapNotNull { it.root }

    /** Every node in our own windows (the IME window and the prompt attached to it). */
    private fun ourNodes(): List<AccessibilityNodeInfo> =
        windows().mapNotNull { it.root }.filter { it.packageName == context.packageName }.flatMap { nodes(it) }

    /** Which keyboard window is on screen; ours is identified by our panel's own view ids. */
    fun shown(): Shown {
        val roots = imeRoots()
        if (roots.any { isFuto(it.packageName) }) return Shown.FUTO
        if (ourNode(R.id.microphone) != null) return Shown.OURS
        return Shown.NONE
    }

    /** One of our panel's views by id, in any of our windows. */
    fun ourNode(id: Int): AccessibilityNodeInfo? {
        val name = context.resources.getResourceName(id)
        return ourNodes().firstOrNull { it.viewIdResourceName == name && it.isVisibleToUser }
    }

    /** A visible, clickable button of ours by its label (the Cancel choice is inline in the panel). */
    fun ourButton(label: Int): AccessibilityNodeInfo? {
        val text = context.getString(label)
        // Icon-only panel buttons (#36) carry their words as the content description.
        return ourNodes().firstOrNull { it.isClickable && it.isVisibleToUser &&
            ((it.text ?: it.contentDescription)?.toString().equals(text, ignoreCase = true)) }
    }

    /** The inline Cancel choice's Discard action, if the choice is open. */
    private fun ourPromptDiscard(): AccessibilityNodeInfo? {
        val label = context.getString(R.string.discard_take)
        return ourNodes().firstOrNull { it.viewIdResourceName == context.resources.getResourceName(R.id.choice_discard) && it.isVisibleToUser && (it.text ?: it.contentDescription)?.toString() == label }
    }

    fun clickOurs(id: Int) {
        val node = ourNode(id) ?: error("our ${context.resources.getResourceName(id)} not on screen")
        check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "click refused" }
    }

    fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end) { if (condition()) return true; Thread.sleep(50) }
        return condition()
    }

    /** Shows the keyboard in the probe editor until FUTO's window is up. */
    fun openFuto(showKeyboard: () -> Unit, timeoutMs: Long = 12_000) {
        var asked = 0L
        val ok = waitFor(timeoutMs) {
            if (SystemClock.uptimeMillis() - asked > 2_000) { asked = SystemClock.uptimeMillis(); instrumentation.runOnMainSync(showKeyboard) }
            shown() == Shown.FUTO
        }
        if (!ok) throw AssertionError("timed out: FUTO keyboard (${selection()}, shown=${shown()})")
    }

    /** FUTO's voice key, identified by its accessibility label only; refuses if ambiguous. */
    fun tapFutoVoiceKey() {
        val root = imeRoots().firstOrNull { isFuto(it.packageName) }
            ?: error("no FUTO keyboard window")
        val all = nodes(root)
        val labelled = all.filter { n -> n.isVisibleToUser &&
            Regex("(?i)voice|micro|speech|dictat").containsMatchIn(n.contentDescription?.toString() ?: "") }
        val voice = labelled.filter { it.isClickable }.ifEmpty { labelled }
        if (voice.size != 1) error("FUTO voice key not identified; voice-key matches: ${labelled.size}")
        val key = voice.single()
        if (key.isClickable && key.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
        // FUTO's keys are virtual nodes that refuse ACTION_CLICK: tap the key's centre once instead.
        val bounds = Rect().also { key.getBoundsInScreen(it) }
        check(!bounds.isEmpty) { "FUTO voice key has no bounds" }
        val at = SystemClock.uptimeMillis()
        for (action in intArrayOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(at, SystemClock.uptimeMillis(), action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            check(ui.injectInputEvent(event, true)) { "tap refused" }
            event.recycle()
            Thread.sleep(40)
        }
    }

    /**
     * After the voice key: waits for our panel and a started capture. An earlier interrupted take
     * makes the next panel open idle (explicit start required); then taps our microphone once, as
     * the owner would. Returns true when that explicit start was needed.
     */
    fun awaitOurPanelRecording(captureStarted: () -> Boolean, timeoutMs: Long = 10_000): Boolean {
        var explicit = false
        var oursSince = 0L
        val ok = waitFor(timeoutMs) {
            val ours = shown() == Shown.OURS
            if (!ours) oursSince = 0L else if (oursSince == 0L) oursSince = SystemClock.uptimeMillis()
            if (ours && captureStarted()) return@waitFor true
            if (ours && !explicit && SystemClock.uptimeMillis() - oursSince > 1_500) {
                val mic = ourNode(R.id.microphone)
                if (mic != null && mic.isEnabled && mic.contentDescription?.toString() == context.getString(R.string.start_recording)) {
                    explicit = true
                    status("panel opened idle (explicit start required): tapping our microphone")
                    mic.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
            }
            false
        }
        if (!ok) throw AssertionError("timed out: our panel recording (shown=${shown()}, explicitStartTapped=$explicit)")
        return explicit
    }

    /**
     * Leaves FUTO selected using only our controls: Discard on our prompt, else our Back, else
     * shows the keyboard so our panel can be reached. Bounded; never throws. Returns success.
     */
    fun handBackToFuto(showKeyboard: () -> Unit, attempts: Int = 6): Boolean {
        repeat(attempts) { attempt ->
            if (futoSelected()) return true
            val discard = ourPromptDiscard()
            when {
                discard != null -> {
                    status("hand-back $attempt: our prompt up, Discard")
                    discard.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    // Discard opens an Undo window (#36); our Back makes it final and hands back.
                    waitFor(1_000) { futoSelected() || ourNode(R.id.undo_discard) != null }
                    if (!futoSelected()) runCatching { clickOurs(R.id.back) }.onFailure { status("hand-back $attempt: ${it.message}") }
                    waitFor(4_000) { futoSelected() }
                }
                shown() == Shown.OURS -> {
                    status("hand-back $attempt: our Back")
                    runCatching { clickOurs(R.id.back) }.onFailure { status("hand-back $attempt: ${it.message}") }
                    waitFor(2_000) { futoSelected() || ourPromptDiscard() != null }
                }
                else -> {
                    status("hand-back $attempt: our panel not shown (${selection()}); showing keyboard")
                    runCatching { instrumentation.runOnMainSync(showKeyboard) }.onFailure { status("hand-back $attempt: ${it.message}") }
                    waitFor(4_000) { futoSelected() || shown() == Shown.OURS || ourPromptDiscard() != null }
                }
            }
        }
        return futoSelected()
    }
}
