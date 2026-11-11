package ch.og.dictation

import java.util.UUID
import java.util.concurrent.Executor

/** Decorates the real take completion; serial I/O owns all transcript access. */
class HistoryTakeOutput(
    private val delegate: TakeOutput,
    private val store: () -> HistoryStore,
    private val io: Executor,
    private val onSaveFailure: () -> Unit,
    private val id: String = UUID.randomUUID().toString(),
    private val timestamp: Long = System.currentTimeMillis(),
    private val delivery: Executor = Executor { it.run() },
    private val onDeleteFailure: () -> Unit = onSaveFailure,
    private val onReceiptFailure: () -> Unit = onSaveFailure,
    private val onTakeFailure: (hasDurablePrefix: Boolean) -> Unit = {},
    private val timeline: TimelineObserver? = null,
) : TakeOutput by delegate {
    private var eligible = false
    private var saved = false // Serial I/O executor only.
    private val lock = Any()
    private data class Pending(
        val text: String,
        val ready: (() -> Unit)?,
        val revision: Long = 0,
        val interruptedReady: ((Boolean) -> Unit)? = null,
        val sourceEndSample: Long? = null,
    )
    private var pending: Pending? = null
    private var pendingAfterTerminal: Pending? = null
    private var scheduled = false
    private var suppressed = false
    private var finalQueued = false
    private var revision = 0L
    private var durablePrefix = false // Serial History I/O executor only.

    override fun startHistory(eligible: Boolean) {
        this.eligible = eligible
        if (eligible) HistoryProcess.owner.register(id)
    }
    override fun finishingHistory() { if (eligible) HistoryProcess.owner.finishing(id) }
    override fun finished(result: TakeResult, pcm: ByteArray) {
        if (eligible && result.outcome == TakeOutcome.FAILED && result.text == null) {
            // Run after all prior checkpoints so the persistent warning reflects what
            // this process actually committed, without delaying take completion.
            io.execute { if (durablePrefix) onTakeFailure(true) }
        }
        if (eligible) HistoryProcess.owner.remove(id)
        delegate.finished(result, pcm)
    }
    override fun preview(text: String) {
        delegate.preview(text)
        if (eligible && text.isNotEmpty()) enqueue(Pending(text, null))
    }
    override fun preview(text: String, sourceEndSample: Long?) {
        delegate.preview(text)
        if (eligible && text.isNotEmpty()) {
            enqueue(Pending(text, null, sourceEndSample = sourceEndSample))
        }
    }
    private fun enqueue(next: Pending) {
        var rejected = false
        var queuedWork: Pending? = null
        val submit = synchronized(lock) {
            val terminal = next.ready != null || next.interruptedReady != null
            if (suppressed || (finalQueued && !terminal)) {
                rejected = true
                false
            } else {
                if (terminal) {
                    finalQueued = true
                    val queuedTerminal = pending?.let { it.ready != null || it.interruptedReady != null } == true
                    if (next.interruptedReady != null && queuedTerminal && pendingAfterTerminal == null) {
                        queuedWork = next.copy(revision = ++revision)
                        pendingAfterTerminal = queuedWork
                    } else if (!terminal || !queuedTerminal) {
                        queuedWork = next.copy(revision = ++revision)
                        pending = queuedWork
                    } else rejected = true
                } else {
                    queuedWork = next.copy(revision = ++revision)
                    pending = queuedWork
                }
                if (rejected) false else if (scheduled) false else { scheduled = true; true }
            }
        }
        if (rejected) {
            next.ready?.let { delivery.execute(it) }
            next.interruptedReady?.let { callback -> delivery.execute { callback(false) } }
            return
        }
        queuedWork?.takeIf { it.sourceEndSample != null }?.let {
            timeline?.emit(TimelineEvent("checkpointQueued", sampleEnd = it.sourceEndSample, revision = it.revision))
        }
        if (submit) io.execute {
            while (true) {
                val work = synchronized(lock) {
                    val item = pending ?: pendingAfterTerminal
                    if (pending != null) pending = null else if (pendingAfterTerminal != null) pendingAfterTerminal = null
                    if (item == null) scheduled = false
                    item
                } ?: break
                if (work.ready != null) saved = false
                var interruptedSaved = false
                try {
                    val history = store()
                    if (work.interruptedReady != null) {
                        val durable = history.checkpoint(HistoryEntry(id, timestamp, work.text, HistoryDelivery.UNKNOWN, HistoryCompletion.INTERRUPTED, work.revision))
                        val committed = history.get(id)
                        if (durable && committed?.text == work.text) {
                            if (committed.completion == HistoryCompletion.COMPLETE) {
                                if (committed.delivery == HistoryDelivery.UNKNOWN) history.updateDelivery(id, HistoryDelivery.NOT_ATTEMPTED)
                                val terminal = history.get(id)
                                interruptedSaved = terminal?.text == work.text && terminal.completion == HistoryCompletion.COMPLETE
                            } else interruptedSaved = committed.completion == HistoryCompletion.INTERRUPTED
                        }
                        if (durable && !interruptedSaved && committed != null) onSaveFailure()
                    } else {
                        val completion = if (work.ready == null) HistoryCompletion.RECORDING else HistoryCompletion.COMPLETE
                        val durable = history.checkpoint(HistoryEntry(id, timestamp, work.text, HistoryDelivery.UNKNOWN, completion, work.revision))
                        val committed = history.get(id)
                        if (durable && committed != null) durablePrefix = true
                        if (durable && committed?.text == work.text && committed.revision >= work.revision) {
                            timeline?.emit(TimelineEvent("checkpointDurable", sampleEnd = work.sourceEndSample, revision = work.revision))
                        } else if (work.sourceEndSample != null) timeline?.emit(TimelineEvent(
                            "checkpointFailed", sampleEnd = work.sourceEndSample, revision = work.revision))
                        if (work.ready != null) saved = durable && committed?.let {
                            it.text == work.text && it.completion == HistoryCompletion.COMPLETE && it.revision >= work.revision
                        } == true
                    }
                    if (interruptedSaved) durablePrefix = true
                } catch (_: Exception) {
                    if (work.sourceEndSample != null) timeline?.emit(TimelineEvent(
                        "checkpointFailed", sampleEnd = work.sourceEndSample, revision = work.revision))
                    onSaveFailure()
                }
                work.ready?.let { callback -> delivery.execute { callback() } }
                work.interruptedReady?.let { callback -> delivery.execute { callback(interruptedSaved) } }
            }
        }
    }
    override fun stage(text: String, retentionEligible: Boolean, ready: () -> Unit) {
        stage(text, retentionEligible, null, ready)
    }
    override fun stage(text: String, retentionEligible: Boolean, sourceEndSample: Long?, ready: () -> Unit) {
        if (!retentionEligible) { eligible = false; ready(); return }
        eligible = true
        if (text.isEmpty()) { ready(); return }
        enqueue(Pending(text, ready, sourceEndSample = sourceEndSample))
    }
    override fun saveInterrupted(text: String, retentionEligible: Boolean, done: (Boolean) -> Unit) {
        if (!retentionEligible) { eligible = false; done(false); return }
        eligible = true
        if (text.isEmpty()) { done(false); return }
        enqueue(Pending(text, null, interruptedReady = done))
    }
    override fun receipt(status: HistoryDelivery) {
        if (eligible) io.execute {
            try { if (saved) store().updateDelivery(id, status) } catch (_: Exception) { onReceiptFailure() }
        }
    }
    override fun discardStaged(done: (Boolean) -> Unit) {
        if (eligible) {
            synchronized(lock) { suppressed = true; pending = null }
            io.execute {
                val deleted = try { store().delete(id); true } catch (_: Exception) { onDeleteFailure(); false }
                delivery.execute { done(deleted) }
            }
        } else done(true)
    }
}
