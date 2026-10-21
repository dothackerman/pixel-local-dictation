package ch.og.dictation

import java.io.File

/** Benchmark allocation settings. Stock defaults perform no filesystem writes. */
object RuntimeConfig {
    fun provider(v: Variant, model: ModelFiles, lid: Boolean = false): String {
        val r = v.runtime
        val arena = if (lid) r.lidArena else r.modelArena
        val pattern = if (lid) r.lidPattern else r.modelPattern
        val webgpu = !lid && r.encoderProvider == "webgpu"
        if (arena && pattern && !webgpu) return "cpu"
        require(model.assets == null) { "runtime controls require file-backed benchmark models" }
        val role = if (lid) "lid" else "model"
        val directory = File(model.root()).parentFile!!
        val config = File.createTempFile("session-$role-", ".config", directory)
        val lines = mutableListOf("EnableCpuMemArena=${if (arena) 1 else 0}", "EnableMemPattern=${if (pattern) 1 else 0}")
        // Read only by the patched ORT 1.31 Sherpa JNI: encoder on WebGPU, decoder/joiner on CPU.
        if (webgpu) lines += "Dictation.EncoderProvider=webgpu"
        // Diagnostic placement listing only: session-level VERBOSE overrides Sherpa's Env ERROR level.
        if (webgpu && r.placementLog) lines += "LogSeverityLevel=0"
        // Profile flag controls the bounded native Run collector; ORT kernel tracing stays off.
        config.writeText(lines.joinToString("\n", postfix = "\n"))
        return "cpu:${config.absolutePath}"
    }
}
