package ch.og.dictation

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class EditedBaselineTest {
    @Test fun userRevisionHasSeparateIdentityAndStillVerifiesEveryAcceptedArtifact() {
        val root = Files.createTempDirectory("edited-baseline").toFile()
        try {
            val manifest = JSONObject(File("src/main/res/raw/candidate_a.json").readText())
            val artifacts = manifest.getJSONObject("artifacts")
            for (path in artifacts.keys()) {
                val file = File(root, path)
                file.parentFile!!.mkdirs()
                file.writeText(if (path == "issue11-dictionary.txt") "Original\n" else "synthetic artifact")
                artifacts.put(path, ArtifactFiles.hash(file))
            }
            manifest.put("dictionary_sha256", artifacts.getString("issue11-dictionary.txt"))
            val apk = File(root, "test.apk")
            ZipOutputStream(apk.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("lib/arm64-v8a/runtime.so")); zip.write(byteArrayOf(1, 2, 3)); zip.closeEntry()
            }
            manifest.put("runtime_sha256", ArtifactFiles.inventoryHash(mapOf("lib/arm64-v8a/runtime.so" to ArtifactFiles.hash(byteArrayOf(1, 2, 3)))))
            val store = DictionaryStore(File(root, "user"), File(root, "issue11-dictionary.txt"))
            val changed = store.prepare(listOf("Bärlauch", "English phrase"))
            val selected = ProvisionalBaseline.load(root, manifest, changed)!!
            selected.verify(apk)
            val receipt = selected.receipt()
            assertTrue(receipt.getBoolean("verified"))
            assertFalse(receipt.getBoolean("accuracy_validated"))
            assertEquals("candidate-a-user-dictionary", receipt.getString("baseline"))
            assertEquals(changed.sha256, receipt.getString("dictionary_sha256"))
            assertEquals(manifest.getString("dictionary_sha256"), receipt.getString("tested_dictionary_sha256"))
            assertNotEquals(manifest.getString("configuration_sha256"), receipt.getString("configuration_sha256"))
            assertFalse(receipt.toString().contains("Bärlauch"))
            File(root, "issue11-dictionary.txt").appendText("tampered")
            assertThrows(IllegalArgumentException::class.java) { selected.verify(apk) }
            assertNull(ProvisionalBaseline.load(root, manifest, store.prepare(emptyList())))
        } finally { root.deleteRecursively() }
    }
}
