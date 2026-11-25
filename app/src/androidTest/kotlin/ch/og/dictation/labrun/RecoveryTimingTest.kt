package ch.og.dictation.labrun

import ch.og.dictation.diagnostics.LabReceipts
import ch.og.dictation.wiring
import ch.og.dictation.history.HistoryIo
import ch.og.dictation.history.HistoryStore
import ch.og.dictation.history.HistoryTakeOutput
import ch.og.dictation.probe.SyntheticFixtureAudio
import ch.og.dictation.recognition.Engine
import ch.og.dictation.take.TakeCapture
import ch.og.dictation.take.TakeOutcome
import ch.og.dictation.take.TakeOutput
import ch.og.dictation.take.TakeResult
import ch.og.dictation.take.TimelineEvent
import ch.og.dictation.take.TimelineObserver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Synthetic audio only; receipt rows contain timing, sample offsets and opaque identities. Times a safe
 * Stop with History attached; its History lives in a scratch folder inside the lab folder and is
 * deleted afterwards. That a safe Stop saves the text it delivers is a host test:
 * HistoryTakeTest.safeStopCompletesWithTheTextPrivateHistoryHolds.
 */
@RunWith(AndroidJUnit4::class)
class RecoveryTimingTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun safeStopStoresContinuousAndPausedSyntheticSpeechWithTimingOnlyReceipts() =
        LabRun.measure("RecoveryTimingTest.safeStopStoresContinuousAndPausedSyntheticSpeechWithTimingOnlyReceipts") { lab -> collect(lab) }

    private fun collect(lab: File) {
        context.wiring.engine.warmUp().get(10, TimeUnit.MINUTES)
        val receipts = File(lab, "recovery-timing.jsonl")
        for (fixtureId in listOf("continuous", "paused")) {
            val wav = SyntheticFixtureAudio.file(lab, fixtureId)
            check(wav.isFile) { "synthetic fixture missing" }
            val samples = SyntheticFixtureAudio.read(wav)
            val takeId = UUID.randomUUID().toString()
            val processId = "test-${android.os.Process.myPid()}-${UUID.randomUUID()}"
            val events = java.util.Collections.synchronizedList(mutableListOf<TimelineEvent>())
            events += TimelineEvent("scenario", fixtureId = fixtureId)
            val timeline = TimelineObserver { events.add(it) }
            val executor = Executors.newSingleThreadExecutor()
            val finished = CountDownLatch(1)
            var result: TakeResult? = null
            val scratch = File(lab, "labrun-scratch/issue54-timing/$takeId")
            val store = HistoryStore(scratch)
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
            val take = context.wiring.engine.openTake { runtime, _ ->
                runtime.open(capture, output, executor, timeline, retentionEligible = true).also { it.start() }
            }
            capture.awaitInput()
            take.stop()
            check(finished.await(10, TimeUnit.MINUTES)) { "synthetic safe Stop did not finish" }
            check(requireNotNull(result).outcome == TakeOutcome.COMPLETED) { "synthetic take did not complete" }
            appendReceipts(receipts, processId, takeId, events)
            HistoryIo.executor.submit<Boolean> { store.delete(takeId); true }.get(10, TimeUnit.SECONDS)
            } finally {
                runCatching { HistoryIo.executor.submit {}.get(10, TimeUnit.SECONDS) }
                scratch.deleteRecursively()
                executor.shutdownNow()
                executor.awaitTermination(10, TimeUnit.SECONDS)
            }
        }
        File(lab, "labrun-scratch").deleteRecursively()
    }

    private fun appendReceipts(file: File, processId: String, takeId: String, events: List<TimelineEvent>) {
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
        LabReceipts.append(file, lines.joinToString("\n", postfix = "\n"))
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
