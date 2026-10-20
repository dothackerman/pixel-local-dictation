package ch.og.dictation

import android.content.Context
import android.os.BatteryManager
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Throwaway spike for ADR-003 (docs/archive/milestones-2026-10-10.md, milestone 2 step 1): replays the takes
 * the user saved in benchmark mode through Parakeet v3 INT8 and writes the numbers the
 * gate asks for to files/benchmark/spike-<model>.json and the log. No UI, no microphone.
 */
@RunWith(AndroidJUnit4::class)
class ReplayTest {
    private val tag = "ReplayTest"
    private val rate = 16_000
    private val ctx: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(ctx.filesDir, "benchmark")

    @Test fun spike() {
        val takes = dir.listFiles { f -> f.name.endsWith(".wav") }.orEmpty().sortedBy { it.name }
        assumeTrue("no takes in files/benchmark", takes.isNotEmpty())
        // Every model directory the build carries; since ADR-003 that is the shipped one only.
        for (model in listOf("parakeet-v3", "parakeet-v3-matmul8")) {
            if (ctx.assets.list(model).isNullOrEmpty()) continue
            // Resets the peak-memory counter (VmHWM) so each model gets its own peak.
            runCatching { File("/proc/self/clear_refs").writeText("5") }
            File(dir, "spike-$model.json").writeText(spike(model, takes).toString(2))
        }
    }

    private fun spike(model: String, takes: List<File>): JSONObject {
        val out = JSONObject().put("model", model)
        out.put("app", ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName)
        out.put("before", state())

        val t0 = SystemClock.elapsedRealtime()
        val rec = recognizer(model, threads = 4)
        out.put("coldLoadMs", SystemClock.elapsedRealtime() - t0).put("threads", 4)
        out.put("pssAfterLoadKb", pssKb())
        decode(rec, FloatArray(rate))  // first inference pays one-time allocation; keep it out of the factors

        val audio = takes.associate { it.name to readWav(it) }
        val perTake = JSONArray()
        for ((name, x) in audio) {
            val take = JSONObject().put("file", name).put("audioSeconds", x.size / rate.toDouble())
            take.put("whole", decode(rec, x))
            val segs = JSONArray()
            for (s in segment(x)) segs.put(decode(rec, s.second).put("startSeconds", s.first / rate.toDouble()))
            take.put("segments", segs)
            // Fixed 5 s and 10 s slices: the 3-10 s real-time factor the gate asks for, text not scored.
            val slices = JSONArray()
            for (len in intArrayOf(5, 10)) {
                var i = 0
                while (i + len * rate <= x.size) { slices.put(decode(rec, x.copyOfRange(i, i + len * rate))); i += len * rate }
            }
            take.put("slices", slices)
            perTake.put(take)
            Log.i(tag, "take $name done")
        }
        out.put("takes", perTake)
        out.put("pssAfterTakesKb", pssKb()).put("vmHwmKb", vmHwmKb())

        // Ten consecutive long takes fed at real-time pace, decoding segments while "recording".
        out.put("beforePaced", state())
        val paced = JSONArray()
        val names = audio.keys.toList()
        repeat(10) { i ->
            val name = names[i % names.size]
            paced.put(pacedTake(rec, audio.getValue(name)).put("file", name).put("thermalAfter", thermal()))
            Log.i(tag, "paced take ${i + 1}/10 done: ${paced.getJSONObject(i).getLong("stopToFinalMs")} ms after stop")
        }
        out.put("paced", paced).put("afterPaced", state()).put("vmHwmAfterPacedKb", vmHwmKb())
        rec.release()

        // Thread count is tuned in milestone 5; one whole-take decode each shows whether 4 is a sane start.
        val sweep = JSONArray()
        val longest = audio.values.maxBy { it.size }
        for (n in intArrayOf(2, 6, 8)) {
            val r = recognizer(model, n); decode(r, FloatArray(rate))
            sweep.put(decode(r, longest).put("threads", n)); r.release()
        }
        out.put("threadSweep", sweep)

        Log.i(tag, "spike $model done: coldLoadMs=${out.getLong("coldLoadMs")} vmHwmKb=${out.getLong("vmHwmKb")}")
        return out
    }

    private fun recognizer(model: String, threads: Int): OfflineRecognizer {
        val files = ctx.assets.list(model).orEmpty()
        fun part(stem: String) = "$model/" + files.single { it.startsWith(stem) && it.endsWith(".onnx") }
        val config = OfflineRecognizerConfig()
        config.modelConfig.transducer.encoder = part("encoder")
        config.modelConfig.transducer.decoder = part("decoder")
        config.modelConfig.transducer.joiner = part("joiner")
        config.modelConfig.tokens = "$model/tokens.txt"
        config.modelConfig.modelType = "nemo_transducer"
        config.modelConfig.numThreads = threads
        return OfflineRecognizer(ctx.assets, config)
    }

