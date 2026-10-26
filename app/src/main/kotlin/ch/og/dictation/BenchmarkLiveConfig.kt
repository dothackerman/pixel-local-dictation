package ch.og.dictation

import java.io.File
import java.security.MessageDigest
import org.json.JSONObject

/** Explicit benchmark-only live selection. The release Engine never reads this file. */
class BenchmarkLiveConfig private constructor(
    private val lab: File, private val manifest: JSONObject, val selectionHash: String,
) {
    companion object {
        fun load(lab: File): BenchmarkLiveConfig? {
            val file = File(lab, "live.json")
            if (!file.exists()) return null
            val bytes = file.readBytes()
            return BenchmarkLiveConfig(lab.canonicalFile, JSONObject(bytes.toString(Charsets.UTF_8)), hash(bytes))
        }
        private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        private fun hash(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { source ->
                val buffer = ByteArray(1024 * 1024)
                while (true) { val n = source.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
    private val identities = setOf("plan_sha256", "build_sha256", "configuration_sha256", "model_sha256", "dictionary_sha256")
    private fun file(relative: String): File {
        require(relative.isNotEmpty() && !File(relative).isAbsolute && relative.split('/').none { it == ".." }) { "live path must be relative to lab" }
        return File(lab, relative).canonicalFile.also { require(it.toPath().startsWith(lab.toPath())) { "live path escapes lab" } }
    }
    val modelDirectory: File
    val dictionary: File
    val variant: Variant
    private val artifacts: Map<String, String>
    @Volatile private var verified = false

    init {
        require(manifest.keys().asSequence().toSet() == identities + setOf("schema", "modelDirectory", "artifacts")) { "unknown or missing live config fields" }
        require(manifest.getInt("schema") == 1) { "unsupported live schema" }
        for (key in identities) require(manifest.getString(key).matches(Regex("[a-f0-9]{64}"))) { "invalid $key" }
        val supplied = manifest.getJSONObject("artifacts")
        artifacts = supplied.keys().asSequence().associateWith { supplied.getString(it) }
        for ((path, digest) in artifacts) { file(path); require(digest.matches(Regex("[a-f0-9]{64}"))) }
        modelDirectory = file(manifest.getString("modelDirectory"))
        require(modelDirectory.isDirectory) { "missing live model directory" }
        val rawVariant = file("variant.json").readBytes()
        require(hash(rawVariant) == manifest.getString("configuration_sha256") && artifacts["variant.json"] == hash(rawVariant)) { "live variant changed" }
        variant = Variant.parse(rawVariant.toString(Charsets.UTF_8))
        require(variant.family == "parakeet" && variant.decoding.method == "modified_beam_search" && variant.decoding.maxActivePaths == 8 &&
            variant.isDictionary && variant.runtime.encoderProvider == "cpu" && !variant.runtime.profile && !variant.runtime.placementLog) { "live check requires the unprofiled CPU beam-8 dictionary candidate" }
        dictionary = file(variant.hotwords.file)
        require(artifacts[variant.hotwords.file] == manifest.getString("dictionary_sha256") && artifacts.containsKey("silero_vad.onnx")) { "missing live dictionary or VAD identity" }
        val modelFiles = modelDirectory.walkTopDown().filter { it.isFile }.map { it.relativeTo(lab).invariantSeparatorsPath }.toSet()
        require(modelFiles.isNotEmpty() && modelFiles.all { it in artifacts }) { "unfrozen model file" }
        val modelHashes = artifacts.filterKeys { it in modelFiles }.toSortedMap()
        val canonical = modelHashes.entries.joinToString(",", "{", "}") { JSONObject.quote(it.key) + ":" + JSONObject.quote(it.value) }
        require(hash(canonical.toByteArray()) == manifest.getString("model_sha256")) { "model inventory differs from frozen identity" }
    }

    /** Hashing/model loading run on the decode executor, never the main thread. */
    fun verify(apk: File) {
        verified = false
        require(hash(apk) == manifest.getString("build_sha256")) { "live APK differs from frozen replay" }
        for ((path, digest) in artifacts) require(hash(file(path)) == digest) { "live artifact changed: $path" }
        verified = true
    }

    /** Metadata only: no recognized text or audio. */
    fun receipt(): JSONObject = JSONObject().also { result ->
        for (key in identities) result.put(key, manifest.getString(key))
        result.put("live_config_sha256", selectionHash).put("verified", verified).put("artifacts", JSONObject(artifacts))
    }
}
