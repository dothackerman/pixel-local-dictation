package ch.og.dictation

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Collections
import java.util.UUID
import java.util.concurrent.Executors

/** Transcript files are private, independent records. All callers serialize access on HistoryIo. */
enum class HistoryDelivery { UNKNOWN, NOT_ATTEMPTED, REJECTED, ACCEPTED }
enum class HistoryCompletion { RECORDING, FINISHING, COMPLETE, INTERRUPTED }
enum class HistoryClearStatus { RUNNING, FAILED, SUCCEEDED }
class HistoryClearOperation internal constructor(val token: Long, val ids: Set<String>, val status: HistoryClearStatus) {
    internal fun withStatus(status: HistoryClearStatus) = HistoryClearOperation(token, ids, status)
}

class HistoryOwner {
    private val active = mutableMapOf<String, HistoryCompletion>()
    private val knownIds = mutableSetOf<String>()
    /** Eligible take registration is synchronous, before any checkpoint can be queued. */
    @Synchronized fun register(id: String) { knownIds.add(id); active[id] = HistoryCompletion.RECORDING }
    @Synchronized fun finishing(id: String) { if (id in active) active[id] = HistoryCompletion.FINISHING }
    @Synchronized fun remove(id: String) { active.remove(id) }
    @Synchronized fun state(id: String): HistoryCompletion? = active[id]
    @Synchronized fun activeIds(): Set<String> = active.keys.toSet()
    /** Refresh the persisted inventory discovered by a serialized History read. */
    @Synchronized fun remember(ids: Set<String>) { knownIds.clear(); knownIds.addAll(ids) }
    /** Immutable acceptance-time target snapshot; later registrations do not mutate this set. */
    @Synchronized fun clearSnapshot(): Set<String> = immutableIds(knownIds + active.keys)
}

private fun immutableIds(ids: Collection<String>): Set<String> = Collections.unmodifiableSet(LinkedHashSet(ids))

object HistoryProcess { val owner = HistoryOwner() }
data class HistoryEntry(val id: String, val timestamp: Long, val text: String, val delivery: HistoryDelivery = HistoryDelivery.REJECTED, val completion: HistoryCompletion = HistoryCompletion.COMPLETE, val revision: Long = 0)

class HistoryStore(private val root: File, private val owner: HistoryOwner = HistoryProcess.owner) {
    // Process-local protection is installed for the whole accepted target set before any disk
    // deletion starts. Durable .deleted markers carry that protection across process restarts.
    private val suppressedIds = mutableSetOf<String>()

    private fun file(id: String): File {
        require(UUID.fromString(id).toString() == id)
        return File(root, "$id.history")
    }

    /** Called once on process startup, on the same serial executor as writers. */
    fun cleanAbandoned() {
        if (!root.exists()) return
        check(root.isDirectory)
        val files = root.listFiles() ?: error("Cannot read history")
        files.filter { it.name.endsWith(".tmp") }.forEach { temporary ->
            val id = temporary.name.removeSuffix(".tmp")
            val retryableDelete = runCatching { file(id) }.isSuccess && File(root, "$id.deleted").exists()
            if (!retryableDelete) check(temporary.delete())
        }
    }

    fun save(entry: HistoryEntry) {
        require(entry.text.isNotEmpty())
        val target = file(entry.id)
        if (isSuppressed(entry.id)) return
        check(root.isDirectory || root.mkdirs())
        if (target.exists()) {
            check(read(target) == entry) { "Conflicting history record" }
            return
        }
        write(entry, target)
    }

    /** True when this or a newer/terminal revision is durably represented; false if suppressed. */
    fun checkpoint(entry: HistoryEntry): Boolean {
        require(entry.text.isNotEmpty())
        val target = file(entry.id)
        if (isSuppressed(entry.id)) return false
        check(root.isDirectory || root.mkdirs())
        if (target.exists()) {
            val previous = read(target)
            if (previous.revision >= entry.revision ||
                (previous.completion == HistoryCompletion.COMPLETE && entry.completion != HistoryCompletion.COMPLETE)) return true
        }
        write(entry, target)
        return true
    }

    /** Never recreate a row removed by the owner while a receipt was pending. */
    fun updateDelivery(id: String, delivery: HistoryDelivery) {
        val target = file(id)
        if (isSuppressed(id) || !target.exists()) return
        write(read(target).copy(delivery = delivery), target)
    }

    private fun write(entry: HistoryEntry, target: File) {
        val temp = File(root, "${entry.id}.tmp")
        try {
            FileOutputStream(temp).use { stream ->
                val out = DataOutputStream(stream)
                out.writeInt(4)
                out.writeLong(entry.timestamp)
                val bytes = entry.text.toByteArray(Charsets.UTF_8)
                out.writeInt(bytes.size)
                out.write(bytes)
                out.writeUTF(entry.delivery.name)
                out.writeUTF(entry.completion.name)
                out.writeLong(entry.revision)
                out.flush()
                stream.fd.sync()
            }
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temp.delete() }
    }

    fun list(): List<HistoryEntry> {
        if (!root.exists()) return emptyList()
        check(root.isDirectory)
        return (root.listFiles() ?: error("Cannot read history"))
            .filter { it.name.endsWith(".history") }.map(::read)
            .sortedWith(compareByDescending<HistoryEntry> { it.timestamp }.thenBy { it.id })
    }

    /** IDs known to History, including tombstones and residual copies omitted from the visible list. */
    fun clearableIds(): Set<String> = idsWithSuffixes(listOf(".history", ".deleted", ".tmp", ".bak"))

    /** IDs with transcript-bearing files, excluding content-free tombstone metadata. */
    fun storedIds(): Set<String> = idsWithSuffixes(listOf(".history", ".tmp", ".bak"))

