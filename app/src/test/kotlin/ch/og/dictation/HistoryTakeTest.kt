package ch.og.dictation

import java.nio.file.Files
import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test

class HistoryTakeTest {
    @Test fun timingReceiptsPairOnlyDurableCheckpointSampleFrontiers() {
        val dir = Files.createTempDirectory("history-timing").toFile()
        try {
            val events = ArrayList<TimelineEvent>()
            val direct = Executor { it.run() }
            val editor = object : TakeOutput {
                override fun preview(text: String) {}
                override fun commit(text: String) = true
                override fun returnToKeyboard() = true
                override fun finished(result: TakeResult, pcm: ByteArray) {}
            }
            val output = HistoryTakeOutput(editor, { HistoryStore(dir) }, direct, { fail("save failed") },
                timeline = TimelineObserver { events.add(it) })
            output.startHistory(true)
            output.preview("private synthetic words", 48_000L)
            val queued = events.single { it.phase == "checkpointQueued" }
            val durable = events.single { it.phase == "checkpointDurable" }
            assertEquals(48_000L, queued.sampleEnd)
            assertEquals(queued.sampleEnd, durable.sampleEnd)
            assertTrue(durable.ns >= queued.ns)
            assertEquals("private synthetic words", HistoryStore(dir).list().single().text)
        } finally { dir.deleteRecursively() }
    }

    @Test fun aLaterSaveFailureLeavesTheLastDurablePrefixAndReportsNoContent() {
        val dir = Files.createTempDirectory("history-late-failure").toFile()
        try {
            var opens = 0
            var failed = false
            val events = ArrayList<TimelineEvent>()
            val delegate = object : TakeOutput {
                override fun preview(text: String) {}
                override fun commit(text: String) = true
                override fun returnToKeyboard() = true
                override fun finished(result: TakeResult, pcm: ByteArray) {}
            }
            val output = HistoryTakeOutput(delegate, {
                if (++opens > 1) error("synthetic later write failure")
                HistoryStore(dir)
            }, Executor { it.run() }, { failed = true }, timeline = TimelineObserver { events.add(it) })
            output.startHistory(true)
            output.preview("private first prefix", 16_000)
            output.preview("private later words", 32_000)
            assertTrue(failed)
            assertEquals("private first prefix", HistoryStore(dir).list().single().text)
            assertEquals(listOf("checkpointQueued", "checkpointDurable", "checkpointQueued", "checkpointFailed"),
                events.map { it.phase })
            assertEquals(listOf(16_000L, 16_000L, 32_000L, 32_000L),
                events.filter { it.phase.startsWith("checkpoint") }.map { it.sampleEnd })
        } finally { dir.deleteRecursively() }
    }

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

    private class Manual : Executor {
        private val work = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { work.add(command) }
        fun drain() { while (work.isNotEmpty()) work.removeFirst().run() }
    }
    private val inert = object : TakeOutput {
        override fun preview(text: String) {}
        override fun commit(text: String) = true
        override fun returnToKeyboard() = true
        override fun finished(result: TakeResult, pcm: ByteArray) {}
    }

    @Test fun excludedWhileTheFinalStageWaitsAcknowledgesOnceUnsavedAndWritesNothing() {
        val dir = Files.createTempDirectory("history-exclude-stage").toFile()
        try {
            val io = Manual()
            val output = HistoryTakeOutput(inert, { HistoryStore(dir) }, io, { fail("save failed") })
            output.startHistory(true)
            val acks = ArrayList<Boolean>()
            output.stage("private words", true) { acks.add(it) }
            output.excludeHistory()
            io.drain()
            assertEquals(listOf(false), acks)
            assertTrue(HistoryStore(dir).list().isEmpty())
        } finally { dir.deleteRecursively() }
    }

    @Test fun excludedWhileTheInterruptedSaveWaitsBehindTheStageAcknowledgesBothUnsaved() {
        val dir = Files.createTempDirectory("history-exclude-both").toFile()
        try {
            val io = Manual()
            val output = HistoryTakeOutput(inert, { HistoryStore(dir) }, io, { fail("save failed") })
            output.startHistory(true)
            val staged = ArrayList<Boolean>()
            val interrupted = ArrayList<Boolean>()
            output.stage("private words", true) { staged.add(it) }
            output.saveInterrupted("private words", true) { interrupted.add(it) }
            output.excludeHistory()
            io.drain()
            assertEquals(listOf(false), staged)
            assertEquals(listOf(false), interrupted)
            assertTrue(HistoryStore(dir).list().isEmpty())
        } finally { dir.deleteRecursively() }
    }

    @Test fun discardingTheStagedTextAcknowledgesItsQueuedStage() {
        val dir = Files.createTempDirectory("history-discard-stage").toFile()
        try {
            val io = Manual()
            val output = HistoryTakeOutput(inert, { HistoryStore(dir) }, io, { fail("save failed") })
            output.startHistory(true)
            val acks = ArrayList<Boolean>()
            var deleted: Boolean? = null
            output.stage("private words", true) { acks.add(it) }
            output.discardStaged { deleted = it }
            io.drain()
            assertEquals(listOf(false), acks)
            assertEquals(true, deleted)
            assertTrue(HistoryStore(dir).list().isEmpty())
        } finally { dir.deleteRecursively() }
    }

    @Test fun theStageAcknowledgementCarriesTheRealSaveResult() {
        val dir = Files.createTempDirectory("history-stage-result").toFile()
        val root = Files.createTempFile("history-unwritable", ".file").toFile()
        try {
            val ok = HistoryTakeOutput(inert, { HistoryStore(dir) }, Executor { it.run() }, { fail("save failed") })
            ok.startHistory(true)
            var saved: Boolean? = null
            ok.stage("private words", true) { saved = it }
            assertEquals(true, saved)
            // The store throws.
            val broken = HistoryTakeOutput(inert, { HistoryStore(root) }, Executor { it.run() }, {})
            broken.startHistory(true)
            saved = null
            broken.stage("private words", true) { saved = it }
            assertEquals(false, saved)
            // History cleared mid-take: the checkpoint is suppressed.
            val cleared = HistoryTakeOutput(inert, { HistoryStore(dir) }, Executor { it.run() }, { fail("save failed") }, id = "00000000-0000-0000-0000-000000000099")
            cleared.startHistory(true)
            cleared.preview("first")
            HistoryStore(dir).delete("00000000-0000-0000-0000-000000000099")
            saved = null
            cleared.stage("first second", true) { saved = it }
            assertEquals(false, saved)
            // Ineligible and empty text.
            saved = null
            ok.stage("x", false) { saved = it }
            assertEquals(false, saved)
            saved = null
            ok.stage("", true) { saved = it }
            assertEquals(false, saved)
        } finally { dir.deleteRecursively(); root.delete() }
    }
}
