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
