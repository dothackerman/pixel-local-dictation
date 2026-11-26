package ch.og.dictation.panel

import ch.og.dictation.R
import android.content.res.Resources
import org.json.JSONObject
import org.json.JSONTokener

/**
 * One panel frame as one line of a golden file (#77), and back. Resource ids are written by name,
 * flags only when set:
 * `[new panel] status_recording | microphone on: stop_recording | back: discard_recording | stopGlyph ringLive | preview: "words"`
 */
object FrameText {
    private const val FRESH = "[new panel] "
    private val flags: List<Pair<String, (PanelFrame) -> Boolean>> = listOf(
        "preparing" to { it.preparing }, "stopGlyph" to { it.stopGlyph }, "unableToInsert" to { it.unableToInsert },
        "held" to { it.held }, "uncertain" to { it.uncertain }, "banner" to { it.banner }, "ringLive" to { it.ringLive })

    fun line(frame: PanelFrame, fresh: Boolean, resources: Resources): String {
        fun name(id: Int) = resources.getResourceEntryName(id)
        val parts = mutableListOf(name(frame.status),
            "microphone ${if (frame.microphoneEnabled) "on" else "off"}: ${name(frame.microphoneLabel)}",
            "back: ${name(frame.backLabel)}")
        frame.choice?.let { parts += "choice: $it" }
        frame.notice?.let { parts += "notice: ${name(it)}" }
        flags.filter { it.second(frame) }.takeIf { it.isNotEmpty() }?.let { set -> parts += set.joinToString(" ") { it.first } }
        parts += "preview: ${JSONObject.quote(frame.preview)}"
        return (if (fresh) FRESH else "") + parts.joinToString(" | ")
    }

    /** True for a line written by [line]; steps (`> …`) are not frames. */
    fun isFrame(line: String) = line.isNotBlank() && !line.startsWith(">")

    fun frame(line: String, resources: Resources): PanelFrame {
        fun id(name: String) = runCatching { R.string::class.java.getField(name).getInt(null) }.getOrDefault(0)
            .also { require(it != 0) { "no string resource $name in: $line" } }
        val body = line.removePrefix(FRESH)
        val cut = body.indexOf(" | preview: ").also { require(it >= 0) { "no preview in: $line" } }
        val parts = body.substring(0, cut).split(" | ")
        fun value(key: String) = parts.firstOrNull { it.startsWith("$key: ") }?.substringAfter(": ")
        val microphone = parts.first { it.startsWith("microphone ") }
        val set = parts.lastOrNull { part -> part.split(" ").all { word -> flags.any { it.first == word } } }?.split(" ").orEmpty()
        return PanelFrame(
            choice = value("choice")?.let { PanelChoice.valueOf(it) },
            preparing = "preparing" in set, stopGlyph = "stopGlyph" in set, unableToInsert = "unableToInsert" in set,
            held = "held" in set, uncertain = "uncertain" in set,
            backLabel = id(value("back")!!), status = id(parts[0]),
            microphoneEnabled = microphone.startsWith("microphone on"), microphoneLabel = id(microphone.substringAfter(": ")),
            preview = JSONTokener(body.substring(cut + " | preview: ".length)).nextValue() as String,
            notice = value("notice")?.let { id(it) }, banner = "banner" in set, ringLive = "ringLive" in set)
    }
}
