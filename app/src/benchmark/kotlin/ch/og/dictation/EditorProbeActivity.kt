package ch.og.dictation

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

/** Passive host observation. No animation callbacks, inset listeners or editor-content reads. */
class EditorProbeActivity : Activity(), Choreographer.FrameCallback {
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
    private var sawIme = false
    private var finished = false
    private var requestedKeyboard = false
    private val deadline = Runnable { stop("duration") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = FrameLayout(this)
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
                enqueue("geometry", JSONObject(geometry))
                if (geometryRows >= maxRows) {
                    stop("rows")
                    return
                }
            }
        }
        Choreographer.getInstance().postFrameCallback(this)
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
            .put("ns", System.nanoTime()).put("phase", phase).put("values", values)
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
        enqueue("end", JSONObject().put("reason", reason).put("geometryRows", geometryRows).put("sawIme", sawIme))
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
