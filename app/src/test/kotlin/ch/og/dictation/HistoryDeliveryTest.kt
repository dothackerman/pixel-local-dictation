package ch.og.dictation

import java.nio.file.Files
import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test

class HistoryDeliveryTest {
    private class Queue : Executor {
        private val tasks = ArrayDeque<Runnable>()
        override fun execute(task: Runnable) { tasks.addLast(task) }
        fun one() { tasks.removeFirst().run() }
        fun drain() { while (tasks.isNotEmpty()) one() }
    }
    private class Harness(val eligible: Boolean = true, val broken: Boolean = false) {
        val root = if (broken) Files.createTempFile("history-broken", ".file").toFile() else Files.createTempDirectory("history-delivery").toFile()
        val io = Queue()
        val delivery = Queue()
        val direct = Executor { it.run() }
        var feed: ((FloatArray) -> Unit)? = null
        var inserts = 0
        var returns = 0
        var warnings = 0
        var speech = "test words"
        var recognition: (FloatArray) -> String = { speech }
        var result: TakeResult? = null
        var decodeWarnings = 0
        var commit: () -> Boolean = { true }
        var guardedCommit: ((String, () -> Boolean) -> Boolean)? = null
        var attempted = true
        var keyboard: () -> Boolean = { true }
        val capture = object : TakeCapture {
            override fun start(accept: (FloatArray) -> Unit) { feed = accept }
            override fun stop(done: (Result<ByteArray>) -> Unit) = done(Result.success(byteArrayOf()))
            override fun cancel(done: () -> Unit) = done()
        }
        val editor = object : TakeOutput {
            override fun preview(text: String) {}
            override fun commit(text: String): Boolean { inserts++; return commit() }
            override fun commit(text: String, allowed: () -> Boolean): Boolean = guardedCommit?.invoke(text, allowed) ?: super.commit(text, allowed)
            override fun commitAttempted() = attempted
            override fun returnToKeyboard(): Boolean { returns++; return keyboard() }
            override fun finished(result: TakeResult, pcm: ByteArray) { this@Harness.result = result }
        }
        val segments = { object : Segmenter {
            override fun accept(samples: FloatArray) = listOf(Chunk(samples, true))
            override fun flush() = emptyList<Chunk>()
            override fun reset() {}
        } }
        val runtime = DictationRuntime(Variant.HISTORICAL_INT8, direct, { Recognizer { recognition(it) } }, segments)
        val take = runtime.open(capture, HistoryTakeOutput(editor, { HistoryStore(root) }, io, { warnings++ }, delivery = delivery,
            onTakeFailure = { if (it) decodeWarnings++ }), direct, retentionEligible = eligible)
        fun stop() { take.start(); feed!!(floatArrayOf(1f)); take.stop(); take.stop() }
        fun entries() = HistoryStore(root).list()
        fun close() { io.drain(); delivery.drain(); runtime.close().join(); root.deleteRecursively() }
    }

    @Test fun cumulativeSnapshotsCoalesceAndColdOwnerSeesInterruptedPrefix() {
        val h = Harness()
        try {
            val before = HistoryProcess.owner.activeIds()
            h.take.start()
            assertEquals(before.size + 1, HistoryProcess.owner.activeIds().size)
            assertTrue(h.entries().isEmpty())
            h.speech = "one"; h.feed!!(floatArrayOf(1f))
            h.speech = "two"; h.feed!!(floatArrayOf(1f))
            h.speech = "three"; h.feed!!(floatArrayOf(1f))
            h.io.one()
            val live = h.entries().single()
            assertEquals("one two three", live.text)
            assertEquals(HistoryCompletion.RECORDING, live.completion)
            val cold = HistoryStore(h.root, HistoryOwner()).list().single()
            assertEquals("one two three", cold.text)
            assertEquals(HistoryCompletion.INTERRUPTED, cold.completion)
            assertEquals(HistoryDelivery.UNKNOWN, cold.delivery)
            h.take.cancel()
        } finally { h.close() }
    }
    @Test fun laterDecodeFailureKeepsDurablePrefixAndColdHistoryExplainsIncompleteRecovery() {
        val h = Harness()
        try {
            h.recognition = { samples -> if (samples[0] == 1f) "saved prefix" else throw IllegalStateException("synthetic decode failure") }
            h.take.start()
            h.feed!!(floatArrayOf(1f)); h.io.one()
            h.feed!!(floatArrayOf(2f)); h.take.stop(); h.io.drain(); h.delivery.drain()

            assertEquals(TakeOutcome.FAILED, h.result!!.outcome)
            assertEquals(0, h.inserts)
            assertEquals(1, h.decodeWarnings)
            val recovered = HistoryStore(h.root, HistoryOwner()).list().single()
            assertEquals("saved prefix", recovered.text)
            assertEquals(HistoryCompletion.INTERRUPTED, recovered.completion)
            assertEquals(HistoryDelivery.UNKNOWN, recovered.delivery)
        } finally { h.close() }
    }

