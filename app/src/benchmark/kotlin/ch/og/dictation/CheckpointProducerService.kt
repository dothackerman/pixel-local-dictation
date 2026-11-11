package ch.og.dictation

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/** Synthetic producer in its own process; never binds to or stops the IME. */
class CheckpointProducerService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val mode = intent?.getStringExtra("mode")
        if (mode != null) {
            val id = intent.getStringExtra("case_id") ?: return START_NOT_STICKY
            val fixture = intent.getStringExtra("fixture_id") ?: return START_NOT_STICKY
            if (runCatching { java.util.UUID.fromString(id).toString() == id }.getOrDefault(false) &&
                mode in setOf("fixture-zero", "fixture-checkpoint-restart", "fixture-later-failure") &&
                fixture in setOf("continuous", "paused")) runFixtureScenario(mode, id, fixture)
            return START_NOT_STICKY
        }
        Thread {
            val root = File(noBackupFilesDir, "checkpoint-process-test")
            check(root.mkdirs() || root.isDirectory)
            val leaveWithoutCheckpoint = intent?.getBooleanExtra("zero_checkpoint", false) == true
            val direct = Executor { it.run() }
            var feed: ((FloatArray) -> Unit)? = null
            val capture = object : TakeCapture {
                override fun start(accept: (FloatArray) -> Unit) { feed = accept }
                override fun stop(done: (Result<ByteArray>) -> Unit) = done(Result.success(byteArrayOf()))
                override fun cancel(done: () -> Unit) = done()
            }
            val output = object : TakeOutput {
                override fun preview(text: String) {}
                override fun commit(text: String) = error("Unexpected insertion")
                override fun returnToKeyboard() = false
                override fun finished(result: TakeResult, pcm: ByteArray) {}
            }
            val runtime = DictationRuntime(Variant.HISTORICAL_INT8, direct, { Recognizer { "synthetic checkpoint" } },
                { object : Segmenter {
                    override fun accept(samples: FloatArray) = if (leaveWithoutCheckpoint) emptyList() else listOf(Chunk(samples, true))
                    override fun flush() = emptyList<Chunk>()
                    override fun reset() {}
                } })
            val take = runtime.open(capture, HistoryTakeOutput(output, { HistoryStore(root) }, direct, { error("save failed") }), direct)
            take.start()
            feed!!(floatArrayOf(1f))
            val ready = File(root, "producer-ready.tmp")
            ready.writeText(Process.myPid().toString())
            check(ready.renameTo(File(root, "producer-ready")))
            Process.killProcess(Process.myPid())
        }.start()
        return START_NOT_STICKY
    }

    /** Uses a synthetic WAV and the production pipeline; dies unbound at a controlled checkpoint boundary. */
    private fun runFixtureScenario(mode: String, caseId: String, fixtureId: String) {
        Thread {
            runCatching { produceFixtureScenario(mode, caseId, fixtureId) }.onFailure {
                val root = File(noBackupFilesDir, "issue54-producer/$caseId")
                root.mkdirs()
                File(root, "producer-failed").writeText("${it.javaClass.simpleName}")
                Process.killProcess(Process.myPid())
            }
        }.start()
    }

    private fun produceFixtureScenario(mode: String, caseId: String, fixtureId: String) {
            val audio = SyntheticFixtureAudio.read(SyntheticFixtureAudio.file(filesDir, fixtureId))
            val root = File(noBackupFilesDir, "issue54-producer/$caseId")
            root.deleteRecursively()
            check(root.mkdirs() || root.isDirectory)
            val readyCheckpoint = CountDownLatch(1)
            val laterFailure = CountDownLatch(1)
            val zeroBoundary = CountDownLatch(1)
            val timeline = TimelineObserver { event ->
                if (event.phase == "checkpointDurable") readyCheckpoint.countDown()
                if (event.phase == "checkpointFailed") laterFailure.countDown()
            }
            Engine.warmUp(applicationContext).get(10, TimeUnit.MINUTES)
            val direct = Executor { it.run() }
            val delegate = object : TakeOutput {
                override fun preview(text: String) {}
                override fun commit(text: String) = error("Unexpected insertion")
                override fun returnToKeyboard() = false
                override fun finished(result: TakeResult, pcm: ByteArray) {}
            }
            var storeCalls = 0
            val store = {
                if (mode == "fixture-later-failure" && ++storeCalls > 1) error("synthetic later save failure")
                HistoryStore(root)
            }
            val output = HistoryTakeOutput(delegate, store, HistoryIo.executor, {}, id = caseId, timeline = timeline)
            val capture = object : TakeCapture {
                override fun start(accept: (FloatArray) -> Unit) {
                    Thread {
                        SyntheticFixtureAudio.feedPaced(audio, accept) {
                            val pause = when (mode) {
                                "fixture-zero" -> zeroBoundary.count == 0L
                                "fixture-checkpoint-restart" -> readyCheckpoint.count == 0L
                                "fixture-later-failure" -> laterFailure.count == 0L
                                else -> false
                            }
                            if (pause) check(awaitFile(File(root, "prefix-ack"), 15000))
                        }
                    }.start()
                }
                override fun stop(done: (Result<ByteArray>) -> Unit) = done(Result.success(ByteArray(0)))
                override fun cancel(done: () -> Unit) = done()
            }
            val take = Engine.openTake(applicationContext) { runtime, _ ->
                runtime.open(capture, output, direct, timeline, retentionEligible = true).also { it.start() }
            }
            when (mode) {
                "fixture-zero" -> { SystemClock.sleep(10_000); zeroBoundary.countDown() }
                "fixture-checkpoint-restart" -> check(readyCheckpoint.await(2, TimeUnit.MINUTES))
                "fixture-later-failure" -> check(laterFailure.await(2, TimeUnit.MINUTES))
                else -> error("unknown fixture scenario")
            }
            val ready = File(root, "producer-ready.tmp")
            ready.writeText(Process.myPid().toString())
            check(ready.renameTo(File(root, "producer-ready")))
            check(awaitFile(File(root, "prefix-ack"), 15000))
            Process.killProcess(Process.myPid())
    }

    private fun awaitFile(file: File, timeoutMs: Long): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (!file.exists() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(20)
        return file.exists()
    }
}
