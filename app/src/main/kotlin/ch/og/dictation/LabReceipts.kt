package ch.og.dictation

import java.io.File

/**
 * The one capped append for files/lab receipts (#64). A file never grows past [MAX_BYTES]: a row
 * that would cross the cap first moves the file to `name.prev.jsonl`, replacing the older
 * generation, and starts a new one. So collection never stops and at most two caps of receipts
 * stay on the phone; pull both generations to keep a visit's evidence.
 */
object LabReceipts {
    const val MAX_BYTES = 2L * 1024 * 1024

    enum class Appended { APPENDED, ROTATED }

    private val noticed = mutableSetOf<String>()

    /** `rotation-probe.jsonl` → `rotation-probe.prev.jsonl`. */
    fun previous(file: File): File = File(file.parentFile, "${file.nameWithoutExtension}.prev.${file.extension}")

    /** Appends whole rows or nothing; throws on I/O failure or a row larger than the cap. */
    @Synchronized fun append(file: File, text: String, maxBytes: Long = MAX_BYTES): Appended {
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= maxBytes) { "${file.name} row exceeds the ${maxBytes}-byte cap" }
        file.parentFile?.mkdirs()
        var appended = Appended.APPENDED
        if (file.length() + bytes.size > maxBytes) {
            val previous = previous(file)
            if (previous.exists()) check(previous.delete()) { "${previous.name} could not be replaced" }
            check(file.renameTo(previous)) { "${file.name} could not move to ${previous.name}" }
            appended = Appended.ROTATED
        }
        file.appendBytes(bytes)
        return appended
    }

    /** Rows of both generations, older first; missing files read as empty. */
    fun lines(file: File): List<String> = listOf(previous(file), file)
        .filter { it.isFile }.flatMap { it.readLines() }.filter { it.isNotBlank() }

    /** True once per process for each file and kind, so a repeated condition logs one notice, not one per row. */
    @Synchronized fun firstNotice(file: File, kind: String): Boolean = noticed.add("${file.absolutePath}:$kind")
}
