package ch.og.dictation

import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test

class RuntimeRetirementTest {
    @Test fun retirementKeepsAnActiveTakeUsableAndReleasesBehindQueuedNativeWork() {
        val jobs = ArrayDeque<Runnable>()
        val queue = Executor { jobs.add(it) }
        var released = 0
        val rec = object : Recognizer { override fun transcribe(samples: FloatArray) = "synthetic" }
        val segmenter = object : Segmenter {
            override fun accept(samples: FloatArray) = emptyList<Chunk>()
            override fun flush() = emptyList<Chunk>()
            override fun reset() {}
        }
        val runtime = DictationRuntime(Variant.DEFAULT, queue, { rec }, { segmenter }, { released++ })
        runtime.warmUp(); jobs.removeFirst().run()
        val capture = object : TakeCapture {
            override fun start(accept: (FloatArray) -> Unit) {}
            override fun stop(done: (Result<ByteArray>) -> Unit) = done(Result.success(byteArrayOf()))
            override fun cancel(done: () -> Unit) = done()
        }
        val output = object : TakeOutput {
            override fun preview(text: String) {}
            override fun commit(text: String) = true
            override fun returnToKeyboard() = true
            override fun finished(result: TakeResult, pcm: ByteArray) {}
        }
        val take = runtime.open(capture, output, Executor { it.run() })
        take.start()
        runtime.retire()
        assertEquals(0, released)
        assertThrows(IllegalStateException::class.java) { runtime.open(capture, output, Executor { it.run() }) }
        take.cancel()
        assertEquals(0, released)
        while (jobs.isNotEmpty()) jobs.removeFirst().run()
        assertEquals(1, released)
    }
}