    @Test fun decodeFailureBeforeFirstCheckpointDoesNotInventRecoverableHistory() {
        val h = Harness()
        try {
            h.recognition = { throw IllegalStateException("synthetic decode failure") }
            h.take.start(); h.feed!!(floatArrayOf(1f)); h.take.stop(); h.io.drain(); h.delivery.drain()

            assertEquals(TakeOutcome.FAILED, h.result!!.outcome)
            assertEquals(0, h.decodeWarnings)
            assertTrue(h.entries().isEmpty())
            assertEquals(0, h.inserts)
        } finally { h.close() }
    }

    @Test fun laterCheckpointWriteFailurePreservesPrefixAndNeverClaimsItsDelivery() {
        val h = Harness()
        var obstruction: java.io.File? = null
        try {
            h.take.start()
            h.speech = "durable prefix"
            h.feed!!(floatArrayOf(1f)); h.io.one()
            val id = h.entries().single().id
            h.speech = "later words"
            h.feed!!(floatArrayOf(2f))
            obstruction = h.root.resolve("$id.tmp")
            assertTrue(obstruction!!.mkdir())
            h.io.one()
            assertEquals("durable prefix", h.entries().single().text)
            assertTrue(obstruction!!.mkdir())
            h.take.stop(); h.io.drain(); h.delivery.drain(); h.io.drain()

            assertTrue("a later write failure is surfaced", h.warnings > 0)
            assertEquals(1, h.inserts)
            assertEquals(TakeOutcome.COMPLETED, h.result!!.outcome)
            assertEquals(true, h.result!!.commitAccepted)
            val recovered = HistoryStore(h.root, HistoryOwner()).list().single()
            assertEquals("durable prefix", recovered.text)
            assertEquals(HistoryCompletion.INTERRUPTED, recovered.completion)
            assertEquals(HistoryDelivery.UNKNOWN, recovered.delivery)
        } finally {
            obstruction?.deleteRecursively()
            if (h.take.active) h.take.cancel()
            h.close()
        }
    }
    @Test fun deleteSuppressesPendingAndLaterCheckpointsWithoutCancellingStop() {
        val h = Harness()
        try {
            h.take.start(); h.feed!!(floatArrayOf(1f)); h.io.one()
            val id = h.entries().single().id
            h.speech = "later"; h.feed!!(floatArrayOf(1f))
            HistoryStore(h.root).delete(id)
            h.io.drain()
            assertTrue(h.entries().isEmpty())
            h.take.stop(); h.io.drain(); h.delivery.drain(); h.io.drain()
            assertEquals(1, h.inserts)
            assertTrue(HistoryStore(h.root, HistoryOwner()).list().isEmpty())
        } finally { h.close() }
    }
    @Test fun reopenSeesCommittedRevisionBeforeAndAfterSaveAcknowledgement() {
        val h = Harness()
        try {
            h.take.start(); h.speech = "first"; h.feed!!(floatArrayOf(1f)); h.io.one()
            h.speech = "second"; h.feed!!(floatArrayOf(1f)); h.take.stop()
            assertEquals("first", HistoryStore(h.root, HistoryOwner()).list().single().text)
            h.io.one()
            val afterCommitBeforeCallback = HistoryStore(h.root, HistoryOwner()).list().single()
            assertEquals("first second", afterCommitBeforeCallback.text)
            assertEquals(HistoryDelivery.UNKNOWN, afterCommitBeforeCallback.delivery)
            assertEquals(0, h.inserts)
            h.delivery.one()
            assertEquals("first second", HistoryStore(h.root, HistoryOwner()).list().single().text)
            h.io.drain()
            assertEquals(HistoryDelivery.ACCEPTED, HistoryStore(h.root, HistoryOwner()).list().single().delivery)
        } finally { h.close() }
    }
    @Test fun pendingSurvivesRestartBeforeAndAfterAttemptUntilReceipt() {
        val h = Harness()
        try {
            h.stop(); assertTrue(h.entries().isEmpty())
            h.io.one()
            assertEquals(HistoryDelivery.UNKNOWN, h.entries().single().delivery)
            assertEquals(0, h.inserts)
            h.delivery.one()
            assertEquals(1, h.inserts)
            assertEquals(HistoryDelivery.UNKNOWN, h.entries().single().delivery)
            h.io.one()
            assertEquals(HistoryDelivery.ACCEPTED, h.entries().single().delivery)
        } finally { h.close() }
    }
    @Test fun cancelWhileSaveWaitsDeletesAndCannotInsertAfterLateAcknowledgement() {
        val h = Harness()
        try {
            h.stop(); h.take.cancel(); h.io.drain(); h.delivery.drain(); h.io.drain()
            assertEquals(0, h.inserts); assertTrue(h.entries().isEmpty())
            assertEquals(TakeOutcome.CANCELLED, h.result!!.outcome)
        } finally { h.close() }
    }
    @Test fun inputInvalidationWhileSaveWaitsPreservesNotAttemptedText() {
        val h = Harness()
        try {
            h.stop(); h.take.cancel(inputChanged = true); h.io.drain(); h.delivery.drain(); h.io.drain()
            assertEquals(0, h.inserts)
            assertEquals(HistoryDelivery.NOT_ATTEMPTED, h.entries().single().delivery)
        } finally { h.close() }
    }
    @Test fun deleteAfterStagingDoesNotCancelStopOrRecreateRow() {
        val h = Harness()
        try {
            h.stop(); h.io.one(); HistoryStore(h.root).delete(h.entries().single().id)
            h.delivery.one(); h.io.drain()
            assertEquals(1, h.inserts); assertTrue(h.entries().isEmpty())
        } finally { h.close() }
    }
    @Test fun exceptionDuringCommitRemainsUncertainAndIsNotRetried() {
        val h = Harness()
        try {
            h.commit = { throw IllegalStateException("editor failed") }
            h.stop(); h.io.one(); h.delivery.one(); h.io.drain()
            assertEquals(1, h.inserts); assertEquals(0, h.returns)
            assertEquals(HistoryDelivery.UNKNOWN, h.entries().single().delivery)
            assertEquals(HistoryCompletion.COMPLETE, h.entries().single().completion)
            assertEquals(0, h.decodeWarnings)
        } finally { h.close() }
    }
    @Test fun synchronousInvalidationInsideAcceptedCommitKeepsAcceptedReceipt() {
        val h = Harness()
        try {
            h.commit = { h.take.cancel(inputChanged = true); true }
            h.stop(); h.io.one(); h.delivery.one(); h.io.drain()
            assertEquals(HistoryDelivery.ACCEPTED, h.entries().single().delivery)
            assertEquals(0, h.returns); assertEquals(true, h.result!!.commitAccepted)
        } finally { h.close() }
    }
    @Test fun deliberateCancelInsideAcceptedCommitKeepsAcceptedReceipt() {
        val h = Harness()
        try {
            h.commit = { h.take.cancel(); true }
            h.stop(); h.io.one(); h.delivery.one(); h.io.drain()
            assertEquals(HistoryDelivery.ACCEPTED, h.entries().single().delivery)
            assertEquals(TakeOutcome.COMPLETED, h.result!!.outcome)
            assertEquals(0, h.returns)
        } finally { h.close() }
    }
    @Test fun deliberateCancelInsideThrowingCommitKeepsUncertainReceipt() {
        val h = Harness()
        try {
            h.commit = { h.take.cancel(); throw IllegalStateException("editor failed") }
            h.stop(); h.io.one(); h.delivery.one(); h.io.drain()
            assertEquals(HistoryDelivery.UNKNOWN, h.entries().single().delivery)
            assertEquals(1, h.inserts)
        } finally { h.close() }
    }
    @Test fun inputInvalidationWhileReadingCursorSkipsDispatchAndKeyboardReturn() {
        val h = Harness()
        try {
            val session = InputSession().apply { start() }
            val token = session.token()
            val connection = Any()
            h.attempted = false
            h.guardedCommit = { text, allowed ->
                commitToInput(session, token, connection, { connection }, allowed,
                    { session.invalidate(); h.take.cancel(inputChanged = true); "x" },
                    { _, _ -> h.attempted = true; h.inserts++; true }, text) ?: false
            }
            h.stop(); h.io.one(); h.delivery.one(); h.io.drain()
            assertEquals(0, h.inserts); assertEquals(0, h.returns)
            assertEquals(HistoryDelivery.NOT_ATTEMPTED, h.entries().single().delivery)
        } finally { h.close() }
    }
    @Test fun keyboardFailureCannotEraseAcceptedReceipt() {
        val h = Harness()
        try {
            h.keyboard = { throw IllegalStateException("return failed") }
            h.stop(); h.io.one(); h.delivery.one(); h.io.drain()
            assertEquals(HistoryDelivery.ACCEPTED, h.entries().single().delivery)
            assertEquals(1, h.inserts)
            assertEquals(true, h.result!!.commitAccepted)
        } finally { h.close() }
    }
    @Test fun synchronousInvalidationDuringKeyboardReturnKeepsAcceptedReceipt() {
        val h = Harness()
        try {
            h.keyboard = { h.take.cancel(inputChanged = true); true }
            h.stop(); h.io.one(); h.delivery.one(); h.io.drain()
            assertEquals(HistoryDelivery.ACCEPTED, h.entries().single().delivery)
            assertEquals(true, h.result!!.commitAccepted)
            assertEquals(TakeOutcome.COMPLETED, h.result!!.outcome)
        } finally { h.close() }
    }
    @Test fun failedInitialSaveWarnsButStillAllowsOneStop() {
        val h = Harness(broken = true)
        try {
            h.stop(); h.io.one(); h.delivery.one(); h.io.drain()
            assertEquals(1, h.inserts); assertTrue(h.warnings > 0)
        } finally { h.close() }
    }
    @Test fun failedFinalSaveCannotAttachAcceptedReceiptToOlderPrefix() {
        val h = Harness()
        try {
            h.take.start(); h.feed!!(floatArrayOf(1f)); h.io.one()
            h.speech = "more"
            h.feed!!(floatArrayOf(1f)); h.io.one()
            val prefix = h.entries().single()
            val obstruction = h.root.resolve("${prefix.id}.tmp")
            assertTrue(obstruction.mkdir())
            h.take.stop(); h.io.one(); h.delivery.one(); h.io.drain()
            assertEquals(1, h.inserts)
            assertEquals(HistoryDelivery.UNKNOWN, h.entries().single().delivery)
            obstruction.delete()
        } finally { h.close() }
    }
    @Test fun failedReceiptWriteLeavesThePendingTranscriptIntact() {
        val h = Harness()
        try {
            h.stop(); h.io.one()
            val entry = h.entries().single()
            val obstruction = h.root.resolve("${entry.id}.tmp")
            assertTrue(obstruction.mkdir())
            h.delivery.one(); h.io.drain()
            assertTrue(h.warnings > 0)
            assertEquals("test words", h.entries().single().text)
            assertEquals(HistoryDelivery.UNKNOWN, h.entries().single().delivery)
            obstruction.delete()
        } finally { h.close() }
    }
    @Test fun failedCancelledDeletionWarnsAndLeavesRecordAvailableForRetry() {
        val h = Harness()
        try {
            h.stop(); h.io.one()
            val entry = h.entries().single()
            val obstruction = h.root.resolve("${entry.id}.bak")
            assertTrue(obstruction.mkdir())
            obstruction.resolve("child").writeText("x")
            h.take.cancel(); h.io.drain(); h.delivery.drain()
            assertTrue(h.warnings > 0); assertEquals(0, h.inserts)
            assertEquals(entry, h.entries().single())
            obstruction.deleteRecursively()
            HistoryStore(h.root).delete(entry.id)
            assertTrue(h.entries().isEmpty())
        } finally { h.close() }
    }
    @Test fun excludedTakeBypassesStore() {
        val h = Harness(eligible = false)
        try {
            h.stop(); h.io.drain(); h.delivery.drain()
            assertEquals(1, h.inserts); assertTrue(h.entries().isEmpty())
        } finally { h.close() }
    }
    @Test fun emptyStopDoesNotCreateHistoryOrCommit() {
        val h = Harness()
        try {
            h.speech = ""
            h.stop(); h.io.drain(); h.delivery.drain()
            assertEquals(0, h.inserts); assertTrue(h.entries().isEmpty())
        } finally { h.close() }
    }
    @Test fun deliberateCancelDuringCursorReadRemovesStagedText() {
        val h = Harness()
        try {
            val session = InputSession().apply { start() }
            val token = session.token()
            val connection = Any()
            h.attempted = false
            h.guardedCommit = { text, allowed ->
                commitToInput(session, token, connection, { connection }, allowed,
                    { h.take.cancel(); "x" },
                    { _, _ -> h.attempted = true; h.inserts++; true }, text) ?: false
            }
            h.stop(); h.io.one(); h.delivery.one(); h.io.drain()
            assertEquals(0, h.inserts)
            assertTrue("Deliberate pre-dispatch Cancel must remove staged transcript", h.entries().isEmpty())
            assertEquals(TakeOutcome.CANCELLED, h.result!!.outcome)
        } finally { h.close() }
    }
    @Test fun deliberateCancelThenCursorFailureRemovesStagedText() {
        val h = Harness()
        try {
            val session = InputSession().apply { start() }
            val token = session.token()
            val connection = Any()
            h.attempted = false
            h.guardedCommit = { text, allowed ->
                commitToInput(session, token, connection, { connection }, allowed,
                    { h.take.cancel(); throw IllegalStateException("cursor failed before commit") },
                    { _, _ -> h.attempted = true; h.inserts++; true }, text) ?: false
            }
            h.stop(); h.io.one(); h.delivery.one(); h.io.drain()
            assertEquals(0, h.inserts)
            assertTrue("Deliberate pre-dispatch Cancel must remove staged transcript", h.entries().isEmpty())
            assertEquals(TakeOutcome.CANCELLED, h.result!!.outcome)
        } finally { h.close() }
    }
}
