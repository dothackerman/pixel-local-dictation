package ch.og.dictation

import android.content.Context
import android.os.BatteryManager
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Stage 2 of the experiment loop: runs the variant that `phone push DIR`
 * put under files/lab/ through the app's own classes (Pipeline, VadSegmenter,
 * TakeTranscriber) on the takes saved in files/benchmark, and writes files/lab/result.json:
 * chunk texts and decode times for every take (fed unpaced, 640 samples at a time, like the
 * laptop twin), a paced run of the longest and the shortest take for the wait after Stop,
 * peak memory, thermal status. No UI, no microphone, nothing leaves the phone.
 * Run with: tools/phone lab
 */
@RunWith(AndroidJUnit4::class)
class LabTest {
    private val tag = "LabTest"
    private val rate = SAMPLE_RATE
    private val ctx: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val takesDir = File(ctx.filesDir, "benchmark")
    private val labDir = File(ctx.filesDir, "lab")

    @Test fun variantOnSavedTakes() {
        if (File(labDir, "encoder-probe.json").isFile) {
            // Class and ORT Java/JNI exist only in the explicitly selected diagnostic build.
            Class.forName("ch.og.dictation.EncoderProbe")
                .getConstructor(Context::class.java, File::class.java)
                .newInstance(ctx, labDir).let { it.javaClass.getMethod("run").invoke(it) }
            return
        }
        if (File(labDir, "accelerator-probe.json").isFile) {
            AcceleratorAccessProbe(ctx, labDir).run()
            return
        }
        if (File(labDir, "measurement.json").isFile) {
            MeasurementRun(ctx, labDir).run()
            return
        }
        val takes = takesDir.listFiles { f -> f.name.endsWith(".wav") }.orEmpty().sortedBy { it.name }
        assumeTrue("no takes in files/benchmark", takes.isNotEmpty())
        val variantFile = File(labDir, "variant.json")
        assumeTrue("no files/lab/variant.json: run phone push DIR first", variantFile.isFile)
        val v = Variant.parse(variantFile.readText())
        val out = JSONObject().put("variant", JSONObject(variantFile.readText())).put("side", "phone")
        // The new release requires measured content identities before any inference.
        // Old invocations remain historical-only and cannot be wrapped as release evidence.
        if (File(labDir, "historical-manifest.json").isFile) {
            val verified = MeasurementRun(ctx, labDir).verifyHistorical(takes.toList())
            out.put("verifiedArtifacts", verified.getJSONObject("verifiedArtifacts"))
                .put("verifiedInputs", verified.getJSONObject("verifiedInputs"))
        }
        out.put("app", ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName)
        out.put("before", state())

        // The screen is off at night; keep the CPU awake so timings mean something.
        val wakeLock = ctx.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dictation:lab")
        wakeLock.acquire(60 * 60 * 1000L)
        try {
            runCatching { File("/proc/self/clear_refs").writeText("5") }  // reset the VmHWM counter
            val model = ModelFiles.dir(File(labDir, "model"))
            val lid = File(labDir, "lid").takeIf { it.isDirectory }?.let { ModelFiles.dir(it) }
            val t0 = SystemClock.elapsedRealtime()
            val hotwords = if (v.isDictionary) File(labDir, v.hotwords.file).also { require(it.isFile) { "missing ${it.name} in files/lab" } }.absolutePath else ""
            val recognizer = Pipeline.recognizer(v, model, lid, hotwords)
            out.put("coldLoadMs", SystemClock.elapsedRealtime() - t0).put("pssAfterLoadKb", pssKb())
            recognizer.transcribe(FloatArray(rate))  // first inference pays one-time allocation, as in the app

            val audio = takes.associate { it.name to readWav(it) }
            val perTake = JSONArray()
            for ((name, x) in audio) {
                perTake.put(unpaced(v, recognizer, x).put("file", name).put("audioSeconds", x.size / rate.toDouble()))
                Log.i(tag, "take $name done")
            }
            out.put("takes", perTake).put("pssAfterTakesKb", pssKb()).put("vmHwmKb", vmHwmKb())

            // The wait after Stop that the user sees: audio fed at real-time pace, one serial decode thread.
            val paced = JSONArray()
            for (name in listOf(audio.maxBy { it.value.size }.key, audio.minBy { it.value.size }.key).distinct()) {
                paced.put(pacedTake(v, recognizer, audio.getValue(name)).put("file", name).put("thermalAfter", thermal()))
                Log.i(tag, "paced $name: ${paced.getJSONObject(paced.length() - 1).getLong("stopToFinalMs")} ms after stop")
            }
            out.put("paced", paced).put("vmHwmAfterPacedKb", vmHwmKb())
            Pipeline.release(recognizer)
        } finally {
            wakeLock.release()
        }
        out.put("after", state())
        // Fresh provenance is additive; the historical allocation/timing sequence above is unchanged.
        out.put("measurementProtocol", "historical-full-v1")
            .put("runId", java.util.UUID.randomUUID().toString())
            .put("processStartId", "${android.os.Process.myPid()}:${File("/proc/self/stat").readText().substringAfterLast(") ").split(' ')[19]}")
            .put("buildSha256", MeasurementRun(ctx, labDir).artifactSha(File(ctx.packageCodePath)))
            .put("testBuildSha256", MeasurementRun(ctx, labDir).instrumentationSha())
            .put("configurationSha256", MeasurementRun(ctx, labDir).artifactSha(variantFile))
        File(labDir, "result.json").writeText(out.toString(1))
        Log.i(tag, "done: ${out.getJSONArray("takes").length()} takes, vmHwmKb=${out.optLong("vmHwmKb")}")
        assertTrue(out.getJSONArray("takes").length() > 0)
    }

    /** Every chunk through the real transcriber, without pacing; texts and decode times per chunk. */
    private fun unpaced(v: Variant, recognizer: Recognizer, x: FloatArray): JSONObject {
        val chunks = JSONArray()
        val timed = TimedRecognizer(recognizer) { secs, ms, text -> chunks.put(JSONObject().put("audioSeconds", secs).put("decodeMs", ms).put("rtf", ms / 1000 / secs).put("text", text)) }
        val text = transcribe(v, timed, x, paced = false)
        return JSONObject().put("chunks", chunks).put("text", text.first).put("lid", timed.detected).put("finishMs", text.second)
    }

    private fun pacedTake(v: Variant, recognizer: Recognizer, x: FloatArray): JSONObject {
        val decodeMs = JSONArray()
        val timed = TimedRecognizer(recognizer) { _, ms, _ -> decodeMs.put(ms) }
        val (text, stopToFinal) = transcribe(v, timed, x, paced = true)
        return JSONObject().put("audioSeconds", x.size / rate.toDouble()).put("stopToFinalMs", stopToFinal)
            .put("chunkDecodeMs", decodeMs).put("text", text)
    }

    /** Feeds a take in 640-sample pieces through VadSegmenter and TakeTranscriber; returns (text, ms after the last sample). */
    private fun transcribe(v: Variant, recognizer: Recognizer, x: FloatArray, paced: Boolean): Pair<String, Long> {
        val queue = Executors.newSingleThreadExecutor()
        val segmenter = VadSegmenter(v, ModelFiles.dir(labDir)).also { it.reset() }
        val take = TakeTranscriber({ recognizer }, segmenter, queue, { it.run() }, v.post) {}
        val start = SystemClock.elapsedRealtime()
        var i = 0
        while (i < x.size) {
            val n = minOf(640, x.size - i)
            if (paced) {
                val due = start + (i + n) * 1000L / rate
                val wait = due - SystemClock.elapsedRealtime()
                if (wait > 0) Thread.sleep(wait)
            }
            take.feed(x.copyOfRange(i, i + n)); i += n
        }
        val stopped = SystemClock.elapsedRealtime()
        val latch = CountDownLatch(1)
        var result: Result<String>? = null
        take.finish { result = it; latch.countDown() }
        assertTrue("no final text within ten minutes", latch.await(10, TimeUnit.MINUTES))
        val done = SystemClock.elapsedRealtime()
        queue.shutdown()
        segmenter.reset()
        return result!!.getOrThrow() to (done - stopped)
    }

    private class TimedRecognizer(private val inner: Recognizer, private val onChunk: (Double, Double, String) -> Unit) : Recognizer {
        var detected = ""
        override fun beginTake(firstChunk: FloatArray): String = inner.beginTake(firstChunk).also { detected = it }
        override fun transcribe(samples: FloatArray): String {
            val t = SystemClock.elapsedRealtimeNanos()
            val text = inner.transcribe(samples)
            onChunk(samples.size / SAMPLE_RATE.toDouble(), (SystemClock.elapsedRealtimeNanos() - t) / 1e6, text.trim())
            return text
        }
    }

    private fun readWav(f: File): FloatArray {
        val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        b.position(44)
        return FloatArray(b.remaining() / 2) { b.short / 32768f }
    }

    private fun pssKb(): Int = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss
    private fun vmHwmKb(): Long = File("/proc/self/status").readLines().first { it.startsWith("VmHWM:") }.filter { it.isDigit() }.toLong()
    private fun thermal(): Int = ctx.getSystemService(PowerManager::class.java).currentThermalStatus
    private fun state(): JSONObject = JSONObject().put("thermalStatus", thermal())
        .put("batteryPercent", ctx.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
        .put("elapsedMs", SystemClock.elapsedRealtime())
}
