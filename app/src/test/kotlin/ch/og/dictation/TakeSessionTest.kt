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
        var attempted = true
        var returns = 0
        override fun commitAttempted() = attempted
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

    @Test fun unconfirmedReleaseKeepsTakeActiveUntilRetryAcknowledges() {
        val runtime = DictationRuntime(Variant.HISTORICAL_INT8, direct, { Recognizer { "word" } }, segments)
        val capture = Capture()
        val output = Output()
        val session = runtime.open(capture, output, direct)
        session.start()
        var released = false
        session.interrupt { released = true }
        capture.drained(Result.failure(CaptureReleaseUnconfirmed()))
        assertTrue(session.active)
        assertFalse(released)
        assertTrue(output.results.isEmpty())
        session.retryCaptureRelease()
        capture.released!!()
        assertTrue(released)
        assertFalse(session.active)
        assertEquals(TakeOutcome.FAILED, output.results.single().outcome)
    }

    @Test fun failedReleaseRetryKeepsLeaseAndRejectsCompletionUntilConfirmed() {
        val runtime = DictationRuntime(Variant.HISTORICAL_INT8, direct, { Recognizer { "word" } }, segments)
        var drained: ((Result<ByteArray>) -> Unit)? = null
        var retry: ((Result<Unit>) -> Unit)? = null
        val capture = object : TakeCapture {
            override fun start(accept: (FloatArray) -> Unit) {}
            override fun stop(done: (Result<ByteArray>) -> Unit) { drained = done }
            override fun cancel(done: () -> Unit) {}
            override fun cancelWithResult(done: (Result<Unit>) -> Unit) { retry = done }
        }
        val output = Output()
        val take = runtime.open(capture, output, direct)
        take.start()
        take.stop()
        drained!!(Result.failure(CaptureReleaseUnconfirmed()))
        take.retryCaptureRelease()
        retry!!(Result.failure(CaptureReleaseUnconfirmed()))
        assertTrue(take.active)
        assertTrue(output.results.isEmpty())
        assertThrows(IllegalStateException::class.java) { runtime.close() }
        take.retryCaptureRelease()
        retry!!(Result.success(Unit))
        assertFalse(take.active)
        assertEquals(TakeOutcome.FAILED, output.results.single().outcome)
        runtime.close()
    }

    @Test fun startupReceiptsDistinguishCaptureRequestFromFirstAudio() {
        val runtime = DictationRuntime(Variant.HISTORICAL_INT8, direct, { Recognizer { "word" } }, segments)
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

    @Test fun excludedRetentionDecisionIsLatchedIntoCompletedTakeWithoutBlockingDictation() {
        val runtime = DictationRuntime(Variant.HISTORICAL_INT8, direct, { Recognizer { "private words" } }, segments)
        val capture = Capture()
        val output = Output()
        val session = runtime.open(capture, output, direct, retentionEligible = false)
        session.start()
        capture.feed(floatArrayOf(1f))
        session.stop()
        capture.drained(Result.success(byteArrayOf(1, 2)))

        assertEquals(listOf("private words"), output.inserted)
        assertFalse(output.results.single().retentionEligible)
        assertFalse(BenchmarkStore.shouldSave(enabled = true, output.results.single()))
        assertFalse(BenchmarkStore.shouldSave(enabled = false, output.results.single()))
    }

    @Test fun ordinaryOptInBenchmarkEligibilityIsPreserved() {
        val runtime = DictationRuntime(Variant.HISTORICAL_INT8, direct, { Recognizer { "ordinary words" } }, segments)
        val capture = Capture()
        val output = Output()
        val session = runtime.open(capture, output, direct, retentionEligible = true)
        session.start()
        capture.feed(floatArrayOf(1f))
        session.stop()
        capture.drained(Result.success(byteArrayOf(1, 2)))

        assertEquals(listOf("ordinary words"), output.inserted)
        assertTrue(BenchmarkStore.shouldSave(enabled = true, output.results.single()))
        assertFalse(BenchmarkStore.shouldSave(enabled = false, output.results.single()))
    }

    @Test fun stopWaitsForCaptureDrainAndInsertsOnce() {
        val queue = Queue()
        val runtime = DictationRuntime(Variant.HISTORICAL_INT8, queue, { Recognizer { "word" } }, segments)
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
        val runtime = DictationRuntime(Variant.HISTORICAL_INT8, queue, { Recognizer { "word" } }, segments) { releases++ }
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

    /** Authority gone before any write: nothing was inserted, so the result carries the text for the panel's Copy. */
    @Test fun commitSkippedBecauseTheInputChangedStillCarriesTheText() {
        val runtime = DictationRuntime(Variant.HISTORICAL_INT8, direct, { Recognizer { "word" } }, segments)
        val capture = Capture()
        val output = Output().also { it.accepts = false; it.attempted = false }
        val session = runtime.open(capture, output, direct)
        session.start()
        capture.feed(floatArrayOf(1f))
        session.stop()
        capture.drained(Result.success(byteArrayOf(1)))
        session.stop()
        val result = output.results.single()
        assertEquals(TakeOutcome.INPUT_CHANGED, result.outcome)
        assertEquals("word", result.text)
        assertNull("a write that was never attempted is not a rejection", result.commitAccepted)
        assertTrue(output.inserted.isEmpty())
    }

    @Test fun rejectedCommitDoesNotReturnToKeyboardOrRetryInsertion() {
        val runtime = DictationRuntime(Variant.HISTORICAL_INT8, direct, { Recognizer { "word" } }, segments)
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
        val runtime = DictationRuntime(Variant.HISTORICAL_INT8, queue, { Recognizer { "word" } }, segments)
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
        val runtime = DictationRuntime(Variant.HISTORICAL_INT8, direct, { Recognizer { "word" } }, segments)
        val output = Output()
        runtime.open(Capture(), output, direct).cancel()
        assertEquals(TakeOutcome.CANCELLED, output.results.single().outcome)
        runtime.close().join()
    }
    @Test fun interruptionFreezesAudioAndReleasesBeforeQueuedDecode() {
        val queue = Queue()
        val runtime = DictationRuntime(Variant.HISTORICAL_INT8, queue, { Recognizer { "word" } }, segments)
        val capture = Capture()
        val output = Output()
        val session = runtime.open(capture, output, direct)
        session.start()
        capture.feed(floatArrayOf(1f))
        var released = false
        session.interrupt { released = true }
        capture.feed(floatArrayOf(2f))
        assertFalse(released)
        capture.drained(Result.success(byteArrayOf(1)))
        assertTrue(released)
        assertTrue(output.results.isEmpty())
        queue.drain()
        assertTrue(output.inserted.isEmpty())
        assertEquals(0, output.returns)
        assertEquals("word", output.results.single().text)
        assertEquals(TakeOutcome.INPUT_CHANGED, output.results.single().outcome)
        assertTrue(output.results.single().interrupted)
    }

    @Test fun interruptionDuringStopDoesNotInsertAndCannotBeBenchmarkedOnFailure() {
        val queue = Queue()
        val runtime = DictationRuntime(Variant.HISTORICAL_INT8, queue, { Recognizer { throw IllegalStateException("decode") } }, segments)
        val capture = Capture()
        val output = Output()
        val session = runtime.open(capture, output, direct)
        session.start()
        capture.feed(floatArrayOf(1f))
        session.stop()
        var released = false
        session.interrupt { released = true }
        capture.feed(floatArrayOf(2f))
        capture.drained(Result.success(byteArrayOf(1)))
        assertTrue(released)
        queue.drain()
        val result = output.results.single()
        assertEquals(TakeOutcome.FAILED, result.outcome)
        assertFalse(BenchmarkStore.shouldSave(true, result))
        assertTrue(output.inserted.isEmpty())
        assertEquals(0, output.returns)
    }

    @Test fun interruptionStillFlushesAcceptedAudioWhenCaptureStopReportsFailure() {
        val queue = Queue()
        val runtime = DictationRuntime(Variant.HISTORICAL_INT8, queue, { Recognizer { "word" } }, segments)
        val capture = Capture()
        val output = Output()
        val session = runtime.open(capture, output, direct)
        session.start()
        capture.feed(floatArrayOf(1f))
        session.interrupt()
        capture.drained(Result.failure(IllegalStateException("stop")))
        queue.drain()
        assertEquals("word", output.results.single().text)
        assertEquals(TakeOutcome.FAILED, output.results.single().outcome)
        assertTrue(output.inserted.isEmpty())
    }

    @Test fun newTakeCanCaptureAfterOldReleaseWhileOldDecodeKeepsLease() {
        val queue = Queue()
        val runtime = DictationRuntime(Variant.HISTORICAL_INT8, queue, { Recognizer { "word" } }, segments)
        val oldCapture = Capture()
        val oldOutput = Output()
        val old = runtime.open(oldCapture, oldOutput, direct)
        old.start()
        oldCapture.feed(floatArrayOf(1f))
        old.interrupt()
        oldCapture.drained(Result.success(byteArrayOf()))
        val newCapture = Capture()
        val newOutput = Output()
        val current = runtime.open(newCapture, newOutput, direct)
        current.start()
        newCapture.feed(floatArrayOf(2f))
        current.stop()
        newCapture.drained(Result.success(byteArrayOf()))
        assertThrows(IllegalStateException::class.java) { runtime.close() }
        queue.drain()
        assertTrue(oldOutput.inserted.isEmpty())
        assertEquals(0, oldOutput.returns)
        assertEquals(listOf("word"), newOutput.inserted)
        runtime.close()
        queue.drain()
    }

    @Test fun interruptionWhileFinalSaveWaitsNeverDispatchesToEditor() {
        val runtime = DictationRuntime(Variant.HISTORICAL_INT8, direct, { Recognizer { "word" } }, segments)
        val capture = Capture()
        lateinit var stageReady: (Boolean) -> Unit
        var commits = 0
        var result: TakeResult? = null
        val output = object : TakeOutput {
            override fun preview(text: String) {}
            override fun stage(text: String, retentionEligible: Boolean, ready: (Boolean) -> Unit) { stageReady = ready }
            override fun saveInterrupted(text: String, retentionEligible: Boolean, done: (Boolean) -> Unit) { done(true) }
            override fun commit(text: String): Boolean { commits++; return true }
            override fun returnToKeyboard(): Boolean { fail("must not return"); return false }
            override fun finished(value: TakeResult, pcm: ByteArray) { result = value }
        }
        val take = runtime.open(capture, output, direct)
        take.start()
        capture.feed(floatArrayOf(1f))
        take.stop()
        capture.drained(Result.success(byteArrayOf()))
        var released = false
        take.interrupt { released = true }
        assertTrue(released)
        stageReady(false)
        assertEquals(0, commits)
        assertTrue(result!!.historySaved)
    }

    @Test fun finishingChoiceHoldsDeliveryUntilKept() {
        val runtime = DictationRuntime(Variant.HISTORICAL_INT8, direct, { Recognizer { "word" } }, segments)
        val capture = Capture()
        val output = Output()
        val take = runtime.open(capture, output, direct)
        take.start()
        capture.feed(floatArrayOf(1f))
        take.stop()
        assertTrue(take.holdDelivery())
        capture.drained(Result.success(byteArrayOf()))
        assertTrue(output.inserted.isEmpty())
        take.resumeDelivery()
        assertEquals(listOf("word"), output.inserted)
        take.resumeDelivery()
        assertEquals(1, output.returns)
    }

    @Test fun finishingChoiceOpenedReentrantlyBeforeDispatchHoldsUntilKept() {
        val runtime = DictationRuntime(Variant.HISTORICAL_INT8, direct, { Recognizer { "word" } }, segments)
        val capture = Capture()
        lateinit var take: TakeSession
        var attempted = false
        var commits = 0
        var results = 0
        val output = object : TakeOutput {
            override fun preview(text: String) {}
            override fun commit(text: String): Boolean = error("guarded commit required")
            override fun commitAttempted() = attempted
            override fun commit(text: String, allowed: () -> Boolean): Boolean {
                if (commits == 0 && !attempted) assertTrue(take.holdDelivery())
                if (!allowed()) return false
                attempted = true
                commits++
                return true
            }
            override fun returnToKeyboard() = true
            override fun finished(result: TakeResult, pcm: ByteArray) { results++ }
        }
        take = runtime.open(capture, output, direct)
        take.start(); capture.feed(floatArrayOf(1f)); take.stop()
        capture.drained(Result.success(byteArrayOf()))
        assertEquals(0, commits)
        // The second dispatch must be allowed to proceed.
        attempted = true
        take.resumeDelivery()
        assertEquals(1, commits)
        assertEquals(1, results)
    }

    @Test fun finishingChoiceDuringDelayedSaveCanDiscardWithoutLateDelivery() {
        val runtime = DictationRuntime(Variant.HISTORICAL_INT8, direct, { Recognizer { "word" } }, segments)
        val capture = Capture()
        lateinit var ready: (Boolean) -> Unit
        var commits = 0
        var deletions = 0
        val output = object : TakeOutput {
            override fun preview(text: String) {}
            override fun stage(text: String, retentionEligible: Boolean, callback: (Boolean) -> Unit) { ready = callback }
            override fun discardStaged(done: (Boolean) -> Unit) { deletions++; done(true) }
            override fun commit(text: String): Boolean { commits++; return true }
            override fun returnToKeyboard() = true
            override fun finished(result: TakeResult, pcm: ByteArray) {}
        }
        val take = runtime.open(capture, output, direct)
        take.start(); capture.feed(floatArrayOf(1f)); take.stop()
        capture.drained(Result.success(byteArrayOf()))
        assertTrue(take.holdDelivery())
        var deleted = false
        take.cancel(onDiscarded = { deleted = it })
        ready(false)
        assertTrue(deleted)
        assertEquals(1, deletions)
        assertEquals(0, commits)
    }

    @Test fun finishingChoiceDuringDelayedSaveKeepsAndDeliversOnce() {
        val runtime = DictationRuntime(Variant.HISTORICAL_INT8, direct, { Recognizer { "word" } }, segments)
        val capture = Capture()
        lateinit var ready: (Boolean) -> Unit
        var commits = 0
        val output = object : TakeOutput {
            override fun preview(text: String) {}
            override fun stage(text: String, retentionEligible: Boolean, callback: (Boolean) -> Unit) { ready = callback }
            override fun commit(text: String): Boolean { commits++; return true }
            override fun returnToKeyboard() = true
            override fun finished(result: TakeResult, pcm: ByteArray) {}
        }
        val take = runtime.open(capture, output, direct)
        take.start(); capture.feed(floatArrayOf(1f)); take.stop()
        capture.drained(Result.success(byteArrayOf()))
        assertTrue(take.holdDelivery())
        ready(false)
        assertEquals(0, commits)
        take.resumeDelivery()
        take.resumeDelivery()
        assertEquals(1, commits)
    }

    @Test fun failedDiscardRemainsSuppressedAndCanBeRetriedInHistory() {
        val root = java.nio.file.Files.createTempDirectory("history-discard").toFile()
        try {
            val id = java.util.UUID.randomUUID().toString()
            val store = HistoryStore(root)
            var failDelete = false
            var failures = 0
            val output = HistoryTakeOutput(Output(), { if (failDelete) error("delete failure") else store }, direct,
                onSaveFailure = {}, id = id, onDeleteFailure = { failures++ })
            output.startHistory(true)
            output.preview("word")
            failDelete = true
            var deleted = true
            output.discardStaged { deleted = it }
            assertFalse(deleted)
            assertEquals(1, failures)
            failDelete = false
            output.preview("later")
            assertEquals("word", store.get(id)!!.text)
            store.delete(id)
            assertNull(store.get(id))
        } finally { root.deleteRecursively() }
    }


    @Test fun excludedWhileTheFinalSaveWaitsStillFinishesWithoutHistory() {
        val dir = java.nio.file.Files.createTempDirectory("take-exclude").toFile()
        try {
            val io = Queue()
            val runtime = DictationRuntime(Variant.HISTORICAL_INT8, direct, { Recognizer { "word" } }, segments)
            val capture = Capture()
            val output = Output()
            val take = runtime.open(capture, HistoryTakeOutput(output, { HistoryStore(dir) }, io, { fail("save failed") }), direct)
            take.start(); capture.feed(floatArrayOf(1f)); take.stop()
            capture.drained(Result.success(byteArrayOf()))
            assertTrue(output.results.isEmpty())
            take.excludeFromHistory()
            io.drain()
            val result = output.results.single()
            assertEquals(TakeOutcome.COMPLETED, result.outcome)
            assertFalse(result.historySaved)
            assertTrue(HistoryStore(dir).list().isEmpty())
        } finally { dir.deleteRecursively() }
    }

    @Test fun aFailedHistorySaveIsNotReportedAsSaved() {
        val root = java.nio.file.Files.createTempFile("take-unwritable", ".file").toFile()
        try {
            val runtime = DictationRuntime(Variant.HISTORICAL_INT8, direct, { Recognizer { "word" } }, segments)
            val capture = Capture()
            val output = Output()
            val take = runtime.open(capture, HistoryTakeOutput(output, { HistoryStore(root) }, direct, {}), direct)
            take.start(); capture.feed(floatArrayOf(1f)); take.stop()
            capture.drained(Result.success(byteArrayOf()))
            assertFalse(output.results.single().historySaved)
        } finally { root.delete() }
    }

    @Test fun cancelWhileTheStageIsQueuedDiscardsEvenWithSynchronousDelivery() {
        val dir = java.nio.file.Files.createTempDirectory("take-cancel").toFile()
        try {
            val io = Queue()
            val runtime = DictationRuntime(Variant.HISTORICAL_INT8, direct, { Recognizer { "word" } }, segments)
            val capture = Capture()
            val output = Output()
            val take = runtime.open(capture, HistoryTakeOutput(output, { HistoryStore(dir) }, io, { fail("save failed") }), direct)
            take.start(); capture.feed(floatArrayOf(1f)); take.stop()
            capture.drained(Result.success(byteArrayOf()))
            take.cancel()
            io.drain()
            assertTrue(output.inserted.isEmpty())
            assertEquals(TakeOutcome.CANCELLED, output.results.single().outcome)
            assertNull(output.results.single().text)
            assertTrue(HistoryStore(dir).list().isEmpty())
        } finally { dir.deleteRecursively() }
    }
}
