package ch.og.dictation

import java.util.concurrent.Executor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TakeTranscriberTest {
    /** Runs jobs only when told to, like a busy decode thread. */
    private class Queue : Executor {
        val jobs = ArrayDeque<Runnable>()
        override fun execute(r: Runnable) { jobs.add(r) }
        fun runAll() { while (jobs.isNotEmpty()) jobs.removeFirst().run() }
    }

    /** Every fed chunk is a segment; flush returns one pending piece. */
    private class ChunkSegmenter(private val pending: FloatArray? = null) : Segmenter {
        override fun accept(samples: FloatArray) = listOf(samples)
        override fun flush() = listOfNotNull(pending)
        override fun reset() {}
    }

    private val direct = Executor { it.run() }
    private fun seg(id: Int) = FloatArray(1) { id.toFloat() }
    private val bySample = FakeRecognizer { mapOf(1f to "eins", 2f to " zwei ", 3f to "", 4f to "vier")[it[0]]!! }

    @Test fun joinsSegmentsInOrderWithSingleSpacesAndSkipsEmptyOnes() {
        val queue = Queue(); val previews = ArrayList<String>(); var done: Result<String>? = null
        val take = TakeTranscriber({ bySample }, ChunkSegmenter(pending = seg(4)), queue, direct) { previews.add(it) }
        take.feed(seg(1)); take.feed(seg(2)); take.feed(seg(3))
        take.finish { done = it }
        assertNull("nothing is delivered before decoding ran", done)
        queue.runAll()
        assertEquals(listOf("eins", "eins zwei", "eins zwei vier"), previews)
        assertEquals("eins zwei vier", done!!.getOrThrow())
    }

    @Test fun cancelStopsAllCallbacksEvenForQueuedWork() {
        val queue = Queue(); val previews = ArrayList<String>(); var done: Result<String>? = null
        val take = TakeTranscriber({ bySample }, ChunkSegmenter(), queue, direct) { previews.add(it) }
        take.feed(seg(1)); take.finish { done = it }
        take.cancel()
        queue.runAll()
        assertTrue(previews.isEmpty()); assertNull(done)
    }

    @Test fun cancelAfterDecodingButBeforeDeliveryDeliversNothing() {
        val queue = Queue(); val main = Queue(); var done: Result<String>? = null
        val take = TakeTranscriber({ bySample }, ChunkSegmenter(), queue, main) {}
        take.feed(seg(1)); take.finish { done = it }
        queue.runAll(); take.cancel(); main.runAll()
        assertNull(done)
    }

    @Test fun recognizerFailureIsReportedOnce() {
        val queue = Queue(); var done: Result<String>? = null
        val failing = FakeRecognizer { throw IllegalStateException("no model") }
        val take = TakeTranscriber({ failing }, ChunkSegmenter(), queue, direct) {}
        take.feed(seg(1)); take.feed(seg(2)); take.finish { done = it }
        queue.runAll()
        assertTrue(done!!.isFailure)
    }

    @Test fun swissSpellingReplacesEszett() {
        val r = SwissSpelling(FakeRecognizer { "Ich weiß, die Straße ist groß. STRAẞE" })
        assertEquals("Ich weiss, die Strasse ist gross. STRASSE", r.transcribe(FloatArray(0)))
    }

    @Test fun shortSegmentsJoinTheNextOne() {
        val m = ShortSegmentMerger(minSamples = 4)
        assertNull(m.add(floatArrayOf(1f, 2f)))
        assertArrayEquals(floatArrayOf(1f, 2f, 3f, 4f, 5f), m.add(floatArrayOf(3f, 4f, 5f)), 0f)
        assertNull(m.rest())
        assertNull(m.add(floatArrayOf(6f)))
        assertArrayEquals(floatArrayOf(6f), m.rest(), 0f)
        assertNull(m.rest())
    }
}
