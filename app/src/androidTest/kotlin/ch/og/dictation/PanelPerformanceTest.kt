package ch.og.dictation

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.Handler
import android.os.PowerManager
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in matched component replay. No mic or real editor: not end-to-end IME timing. */
@RunWith(AndroidJUnit4::class)
class PanelPerformanceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val main = Handler(Looper.getMainLooper())
    private val delivery = Executor { main.post(it) }

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    @Test fun matchedReplayWithInstalledPanel() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("explicit opt-in required", arguments.getString("panelPerformance") == "true")
        val label = requireNotNull(arguments.getString("label"))
        require(label.matches(Regex("[a-z]+")))
        val wakeLock = context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dictation:panel-performance")
        wakeLock.acquire(10 * 60 * 1000L)
        val report = JSONObject().put("protocol", "panel-component-replay-v1").put("label", label).put("passed", false)
        var reportFile: File? = null
        try {
            val lab = File(context.filesDir, "lab")
            val outputFile = File(lab, "panel-performance-$label.json")
            require(outputFile.createNewFile()) { "preserve the previous report; choose a new label" }
            reportFile = outputFile
            report.put("phase", "setup")
            outputFile.writeText(report.toString(2))
            require(!File(lab, "live.json").exists()) { "normal baseline only" }
            val cases = JSONObject(File(lab, "baseline-parity.json").readText())
            val width = context.resources.displayMetrics.widthPixels
            val inflateTimes = JSONArray()
            report.put("inflateDrawMs", inflateTimes)
            // Component-only startup; no claim about FUTO touch, process creation or physical display.
            repeat(12) {
                instrumentation.runOnMainSync {
                    val began = System.nanoTime()
                    val view = panel(width)
                    val image = Bitmap.createBitmap(width, view.height, Bitmap.Config.ARGB_8888)
                    try { view.draw(Canvas(image)) } finally { image.recycle() }
                    inflateTimes.put((System.nanoTime() - began) / 1e6)
                }
            }
            report.put("phase", "model warm-up")
            outputFile.writeText(report.toString(2))
            val runtime = Engine.runtime(context)
            runtime.warmUp().get(10, TimeUnit.MINUTES)
            val identity = requireNotNull(Engine.liveReceipt())
            assertEquals("candidate-a-provisional", identity.getString("baseline"))
            assertTrue(identity.getBoolean("verified"))
            report.put("identity", identity)
            var warmed = false
            val results = JSONArray()
            report.put("takes", results)
            for (id in cases.keys().asSequence().sorted()) {
                report.put("phase", "replay").put("currentCase", id)
                outputFile.writeText(report.toString(2))
                val item = cases.getJSONObject(id)
                val file = File(lab, item.getString("path")).canonicalFile
                require(file.toPath().startsWith(lab.canonicalFile.toPath()))
                val bytes = file.readBytes()
                require(hash(bytes) == item.getString("sha256"))
                val pcm = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).apply { position(44) }
                val samples = FloatArray(pcm.remaining() / 2) { pcm.short / 32768f }
                if (!warmed) {
                    // Both arms decode the same real clip before timing, paying first-inference allocation.
                    replaySession(runtime, samples, paced = false)
                    warmed = true
                }
                results.put(replay(runtime, samples, width).put("id", id)
                    .put("input_sha256", item.getString("sha256")))
                outputFile.writeText(report.toString(2))
                assertEquals("COMPLETED", results.getJSONObject(results.length() - 1).getString("outcome"))
                assertTrue(results.getJSONObject(results.length() - 1).getBoolean("commitAccepted"))
                assertEquals("text changed for $id", item.getString("output_sha256"),
                    results.getJSONObject(results.length() - 1).getString("output_sha256"))
            }
            report.put("build_sha256", ArtifactFiles.hash(File(context.applicationInfo.sourceDir)))
                .put("test_build_sha256", ArtifactFiles.hash(File(instrumentation.context.applicationInfo.sourceDir)))
                .put("phase", "complete").put("passed", true)
            outputFile.writeText(report.toString(2))
            instrumentation.sendStatus(0, Bundle().apply {
                putString("stream", "Component replay completed: $label (${results.length()} takes)\n")
            })
        } catch (failure: Throwable) {
            report.put("failureClass", failure.javaClass.name)
            throw failure
        } finally {
            try { reportFile?.writeText(report.toString(2)) } finally { wakeLock.release() }
        }
    }

    private fun panel(width: Int): View {
        // Resource lookup and reflection keep this test APK compatible with the original app.
        val layout = context.resources.getIdentifier("ime", "layout", context.packageName)
        return LayoutInflater.from(context).inflate(layout, null).apply {
            measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            layout(0, 0, measuredWidth, measuredHeight)
        }
    }

    private fun replay(runtime: DictationRuntime, samples: FloatArray, width: Int): JSONObject {
        val power = context.getSystemService(PowerManager::class.java)
        val thermalBefore = power.currentThermalStatus
        lateinit var view: View
        lateinit var image: Bitmap
        var ring: View? = null
        var feedback: Any? = null
        var preview: TextView? = null
        var frames = 0
        var drawNs = 0L
        var maxDrawNs = 0L
        var drawing = false
        instrumentation.runOnMainSync {
            view = panel(width)
            image = Bitmap.createBitmap(width, view.height, Bitmap.Config.ARGB_8888)
            fun find(node: View) {
                if (node.javaClass.name == "${context.packageName}.RecordingRingView") ring = node
                if (node is ViewGroup) repeat(node.childCount) { find(node.getChildAt(it)) }
            }
            find(view)
            preview = view.findViewById(context.resources.getIdentifier("preview", "id", context.packageName))
            ring?.let {
                val type = Class.forName("${context.packageName}.RecordingFeedback")
                feedback = type.getConstructor().newInstance()
                type.getMethod("start").invoke(feedback)
                it.javaClass.getMethod("follow", type).invoke(it, feedback)
            }
        }
        val acceptLevel = feedback?.javaClass?.getMethod("accept", FloatArray::class.java)
        val stopLevel = feedback?.javaClass?.getMethod("stop")
        val canvas = Canvas(image)
        val frame = object : Runnable {
            override fun run() {
                if (!drawing) return
                val began = System.nanoTime()
                view.draw(canvas)
                val elapsed = System.nanoTime() - began
                frames++; drawNs += elapsed; maxDrawNs = maxOf(maxDrawNs, elapsed)
                main.postDelayed(this, 16)
            }
        }
        val completed = CompletableFuture<TakeResult>()
        lateinit var feed: (FloatArray) -> Unit
        val capture = object : TakeCapture {
            override fun start(accept: (FloatArray) -> Unit) { feed = accept }
            override fun stop(done: (Result<ByteArray>) -> Unit) = done(Result.success(ByteArray(0)))
            override fun cancel(done: () -> Unit) = done()
        }
        val output = object : TakeOutput {
            override fun preview(text: String) { preview?.text = text.takeLast(240) }
            override fun commit(text: String) = true
            override fun returnToKeyboard() = true
            override fun finished(result: TakeResult, pcm: ByteArray) { completed.complete(result) }
        }
        val take = runtime.open(capture, output, delivery)
        try {
            instrumentation.runOnMainSync {
                take.start()
                // Original panel is static. Only the new ring continuously redraws.
                drawing = ring != null
                if (drawing) main.post(frame)
            }
            val began = System.nanoTime()
            var maxFeedNs = 0L
            for (offset in samples.indices step 640) {
                val end = minOf(offset + 640, samples.size)
                val due = began + end * 1_000_000_000L / SAMPLE_RATE
                val remaining = due - System.nanoTime()
                if (remaining > 0) TimeUnit.NANOSECONDS.sleep(remaining)
                val piece = samples.copyOfRange(offset, end)
                val feedBegan = System.nanoTime()
                acceptLevel?.invoke(feedback, piece)
                feed(piece)
                maxFeedNs = maxOf(maxFeedNs, System.nanoTime() - feedBegan)
            }
            instrumentation.runOnMainSync {
                stopLevel?.invoke(feedback)
                ring?.let { it.javaClass.getMethod("follow", feedback!!.javaClass).invoke(it, null) }
                if (drawing) main.postDelayed({ drawing = false; main.removeCallbacks(frame) }, 1_000)
                take.stop()
            }
            val result = completed.get(10, TimeUnit.MINUTES)
            instrumentation.runOnMainSync { drawing = false; main.removeCallbacks(frame) }
            return JSONObject().put("thermalBefore", thermalBefore).put("thermalAfter", power.currentThermalStatus)
                .put("audioSeconds", samples.size.toDouble() / SAMPLE_RATE)
                .put("outcome", result.outcome.name).put("commitAccepted", result.commitAccepted ?: false)
                .put("output_sha256", result.text?.let { hash(it.toByteArray()) } ?: JSONObject.NULL)
                .put("stopCommitMs", result.eventsNs["commitAccepted"]?.let { end ->
                    result.eventsNs["stopRequested"]?.let { start -> (end - start) / 1e6 }
                } ?: JSONObject.NULL)
                .put("frames", frames).put("meanSoftwareDrawMs", if (frames == 0) 0.0 else drawNs / 1e6 / frames)
                .put("maxSoftwareDrawMs", maxDrawNs / 1e6).put("maxFeedMs", maxFeedNs / 1e6)
        } finally {
            instrumentation.runOnMainSync { drawing = false; main.removeCallbacks(frame); take.cancel(); image.recycle() }
        }
    }
}
