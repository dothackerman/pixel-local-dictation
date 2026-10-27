package ch.og.dictation

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** Byte/path primitives only. Selection and acceptance policies remain with their callers. */
class ArtifactFiles(root: File) {
    private val root = root.canonicalFile

    fun resolve(relative: String): File {
        require(relative.isNotEmpty() && !File(relative).isAbsolute && relative.split('/').none { it == ".." }) { "artifact path must be relative" }
        return File(root, relative).canonicalFile.also { require(it.toPath().startsWith(root.toPath())) { "artifact path escapes root" } }
    }

    companion object {
        fun hash(bytes: ByteArray) = hash(bytes.inputStream())
        fun hash(file: File) = hash(file.inputStream())
        fun hash(source: InputStream): String = source.use {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(1024 * 1024)
            while (true) { val n = it.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
            digest.digest().joinToString("") { b -> "%02x".format(b) }
        }
        // Frozen Python json.dumps defaults: ASCII escapes, unescaped slash, sorted keys.
        // org.json.quote is platform-dependent (Android escapes every slash).
        private fun quote(value: String) = buildString {
            append('"')
            for (c in value) when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ' || c > '~') append("\\u").append(c.code.toString(16).padStart(4, '0')) else append(c)
            }
            append('"')
        }
        fun inventoryHash(artifacts: Map<String, String>): String {
            val canonical = artifacts.toSortedMap().entries.joinToString(",", "{", "}") { quote(it.key) + ":" + quote(it.value) }
            return hash(canonical.toByteArray(Charsets.UTF_8))
        }
    }
}
