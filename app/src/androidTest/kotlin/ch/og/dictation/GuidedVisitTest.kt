package ch.og.dictation

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Owner visit (ticket #61): one screen per step. Each attempt has its own run ID; failures and skips
 * do not discard other observations. `extended=true` runs the whole script; the default runs A2 only;
 * `steps=A2,A3,A7,A8,B9` runs only those steps and `start=A7` starts at one (no welcome screen).
 */
@RunWith(AndroidJUnit4::class)
class GuidedVisitTest {
    @Test fun ownerVisit() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val visit = UUID.randomUUID().toString()
        val output = File(context.filesDir, "lab/guided-visit-$visit.jsonl")
        output.parentFile?.mkdirs()
        fun show(intent: Intent, minutes: Long): Boolean {
            RotationProbeActivity.done = CountDownLatch(1)
            RotationProbeActivity.result = "interrupted"
            instrumentation.startActivitySync(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return RotationProbeActivity.done.await(minutes, TimeUnit.MINUTES)
        }
        val arguments = InstrumentationRegistry.getArguments()
        val extended = arguments.getString("extended") == "true"
        // Re-visit: `steps=A2,A3,A7,A8,B9` runs only those; `start=A7` starts there.
        val chosen = arguments.getString("steps")
        val start = arguments.getString("start")
        val steps = GuidedVisit.select(chosen, start, extended)
        val welcome = extended && chosen == null && start == null
        if (welcome && (!show(Intent(context, RotationProbeActivity::class.java).putExtra("welcome", true), 30) ||
                RotationProbeActivity.result != "start")) return
        val progress = GuidedVisitProgress(steps, if (welcome) RotationProbeActivity.selected else 0)
        fun inventory(dir: File): Map<String, Pair<Long, Long>> =
            if (!dir.exists()) emptyMap() else requireNotNull(dir.listFiles()).associate { it.name to (it.length() to it.lastModified()) }
        while (true) {
            val step = progress.current ?: break
            val run = UUID.randomUUID().toString()
            val traceCase = step.traceCase
            HistoryIo.executor.submit {}.get()
            val history = File(context.noBackupFilesDir, "history")
            val benchmark = File(context.filesDir, "benchmark")
            val beforeHistory = inventory(history)
            val beforeBenchmark = inventory(benchmark)
            var traceComplete = false
            try {
                if (traceCase != null) makeRoomForRotationTrace(context)
                if (traceCase != null) RotationTrace.beginProbe(traceCase, run, step.traceMinutes.toLong())
                val finished = show(Intent(context, RotationProbeActivity::class.java).putExtra("step", step.id)
                    .putExtra("position", steps.indexOf(step) + 1).putExtra("stepCount", steps.size),
                    (step.traceMinutes + 5).toLong().coerceAtLeast(30))
                val result = StepResult.parse(step, RotationProbeActivity.result)
                val outcome = if (finished) result.outcome else Outcome.INTERRUPTED
                if (traceCase != null && finished && outcome in setOf(Outcome.DONE, Outcome.END)) traceComplete = RotationTrace.finishProbe(10_000)
                HistoryIo.executor.submit {}.get()
                val afterHistory = inventory(history)
                val afterBenchmark = inventory(benchmark)
                val row = GuidedVisit.row(visit, run, step, result.copy(outcome = outcome), traceComplete)
                if (step.privacyCheck) {
                    row.put("historyChanged", beforeHistory != afterHistory)
                        .put("benchmarkChanged", beforeBenchmark != afterBenchmark)
                        .put("historyAdded", afterHistory.keys.any { it.endsWith(".history") && it !in beforeHistory })
                        .put("benchmarkAdded", afterBenchmark.keys.any { it.endsWith(".wav") && it !in beforeBenchmark })
                        .put("benchmarkEligible", BenchmarkStore.recordingEnabled(BenchmarkStore.isEnabled(context), Engine.liveReceipt() != null))
                }
                output.appendText(row.toString() + "\n")
                progress.record(outcome)
            } catch (e: Exception) {
                output.appendText(JSONObject().put("visit", visit).put("run", run).put("step", step.id).put("case", traceCase ?: step.id)
                    .put("observation", "error").put("errorType", e.javaClass.simpleName).toString() + "\n")
                progress.record(Outcome.SKIPPED)
            } finally {
                if (traceCase != null) RotationTrace.abortProbe()
            }
        }
    }
}