    private fun idsWithSuffixes(suffixes: List<String>): Set<String> {
        if (!root.exists()) return emptySet()
        check(root.isDirectory)
        val files = root.listFiles() ?: error("Cannot read history")
        return files.mapNotNullTo(mutableSetOf()) { candidate ->
            val suffix = suffixes.firstOrNull(candidate.name::endsWith) ?: return@mapNotNullTo null
            val id = candidate.name.removeSuffix(suffix)
            runCatching { file(id) }.getOrNull()?.let { id }
        }
    }

    fun get(id: String): HistoryEntry? = file(id).let { if (it.exists()) read(it) else null }

    fun delete(id: String) {
        val target = file(id)
        suppressedIds.add(id)
        check(root.isDirectory || root.mkdirs())
        writeDeletionMarker(id)
        removeCopies(id, target)
    }

    /**
     * Suppress and delete exactly the accepted IDs, including IDs with no saved row yet.
     * Call on HistoryIo's serial executor. A retry may pass the same set: already removed rows
     * stay removed, and no later checkpoint for any target can recreate them.
     */
    fun clear(ids: Set<String>) {
        val targets = ids.toSortedSet()
        // Reject malformed caller input before changing any records.
        targets.forEach(::file)
        if (targets.isEmpty()) return

        // Install every in-process suppression before the first filesystem operation, so an
        // individual marker/delete failure cannot leave a later active target writable.
        suppressedIds.addAll(targets)
        check(root.isDirectory || root.mkdirs())
        targets.forEach(::writeDeletionMarker)
        targets.forEach { id -> removeCopies(id, file(id)) }
    }

    private fun writeDeletionMarker(id: String) {
        val marker = File(root, "$id.deleted")
        if (!marker.exists()) FileOutputStream(marker).use { it.fd.sync() }
    }

    private fun removeCopies(id: String, target: File) {
        // Remove known residuals first; retain the record if any removal fails.
        for (suffix in listOf(".tmp", ".bak")) {
            val extra = File(root, "$id$suffix")
            check(!extra.exists() || extra.delete()) { "Cannot delete history copy" }
        }
        check(!target.exists() || target.delete()) { "Cannot delete history" }
    }

    private fun isSuppressed(id: String): Boolean =
        id in suppressedIds || File(root, "$id.deleted").exists()

    private fun read(file: File): HistoryEntry {
        val id = file.name.removeSuffix(".history")
        require(this.file(id) == file)
        DataInputStream(file.inputStream().buffered()).use { input ->
            val version = input.readInt()
            require(version in 1..4) { "Unsupported history format" }
            val timestamp = input.readLong()
            val size = input.readInt()
            require(size > 0 && size <= file.length() - 16)
            val bytes = ByteArray(size)
            input.readFully(bytes)
            val delivery = if (version == 1) HistoryDelivery.REJECTED else HistoryDelivery.valueOf(input.readUTF())
            val storedCompletion = if (version >= 3) HistoryCompletion.valueOf(input.readUTF()) else HistoryCompletion.COMPLETE
            val revision = if (version >= 4) input.readLong() else 0L
            require(input.read() == -1)
            val decoder = Charsets.UTF_8.newDecoder()
            val completion = if (storedCompletion == HistoryCompletion.RECORDING || storedCompletion == HistoryCompletion.FINISHING) owner.state(id) ?: HistoryCompletion.INTERRUPTED else storedCompletion
            return HistoryEntry(id, timestamp, decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString(), delivery, completion, revision)
        }
    }
}

object HistoryIo {
    val executor = Executors.newSingleThreadExecutor()
    private val stores = mutableMapOf<File, HistoryStore>()
    private val clearLock = Any()
    private val clearOperations = mutableMapOf<File, HistoryClearOperation>()
    private var nextClearToken = 0L
    // Startup is best effort. Failed initialization is not cached; later reads or writes retry
    // and report their own errors without an uncaught exception killing the process.
    fun start(root: File) = executor.submit { runCatching { store(root) } }
    fun store(root: File): HistoryStore = stores.getOrPut(root.absoluteFile) {
        HistoryStore(root).also { it.cleanAbandoned() }
    }

    /** Freezes the positive-confirmation target set before any queued I/O can run. */
    fun acceptClear(root: File, ids: Set<String>): HistoryClearOperation? = synchronized(clearLock) {
        val key = root.absoluteFile
        val current = clearOperations[key]
        if (current?.status == HistoryClearStatus.RUNNING || current?.status == HistoryClearStatus.FAILED) return@synchronized null
        require(ids.isNotEmpty())
        HistoryClearOperation(++nextClearToken, immutableIds(ids), HistoryClearStatus.RUNNING).also { clearOperations[key] = it }
    }

    fun currentClear(root: File): HistoryClearOperation? = synchronized(clearLock) { clearOperations[root.absoluteFile] }

    /** Retry the same accepted set; never scan for new targets here. */
    fun retryClear(root: File): HistoryClearOperation? = synchronized(clearLock) {
        val key = root.absoluteFile
        val current = clearOperations[key] ?: return@synchronized null
        if (current.status != HistoryClearStatus.FAILED) return@synchronized null
        current.withStatus(HistoryClearStatus.RUNNING).also { clearOperations[key] = it }
    }

    fun finishClear(root: File, token: Long, succeeded: Boolean) = synchronized(clearLock) {
        val key = root.absoluteFile
        val current = clearOperations[key] ?: return@synchronized
        if (current.token != token || current.status != HistoryClearStatus.RUNNING) return@synchronized
        clearOperations[key] = current.withStatus(if (succeeded) HistoryClearStatus.SUCCEEDED else HistoryClearStatus.FAILED)
    }
}
