package ch.og.dictation

import java.io.File
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
    }

    val variant = Variant.parse(manifest.getJSONObject("variant"))
    private val files = ArtifactFiles(root)
    private fun file(relative: String) = files.resolve(relative)
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
        for ((relative, digest) in artifacts) require(file(relative).isFile && ArtifactFiles.hash(file(relative)) == digest) {
            "provisional baseline artifact changed: $relative"
        }
        val models = modelDirectory.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).invariantSeparatorsPath }.toSet()
        require(models == artifacts.keys.filter { it.startsWith(manifest.getString("modelDirectory") + "/") }.toSet()) { "unfrozen model files" }
        requireNotNull(apk) { "APK identity required" }
        ZipFile(apk).use { zip ->
            val natives = zip.entries().asSequence().filter { it.name.startsWith("lib/") && it.name.endsWith(".so") }
                .associate { it.name to ArtifactFiles.hash(zip.getInputStream(it)) }.toSortedMap()
            require(ArtifactFiles.inventoryHash(natives) == manifest.getString("runtime_sha256")) { "runtime differs from tested A" }
        }
        buildHash = ArtifactFiles.hash(apk)
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
