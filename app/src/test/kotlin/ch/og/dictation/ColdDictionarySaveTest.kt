package ch.og.dictation

import android.app.Application
import java.io.File
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ColdDictionarySaveTest {
    @Test fun missingProvisioningRejectsColdEditAndPreservesWorkingFallback() {
        val app: Application = RuntimeEnvironment.getApplication()
        val store = Engine.dictionaryStore(app)
        val original = store.read()
        val manifest = JSONObject(app.resources.openRawResource(R.raw.candidate_a).bufferedReader().use { it.readText() })
        val lab = File(app.filesDir, "lab")
        assertNull(ProvisionalBaseline.load(lab, manifest, original))

        assertThrows(ExecutionException::class.java) {
            Engine.saveDictionary(app, original, listOf("Bärlauch")).get(5, TimeUnit.SECONDS)
        }

        assertEquals(original, store.read())
        assertFalse(File(app.filesDir, "dictionary/current.json").exists())
        assertNull(ProvisionalBaseline.load(lab, manifest, store.read()))
        assertEquals(0, Engine.dictionaryModelLoads)
    }

    @Test fun coldSaveRequiresEveryProvisionedArtifactButDoesNotLoadOrHashModels() {
        val app: Application = RuntimeEnvironment.getApplication()
        val manifest = JSONObject(app.resources.openRawResource(R.raw.candidate_a).bufferedReader().use { it.readText() })
        val artifacts = manifest.getJSONObject("artifacts").keys().asSequence().map { path ->
            File(app.filesDir, "lab/$path").apply {
                parentFile!!.mkdirs()
                writeText(if (path == manifest.getJSONObject("variant").getJSONObject("hotwords").getString("file")) "Original\n" else "availability fixture")
            }
        }.toList()
        val store = Engine.dictionaryStore(app)
        val original = store.read()
        // Each incomplete provisioning must leave the saved pointer alone.
        for (artifact in artifacts) {
            val bytes = artifact.readBytes()
            artifact.delete()
            val failure = assertThrows(ExecutionException::class.java) {
                Engine.saveDictionary(app, original, listOf("New term")).get(5, TimeUnit.SECONDS)
            }
            assertTrue(failure.cause is DictionaryModelUnavailable)
            assertFalse(File(app.filesDir, "dictionary/current.json").exists())
            artifact.writeBytes(bytes)
        }
        // Synthetic, deliberately wrong model hashes demonstrate this is a stat-only preflight.
        // Full cryptographic verification remains required at first recognition.
        val saved = Engine.saveDictionary(app, original, listOf("New term")).get(5, TimeUnit.SECONDS)
        assertEquals(saved, store.read())
        val empty = Engine.saveDictionary(app, saved, emptyList()).get(5, TimeUnit.SECONDS)
        assertTrue(empty.edited)
        assertTrue(store.read().entries.isEmpty())
        assertNotNull(ProvisionalBaseline.load(File(app.filesDir, "lab"), manifest, empty))
        assertEquals(0, Engine.dictionaryModelLoads)
    }

}
