package ch.og.dictation

import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import org.json.JSONObject

/** Benchmark timing/geometry only: no audio, text, editor identity or permission changes. */
class StartupTrace(private val context: Context) {
    companion object {
        private val processId = UUID.randomUUID().toString()
        private val writer = Executors.newSingleThreadExecutor { Thread(it, "startup-receipts") }
    }
    private val serviceId = UUID.randomUUID().toString()
    private var takeNumber = 0

    fun beginTake(modelReady: Boolean): String {
        val id = "$serviceId:${++takeNumber}"
        event("panelRequested", id, modelReady = modelReady)
        return id
    }

    fun liveResult(takeId: String?, identity: JSONObject, result: TakeResult) = append("live-results") {
        JSONObject().put("protocol", "ime-candidate-live-v1").put("processId", processId)
            .put("serviceId", serviceId).put("takeId", takeId).put("identity", identity)
            .put("outcome", result.outcome.name).put("eventsNs", JSONObject(result.eventsNs))
            .put("commitAccepted", result.commitAccepted ?: JSONObject.NULL)
            .put("returnAccepted", result.returnAccepted ?: JSONObject.NULL)
    }

    /** Values sampled synchronously at the real IME callback; all distances are pixels. */
    fun geometry(phase: String, takeId: String?, ns: Long, geometry: JSONObject) = append("geometry") {
        JSONObject().put("protocol", "ime-geometry-v1")
            .put("clock", "CLOCK_MONOTONIC").put("processId", processId)
            .put("serviceId", serviceId).put("takeId", takeId ?: JSONObject.NULL).put("phase", phase)
            .put("ns", ns).put("geometry", geometry)
    }

    /** Historical ns events have no uptime pair unless their caller captured one. */
    fun event(phase: String, takeId: String? = null, ns: Long? = null,
              modelReady: Boolean? = null, uptimeMs: Long? = null) {
        val eventNs = ns ?: System.nanoTime()
        val eventUptimeMs = uptimeMs ?: if (ns == null) SystemClock.uptimeMillis() else null
        val pid = Process.myPid()
        val requestedUptimeMs = if (phase == "serviceCreate") Process.getStartRequestedUptimeMillis() else null
        val startedUptimeMs = if (phase == "serviceCreate") Process.getStartUptimeMillis() else null
        append("startup") {
            JSONObject().put("protocol", "ime-startup-v1")
                .put("clock", "CLOCK_MONOTONIC").put("processId", processId).put("pid", pid)
                .put("serviceId", serviceId).put("phase", phase).put("ns", eventNs).also { row ->
                    eventUptimeMs?.let { row.put("uptimeMs", it) }
                    requestedUptimeMs?.let { row.put("processStartRequestedUptimeMs", it) }
                    startedUptimeMs?.let { row.put("processStartUptimeMs", it) }
                    takeId?.let { row.put("takeId", it) }
                    modelReady?.let { row.put("modelReadyAtPanelRequest", it) }
                }
        }
    }

    /**
     * Rows are built and written on the writer thread, off the IME callbacks. Never silently
     * overwrite visit evidence: each files/lab/[name].jsonl stops growing before it exceeds 2 MiB.
     */
    private fun append(name: String, row: () -> JSONObject) = writer.execute {
        runCatching {
            val line = row().toString() + "\n"
            val file = File(context.filesDir, "lab/$name.jsonl")
            file.parentFile?.mkdirs()
            check(file.length() + line.toByteArray(Charsets.UTF_8).size <= 2 * 1024 * 1024) {
                "$name receipt file full; pull and archive it"
            }
            file.appendText(line)
        }.onFailure { Log.e("StartupTrace", "$name receipt unavailable", it) }
    }
}
