package ch.og.dictation

import ch.og.dictation.history.HistoryEntry
import ch.og.dictation.history.HistoryIo
import ch.og.dictation.history.HistoryStore
import ch.og.dictation.history.startHistory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoryStartupTest {
    @Test fun applicationStartupSurvivesCleanupFailureAndCanRetry() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = java.nio.file.Files.createTempDirectory(context.cacheDir.toPath(), "history-startup-failure-").toFile()
        val entry = HistoryEntry("b3de7a72-84ce-4721-8412-497b90c09315", 123, "synthetic existing data")
        try {
            HistoryStore(root).save(entry)
            val obstruction = File(root, "abandoned.tmp").apply { mkdir(); File(this, "partial").writeText("synthetic") }
            startHistory(owns = true, root = root)
            HistoryIo.executor.submit {}.get(10, TimeUnit.SECONDS)
            assertEquals(entry, HistoryStore(root).get(entry.id))
            obstruction.deleteRecursively()
            val reopened = HistoryIo.executor.submit<HistoryStore> { HistoryIo.store(root) }.get(10, TimeUnit.SECONDS)
            assertEquals(entry, reopened.get(entry.id))
        } finally { root.deleteRecursively() }
    }

    @Test fun applicationStartupRemovesAbandonedTempWithoutOpeningHistory() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(context.applicationContext is DictationApplication)
        val root = java.nio.file.Files.createTempDirectory(context.cacheDir.toPath(), "history-startup-").toFile()
        val temp = File(root, "abandoned.tmp").apply { writeText("synthetic") }
        try {
            startHistory(owns = true, root = root)
            HistoryIo.executor.submit {}.get(10, TimeUnit.SECONDS)
            assertFalse(temp.exists())
        } finally { root.deleteRecursively() }
    }
}
