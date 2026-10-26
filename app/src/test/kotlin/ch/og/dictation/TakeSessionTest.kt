package ch.og.dictation

import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test

class TakeSessionTest {
    private class Queue : Executor {
        private val work = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { work.add(command) }
        fun drain() { while (work.isNotEmpty()) work.removeFirst().run() }
    }

    private class Capture : TakeCapture {
        lateinit var feed: (FloatArray) -> Unit
        lateinit var drained: (Result<ByteArray>) -> Unit
        var cancelled = false
        var released: (() -> Unit)? = null
        override fun start(accept: (FloatArray) -> Unit) { feed = accept }
        override fun stop(done: (Result<ByteArray>) -> Unit) { drained = done }
        override fun cancel(done: () -> Unit) { cancelled = true; released = done }
    }

    private class Output : TakeOutput {
        val inserted = ArrayList<String>()
        val results = ArrayList<TakeResult>()
        var accepts = true
        var returns = 0
        override fun preview(text: String) {}
        override fun commit(text: String): Boolean {
            if (accepts) inserted.add(text)
            return accepts
        }
        override fun returnToKeyboard(): Boolean { returns++; return true }
        override fun finished(result: TakeResult, pcm: ByteArray) { results.add(result) }
    }

    private val direct = Executor { it.run() }
    private val segments = {
        object : Segmenter {
            override fun accept(samples: FloatArray) = listOf(Chunk(samples, true))
            override fun flush() = emptyList<Chunk>()
            override fun reset() {}
        }
    }

    @Test fun startupReceiptsDistinguishCaptureRequestFromFirstAudio() {
        val runtime = DictationRuntime(Variant.DEFAULT, direct, { FakeRecognizer { "word" } }, segments)
        val capture = Capture()
        val output = Output()
        var now = 10L
        val session = runtime.open(capture, output, direct, timeline = TimelineObserver {}, clock = { now })
        session.start()
        now = 30L
        capture.feed(floatArrayOf(1f))
        now = 50L
        capture.feed(floatArrayOf(1f))
        session.stop()
        capture.drained(Result.success(byteArrayOf()))
        val events = output.results.single().eventsNs
        assertEquals(10L, events["captureStart"])
        assertEquals(30L, events["firstAudio"])
    }

    @Test fun stopWaitsForCaptureDrainAndInsertsOnce() {
        val queue = Queue()
        val runtime = DictationRuntime(Variant.DEFAULT, queue, { FakeRecognizer { "word" } }, segments)
        val capture = Capture()
        val output = Output()
        val session = runtime.open(capture, output, direct)
        session.start()
        capture.feed(floatArrayOf(1f))
        session.stop()
        session.stop()
        queue.drain()
        assertTrue(output.inserted.isEmpty())
        capture.feed(floatArrayOf(2f)) // Audio already in flight when Stop was pressed.
        val pcm = byteArrayOf(1, 2)
        capture.drained(Result.success(pcm))
        queue.drain()
        assertEquals(listOf("word word"), output.inserted)
        assertEquals(1, output.returns)
        assertEquals(TakeOutcome.COMPLETED, output.results.single().outcome)
        assertTrue(pcm.all { it == 0.toByte() })
        assertFalse(session.active)
    }

    @Test fun invalidationDuringDrainDiscardsLateAudioAndAllowsAFollowingTake() {
        val queue = Queue()
        var releases = 0
        val runtime = DictationRuntime(Variant.DEFAULT, queue, { FakeRecognizer { "word" } }, segments) { releases++ }
        val oldCapture = Capture()
        val oldOutput = Output()
        val old = runtime.open(oldCapture, oldOutput, direct)
        old.start()
        oldCapture.feed(floatArrayOf(1f))
        old.stop()
        old.cancel(inputChanged = true)
        assertTrue(old.active) // A following microphone take must wait for the capture acknowledgement.
        assertTrue(oldOutput.results.isEmpty())
        assertThrows(IllegalStateException::class.java) { runtime.close() }
        oldCapture.released!!()
        assertFalse(old.active)
        val latePcm = byteArrayOf(8)
        oldCapture.drained(Result.success(latePcm))
        val capture = Capture()
        val output = Output()
        val current = runtime.open(capture, output, direct)
        current.start()
        capture.feed(floatArrayOf(2f))
        current.stop()
        capture.drained(Result.success(byteArrayOf(2)))
        queue.drain()
        assertTrue(oldOutput.inserted.isEmpty())
        assertEquals(TakeOutcome.INPUT_CHANGED, oldOutput.results.single().outcome)
        assertEquals(listOf("word"), output.inserted)
        assertEquals(0.toByte(), latePcm[0])
        runtime.close()
        queue.drain()
        assertEquals(1, releases)
        assertThrows(IllegalStateException::class.java) { runtime.open(Capture(), Output(), direct) }
    }

    @Test fun rejectedCommitDoesNotReturnToKeyboardOrRetryInsertion() {
        val runtime = DictationRuntime(Variant.DEFAULT, direct, { FakeRecognizer { "word" } }, segments)
        val capture = Capture()
        val output = Output().also { it.accepts = false }
        val session = runtime.open(capture, output, direct)
        session.start()
        capture.feed(floatArrayOf(1f))
        session.stop()
        capture.drained(Result.success(byteArrayOf(1)))
        session.stop()
        assertEquals(TakeOutcome.COMMIT_REJECTED, output.results.single().outcome)
        assertEquals(0, output.returns)
        assertTrue(output.inserted.isEmpty())
    }

    @Test fun cancelledFinalWaitingForDeliveryCannotCommit() {
        val queue = Queue()
        val delivery = Queue()
        val runtime = DictationRuntime(Variant.DEFAULT, queue, { FakeRecognizer { "word" } }, segments)
        val capture = Capture()
        val output = Output()
        val session = runtime.open(capture, output, delivery)
        session.start()
        capture.feed(floatArrayOf(1f))
        session.stop()
        capture.drained(Result.success(byteArrayOf(1)))
        delivery.drain()
        queue.drain()
        session.cancel()
        delivery.drain()
        assertTrue(output.inserted.isEmpty())
        assertEquals(1, output.results.size)
    }

    @Test fun abandonedUnstartedSessionReleasesItsRuntimeLease() {
        val runtime = DictationRuntime(Variant.DEFAULT, direct, { FakeRecognizer { "word" } }, segments)
        val output = Output()
        runtime.open(Capture(), output, direct).cancel()
        assertEquals(TakeOutcome.CANCELLED, output.results.single().outcome)
        runtime.close().join()
    }
}
