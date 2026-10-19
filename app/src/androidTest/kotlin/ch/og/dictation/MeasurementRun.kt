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
    /** Preflight only: keep hashing outside the historical load/timing sequence. */
    fun verifyHistorical(takes: List<File>): JSONObject {
        val manifest = JSONObject(File(lab, "historical-manifest.json").readText())
        require(manifest.getString("mode") == "historical-full")
        val measuredArtifacts = JSONObject()
        val artifacts = manifest.getJSONObject("artifacts")
        require(artifacts.length() > 0)
        for (path in artifacts.keys()) {
            val file = File(lab, path).canonicalFile
            require(file.toPath().startsWith(lab.canonicalFile.toPath()))
            val actual = sha(file)
            require(actual == artifacts.getString(path)) { "historical artifact mismatch: $path" }
            measuredArtifacts.put(path, actual)
        }
        val measuredInputs = JSONObject()
        val expectedFiles = mutableSetOf<File>()
        val inputs = manifest.getJSONArray("inputs")
        for (i in 0 until inputs.length()) {
            val item = inputs.getJSONObject(i)
            require(!item.has("reference")) { "historical inputs must be full takes" }
            val path = item.getString("path")
            val file = File(lab, path).canonicalFile
            require(file.parentFile == File(context.filesDir, "benchmark").canonicalFile)
            require(expectedFiles.add(file)) { "duplicate historical input" }
            val actual = sha(file)
            require(actual == item.getString("sha256")) { "historical input mismatch: $path" }
            measuredInputs.put(path, actual)
        }
        require(expectedFiles.isNotEmpty() && expectedFiles == takes.map { it.canonicalFile }.toSet()) {
            "historical take inventory differs from manifest"
        }
        val provenance = manifest.getJSONObject("provenance")
        require(sha(File(context.packageCodePath)) == provenance.getString("build_sha256"))
        require(instrumentationSha() == provenance.getString("test_build_sha256"))
        return JSONObject().put("verifiedArtifacts", measuredArtifacts).put("verifiedInputs", measuredInputs)
    }

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
            .put("thermalStatus", context.getSystemService(android.os.PowerManager::class.java).currentThermalStatus)
            .put("batteryPercent", context.getSystemService(android.os.BatteryManager::class.java)
                .getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY))
        synchronized(memory) { memory.put(row) }
    }

    fun run() {
        val nativeRoleProfiling = when (val argument = androidx.test.platform.app.InstrumentationRegistry
            .getArguments().getString("nativeRoleProfiler")) {
            null -> false
            "true" -> true
            else -> error("unsupported nativeRoleProfiler argument: $argument")
        }
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
        val measuredArtifacts = JSONObject()
        for (path in artifacts.keys()) {
            val file = File(lab, path).canonicalFile
            require(file.toPath().startsWith(lab.canonicalFile.toPath()))
            val actual = sha(file)
            require(actual == artifacts.getString(path)) { "artifact hash mismatch: $path" }
            measuredArtifacts.put(path, actual)
        }
        val rawVariant = JSONObject(File(lab, "variant.json").readText())
        val variant = Variant.parse(rawVariant)
        val expectedProvenance = manifest.getJSONObject("provenance")
        val buildSha = sha(File(context.applicationInfo.sourceDir))
        val testBuildSha = instrumentationSha()
        require(buildSha == expectedProvenance.getString("build_sha256"))
        require(testBuildSha == expectedProvenance.getString("test_build_sha256"))
        val measuredInputs = JSONObject()
        for (i in 0 until inputs.length()) {
            val item = inputs.getJSONObject(i)
            val path = item.getString("path")
            val actual = if (measuredInputs.has(path)) {
                measuredInputs.getString(path)
            } else {
                val file = File(lab, path).canonicalFile
                require(file.toPath().startsWith(context.filesDir.canonicalFile.toPath()))
                sha(file)
            }
            require(actual == item.getString("sha256")) { "input hash mismatch before inference" }
            measuredInputs.put(path, actual)
        }
        val runId = UUID.randomUUID().toString()
        val provenance = manifest.getJSONObject("provenance")
            .put("platform", "phone").put("mode", mode).put("feed", 640)
            .put("run_id", runId).put("process_start_id", processStart)
            .put("build_sha256", buildSha)
            .put("test_build_sha256", testBuildSha)
            .put("configuration_sha256", sha(File(lab, "variant.json")))
        val report = JSONObject().put("variant", rawVariant).put("memory", memory)
            .put("takes", JSONArray()).put("endpoints", JSONArray())
            .put("verifiedArtifacts", measuredArtifacts).put("verifiedInputs", measuredInputs)
            .put("buildSha256", buildSha).put("testBuildSha256", testBuildSha)
            .put("runId", runId).put("processStartId", processStart)
        val events = JSONArray()
        var droppedEvents = 0
        val timelineReport = JSONObject().put("clock", "CLOCK_MONOTONIC").put("events", events).put("eventLimit", 65536)
            .put("nativeAttribution", "absent").put("nativeClockAlignment", "unverified")
            .put("nativeRoleProfilerRequested", nativeRoleProfiling)
        val nativeClockSamples = JSONArray()
        timelineReport.put("nativeClockSamples", nativeClockSamples)
        var nativeClockAligned = true
        fun sampleNativeClock(phase: String) {
            val before = System.nanoTime()
            val native = NativeRoleProfiler.clockNs()
            val after = System.nanoTime()
            val valid = after >= before && native in before..after
            nativeClockAligned = nativeClockAligned && valid
            nativeClockSamples.put(JSONObject().put("phase", phase).put("beforeNs", before)
                .put("nativeNs", native).put("afterNs", after).put("valid", valid))
        }
        report.put("timeline", timelineReport)
        fun record(event: TimelineEvent, input: Int? = null, repetition: Int? = null) = synchronized(events) {
            if (events.length() == 65536) { droppedEvents++; return@synchronized }
            val row = JSONObject().put("phase", event.phase).put("ns", event.ns)
            event.chunkId?.let { row.put("chunkId", it) }
            event.samples?.let { row.put("samples", it) }
            event.active?.let { row.put("active", it) }
            event.queued?.let { row.put("queued", it) }
            event.scheduledNs?.let { row.put("scheduledNs", it).put("latenessNs", event.ns - it) }
            event.language?.let { row.put("language", it) }
            event.fallback?.let { row.put("fallback", it) }
            input?.let { row.put("inputIndex", it) }
            repetition?.let { row.put("repetition", it) }
            events.put(row)
            Unit
        }
        val timeline = TimelineObserver { record(it) }
        val result = JSONObject().put("schema", 4).put("outcome", "running").put("provenance", provenance).put("report", report)
        val wakeLock = context.getSystemService(android.os.PowerManager::class.java)
            .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "dictation:measurement")
        wakeLock.acquire(60 * 60 * 1000L)
        val queue = Executors.newSingleThreadExecutor()
        var recognizer: Recognizer? = null
        var nativeStarted = false
        val profileFile = File(lab, "profile.json")
        File(lab, "result.json").writeText(result.toString(1))
        try {
            if (nativeRoleProfiling) {
                require(!profileFile.exists()) { "profile output already exists; stage a fresh lab" }
                check(NativeRoleProfiler.start(variant.runtime.profile)) { "native collector was already active" }
                nativeStarted = true
                sampleNativeClock("beforeModelConstruction")
            }
            memory("before-load")
            val started = SystemClock.elapsedRealtime()
            val model = ModelFiles.dir(File(lab, "model"))
            val lid = File(lab, "lid").takeIf { it.isDirectory }?.let { ModelFiles.dir(it) }
            timeline.record(TimelineEvent("modelConstructionStart"))
            try {
                recognizer = Pipeline.recognizer(variant, model, lid,
                    if (variant.isDictionary) File(lab, variant.hotwords.file).absolutePath else "", timeline)
            } finally { timeline.record(TimelineEvent("modelConstructionEnd")) }
            report.put("coldLoadMs", SystemClock.elapsedRealtime() - started)
            memory("after-load")
            timeline.record(TimelineEvent("silenceWarmupStart", samples = SAMPLE_RATE))
            try { recognizer.transcribe(FloatArray(SAMPLE_RATE)) }
            finally { timeline.record(TimelineEvent("silenceWarmupEnd", samples = SAMPLE_RATE)) }
            memory("after-warmup")
            repeat(repetitions) { repetition ->
                for (i in 0 until inputs.length()) {
                    val item = inputs.getJSONObject(i)
                    val file = File(lab, item.getString("path")).canonicalFile
                    require(file.toPath().startsWith(context.filesDir.canonicalFile.toPath()))
                    require(sha(file) == item.getString("sha256")) { "input hash mismatch" }
                    val inputTimeline = TimelineObserver { record(it, i, repetition) }
                    val row = runInput(variant, recognizer, file, item, queue, mode != "unpaced-only", inputTimeline)
                        .put("id", item.getString("id")).put("file", file.name).put("repetition", repetition)
                    report.getJSONArray(if (item.has("reference")) "endpoints" else "takes").put(row)
                    memory("buffer-released")
                    File(lab, "result.json").writeText(result.toString(1))
                }
            }
            result.put("outcome", if (droppedEvents == 0) "complete" else "invalid-timeline")
        } catch (failure: Throwable) {
            result.put("outcome", "failed")
            result.put("failure", failure.toString())
            throw failure
        } finally {
            try {
                queue.shutdown()
                if (queue.awaitTermination(10, TimeUnit.MINUTES)) {
                    recognizer?.let {
                        timeline.record(TimelineEvent("recognizerReleaseStart"))
                        try { Pipeline.release(it) } finally { timeline.record(TimelineEvent("recognizerReleaseEnd")) }
                    }
                    memory("recognizer-released")
                } else {
                    result.put("outcome", "failed")
                    result.put("failure", "decode executor did not terminate; recognizer retained until process exit")
                }
            } catch (cleanup: Throwable) {
                result.put("outcome", "failed")
                result.put("cleanupFailure", cleanup.toString())
            } finally {
                if (nativeStarted) {
                    try {
                        timeline.record(TimelineEvent("profileFlushStart"))
                        sampleNativeClock("beforeNativeFlush")
                        val complete = NativeRoleProfiler.finish(profileFile.absolutePath)
                        sampleNativeClock("afterNativeFlush")
                        timelineReport.put("nativeProfile", JSONObject().put("path", "profile.json")
                            .put("enabled", variant.runtime.profile).put("complete", complete)
                            .put("byteLimit", 64L * 1024 * 1024))
                        check(complete) { "native collector reported incomplete or invalid spans" }
                        check(profileFile.isFile && profileFile.length() <= 64L * 1024 * 1024) {
                            "native profile missing or exceeds its byte limit"
                        }
                        timelineReport.getJSONObject("nativeProfile").put("bytes", profileFile.length())
                            .put("sha256", sha(profileFile))
                        check(nativeClockAligned) { "native clock is outside monotonic brackets" }
                        timelineReport.put("nativeAttribution", if (variant.runtime.profile) "actual-ort-session-run" else "disabled-control")
                            .put("nativeClockAlignment", "verified-brackets")
                        memory("profile-flushed")
                    } catch (profileFailure: Throwable) {
                        result.put("outcome", "failed").put("nativeFailure", profileFailure.toString())
                        timelineReport.put("nativeClockAlignment", if (nativeClockAligned) "unverified" else "invalid-brackets")
                    } finally {
                        timeline.record(TimelineEvent("profileFlushEnd"))
                    }
                }
                timelineReport.put("valid", droppedEvents == 0).put("droppedEvents", droppedEvents)
                if (droppedEvents > 0 && result.getString("outcome") == "complete") result.put("outcome", "invalid-timeline")
                File(lab, "result.json").writeText(result.toString(1))
                wakeLock.release()
            }
        }
    }

    private fun runInput(v: Variant, recognizer: Recognizer, file: File, item: JSONObject,
                         queue: java.util.concurrent.ExecutorService, paced: Boolean, timeline: TimelineObserver): JSONObject {
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
        val transcriber = TakeTranscriber({ timed }, segmenter, measuredQueue, { it.run() }, v.post, timeline) {}
        val captureStartNs = System.nanoTime()
        timeline.record(TimelineEvent("captureStart", ns = captureStartNs))
        val start = SystemClock.elapsedRealtime()
        var i = 0
        while (i < audio.size) {
            val n = minOf(640, audio.size - i)
            if (paced) {
                val wait = start + (i + n) * 1000L / SAMPLE_RATE - SystemClock.elapsedRealtime()
                if (wait > 0) Thread.sleep(wait)
            }
            val frame = audio.copyOfRange(i, i + n)
            timeline.record(TimelineEvent("feedStart", samples = n,
                scheduledNs = if (paced) captureStartNs + (i + n) * 1_000_000_000L / SAMPLE_RATE else null))
            transcriber.feed(frame); i += n
        }
        transcriber.timelineSnapshot("producerStop", captureStartNs + count * 1_000_000_000L / SAMPLE_RATE)
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

    fun instrumentationSha(): String = sha(File(androidx.test.platform.app.InstrumentationRegistry
        .getInstrumentation().context.applicationInfo.sourceDir))

    private fun sha(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(1024 * 1024)
            while (true) { val n = stream.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
