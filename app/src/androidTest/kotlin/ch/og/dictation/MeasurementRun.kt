package ch.og.dictation

import android.content.Context
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject

/** Separate diagnostic protocol. LabTest's historical full-run procedure remains available. */
class MeasurementRun(private val context: Context, private val lab: File) {
    private val memory = JSONArray()
    private fun memory(phase: String) {
        val status = File("/proc/self/status").readLines()
        fun value(key: String) = status.first { it.startsWith("$key:") }.trim().split(Regex("\\s+"))[1].toLong()
        val runtime = Runtime.getRuntime()
        val row = JSONObject().put("phase", phase).put("elapsedNs", SystemClock.elapsedRealtimeNanos())
            .put("rssKb", value("VmRSS")).put("highWaterKb", value("VmHWM"))
            .put("pssKb", Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss)
            .put("javaUsedBytes", runtime.totalMemory() - runtime.freeMemory())
            .put("nativeAllocatedBytes", Debug.getNativeHeapAllocatedSize())
        synchronized(memory) { memory.put(row) }
    }

    fun run() {
        val manifest = JSONObject(File(lab, "measurement.json").readText())
        val mode = manifest.getString("mode")
        require(mode in setOf("unpaced-only", "paced-only", "persistent"))
        val repetitions = manifest.optInt("repetitions", 1)
        require(repetitions in 1..3 && (mode == "persistent" || repetitions == 1))
        val processStart = "${Process.myPid()}:${File("/proc/self/stat").readText().substringAfterLast(") ").split(' ')[19]}"
        val marker = File(context.cacheDir, "measurement-process")
        require(!marker.exists() || marker.readText() != processStart) { "diagnostic mode requires a fresh process" }
        marker.writeText(processStart)
        val inputs = manifest.getJSONArray("inputs")
        require(inputs.length() in 1..32)
        val artifacts = manifest.getJSONObject("artifacts")
        require(artifacts.length() > 0) { "missing artifact inventory" }
        for (path in artifacts.keys()) {
            val file = File(lab, path).canonicalFile
            require(file.toPath().startsWith(lab.canonicalFile.toPath()))
            require(sha(file) == artifacts.getString(path)) { "artifact hash mismatch: $path" }
        }
        val rawVariant = JSONObject(File(lab, "variant.json").readText())
        val variant = Variant.parse(rawVariant)
        val provenance = manifest.getJSONObject("provenance")
            .put("platform", "phone").put("mode", mode).put("feed", 640)
            .put("run_id", UUID.randomUUID().toString()).put("process_start_id", processStart)
            .put("build_sha256", sha(File(context.packageCodePath)))
            .put("configuration_sha256", sha(File(lab, "variant.json")))
        val report = JSONObject().put("variant", rawVariant).put("memory", memory)
            .put("takes", JSONArray()).put("endpoints", JSONArray())
        val result = JSONObject().put("schema", 4).put("outcome", "running").put("provenance", provenance).put("report", report)
        val wakeLock = context.getSystemService(android.os.PowerManager::class.java)
            .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "dictation:measurement")
        wakeLock.acquire(60 * 60 * 1000L)
        val queue = Executors.newSingleThreadExecutor()
        var recognizer: Recognizer? = null
        File(lab, "result.json").writeText(result.toString(1))
        try {
            memory("before-load")
            val started = SystemClock.elapsedRealtime()
            val model = ModelFiles.dir(File(lab, "model"))
            val lid = File(lab, "lid").takeIf { it.isDirectory }?.let { ModelFiles.dir(it) }
            recognizer = Pipeline.recognizer(variant, model, lid,
                if (variant.isDictionary) File(lab, variant.hotwords.file).absolutePath else "")
            report.put("coldLoadMs", SystemClock.elapsedRealtime() - started)
            memory("after-load")
            recognizer.transcribe(FloatArray(SAMPLE_RATE))
            memory("after-warmup")
            repeat(repetitions) { repetition ->
                for (i in 0 until inputs.length()) {
                    val item = inputs.getJSONObject(i)
                    val file = File(lab, item.getString("path")).canonicalFile
                    require(file.toPath().startsWith(context.filesDir.canonicalFile.toPath()))
                    require(sha(file) == item.getString("sha256")) { "input hash mismatch" }
                    val row = runInput(variant, recognizer, file, item, queue, mode != "unpaced-only")
                        .put("id", item.getString("id")).put("file", file.name).put("repetition", repetition)
                    report.getJSONArray(if (item.has("reference")) "endpoints" else "takes").put(row)
                    memory("buffer-released")
                    File(lab, "result.json").writeText(result.toString(1))
                }
            }
            result.put("outcome", "complete")
        } catch (failure: Throwable) {
            result.put("outcome", "failed")
            result.put("failure", failure.toString())
            throw failure
        } finally {
            try {
                queue.shutdown()
                if (queue.awaitTermination(10, TimeUnit.MINUTES)) {
                    recognizer?.let { Pipeline.release(it) }
                    memory("recognizer-released")
                } else {
                    result.put("failure", "decode executor did not terminate; recognizer retained until process exit")
                }
            } catch (cleanup: Throwable) {
                result.put("cleanupFailure", cleanup.toString())
            } finally {
                File(lab, "result.json").writeText(result.toString(1))
                wakeLock.release()
            }
        }
    }

    private fun runInput(v: Variant, recognizer: Recognizer, file: File, item: JSONObject,
                         queue: java.util.concurrent.ExecutorService, paced: Boolean): JSONObject {
        val audio = readAudio(file, item)
        val count = audio.size
        memory("buffer-loaded")
        val chunks = JSONArray()
        val waits = JSONArray()
        val stateLock = Any()
        val active = AtomicBoolean(false)
        val pending = AtomicInteger(0)
        val measuredQueue = Executor { command ->
            val queued = SystemClock.elapsedRealtimeNanos()
            synchronized(stateLock) { pending.incrementAndGet() }
            queue.execute {
                synchronized(stateLock) { pending.decrementAndGet(); active.set(true) }
                waits.put((SystemClock.elapsedRealtimeNanos() - queued) / 1e6)
                try { command.run() } finally { synchronized(stateLock) { active.set(false) } }
            }
        }
        var covered = 0
        val realSegmenter = VadSegmenter(v, ModelFiles.dir(lab)).also { it.reset() }
        val segmenter = object : Segmenter {
            override fun accept(samples: FloatArray): List<Chunk> = realSegmenter.accept(samples).also { parts -> covered += parts.sumOf { it.samples.size } }
            override fun flush(): List<Chunk> = realSegmenter.flush().also { parts -> covered += parts.sumOf { it.samples.size } }
            override fun reset() = realSegmenter.reset()
        }
        var lidMs = 0.0
        val timed = object : Recognizer {
            override fun beginTake(firstChunk: FloatArray): String {
                val start = SystemClock.elapsedRealtimeNanos()
                return recognizer.beginTake(firstChunk).also { lidMs += (SystemClock.elapsedRealtimeNanos() - start) / 1e6 }
            }
            override fun transcribe(samples: FloatArray): String {
                val start = SystemClock.elapsedRealtimeNanos()
                val text = recognizer.transcribe(samples)
                val ms = (SystemClock.elapsedRealtimeNanos() - start) / 1e6
                chunks.put(JSONObject().put("audioSeconds", samples.size / SAMPLE_RATE.toDouble())
                    .put("decodeMs", ms).put("rtf", ms * SAMPLE_RATE / 1000 / samples.size).put("text", text))
                memory("after-chunk")
                return text
            }
        }
        val transcriber = TakeTranscriber({ timed }, segmenter, measuredQueue, { it.run() }, v.post) {}
        val start = SystemClock.elapsedRealtime()
        var i = 0
        while (i < audio.size) {
            val n = minOf(640, audio.size - i)
            if (paced) {
                val wait = start + (i + n) * 1000L / SAMPLE_RATE - SystemClock.elapsedRealtime()
                if (wait > 0) Thread.sleep(wait)
            }
            transcriber.feed(audio.copyOfRange(i, i + n)); i += n
        }
        val stopped = SystemClock.elapsedRealtime()
        val stopState = synchronized(stateLock) { JSONObject().put("active", active.get()).put("queued", pending.get()) }
        val residual = count - covered
        val latch = CountDownLatch(1)
        var output: Result<String>? = null
        transcriber.finish { output = it; latch.countDown() }
        check(latch.await(10, TimeUnit.MINUTES)) { "no final output" }
        val done = SystemClock.elapsedRealtime()
        segmenter.reset()
        check(covered == count) { "incomplete audio coverage" }
        val text = output!!.getOrThrow()
        return JSONObject().put("text", text).put("chunks", chunks).put("queueWaitMs", waits)
            .put("inputSamples", count).put("coverageSamples", covered)
            .put("audioSeconds", count / SAMPLE_RATE.toDouble()).put("stopToFinalMs", done - stopped)
            .put("lidMs", lidMs).put("stopState", stopState).put("residualSamples", residual)
            .put("missingFinalOutput", item.optString("reference").isNotEmpty() && text.isBlank())
            .put("cutWord", item.opt("cut_word") ?: JSONObject.NULL)
    }

    private fun readAudio(file: File, item: JSONObject): FloatArray {
        val bytes = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        // Only the canonical PCM WAVs prepared by setup are accepted.
        require(bytes.getInt(0) == 0x46464952 && bytes.getInt(8) == 0x45564157)
        require(bytes.getInt(12) == 0x20746d66 && bytes.getInt(16) == 16)
        require(bytes.getShort(20).toInt() == 1 && bytes.getShort(22).toInt() == 1)
        require(bytes.getInt(24) == SAMPLE_RATE && bytes.getShort(34).toInt() == 16)
        require(bytes.getInt(36) == 0x61746164 && bytes.getInt(40) == bytes.limit() - 44)
        val count = item.optInt("stop_sample", (bytes.limit() - 44) / 2)
        require(count in 1..((bytes.limit() - 44) / 2))
        bytes.position(44)
        return FloatArray(count) { bytes.short / 32768f }
    }

    fun artifactSha(file: File): String = sha(file)

    private fun sha(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(1024 * 1024)
            while (true) { val n = stream.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