    private fun vad(): Vad {
        val config = VadModelConfig()
        config.sileroVadModelConfig.model = "parakeet-v3/silero_vad.onnx"
        config.sileroVadModelConfig.minSilenceDuration = 0.8f
        config.sileroVadModelConfig.minSpeechDuration = 0.25f
        config.sileroVadModelConfig.maxSpeechDuration = 20f
        config.sampleRate = rate
        return Vad(ctx.assets, config)
    }

    private fun decode(rec: OfflineRecognizer, samples: FloatArray): JSONObject {
        val t = SystemClock.elapsedRealtimeNanos()
        val stream = rec.createStream()
        stream.acceptWaveform(samples, rate)
        rec.decode(stream)
        val result = rec.getResult(stream)
        stream.release()
        val ms = (SystemClock.elapsedRealtimeNanos() - t) / 1e6
        val seconds = samples.size / rate.toDouble()
        return JSONObject().put("audioSeconds", seconds).put("decodeMs", ms).put("rtf", ms / 1000 / seconds)
            .put("text", result.text.trim()).put("lang", result.lang)
    }

    /** Segments under 2 s are carried into the next one (plan design). */
    private class Merger {
        private var carry: Pair<Int, FloatArray>? = null
        fun add(start: Int, samples: FloatArray): Pair<Int, FloatArray>? {
            val c = carry
            val seg = if (c == null) start to samples else c.first to (c.second + samples)
            return if (seg.second.size < 2 * 16_000) { carry = seg; null } else { carry = null; seg }
        }
        fun rest(): Pair<Int, FloatArray>? = carry.also { carry = null }
    }

    private fun drain(v: Vad, m: Merger, emit: (Pair<Int, FloatArray>) -> Unit) {
        while (!v.empty()) { val s = v.front(); v.pop(); m.add(s.start, s.samples)?.let(emit) }
    }

    private fun segment(x: FloatArray): List<Pair<Int, FloatArray>> {
        val v = vad(); val m = Merger(); val out = ArrayList<Pair<Int, FloatArray>>()
        val win = v.config.sileroVadModelConfig.windowSize
        var i = 0
        while (i + win <= x.size) { v.acceptWaveform(x.copyOfRange(i, i + win)); drain(v, m) { out.add(it) }; i += win }
        v.flush(); drain(v, m) { out.add(it) }
        m.rest()?.let { out.add(it) }
        v.release()
        return out
    }

    /** Feeds the take at real-time pace; one serial decode thread; returns the wait after the last sample. */
    private fun pacedTake(rec: OfflineRecognizer, x: FloatArray): JSONObject {
        val v = vad(); val m = Merger()
        val queue = Executors.newSingleThreadExecutor()
        val texts = java.util.Collections.synchronizedList(ArrayList<String>())
        val decodeMs = java.util.Collections.synchronizedList(ArrayList<Double>())
        val submit = { s: Pair<Int, FloatArray> ->
            queue.execute { val r = decode(rec, s.second); texts.add(r.getString("text")); decodeMs.add(r.getDouble("decodeMs")) }
        }
        val win = v.config.sileroVadModelConfig.windowSize
        val start = SystemClock.elapsedRealtime()
        var i = 0
        while (i + win <= x.size) {
            val due = start + (i + win) * 1000L / rate
            val wait = due - SystemClock.elapsedRealtime()
            if (wait > 0) Thread.sleep(wait)
            v.acceptWaveform(x.copyOfRange(i, i + win)); drain(v, m, submit)
            i += win
        }
        val stop = SystemClock.elapsedRealtime()
        v.flush(); drain(v, m, submit); m.rest()?.let(submit)
        queue.shutdown(); queue.awaitTermination(10, java.util.concurrent.TimeUnit.MINUTES)
        val done = SystemClock.elapsedRealtime()
        v.release()
        return JSONObject().put("audioSeconds", x.size / rate.toDouble()).put("stopToFinalMs", done - stop)
            .put("segments", texts.size).put("segmentDecodeMs", JSONArray(decodeMs.toList()))
            .put("text", texts.filter { it.isNotEmpty() }.joinToString(" "))
    }

    private fun readWav(f: File): FloatArray {
        val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        b.position(44)
        return FloatArray(b.remaining() / 2) { b.short / 32768f }
    }

    private fun pssKb(): Int = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss

    private fun vmHwmKb(): Long = File("/proc/self/status").readLines()
        .first { it.startsWith("VmHWM:") }.filter { it.isDigit() }.toLong()

    private fun thermal(): Int = ctx.getSystemService(PowerManager::class.java).currentThermalStatus

    private fun state(): JSONObject = JSONObject().put("thermalStatus", thermal())
        .put("batteryPercent", ctx.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
        .put("elapsedMs", SystemClock.elapsedRealtime())
}
