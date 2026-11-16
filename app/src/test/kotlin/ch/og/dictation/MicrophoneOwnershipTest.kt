package ch.og.dictation

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class MicrophoneOwnershipTest {
    @Test fun replacementRetriesOldSessionReleaseBeforeStartingAnotherMicrophone() {
        var releases = 0
        var starts = 0
        val original = MicrophoneCapture(recorder = Recorder { object : Recorder.AudioInput {
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
        val direct = Executor { it.run() }
        val runtime = DictationRuntime(Variant.HISTORICAL_INT8, direct, { Recognizer { "" } }, {
            object : Segmenter {
                override fun accept(samples: FloatArray) = emptyList<Chunk>()
                override fun flush() = emptyList<Chunk>()
                override fun reset() {}
            }
        })
        val finished = CountDownLatch(1)
        val failed = CountDownLatch(1)
        val listener: (MicrophoneOwner.State) -> Unit = { if (it.releaseFailed) failed.countDown() }
        MicrophoneOwner.observe(listener)
        val output = object : TakeOutput {
            override fun preview(text: String) {}
            override fun commit(text: String) = false
            override fun returnToKeyboard() = false
            override fun finished(result: TakeResult, pcm: ByteArray) { finished.countDown() }
        }
        val take = runtime.open(original, output, direct)
        take.start()
        original.setReleaseRetry { take.retryCaptureRelease() }
        take.interrupt()
        assertTrue(failed.await(2, TimeUnit.SECONDS))
        assertTrue(take.active)
        assertTrue(runCatching { replacement.start {} }.isFailure)
        assertEquals(1, starts)
        val deniedCleanup = CountDownLatch(1)
        replacement.cancelWithResult { deniedCleanup.countDown() }
        assertTrue(deniedCleanup.await(2, TimeUnit.SECONDS))
        assertTrue(MicrophoneOwner.state().occupied)
        assertThrows(IllegalStateException::class.java) { runtime.close() }
        MicrophoneOwner.retryRelease()
        assertTrue(finished.await(2, TimeUnit.SECONDS))
        assertFalse(take.active)
        assertFalse(MicrophoneOwner.state().occupied)
        assertEquals(1, starts) // release never starts a new microphone
        replacement.start { }
        assertEquals(2, starts)
        val cleanup = CountDownLatch(1)
        replacement.cancelWithResult { cleanup.countDown() }
        assertTrue(cleanup.await(2, TimeUnit.SECONDS))
        MicrophoneOwner.remove(listener)
        runtime.close()
    }
}
