package ch.og.dictation

import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipFile
import org.json.JSONObject

/** Owner-selected A. The packaged record is immutable; files/lab/live.json is irrelevant. */
class ProvisionalBaseline private constructor(private val root: File, private val manifest: JSONObject) {
    companion object {
        /** Empty/missing dictionary means exactly the former shipped pipeline, including INT8. */
        fun load(root: File, manifest: JSONObject): ProvisionalBaseline? {
            val selected = ProvisionalBaseline(root.canonicalFile, manifest)
            if (!selected.dictionary.exists() || selected.dictionary.readText().isBlank()) return null
            return selected
        }
        private fun hash(source: InputStream): String = source.use {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(1024 * 1024)
            while (true) { val n = it.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
            digest.digest().joinToString("") { b -> "%02x".format(b) }
        }
        private fun hash(file: File) = hash(file.inputStream())
    }

    val variant = Variant.parse(manifest.getJSONObject("variant"))
    private fun file(relative: String): File {
        require(relative.isNotEmpty() && !File(relative).isAbsolute && relative.split('/').none { it == ".." })
        return File(root, relative).canonicalFile.also { require(it.toPath().startsWith(root.toPath())) }
    }
    val modelDirectory = file(manifest.getString("modelDirectory"))
    val dictionary = file(variant.hotwords.file)
    private val artifacts = manifest.getJSONObject("artifacts").let { o -> o.keys().asSequence().associateWith { o.getString(it) } }
    @Volatile private var verified = false
    private var buildHash: String? = null

    init {
        require(manifest.getInt("schema") == 1 && manifest.getString("decision") == "owner-accepted-stop-wait-issue-28")
        require(variant.model == "parakeet-v3-fp32" && variant.family == "parakeet" && variant.threads == 4 &&
            variant.decoding == Variant.Decoding("modified_beam_search", 8) &&
            variant.hotwords.score == 1.5f && variant.isDictionary && variant.runtime == Variant.Runtime() &&
            variant.cutting == Variant.HISTORICAL_INT8.cutting &&
            variant.post == Variant.Post(skipSilentChunks = true)) { "not the accepted Candidate A" }
        require(artifacts[variant.hotwords.file] == manifest.getString("dictionary_sha256"))
        for ((path, digest) in artifacts) { file(path); require(digest.matches(Regex("[a-f0-9]{64}"))) }
    }

    /** Decode executor only. A nonempty altered dictionary/model/runtime fails closed. */
    fun verify(apk: File? = null) {
        verified = false
        for ((relative, digest) in artifacts) require(file(relative).isFile && hash(file(relative)) == digest) {
            "provisional baseline artifact changed: $relative"
        }
        val models = modelDirectory.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).invariantSeparatorsPath }.toSet()
        require(models == artifacts.keys.filter { it.startsWith(manifest.getString("modelDirectory") + "/") }.toSet()) { "unfrozen model files" }
        requireNotNull(apk) { "APK identity required" }
        ZipFile(apk).use { zip ->
            val natives = zip.entries().asSequence().filter { it.name.startsWith("lib/") && it.name.endsWith(".so") }
                .associate { it.name to hash(zip.getInputStream(it)) }.toSortedMap()
            val canonical = natives.entries.joinToString(",", "{", "}") { JSONObject.quote(it.key) + ":" + JSONObject.quote(it.value) }
            require(hash(canonical.byteInputStream()) == manifest.getString("runtime_sha256")) { "runtime differs from tested A" }
        }
        buildHash = hash(apk)
        verified = true
    }

    /** No recognized text/audio. A fresh APK hash is deliberately separate from historical replay. */
    fun receipt() = JSONObject().also { result ->
        for (key in listOf("plan_sha256", "configuration_sha256", "model_sha256", "dictionary_sha256", "runtime_sha256", "historical_build_sha256", "decision"))
            result.put(key, manifest.getString(key))
        result.put("baseline", "candidate-a-provisional").put("build_sha256", buildHash ?: JSONObject.NULL)
            .put("verified", verified).put("artifacts", JSONObject(artifacts))
    }
}
