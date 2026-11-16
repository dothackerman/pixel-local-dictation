package ch.og.dictation

import android.content.Intent
import android.app.ActivityManager
import android.content.Context
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoryProcessTest {
    @Test fun continuousAndPausedSyntheticSpeechExerciseRestartZeroCheckpointAndLaterFailure() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val zero = runScenario(context, UUID.randomUUID().toString(), "fixture-zero", "continuous")
        try { assertTrue("continuous pre-checkpoint death invented recovery text", HistoryStore(zero.root, HistoryOwner()).list().isEmpty()) }
        finally { zero.root.deleteRecursively() }

        val restart = runScenario(context, UUID.randomUUID().toString(), "fixture-checkpoint-restart", "paused")
        try {
            val recovered = HistoryStore(restart.root, HistoryOwner()).list().singleOrNull()
            assertTrue("paused checkpoint did not survive producer death", restart.prefixBeforeDeath != null && restart.prefixBeforeDeath == recovered?.text)
            assertEquals(HistoryCompletion.INTERRUPTED, recovered?.completion)
        } finally { restart.root.deleteRecursively() }

        val failure = runScenario(context, UUID.randomUUID().toString(), "fixture-later-failure", "paused")
        try {
            val recovered = HistoryStore(failure.root, HistoryOwner()).list().singleOrNull()
            assertTrue("later failed write replaced the known good prefix", failure.prefixBeforeDeath != null && failure.prefixBeforeDeath == recovered?.text)
            assertEquals(HistoryCompletion.INTERRUPTED, recovered?.completion)
        } finally { failure.root.deleteRecursively() }
    }

    private data class ScenarioResult(val root: File, val prefixBeforeDeath: String?)

    private fun runScenario(context: Context, id: String, mode: String, fixtureId: String): ScenarioResult {
        val root = File(context.noBackupFilesDir, "issue54-producer/$id")
        root.deleteRecursively()
        val intent = Intent().setClassName(context, "ch.og.dictation.CheckpointProducerService")
            .putExtra("mode", mode).putExtra("case_id", id).putExtra("fixture_id", fixtureId)
        try {
        context.startService(intent)
        val deadline = SystemClock.uptimeMillis() + TimeUnit.MINUTES.toMillis(3)
        var prefixBeforeDeath: String? = null
        while (!File(root, "producer-ready").exists() && !File(root, "producer-failed").exists() && SystemClock.uptimeMillis() < deadline) {
            if (mode != "fixture-zero" && prefixBeforeDeath == null) {
                prefixBeforeDeath = HistoryStore(root, HistoryOwner()).list().singleOrNull()?.text
            }
            SystemClock.sleep(100)
        }
        assertFalse("synthetic fixture boundary was not reached", File(root, "producer-failed").exists())
        assertTrue("synthetic producer did not reach its case boundary", File(root, "producer-ready").exists())
        if (mode != "fixture-zero" && prefixBeforeDeath == null) {
            prefixBeforeDeath = HistoryStore(root, HistoryOwner()).list().singleOrNull()?.text
        }
        File(root, "prefix-ack").writeText("ack")
        val pid = File(root, "producer-ready").readText().toInt()
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        while (manager.runningAppProcesses.orEmpty().any { it.pid == pid } && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(20)
        assertFalse("producer process remained alive", manager.runningAppProcesses.orEmpty().any { it.pid == pid })
        return ScenarioResult(root, prefixBeforeDeath)
        } catch (failure: Throwable) {
            root.mkdirs()
            File(root, "prefix-ack").writeText("ack")
            val ready = File(root, "producer-ready")
            val pid = ready.takeIf { it.exists() }?.readText()?.toIntOrNull()
            if (pid != null) {
                val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val cleanupDeadline = SystemClock.uptimeMillis() + 20_000
                while (manager.runningAppProcesses.orEmpty().any { it.pid == pid } && SystemClock.uptimeMillis() < cleanupDeadline) SystemClock.sleep(20)
            }
            root.deleteRecursively()
            throw failure
        }
    }

    @Test fun unboundProducerDeathWithNoCheckpointLeavesNoRecoverableText() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.noBackupFilesDir, "checkpoint-process-test")
        root.deleteRecursively()
        try {
            context.startService(Intent().setClassName(context, "ch.og.dictation.CheckpointProducerService")
                .putExtra("zero_checkpoint", true))
            val deadline = SystemClock.uptimeMillis() + 10000
            while (!File(root, "producer-ready").exists() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(20)
            assertTrue("synthetic producer did not reach its no-checkpoint boundary", File(root, "producer-ready").exists())
            val pid = File(root, "producer-ready").readText().toInt()
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            while (manager.runningAppProcesses.orEmpty().any { it.pid == pid } && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(20)
            assertFalse(manager.runningAppProcesses.orEmpty().any { it.pid == pid })
            assertTrue("process death invented a checkpoint", HistoryStore(root, HistoryOwner()).list().isEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test fun unboundProducerLeavesInterruptedCheckpointForNewProcessOwner() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.noBackupFilesDir, "checkpoint-process-test")
        val normalRoot = File(context.noBackupFilesDir, "history")
        // Represents an in-progress write belonging to the already-running main process.
        val pending = File(normalRoot, "${UUID.randomUUID()}.tmp")
        root.deleteRecursively()
        try {
            HistoryIo.executor.submit {}.get(10, TimeUnit.SECONDS)
            assertTrue(normalRoot.isDirectory || normalRoot.mkdirs())
            pending.writeText("synthetic pending checkpoint")
            context.startService(Intent().setClassName(context, "ch.og.dictation.CheckpointProducerService"))
            val deadline = SystemClock.uptimeMillis() + 10000
            while (!File(root, "producer-ready").exists() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(20)
            assertTrue(File(root, "producer-ready").exists())
            val pid = File(root, "producer-ready").readText().toInt()
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            while (manager.runningAppProcesses.orEmpty().any { it.pid == pid } && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(20)
            assertFalse(manager.runningAppProcesses.orEmpty().any { it.pid == pid })
            assertTrue("producer startup removed the main process's pending checkpoint", pending.exists())
            assertEquals("synthetic pending checkpoint", pending.readText())
            val recovered = HistoryStore(root, HistoryOwner()).list().single()
            assertEquals("synthetic checkpoint", recovered.text)
            assertEquals(HistoryCompletion.INTERRUPTED, recovered.completion)
            assertEquals(HistoryDelivery.UNKNOWN, recovered.delivery)
        } finally { pending.delete(); root.deleteRecursively() }
    }
}
