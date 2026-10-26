package ch.og.dictation

import android.content.Context
import android.os.Process
import android.util.Log
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import org.json.JSONObject

/** Benchmark timing only: no audio, text, editor identity or permission changes. */
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

    fun liveResult(takeId: String?, identity: JSONObject, result: TakeResult) {
        val row = JSONObject().put("protocol", "ime-candidate-live-v1").put("processId", processId)
            .put("serviceId", serviceId).put("takeId", takeId).put("identity", identity)
            .put("outcome", result.outcome.name).put("eventsNs", JSONObject(result.eventsNs))
            .put("commitAccepted", result.commitAccepted ?: JSONObject.NULL)
            .put("returnAccepted", result.returnAccepted ?: JSONObject.NULL)
        writer.execute {
            runCatching {
                val file = File(context.filesDir, "lab/live-results.jsonl")
                file.parentFile?.mkdirs()
                check(file.length() < 2 * 1024 * 1024) { "live receipt file full" }
                file.appendText(row.toString() + "\n")
            }.onFailure { Log.e("StartupTrace", "live receipt unavailable", it) }
        }
    }

    fun event(phase: String, takeId: String? = null, ns: Long = System.nanoTime(), modelReady: Boolean? = null) {
        val pid = Process.myPid()
        writer.execute {
            runCatching {
                val row = JSONObject().put("protocol", "ime-startup-v1")
                    .put("clock", "CLOCK_MONOTONIC").put("processId", processId).put("pid", pid)
                    .put("serviceId", serviceId).put("phase", phase).put("ns", ns)
                takeId?.let { row.put("takeId", it) }
                modelReady?.let { row.put("modelReadyAtPanelRequest", it) }
                val file = File(context.filesDir, "lab/startup.jsonl")
                file.parentFile?.mkdirs()
                // Never silently overwrite visit evidence. Stop collecting when the bounded file is full.
                check(file.length() < 2 * 1024 * 1024) { "startup receipt file full; pull and archive it" }
                file.appendText(row.toString() + "\n")
            }.onFailure { Log.e("StartupTrace", "startup receipt unavailable", it) }
        }
    }
}
