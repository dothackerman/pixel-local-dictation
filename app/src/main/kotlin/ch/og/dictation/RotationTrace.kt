package ch.og.dictation

import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

/** Only fixed event names and boolean state may leave the process. No editor data is accepted. */
enum class RotationEvent {
    SERVICE_CREATE, SERVICE_ATTACH, SERVICE_DESTROY, VIEW_CREATE, INPUT_START, INPUT_VIEW_START,
    INPUT_VIEW_FINISH, INPUT_FINISH, CONFIGURATION, WINDOW_SHOWN, WINDOW_HIDDEN,
    SCREEN_OFF, INPUT_START_DONE, INPUT_VIEW_START_DONE, INPUT_VIEW_FINISH_DONE, INPUT_FINISH_DONE, CONFIGURATION_DONE, CAPTURE_START, CAPTURE_STARTED, STOP_REQUESTED, CAPTURE_STOPPED,
    CANCEL_REQUESTED, CAPTURE_CANCELLED, TAKE_ENDED, COMMIT_ATTEMPT, COMMIT_ACCEPTED,
    COMMIT_REJECTED, CAPTURE_INTERRUPTED, CAPTURE_FAILED, CAPTURE_RELEASED, CAPTURE_NOT_ACQUIRED,
    TERMINAL_COMPLETED, TERMINAL_CANCELLED, TERMINAL_INPUT_CHANGED, TERMINAL_FAILED,
    TERMINAL_COMMIT_REJECTED
}

/** One service's observations; the shared writer serializes rows from successive services. */
class RotationTrace(private val file: File, private val maxBytes: Long = 2 * 1024 * 1024) {
    companion object {
        private val process = UUID.randomUUID().toString()
        private val services = AtomicLong()
        private val traces = mutableListOf<RotationTrace>()
        private val probeLock = Object()
        private var deadline = 0L
        private var viewStarted = false
        private var viewFinished = false
        private var captureStarted = false
        private var invalid = false
        private val pending = mutableSetOf<Pair<Long, Long>>()
        private val released = mutableSetOf<Pair<Long, Long>>()
        private val terminal = mutableSetOf<Pair<Long, Long>>()

        fun beginProbe() {
            synchronized(probeLock) {
                check(deadline == 0L || System.nanoTime() >= deadline) { "probe already active" }
                deadline = 0L
            }
            closeTraces()
            synchronized(probeLock) {
                deadline = System.nanoTime() + 6L * 60 * 1_000_000_000
                viewStarted = false
                viewFinished = false
                captureStarted = false
                invalid = false
                pending.clear()
                released.clear()
                terminal.clear()
            }
        }

        fun probeActive(): Boolean = synchronized(probeLock) {
            deadline != 0L && System.nanoTime() < deadline
        }

        private fun observe(service: Long, event: RotationEvent, take: Long?, active: Boolean?) = synchronized(probeLock) {
            if (deadline == 0L) return@synchronized
            when (event) {
                RotationEvent.INPUT_VIEW_START -> { viewStarted = true; viewFinished = false }
                RotationEvent.INPUT_VIEW_FINISH_DONE -> viewFinished = true
                RotationEvent.SERVICE_ATTACH -> if (active == true) invalid = true
                RotationEvent.CAPTURE_START -> if (take != null) { captureStarted = true; pending.add(service to take) }
                RotationEvent.TERMINAL_COMPLETED, RotationEvent.TERMINAL_CANCELLED,
                RotationEvent.TERMINAL_INPUT_CHANGED, RotationEvent.TERMINAL_FAILED,
                RotationEvent.TERMINAL_COMMIT_REJECTED -> if (take != null) terminal.add(service to take)
                RotationEvent.CAPTURE_RELEASED, RotationEvent.CAPTURE_NOT_ACQUIRED ->
                    if (take != null) released.add(service to take)
                else -> Unit
            }
            if (take != null && service to take in terminal && service to take in released) pending.remove(service to take)
            probeLock.notifyAll()
        }

        /** A successful probe requires view teardown and every started take's terminal receipt. */
        fun finishProbe(timeoutMs: Long): Boolean {
            val end = System.nanoTime() + timeoutMs.coerceAtLeast(0) * 1_000_000
            synchronized(probeLock) {
                while (deadline != 0L && System.nanoTime() < deadline &&
                    !(viewStarted && viewFinished && captureStarted && pending.isEmpty()) && !invalid) {
                    val remaining = minOf(end, deadline) - System.nanoTime()
                    if (remaining <= 0) return false
                    probeLock.wait((remaining / 1_000_000).coerceAtLeast(1))
                }
                if (invalid || deadline == 0L || System.nanoTime() >= deadline) return false
                deadline = 0L
            }
            return closeTraces()
        }

        fun abortProbe() {
            synchronized(probeLock) { deadline = 0L; probeLock.notifyAll() }
            closeTraces()
        }

        private fun closeTraces(): Boolean {
            val current = synchronized(traces) { traces.toList().also { traces.clear() } }
            return current.isNotEmpty() && current.map { it.close() }.all { it }
        }
        private val writer = Executors.newSingleThreadExecutor { Thread(it, "rotation-trace").apply { isDaemon = true } }
    }
    private val service = services.incrementAndGet()
    private val probeScoped = probeActive()
    private var sequence = 0L
    @Volatile private var failed = false
    @Volatile var closed = false
        private set

    init { synchronized(traces) { traces.add(this) } }

    @Synchronized fun event(event: RotationEvent, active: Boolean? = null,
                            authority: Boolean? = null, flag: Boolean? = null,
                            view: Long? = null, take: Long? = null) {
        if (closed || (probeScoped && !probeActive())) return
        observe(service, event, take, active)
        val row = JSONObject().put("protocol", "ime-rotation-v1").put("process", process)
            .put("service", service).put("sequence", ++sequence).put("ns", System.nanoTime())
            .put("event", event.name)
        active?.let { row.put("active", it) }
        authority?.let { row.put("authority", it) }
        flag?.let { row.put("flag", it) }
        view?.let { row.put("view", it) }
        take?.let { row.put("take", it) }
        val line = row.toString() + "\n"
        writer.execute {
            if (!failed) try {
                check(file.length() + line.toByteArray(Charsets.UTF_8).size <= maxBytes)
                file.parentFile?.mkdirs()
                file.appendText(line)
            } catch (_: Exception) { failed = true }
        }
    }

    /** Waits for previously submitted rows and reports errors or capacity exhaustion. */
    fun flush(): Boolean = try { writer.submit<Boolean> { !failed }.get() } catch (_: Exception) { false }
    @Synchronized fun close(): Boolean {
        closed = true
        return flush()
    }
}
