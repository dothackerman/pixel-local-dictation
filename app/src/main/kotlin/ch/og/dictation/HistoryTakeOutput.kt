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
) : TakeOutput by delegate {
    private var eligible = false
    private var saved = false // Serial I/O executor only.
    private val lock = Any()
    private data class Pending(
        val text: String,
        val ready: (() -> Unit)?,
        val revision: Long = 0,
        val interruptedReady: ((Boolean) -> Unit)? = null,
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
    private fun enqueue(next: Pending) {
        var rejected = false
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
                        pendingAfterTerminal = next.copy(revision = ++revision)
                    } else if (!terminal || !queuedTerminal) {
                        pending = next.copy(revision = ++revision)
                    } else rejected = true
                } else pending = next.copy(revision = ++revision)
                if (rejected) false else if (scheduled) false else { scheduled = true; true }
            }
        }
        if (rejected) {
            next.ready?.let { delivery.execute(it) }
            next.interruptedReady?.let { callback -> delivery.execute { callback(false) } }
            return
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
                        if (work.ready != null) saved = durable && committed?.let {
                            it.text == work.text && it.completion == HistoryCompletion.COMPLETE && it.revision >= work.revision
                        } == true
                    }
                    if (interruptedSaved) durablePrefix = true
                } catch (_: Exception) { onSaveFailure() }
                work.ready?.let { callback -> delivery.execute { callback() } }
                work.interruptedReady?.let { callback -> delivery.execute { callback(interruptedSaved) } }
            }
        }
    }
    override fun stage(text: String, retentionEligible: Boolean, ready: () -> Unit) {
        if (!retentionEligible) { eligible = false; ready(); return }
        eligible = true
        if (text.isEmpty()) { ready(); return }
        enqueue(Pending(text, ready))
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
    override fun discardStaged() {
        if (eligible) {
            synchronized(lock) { suppressed = true; pending = null }
            io.execute {
                try { store().delete(id) } catch (_: Exception) { onDeleteFailure() }
            }
        }
    }
}
