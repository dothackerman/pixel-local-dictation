package ch.og.dictation

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BenchmarkLiveConfigTest {
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test fun explicitCandidateVerifiesActualFilesAndRejectsDictionaryDrift() {
        val lab = Files.createTempDirectory("live-variant").toFile()
        try {
            val apk = File(lab, "test.apk").apply { writeText("synthetic APK") }
            File(lab, "model").mkdir()
            val files = listOf("model/encoder.onnx", "model/decoder.onnx", "model/joiner.onnx", "model/tokens.txt",
                "model/bpe.vocab", "silero_vad.onnx", "dictionary.txt")
            for (name in files) File(lab, name).writeText(name)
            File(lab, "variant.json").writeText("""{"family":"parakeet","model":"parakeet-v3-fp32",
                "decoding":{"method":"modified_beam_search","max_active_paths":8},
                "hotwords":{"file":"dictionary.txt","score":2.0}}""")
            val artifacts = (files + "variant.json").associateWith { hash(File(lab, it).readBytes()) }
            val models = artifacts.filterKeys { it.startsWith("model/") }.toSortedMap()
            val modelHash = hash(models.entries.joinToString(",", "{", "}") { JSONObject.quote(it.key) + ":" + JSONObject.quote(it.value) }.toByteArray())
            val manifest = JSONObject().put("schema", 1).put("plan_sha256", "a".repeat(64))
                .put("build_sha256", hash(apk.readBytes())).put("configuration_sha256", artifacts.getValue("variant.json"))
                .put("model_sha256", modelHash).put("dictionary_sha256", artifacts.getValue("dictionary.txt"))
                .put("modelDirectory", "model").put("artifacts", JSONObject(artifacts))
            File(lab, "live.json").writeText(manifest.toString())
            val config = requireNotNull(BenchmarkLiveConfig.load(lab))
            assertEquals("modified_beam_search", config.variant.decoding.method)
            assertFalse(config.receipt().getBoolean("verified"))
            config.verify(apk)
            assertTrue(config.receipt().getBoolean("verified"))
            File(lab, "dictionary.txt").appendText(" changed")
            assertThrows(IllegalArgumentException::class.java) { requireNotNull(BenchmarkLiveConfig.load(lab)).verify(apk) }
            manifest.put("modelDirectory", "../outside")
            File(lab, "live.json").writeText(manifest.toString())
            assertThrows(IllegalArgumentException::class.java) { BenchmarkLiveConfig.load(lab) }
        } finally { lab.deleteRecursively() }
    }

    @Test fun absentOptInKeepsDefaultSelection() {
        val lab = Files.createTempDirectory("no-live-variant").toFile()
        try { assertNull(BenchmarkLiveConfig.load(lab)) } finally { lab.deleteRecursively() }
    }
}
