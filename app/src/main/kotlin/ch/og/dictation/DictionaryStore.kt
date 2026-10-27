package ch.og.dictation

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.Normalizer
import java.util.Locale
import org.json.JSONObject

class DictionaryRevisionConflict : IllegalArgumentException("dictionary changed; refresh before retry")
class DictionaryModelUnavailable : IllegalStateException("dictionary speech model is not installed")

/** Immutable native input. Saved user revisions never overwrite the accepted experiment's files. */
data class DictionarySnapshot(val file: File, val sha256: String, val entries: List<String>, val edited: Boolean) {
    fun verify() {
        require(file.isFile && ArtifactFiles.hash(file) == sha256) { "dictionary revision changed" }
    }
}

/** Private, content-addressed revisions plus one atomic pointer. No terms in receipts or logs. */
class DictionaryStore(private val root: File, private val provisioned: File) {
    private val current = File(root, "current.json")

    fun read(): DictionarySnapshot {
        if (!current.exists()) {
            val bytes = if (provisioned.exists()) provisioned.readBytes() else byteArrayOf()
            return DictionarySnapshot(provisioned, ArtifactFiles.hash(bytes), parse(bytes), false)
        }
        val state = JSONObject(current.readText())
        require(state.getInt("schema") == 1) { "unsupported dictionary format" }
        val hash = state.getString("sha256")
        require(hash.matches(Regex("[a-f0-9]{64}"))) { "invalid dictionary revision" }
        val file = File(root, "revisions/$hash.txt")
        val snapshot = DictionarySnapshot(file, hash, parse(file.readBytes()), true)
        snapshot.verify()
        return snapshot
    }

    /** Stage and validate before native preparation; does not make this revision current. */
    fun prepare(entries: List<String>): DictionarySnapshot {
        val clean = validate(entries)
        val bytes = (if (clean.isEmpty()) "" else clean.joinToString("\n", postfix = "\n")).toByteArray(Charsets.UTF_8)
        require(bytes.size <= 256 * 1024) { "dictionary is too large" }
        val hash = ArtifactFiles.hash(bytes)
        val file = File(root, "revisions/$hash.txt")
        if (!file.exists()) writeAtomic(file, bytes)
        return DictionarySnapshot(file, hash, clean, true).also { it.verify() }
    }

    /** Only after native preparation succeeds. Conflict/failure leaves the former pointer intact. */
    fun commit(snapshot: DictionarySnapshot, expected: DictionarySnapshot) {
        if (read().sha256 != expected.sha256) throw DictionaryRevisionConflict()
        snapshot.verify()
        writeAtomic(current, JSONObject().put("schema", 1).put("sha256", snapshot.sha256).toString().toByteArray(Charsets.UTF_8))
    }

    companion object {
        fun normalise(entry: String): String {
            require(entry.none { it == '\n' || it == '\r' || it.isISOControl() || it == ':' || it == '#' || it == '/' }) {
                "Use one word or phrase, without line breaks, :, # or /."
            }
            val clean = Normalizer.normalize(entry.trim().replace(Regex("\\s+"), " "), Normalizer.Form.NFC)
            require(clean.isNotEmpty()) { "Enter a word or phrase." }
            require(clean.length <= 200) { "Use at most 200 characters." }
            require(clean.any { it.isLetterOrDigit() }) { "Include a letter or number." }
            return clean
        }
        fun validate(entries: List<String>): List<String> {
            require(entries.size <= 5000) { "The dictionary can hold up to 5,000 terms." }
            val clean = entries.map(::normalise)
            require(clean.map { it.lowercase(Locale.ROOT) }.toSet().size == clean.size) { "Already in your dictionary." }
            return clean
        }
        private fun parse(bytes: ByteArray): List<String> = validate(bytes.toString(Charsets.UTF_8).lineSequence()
            .filter { it.isNotBlank() }.toList())

        private fun writeAtomic(file: File, bytes: ByteArray) {
            check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs()) { "Cannot create dictionary storage." }
            val temporary = File.createTempFile("dictionary-", ".tmp", file.parentFile)
            try {
                FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally { temporary.delete() }
        }
    }
}
