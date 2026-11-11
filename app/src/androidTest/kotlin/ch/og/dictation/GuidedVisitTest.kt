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

/** Each attempt has its own run ID; failures and skips do not discard other observations. */
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
        if (!show(Intent(context, RotationProbeActivity::class.java).putExtra("welcome", true), 60) || RotationProbeActivity.result != "start") return
        var index = RotationProbeActivity.selected
        fun inventory(dir: File): Map<String, Pair<Long, Long>> =
            if (!dir.exists()) emptyMap() else requireNotNull(dir.listFiles()).associate { it.name to (it.length() to it.lastModified()) }
        while (index < RotationProbeActivity.cases.size) {
            val name = RotationProbeActivity.cases[index]
            val run = UUID.randomUUID().toString()
            val traced = name in RotationTrace.allowedCases
            HistoryIo.executor.submit {}.get()
            val history = File(context.noBackupFilesDir, "history")
            val benchmark = File(context.filesDir, "benchmark")
            val beforeHistory = inventory(history)
            val beforeBenchmark = inventory(benchmark)
            var traceComplete = false
            try {
                if (traced) RotationTrace.beginProbe(name, run)
                RotationProbeActivity.selected = index
                val finished = show(Intent(context, RotationProbeActivity::class.java).putExtra("case", name).putExtra("guided", true), 30)
                if (traced && finished && RotationProbeActivity.result !in setOf("skipped", "retry", "interrupted")) traceComplete = RotationTrace.finishProbe(10_000)
                HistoryIo.executor.submit {}.get()
                val afterHistory = inventory(history)
                val afterBenchmark = inventory(benchmark)
                val row = JSONObject().put("visit", visit).put("run", run).put("case", name)
                    .put("observation", RotationProbeActivity.result).put("traceComplete", traceComplete)
                if (name.startsWith("privacy-")) {
                    row.put("historyChanged", beforeHistory != afterHistory)
                        .put("benchmarkChanged", beforeBenchmark != afterBenchmark)
                        .put("historyAdded", afterHistory.keys.any { it.endsWith(".history") && it !in beforeHistory })
                        .put("benchmarkAdded", afterBenchmark.keys.any { it.endsWith(".wav") && it !in beforeBenchmark })
                        .put("benchmarkEligible", BenchmarkStore.recordingEnabled(BenchmarkStore.isEnabled(context), Engine.liveReceipt() != null))
                }
                output.appendText(row.toString() + "\n")
                if (!finished) return
                if (!RotationProbeActivity.result.startsWith("retry")) index++
            } catch (e: Exception) {
                output.appendText(JSONObject().put("visit", visit).put("run", run).put("case", name)
                    .put("observation", "error").put("errorType", e.javaClass.simpleName).toString() + "\n")
                index++
            } finally {
                if (traced) RotationTrace.abortProbe()
            }
        }
    }
}
