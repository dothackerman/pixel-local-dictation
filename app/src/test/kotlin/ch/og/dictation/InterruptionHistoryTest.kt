package ch.og.dictation

import java.nio.file.Files
import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test

class InterruptionHistoryTest {
    private class Queue : Executor {
        private val items = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { items.add(command) }
        fun drain() { while (items.isNotEmpty()) items.removeFirst().run() }
    }

    @Test fun interruptionSavesOnlyAcceptedAudioWithoutInserting() {
        val directory = Files.createTempDirectory("interrupted-history").toFile()
        try {
            val decode = Queue()
            val io = Queue()
            val direct = Executor { it.run() }
            lateinit var feed: (FloatArray) -> Unit
            lateinit var ack: (Result<ByteArray>) -> Unit
            val capture = object : TakeCapture {
                override fun start(accept: (FloatArray) -> Unit) { feed = accept }
                override fun stop(done: (Result<ByteArray>) -> Unit) { ack = done }
                override fun cancel(done: () -> Unit) = done()
            }
            var inserted = false
            var returned = false
            var result: TakeResult? = null
            val output = object : TakeOutput {
                override fun preview(text: String) {}
                override fun commit(text: String): Boolean { inserted = true; return true }
                override fun returnToKeyboard(): Boolean { returned = true; return true }
                override fun finished(value: TakeResult, pcm: ByteArray) { result = value }
            }
            val segments = { object : Segmenter {
                private var tail: FloatArray? = null
                override fun accept(samples: FloatArray): List<Chunk> {
                    if (samples[0] == 1f) return listOf(Chunk(samples, true))
                    tail = samples
                    return emptyList()
                }
                override fun flush() = tail?.let { listOf(Chunk(it, true)) } ?: emptyList()
                override fun reset() {}
            } }
            val runtime = DictationRuntime(Variant.HISTORICAL_INT8, decode, { Recognizer { "word" } }, segments)
            val take = runtime.open(capture, HistoryTakeOutput(output, { HistoryStore(directory) }, io,
                { fail("save failed") }, id = "00000000-0000-0000-0000-000000000045", delivery = direct), direct)
            take.start()
            feed(floatArrayOf(1f))
            feed(floatArrayOf(2f))
            var released = false
            take.interrupt { released = true }
            feed(floatArrayOf(3f))
            ack(Result.success(byteArrayOf(1)))
            assertTrue(released)
            assertNull(result)
            decode.drain()
            assertNull(result)
            io.drain()
            assertEquals("word word", HistoryStore(directory).list().single().text)
            assertEquals(HistoryCompletion.INTERRUPTED, HistoryStore(directory).list().single().completion)
            assertTrue(result!!.historySaved)
            assertFalse(inserted)
            assertFalse(returned)
        } finally { directory.deleteRecursively() }
    }
    @Test fun interruptionWhileCompletedWriteAwaitsDeliveryNeverInserts() {
        val directory = Files.createTempDirectory("interrupted-stage").toFile()
        try {
            val io = Queue()
            val delivery = Queue()
            val direct = Executor { it.run() }
            lateinit var feed: (FloatArray) -> Unit
            lateinit var ack: (Result<ByteArray>) -> Unit
            val capture = object : TakeCapture {
                override fun start(accept: (FloatArray) -> Unit) { feed = accept }
                override fun stop(done: (Result<ByteArray>) -> Unit) { ack = done }
                override fun cancel(done: () -> Unit) = done()
            }
            var inserted = false
            var finished: TakeResult? = null
            val output = object : TakeOutput {
                override fun preview(text: String) {}
                override fun commit(text: String): Boolean { inserted = true; return true }
                override fun returnToKeyboard(): Boolean { fail("must not return"); return false }
                override fun finished(result: TakeResult, pcm: ByteArray) { finished = result }
            }
            val segments = { object : Segmenter {
                override fun accept(samples: FloatArray) = listOf(Chunk(samples, true))
                override fun flush() = emptyList<Chunk>()
                override fun reset() {}
            } }
            val runtime = DictationRuntime(Variant.HISTORICAL_INT8, direct, { Recognizer { "word" } }, segments)
            val take = runtime.open(capture, HistoryTakeOutput(output, { HistoryStore(directory) }, io,
                { fail("save failed") }, id = "00000000-0000-0000-0000-000000000046", delivery = delivery), delivery)
            take.start()
            feed(floatArrayOf(1f))
            take.stop()
            ack(Result.success(byteArrayOf()))
            delivery.drain()
            io.drain()
            take.interrupt()
            delivery.drain()
            io.drain()
            delivery.drain()
            assertFalse(inserted)
            assertTrue(finished!!.historySaved)
            val entry = HistoryStore(directory).list().single()
            assertEquals("word", entry.text)
            assertEquals(HistoryCompletion.COMPLETE, entry.completion)
            assertEquals(HistoryDelivery.NOT_ATTEMPTED, entry.delivery)
        } finally { directory.deleteRecursively() }
    }

    @Test fun emptyInterruptedTakeLeavesNoActiveHistoryOwner() {
        val directory = Files.createTempDirectory("interrupted-empty").toFile()
        try {
            val id = "00000000-0000-0000-0000-000000000047"
            val direct = Executor { it.run() }
            lateinit var ack: (Result<ByteArray>) -> Unit
            val capture = object : TakeCapture {
                override fun start(accept: (FloatArray) -> Unit) {}
                override fun stop(done: (Result<ByteArray>) -> Unit) { ack = done }
                override fun cancel(done: () -> Unit) = done()
            }
            val output = object : TakeOutput {
                override fun preview(text: String) {}
                override fun commit(text: String) = false
                override fun returnToKeyboard() = false
                override fun finished(result: TakeResult, pcm: ByteArray) {}
            }
            val segments = { object : Segmenter {
                override fun accept(samples: FloatArray) = emptyList<Chunk>()
                override fun flush() = emptyList<Chunk>()
                override fun reset() {}
            } }
            val runtime = DictationRuntime(Variant.HISTORICAL_INT8, direct, { Recognizer { "" } }, segments)
            val take = runtime.open(capture, HistoryTakeOutput(output, { HistoryStore(directory) }, direct,
                { fail("save failed") }, id = id), direct)
            take.start()
            assertTrue(id in HistoryProcess.owner.activeIds())
            take.interrupt()
            ack(Result.success(byteArrayOf()))
            assertFalse(id in HistoryProcess.owner.activeIds())
            assertTrue(HistoryStore(directory).list().isEmpty())
        } finally { directory.deleteRecursively() }
    }

}
