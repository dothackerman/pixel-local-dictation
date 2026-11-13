package ch.og.dictation

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class RecorderTest {
    @Test fun replacementCaptureCannotAcquireUntilOldReleaseIsConfirmed() {
        var releases = 0
        var starts = 0
        val first = MicrophoneCapture(recorder = Recorder { object : Recorder.AudioInput {
            override val bufferSize = 8
            override fun start() { starts++ }
            override fun read(buffer: ShortArray) = -1
            override fun stop() {}
            override fun release() { if (++releases == 1) error("release") }
        } })
        val replacement = MicrophoneCapture(recorder = Recorder { object : Recorder.AudioInput {
            override val bufferSize = 8
            override fun start() { starts++ }
            override fun read(buffer: ShortArray) = -1
            override fun stop() {}
            override fun release() {}
        } })
        first.start { }
        val failed = CountDownLatch(1)
        first.stop { failed.countDown() }
        assertTrue(failed.await(2, TimeUnit.SECONDS))
        val denied = runCatching { replacement.start {} }
        assertTrue("replacement must not acquire the microphone", denied.isFailure)
        assertEquals(1, starts)
        val released = CountDownLatch(1)
        first.cancelWithResult { released.countDown() }
        assertTrue(released.await(2, TimeUnit.SECONDS))
        replacement.start { }
        assertEquals(2, starts)
        val finished = CountDownLatch(1)
        replacement.cancelWithResult { finished.countDown() }
        assertTrue(finished.await(2, TimeUnit.SECONDS))
    }
    @Test fun captureReportsUnconfirmedReleaseAndAcknowledgesRetry() {
        var releases = 0
        val device = object : Recorder.AudioInput {
            override val bufferSize = 8
            override fun start() {}
            override fun read(buffer: ShortArray) = -1
            override fun stop() {}
            override fun release() { if (++releases == 1) error("release") }
        }
        val capture = MicrophoneCapture(recorder = Recorder { device })
        capture.start { }
        val first = CountDownLatch(1)
        var failure: Throwable? = null
        capture.stop { failure = it.exceptionOrNull(); first.countDown() }
        assertTrue(first.await(2, TimeUnit.SECONDS))
        assertTrue(failure is CaptureReleaseUnconfirmed)
        val retry = CountDownLatch(1)
        var released = false
        capture.cancelWithResult { released = it.isSuccess; retry.countDown() }
        assertTrue(retry.await(2, TimeUnit.SECONDS))
        assertTrue(released)
        assertEquals(2, releases)
    }
    @Test fun failedStartKeepsDeviceWhenReleaseFailsAndRetriesRelease() {
        var releases = 0
        val device = object : Recorder.AudioInput {
            override val bufferSize = 8
            override fun start() { error("start") }
            override fun read(buffer: ShortArray) = -1
            override fun stop() {}
            override fun release() { if (++releases == 1) error("release") }
        }
        val recorder = Recorder { device }
        assertTrue(runCatching { recorder.start() }.isFailure)
        assertTrue(recorder.isRunning)
        assertTrue(runCatching { recorder.start() }.isFailure)
        recorder.stop()
        assertEquals(2, releases)
        assertFalse(recorder.isRunning)
    }

    @Test fun stopErrorCanStillConfirmRelease() {
        var released = false
        val recorder = Recorder { object : Recorder.AudioInput {
            override val bufferSize = 8
            override fun start() {}
            override fun read(buffer: ShortArray) = -1
            override fun stop() { error("stop") }
            override fun release() { released = true }
        } }
        recorder.start()
        assertTrue(runCatching { recorder.stop() }.isFailure)
        assertTrue(released)
        assertFalse(recorder.isRunning)
    }
    @Test fun stopUnblocksReadAndAcknowledgesAfterCallbacksDrain() {
        val reading = CountDownLatch(1)
        val unblocked = CountDownLatch(1)
        val device = object : Recorder.AudioInput {
            override val bufferSize = 8
            override fun start() {}
            override fun read(buffer: ShortArray): Int {
                reading.countDown()
                unblocked.await()
                return -1
            }
            override fun stop() { unblocked.countDown() }
            override fun release() {}
        }
        val recorder = Recorder { device }
        recorder.start()
        assertTrue(reading.await(2, TimeUnit.SECONDS))
        val stopped = CountDownLatch(1)
        Thread { recorder.stop(); stopped.countDown() }.start()
        assertTrue("stop must unblock a pending read", stopped.await(2, TimeUnit.SECONDS))
        assertFalse(recorder.isRunning)
    }
    @Test fun stopWaitsForAnInFlightCallbackBeforeReturning() {
        val callbackEntered = CountDownLatch(1)
        val finishCallback = CountDownLatch(1)
        val stopReturned = CountDownLatch(1)
        val device = object : Recorder.AudioInput {
            override val bufferSize = 8
            override fun start() {}
            override fun read(buffer: ShortArray): Int { buffer[0] = 1; return 1 }
            override fun stop() {}
            override fun release() {}
        }
        val recorder = Recorder { device }
        recorder.start({ callbackEntered.countDown(); finishCallback.await() })
        assertTrue(callbackEntered.await(2, TimeUnit.SECONDS))
        Thread { recorder.stop(); stopReturned.countDown() }.start()
        assertFalse(stopReturned.await(100, TimeUnit.MILLISECONDS))
        finishCallback.countDown()
        assertTrue(stopReturned.await(2, TimeUnit.SECONDS))
    }

    /** Blocks in read until stop(); after [failFromStart] starts every read throws; unblocked reads return 0. */
    private class FakeInput(private val failFromStart: Int = Int.MAX_VALUE, private val failAfterFirstChunk: Boolean = false) : Recorder.AudioInput {
        override val bufferSize = 8
        @Volatile var starts = 0
        private var gate = CountDownLatch(1)
        private var chunks = 0
        override fun start() { starts++; gate = CountDownLatch(1) }
        override fun read(buffer: ShortArray): Int {
            if (starts >= failFromStart) error("lost")
            if (failAfterFirstChunk) { if (chunks++ == 0) { buffer[0] = 1; return 1 }; error("lost") }
            gate.await()
            return -1
        }
        override fun stop() { gate.countDown() }
        override fun release() {}
    }

    @Test fun lostMicrophoneIsReportedWithoutFeedback() {
        val interrupted = CountDownLatch(1)
        val capture = MicrophoneCapture(onInterrupted = { interrupted.countDown() },
            recorder = Recorder { FakeInput(failAfterFirstChunk = true) })
        capture.start { }
        assertTrue(interrupted.await(2, TimeUnit.SECONDS))
        val done = CountDownLatch(1)
        capture.cancelWithResult { done.countDown() }
        assertTrue(done.await(2, TimeUnit.SECONDS))
    }

    @Test fun readFailureRightAfterResumeInterruptsAndEndsFeedback() {
        repeat(100) {
            val feedback = RecordingFeedback()
            val interrupted = CountDownLatch(1)
            val capture = MicrophoneCapture(feedback = feedback, onInterrupted = { interrupted.countDown() },
                recorder = Recorder { FakeInput(failFromStart = 2) })
            capture.start { }
            capture.pause()
            capture.resume()
            assertTrue("iteration $it", interrupted.await(2, TimeUnit.SECONDS))
            assertFalse(feedback.isCapturing)
            val done = CountDownLatch(1)
            capture.cancelWithResult { done.countDown() }
            assertTrue(done.await(2, TimeUnit.SECONDS))
        }
    }

    @Test fun requestedStopAndPauseDoNotReportAnEnd() {
        var ended = 0
        val recorder = Recorder { FakeInput() }
        recorder.start(onEnded = { ended++ })
        recorder.pause()
        recorder.resume()
        recorder.stop()
        assertEquals(0, ended)

        var interrupted = 0
        val capture = MicrophoneCapture(onInterrupted = { interrupted++ }, recorder = Recorder { FakeInput() })
        capture.start { }
        capture.pause()
        capture.resume()
        val done = CountDownLatch(1)
        capture.stop { done.countDown() }
        assertTrue(done.await(2, TimeUnit.SECONDS))
        assertEquals(0, interrupted)
    }
}
