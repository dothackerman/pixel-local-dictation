package ch.og.dictation

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsAnimation
import android.graphics.Insets
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

/** Host observation; optional animation callback is a controlled, behavior-affecting variant. */
class EditorProbeActivity : Activity(), Choreographer.FrameCallback {
    companion object {
        const val EXTRA_ANIMATION_CALLBACK = "ch.og.dictation.editorAnimationCallback"
    }

    data class Receipt(val geometryRows: Int, val sawIme: Boolean, val reason: String, val failure: String?)

    val receipt = CompletableFuture<Receipt>()
    private val writer = Executors.newSingleThreadExecutor { Thread(it, "editor-geometry") }
    private val writeFailure = AtomicReference<String?>(null)
    private val handler = Handler(Looper.getMainLooper())
    private val startNs = System.nanoTime()
    private val durationMs = 90_000L
    private val maxRows = 4096
    private val maxBytes = 2 * 1024 * 1024L
    private lateinit var root: FrameLayout
    private lateinit var editor: EditText
    private var lastGeometry: String? = null
    private var geometryRows = 0
    private var observationRows = 0
    private var animationCallback = false
    private val mode get() = if (animationCallback) "animationCallback" else "plain"
    private val animations = mutableMapOf<WindowInsetsAnimation, Int>()
    private var nextAnimation = 0
    private var sawIme = false
    private var finished = false
    private var requestedKeyboard = false
    private val deadline = Runnable { stop("duration") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        animationCallback = intent.getBooleanExtra(EXTRA_ANIMATION_CALLBACK, false)
        root = FrameLayout(this)
        if (animationCallback) installAnimationCallback()
        val instructions = TextView(this).apply {
            text = "Geometry probe · 90 seconds\nTap keyboard mic, then Cancel. Do this twice total.\nNo typing needed. Keep this editor open."
            setPadding(dp(16), dp(48), dp(16), dp(16))
        }
        root.addView(instructions, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        editor = EditText(this).apply {
            hint = "Focus here; use current keyboard microphone"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            gravity = Gravity.TOP
        }
        root.addView(editor, FrameLayout.LayoutParams(-1, dp(120), Gravity.TOP).apply {
            setMargins(dp(16), dp(160), dp(16), 0)
        })
        setContentView(root)
        enqueue("start", JSONObject().put("durationMs", durationMs).put("maxRows", maxRows))
        editor.requestFocus()
        Choreographer.getInstance().postFrameCallback(this)
        handler.postDelayed(deadline, durationMs)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !requestedKeyboard && !finished) {
            requestedKeyboard = true
            editor.post {
                if (!finished) getSystemService(InputMethodManager::class.java)
                    .showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun doFrame(frameTimeNanos: Long) {
        if (finished) return
        if (System.nanoTime() - startNs >= durationMs * 1_000_000) {
            stop("duration")
            return
        }
        val insets = root.rootWindowInsets
        if (root.isLaidOut && editor.isLaidOut && insets != null) {
            val ime = insets.getInsets(WindowInsets.Type.ime())
            val visible = insets.isVisible(WindowInsets.Type.ime())
            sawIme = sawIme || visible
            val geometry = JSONObject().put("imeVisible", visible)
                .put("imeLeft", ime.left).put("imeTop", ime.top)
                .put("imeRight", ime.right).put("imeBottom", ime.bottom)
                .put("decor", bounds(window.decorView)).put("root", bounds(root))
                .put("editor", bounds(editor)).toString()
            if (geometry != lastGeometry) {
                lastGeometry = geometry
                geometryRows++
                record("geometry", JSONObject(geometry))
                if (finished) return
            }
        }
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun installAnimationCallback() {
        root.setWindowInsetsAnimationCallback(object : WindowInsetsAnimation.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
            override fun onPrepare(animation: WindowInsetsAnimation) {
                animationRow("animationPrepare", animation)
            }

            override fun onStart(animation: WindowInsetsAnimation, bounds: WindowInsetsAnimation.Bounds): WindowInsetsAnimation.Bounds {
                animationRow("animationStart", animation, JSONObject()
                    .put("lower", insetValues(bounds.lowerBound)).put("upper", insetValues(bounds.upperBound)))
                return bounds
            }

            override fun onProgress(insets: WindowInsets, runningAnimations: MutableList<WindowInsetsAnimation>): WindowInsets {
                for (animation in runningAnimations) animationRow("animationProgress", animation,
                    JSONObject().put("ime", insetValues(insets.getInsets(WindowInsets.Type.ime()))))
                return insets
            }

            override fun onEnd(animation: WindowInsetsAnimation) {
                animationRow("animationEnd", animation)
                animations.remove(animation)
            }
        })
    }

    private fun insetValues(insets: Insets) = JSONObject().put("left", insets.left)
        .put("top", insets.top).put("right", insets.right).put("bottom", insets.bottom)

    private fun animationRow(phase: String, animation: WindowInsetsAnimation, values: JSONObject = JSONObject()) {
        if (finished || animation.typeMask and WindowInsets.Type.ime() == 0) return
        val number = animations.getOrPut(animation) { ++nextAnimation }
        record(phase, values.put("animation", number).put("typeMask", animation.typeMask)
            .put("durationMs", animation.durationMillis).put("fraction", animation.fraction)
            .put("interpolatedFraction", animation.interpolatedFraction))
    }

    private fun record(phase: String, values: JSONObject) {
        observationRows++
        enqueue(phase, values)
        if (observationRows >= maxRows) stop("rows")
    }

    private fun bounds(view: View): JSONObject {
        val position = IntArray(2)
        view.getLocationOnScreen(position)
        return JSONObject().put("x", position[0]).put("y", position[1])
            .put("width", view.width).put("height", view.height)
    }

    private fun enqueue(phase: String, values: JSONObject) {
        val line = JSONObject().put("protocol", "editor-geometry-v1")
            .put("clock", "CLOCK_MONOTONIC").put("runStartNs", startNs)
            .put("ns", System.nanoTime()).put("mode", mode).put("phase", phase).put("values", values)
            .toString() + "\n"
        writer.execute {
            if (writeFailure.get() == null) runCatching {
                val file = File(filesDir, "lab/editor-geometry.jsonl")
                file.parentFile?.mkdirs()
                check(file.length() + line.toByteArray(Charsets.UTF_8).size <= maxBytes) {
                    "editor geometry file full; pull and archive before collecting again"
                }
                file.appendText(line)
            }.onFailure { writeFailure.compareAndSet(null, it.message ?: "write failed") }
        }
    }

    private fun stop(reason: String) {
        if (finished) return
        finished = true
        handler.removeCallbacks(deadline)
        Choreographer.getInstance().removeFrameCallback(this)
        enqueue("end", JSONObject().put("reason", reason).put("geometryRows", geometryRows)
            .put("observationRows", observationRows).put("sawIme", sawIme))
        val rows = geometryRows
        val visible = sawIme
        writer.execute { receipt.complete(Receipt(rows, visible, reason, writeFailure.get())) }
        writer.shutdown()
    }

    override fun onDestroy() {
        stop("destroyed")
        super.onDestroy()
    }
}
