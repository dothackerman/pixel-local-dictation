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
        override fun accept(samples: FloatArray) = listOf(Chunk(samples, speech = true))
        override fun flush() = listOfNotNull(pending?.let { Chunk(it, speech = true) })
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

    @Test fun silentChunksAreSkippedOnlyWithTheGate() {
        class Mixed : Segmenter {
            override fun accept(samples: FloatArray) = listOf(Chunk(seg(1), speech = true), Chunk(seg(2), speech = false))
            override fun flush() = listOf(Chunk(seg(4), speech = false))
            override fun reset() {}
        }
        var gated: Result<String>? = null; var open: Result<String>? = null
        TakeTranscriber({ bySample }, Mixed(), direct, direct, Variant.Post(skipSilentChunks = true)) {}.also { it.feed(seg(0)); it.finish { gated = it } }
        TakeTranscriber({ bySample }, Mixed(), direct, direct, Variant.Post(skipSilentChunks = false)) {}.also { it.feed(seg(0)); it.finish { open = it } }
        assertEquals("eins", gated!!.getOrThrow())
        assertEquals("eins zwei vier", open!!.getOrThrow())
    }

    @Test fun swissSpellingReplacesEszett() {
        val r = SwissSpelling(FakeRecognizer { "Ich weiß, die Straße ist groß. STRAẞE" })
        assertEquals("Ich weiss, die Strasse ist gross. STRASSE", r.transcribe(FloatArray(0)))
    }

    @Test fun chunksAreContiguousAndCutOnlyAtPausesOnceLongEnough() {
        val c = PauseChunker(minSamples = 10, padSamples = 2)
        c.append(FloatArray(8) { it.toFloat() })                 // positions 0..7
        assertNull("too short: pause after 6 samples", c.onSpeechEnd(6))
        c.append(FloatArray(8) { 8f + it })                      // positions 8..15
        assertArrayEquals(FloatArray(14) { it.toFloat() }, c.onSpeechEnd(12)!!.samples, 0f)   // 12 + pad
        c.append(FloatArray(4) { 16f + it })                     // positions 16..19
        assertNull(c.onSpeechEnd(18))
        assertArrayEquals(floatArrayOf(14f, 15f, 16f, 17f, 18f, 19f), c.rest()!!.samples, 0f)
        assertNull(c.rest())
    }

    @Test fun padNeverReachesBeyondRecordedAudio() {
        val c = PauseChunker(minSamples = 4, padSamples = 100)
        c.append(FloatArray(6) { it.toFloat() })
        assertArrayEquals(FloatArray(6) { it.toFloat() }, c.onSpeechEnd(5)!!.samples, 0f)
        assertNull(c.rest())
    }
}
