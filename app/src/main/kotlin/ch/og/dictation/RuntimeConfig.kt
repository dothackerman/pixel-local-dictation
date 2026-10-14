package ch.og.dictation

import java.io.File

/** Benchmark allocation settings. Stock defaults perform no filesystem writes. */
object RuntimeConfig {
    fun provider(v: Variant, model: ModelFiles, lid: Boolean = false): String {
        val r = v.runtime
        val arena = if (lid) r.lidArena else r.modelArena
        val pattern = if (lid) r.lidPattern else r.modelPattern
        if (arena && pattern && !r.profile) return "cpu"
        require(model.assets == null) { "runtime controls require file-backed benchmark models" }
        val role = if (lid) "lid" else "model"
        val directory = File(model.root()).parentFile!!
        val config = File.createTempFile("session-$role-", ".config", directory)
        val lines = mutableListOf("EnableCpuMemArena=${if (arena) 1 else 0}", "EnableMemPattern=${if (pattern) 1 else 0}")
        if (r.profile) lines.add("ProfilingFilePrefix=${config.absolutePath}-$role")
        config.writeText(lines.joinToString("\n", postfix = "\n"))
        return "cpu:${config.absolutePath}"
    }
}
