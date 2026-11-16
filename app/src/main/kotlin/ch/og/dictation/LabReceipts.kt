package ch.og.dictation

import android.util.Log
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/**
 * The one capped append for files/lab receipts (#64). A file never grows past [MAX_BYTES]: a row
 * that would cross the cap first moves the file to `name.prev.jsonl`, replacing the older
 * generation, and starts a new one. So collection never stops and the phone keeps a rolling
 * window of one to two caps, not an archive; pull both generations right after measuring.
 *
 * Rotation and write failure are each logged here once per file per process, for every caller.
 */
object LabReceipts {
    const val MAX_BYTES = 2L * 1024 * 1024

    private val noticed = mutableSetOf<String>()

    /** Where notices go; host tests replace it. Plain JUnit has no working [Log], hence the guard. */
    @Volatile internal var notice: (message: String, failure: Throwable?) -> Unit = { message, failure ->
        runCatching { if (failure == null) Log.w("LabReceipts", message) else Log.e("LabReceipts", message, failure) }
    }

    /** `rotation-probe.jsonl` → `rotation-probe.prev.jsonl`. */
    fun previous(file: File): File = File(file.parentFile, "${file.nameWithoutExtension}.prev.${file.extension}")

    /** Appends whole rows or nothing and never throws; false when the rows were not written. */
    @Synchronized fun append(file: File, text: String, maxBytes: Long = MAX_BYTES): Boolean = try {
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= maxBytes) { "${file.name} row exceeds the ${maxBytes}-byte cap" }
        file.parentFile?.mkdirs()
        if (file.length() + bytes.size > maxBytes) {
            // One rename(2): the older generation is replaced only if the move succeeds.
            Files.move(file.toPath(), previous(file).toPath(), REPLACE_EXISTING, ATOMIC_MOVE)
            once(file, "rotated") { notice("${file.name} reached the cap; older rows moved to ${previous(file).name}", null) }
        }
        file.appendBytes(bytes)
        true
    } catch (e: Exception) {
        once(file, "failed") { notice("${file.name} receipt unavailable", e) }
        false
    }

    /** Rows of both generations, older first; missing files read as empty. Locked against a rotation in between. */
    @Synchronized fun lines(file: File): List<String> = listOf(previous(file), file)
        .filter { it.isFile }.flatMap { it.readLines() }.filter { it.isNotBlank() }

    private fun once(file: File, kind: String, tell: () -> Unit) {
        if (noticed.add("${file.absolutePath}:$kind")) tell()
    }
}
