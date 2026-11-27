package ch.og.dictation.diagnostics

import java.io.File
import org.json.JSONObject

/**
 * Golden rotation traces (#73, #84): one file per scripted take scenario in
 * `app/src/test/golden/rotation-trace`. A line is one trace row: the event and every fact it
 * carries, without what differs between runs (process, service, sequence, clock, probe case and run).
 * `-PupdateGolden` on the Gradle command line rewrites the files; only when a ticket intends the change.
 */
object GoldenTrace {
    private val folder = File("src/test/golden/rotation-trace")
    private val perRun = setOf("protocol", "process", "service", "sequence", "ns", "case", "run", "event")
    private val order = listOf("active", "authority", "flag", "view", "take", "rotation", "rotations", "configDiff",
        "handledConfig", "retention")

    /** One row as a golden line. */
    fun line(row: JSONObject): String {
        val keys = row.keys().asSequence().filter { it !in perRun }.toList()
        val sorted = order.filter { it in keys } + keys.filter { it !in order }.sorted()
        return (listOf(row.getString("event")) + sorted.map { "$it=${row.get(it)}" }).joinToString(" ")
    }

    fun check(scenario: String, lines: List<String>) {
        val golden = File(folder, "$scenario.txt")
        if (System.getProperty("golden.update") == "true") {
            folder.mkdirs()
            golden.writeText(lines.joinToString("\n", postfix = "\n"))
        }
        val expected = if (golden.isFile) golden.readLines() else emptyList()
        if (expected == lines) return
        val first = (0 until maxOf(expected.size, lines.size)).first { expected.getOrNull(it) != lines.getOrNull(it) }
        throw AssertionError(buildString {
            appendLine("rotation trace of scenario $scenario differs from ${golden.path} at line ${first + 1}")
            appendLine("golden:   ${expected.getOrNull(first) ?: "(end)"}")
            appendLine("this run: ${lines.getOrNull(first) ?: "(end)"}")
            appendLine("+++ this run")
            lines.forEach { appendLine(it) }
            append("The golden rotation trace is unchanged by a refactor: same events, same order, same facts.")
        })
    }
}
