package ch.og.dictation

import android.content.Intent
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Owner runs one harmless take to completion; inspect file metadata only, never content. */
@RunWith(AndroidJUnit4::class)
class PrivacyProbeTest {
    @Test fun excludedFieldDoesNotRetainTake() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val caseName = requireNotNull(InstrumentationRegistry.getArguments().getString("case"))
        require(caseName in setOf("privacy-password", "privacy-no-learning", "privacy-ordinary"))
        assertFalse("rotation trace must be inactive", RotationTrace.probeActive())
        fun inventory(dir: File): Map<String, Pair<Long, Long>> =
            if (!dir.exists()) emptyMap() else requireNotNull(dir.listFiles()).associate { it.name to (it.length() to it.lastModified()) }
        val history = File(context.noBackupFilesDir, "history")
        val benchmark = File(context.filesDir, "benchmark")
        HistoryIo.executor.submit {}.get()
        val beforeHistory = inventory(history)
        val beforeBenchmark = inventory(benchmark)
        RotationProbeActivity.done = CountDownLatch(1)
        instrumentation.startActivitySync(Intent(context, RotationProbeActivity::class.java)
            .putExtra("case", caseName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertTrue("owner did not finish probe within five minutes", RotationProbeActivity.done.await(5, TimeUnit.MINUTES))
        HistoryIo.executor.submit {}.get()
        val afterHistory = inventory(history)
        val afterBenchmark = inventory(benchmark)
        val benchmarkEligible = BenchmarkStore.recordingEnabled(BenchmarkStore.isEnabled(context), Engine.liveReceipt() != null)
        Log.i("PrivacyProbe", "benchmark retention enabled for ordinary take: $benchmarkEligible")
        if (caseName == "privacy-ordinary") {
            assertTrue("ordinary take did not create a History file", afterHistory.keys.any { it.endsWith(".history") && it !in beforeHistory })
            if (benchmarkEligible) assertTrue("ordinary take did not create a benchmark recording", afterBenchmark.keys.any { it.endsWith(".wav") && it !in beforeBenchmark })
        } else {
            assertEquals("History files changed", beforeHistory, afterHistory)
            assertEquals("benchmark files changed", beforeBenchmark, afterBenchmark)
        }
    }
}
