package ch.og.dictation

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic audio only; receipt rows contain timing, sample offsets and opaque identities. */
@RunWith(AndroidJUnit4::class)
class RecoveryTimingTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun safeStopStoresContinuousAndPausedSyntheticSpeechWithTimingOnlyReceipts() {
        Engine.warmUp(context).get(10, TimeUnit.MINUTES)
        val receipts = File(context.filesDir, "lab/recovery-timing.jsonl")
        for (fixtureId in listOf("continuous", "paused")) {
            val wav = SyntheticFixtureAudio.file(context.filesDir, fixtureId)
            assertTrue("synthetic fixture missing", wav.isFile)
            val samples = SyntheticFixtureAudio.read(wav)
            val takeId = UUID.randomUUID().toString()
            val processId = "test-${android.os.Process.myPid()}-${UUID.randomUUID()}"
            val events = java.util.Collections.synchronizedList(mutableListOf<TimelineEvent>())
            events += TimelineEvent("scenario", fixtureId = fixtureId)
            val timeline = TimelineObserver { events.add(it) }
            val executor = Executors.newSingleThreadExecutor()
            val finished = CountDownLatch(1)
            var result: TakeResult? = null
            val storage = File(context.noBackupFilesDir, "issue54-timing/$takeId")
            val store = HistoryStore(storage)
            try {
            val delegate = object : TakeOutput {
                override fun preview(text: String) {}
                override fun commit(text: String) = true
                override fun returnToKeyboard() = true
                override fun finished(value: TakeResult, pcm: ByteArray) {
                    result = value
                    finished.countDown()
                }
            }
            val capture = PacedFixtureCapture(samples)
            val output = HistoryTakeOutput(delegate, { store }, HistoryIo.executor, {}, id = takeId,
                delivery = executor, timeline = timeline)
            val take = Engine.openTake(context) { runtime, _ ->
                runtime.open(capture, output, executor, timeline, retentionEligible = true).also { it.start() }
            }
            capture.awaitInput()
            take.stop()
            assertTrue("synthetic safe Stop did not finish", finished.await(10, TimeUnit.MINUTES))
            val completed = requireNotNull(result)
            assertEquals(TakeOutcome.COMPLETED, completed.outcome)
            val entry = store.get(takeId)
            assertTrue("final synthetic transcript was not saved", entry != null)
            assertTrue("safe Stop differs from private History", completed.text == entry?.text)
            appendReceipts(receipts, processId, takeId, events)
            HistoryIo.executor.submit<Boolean> { store.delete(takeId); true }.get(10, TimeUnit.SECONDS)
            } finally {
                runCatching { HistoryIo.executor.submit {}.get(10, TimeUnit.SECONDS) }
                storage.deleteRecursively()
                executor.shutdownNow()
                executor.awaitTermination(10, TimeUnit.SECONDS)
            }
            storage.deleteRecursively()
        }
    }

    private fun appendReceipts(file: File, processId: String, takeId: String, events: List<TimelineEvent>) {
        file.parentFile?.mkdirs()
        val rows = synchronized(events) { events.toList() }
        val lines = rows.map { event ->
            JSONObject().put("protocol", "recovery-timing-v1").put("clock", "CLOCK_MONOTONIC")
                .put("processId", processId).put("takeId", takeId).put("phase", event.phase).put("ns", event.ns).also { row ->
                    event.fixtureId?.let { row.put("fixtureId", it) }
                    event.chunkId?.let { row.put("chunkId", it) }
                    event.samples?.let { row.put("samples", it) }
                    event.sampleStart?.let { row.put("sampleStart", it) }
                    event.sampleEnd?.let { row.put("sampleEnd", it) }
                    event.queued?.let { row.put("queued", it) }
                    event.active?.let { row.put("active", it) }
                    event.revision?.let { row.put("revision", it) }
                }.toString()
        }
        val bytes = (lines.joinToString("\n", postfix = "\n")).toByteArray(Charsets.UTF_8)
        check(file.length() + bytes.size <= 2 * 1024 * 1024) { "timing receipt file full; pull it before rerunning" }
        file.appendBytes(bytes)
    }

    private class PacedFixtureCapture(private val audio: FloatArray) : TakeCapture {
        private lateinit var feeder: Thread
        override fun start(accept: (FloatArray) -> Unit) {
            feeder = Thread({ SyntheticFixtureAudio.feedPaced(audio, accept) }, "synthetic-audio-capture").apply { start() }
        }
        fun awaitInput() { feeder.join(TimeUnit.MINUTES.toMillis(3)) }
        override fun stop(done: (Result<ByteArray>) -> Unit) {
            awaitInput()
            done(Result.success(ByteArray(0)))
        }
        override fun cancel(done: () -> Unit) { done() }
    }
}
