package ch.og.dictation

import java.nio.file.Files
import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test

class HistoryTakeTest {
    @Test fun successfulStopIsSavedBeforeEditorReceivesText() {
        val dir = Files.createTempDirectory("history-staged").toFile()
        try {
            var feed: ((FloatArray) -> Unit)? = null
            val capture = object : TakeCapture {
                override fun start(accept: (FloatArray) -> Unit) { feed = accept }
                override fun stop(done: (Result<ByteArray>) -> Unit) = done(Result.success(byteArrayOf()))
                override fun cancel(done: () -> Unit) = done()
            }
            val direct = Executor { it.run() }
            var observed = false
            val editor = object : TakeOutput {
                override fun preview(text: String) {}
                override fun commit(text: String): Boolean {
                    observed = HistoryStore(dir).list().single().text == "synthetic words"
                    return true
                }
                override fun returnToKeyboard() = true
                override fun finished(result: TakeResult, pcm: ByteArray) {}
            }
            val segments = { object : Segmenter {
                override fun accept(samples: FloatArray) = listOf(Chunk(samples, true))
                override fun flush() = emptyList<Chunk>()
                override fun reset() {}
            } }
            val runtime = DictationRuntime(Variant.HISTORICAL_INT8, direct, { Recognizer { "synthetic words" } }, segments)
            val take = runtime.open(capture, HistoryTakeOutput(editor, { HistoryStore(dir) }, direct, { fail("save failed") }), direct)
            take.start(); feed!!(floatArrayOf(1f)); take.stop()
            assertTrue(observed)
            runtime.close().join()
        } finally { dir.deleteRecursively() }
    }
    @Test fun completedChunkIsRecoverableBeforeStop() {
        val dir = Files.createTempDirectory("history-checkpoint").toFile()
        try {
            var feed: ((FloatArray) -> Unit)? = null
            val capture = object : TakeCapture {
                override fun start(accept: (FloatArray) -> Unit) { feed = accept }
                override fun stop(done: (Result<ByteArray>) -> Unit) = done(Result.success(byteArrayOf()))
                override fun cancel(done: () -> Unit) = done()
            }
            val direct = Executor { it.run() }
            val editor = object : TakeOutput {
                override fun preview(text: String) {}
                override fun commit(text: String) = true
                override fun returnToKeyboard() = true
                override fun finished(result: TakeResult, pcm: ByteArray) {}
            }
            val runtime = DictationRuntime(Variant.HISTORICAL_INT8, direct, { Recognizer { "first words" } },
                { object : Segmenter {
                    override fun accept(samples: FloatArray) = listOf(Chunk(samples, true))
                    override fun flush() = emptyList<Chunk>()
                    override fun reset() {}
                } })
            val take = runtime.open(capture, HistoryTakeOutput(editor, { HistoryStore(dir) }, direct, { fail("save failed") }), direct)
            take.start(); feed!!(floatArrayOf(1f))
            assertEquals("first words", HistoryStore(dir).list().single().text)
            assertEquals(HistoryCompletion.RECORDING, HistoryStore(dir).list().single().completion)
            take.cancel()
            assertTrue(HistoryStore(dir).list().isEmpty())
            runtime.close().join()
        } finally { dir.deleteRecursively() }
    }
    @Test fun deletingCheckpointSuppressesLaterSnapshots() {
        val dir = Files.createTempDirectory("history-suppressed").toFile()
        try {
            val direct = Executor { it.run() }
            val editor = object : TakeOutput {
                override fun preview(text: String) {}
                override fun commit(text: String) = true
                override fun returnToKeyboard() = true
                override fun finished(result: TakeResult, pcm: ByteArray) {}
            }
            val output = HistoryTakeOutput(editor, { HistoryStore(dir) }, direct, { fail("save failed") })
            output.startHistory(true)
            output.preview("first")
            val store = HistoryStore(dir)
            store.delete(store.list().single().id)
            output.preview("first second")
            assertTrue(store.list().isEmpty())
        } finally { dir.deleteRecursively() }
    }
    @Test fun storageFailureIsReported() {
        val root = Files.createTempFile("history-unwritable", ".file").toFile()
        try {
            var failed = false
            val delegate = object : TakeOutput {
                override fun preview(text: String) {}
                override fun commit(text: String) = false
                override fun returnToKeyboard() = true
                override fun finished(result: TakeResult, pcm: ByteArray) {}
            }
            val output = HistoryTakeOutput(delegate, { HistoryStore(root) }, Executor { it.run() }, { failed = true })
            output.stage("synthetic", true) {}
            assertTrue(failed)
        } finally { root.delete() }
    }
    @Test fun eligibleStopsRecordTheirDeliveryAndExcludedStopsAreNotSaved() {
        val dir = Files.createTempDirectory("history-take").toFile()
        try {
            val direct = Executor { it.run() }
            for ((eligible, attempted, accepted) in listOf(Triple(true, true, false), Triple(false, true, false), Triple(true, false, false), Triple(true, true, true))) {
                var feed: ((FloatArray) -> Unit)? = null
                val capture = object : TakeCapture {
                    override fun start(accept: (FloatArray) -> Unit) { feed = accept }
                    override fun stop(done: (Result<ByteArray>) -> Unit) = done(Result.success(byteArrayOf()))
                    override fun cancel(done: () -> Unit) = done()
                }
                var outcome: TakeOutcome? = null
                val editor = object : TakeOutput {
                    override fun preview(text: String) {}
                    override fun commit(text: String) = accepted
                    override fun commitAttempted() = attempted
                    override fun returnToKeyboard() = true
                    override fun finished(result: TakeResult, pcm: ByteArray) { outcome = result.outcome }
                }
                val segments = { object : Segmenter {
                    override fun accept(samples: FloatArray) = listOf(Chunk(samples, true))
                    override fun flush() = emptyList<Chunk>()
                    override fun reset() {}
                } }
                val runtime = DictationRuntime(Variant.HISTORICAL_INT8, direct, { Recognizer { "synthetic words" } }, segments)
                val output = HistoryTakeOutput(editor, { HistoryStore(dir) }, direct, { fail("save failed") })
                val take = runtime.open(capture, output, direct, retentionEligible = eligible)
                take.start(); feed!!(floatArrayOf(0.25f)); take.stop()
                assertEquals(if (!attempted) TakeOutcome.INPUT_CHANGED else if (accepted) TakeOutcome.COMPLETED else TakeOutcome.COMMIT_REJECTED, outcome)
                runtime.close().join()
            }
            val entries = HistoryStore(dir).list()
            assertEquals(3, entries.size)
            assertTrue(entries.all { it.text == "synthetic words" })
            assertEquals(setOf(HistoryDelivery.REJECTED, HistoryDelivery.NOT_ATTEMPTED, HistoryDelivery.ACCEPTED), entries.map { it.delivery }.toSet())
        } finally { dir.deleteRecursively() }
    }
}
