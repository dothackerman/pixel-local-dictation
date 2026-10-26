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
