package ch.og.dictation

import ch.og.dictation.dictionary.DictionaryActivity
import ch.og.dictation.dictionary.DictionaryModel
import ch.og.dictation.dictionary.DictionarySnapshot
import ch.og.dictation.dictionary.DictionaryStore
import ch.og.dictation.recognition.ArtifactFiles
import ch.og.dictation.recognition.Chunk
import ch.og.dictation.recognition.DictationRuntime
import ch.og.dictation.recognition.Engine
import ch.og.dictation.recognition.ProvisionalBaseline
import ch.og.dictation.recognition.Recognizer
import ch.og.dictation.recognition.Segmenter
import ch.og.dictation.take.TakeCapture
import ch.og.dictation.take.TakeOutcome
import ch.og.dictation.take.TakeOutput
import ch.og.dictation.take.TakeResult
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * The engine around a fake runtime builder, placed through Wiring: saving the Dictionary before the
 * first take builds nothing; saving it later builds one runtime per revision and publishes it while
 * an open take keeps the runtime it started with. The Dictionary is the one Storage names.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DictionaryRuntimeUpdateTest {
    /** Stands in for the model: Candidate A's record over synthetic files, one fake recognizer per runtime. */
    private class FakeRuntimes(private val lab: File, private val manifest: JSONObject, private val apk: File) : Engine.Runtimes {
        val built = mutableListOf<DictionarySnapshot>()
        var availabilityChecks = 0
        override val modelLoads: Int get() = built.size

        override fun build(dictionary: DictionarySnapshot): Pair<DictationRuntime, ProvisionalBaseline?> {
            built += dictionary
            val baseline = requireNotNull(ProvisionalBaseline.load(lab, manifest, dictionary))
            return DictationRuntime(baseline.variant, Executor { it.run() }, { baseline.verify(apk); Recognizer { "synthetic" } }, {
                object : Segmenter {
                    override fun accept(samples: FloatArray) = emptyList<Chunk>()
                    override fun flush() = emptyList<Chunk>()
                    override fun reset() {}
                }
            }) to baseline
        }

        override fun requireAvailable(staged: DictionarySnapshot) { availabilityChecks++ }
    }

    class Settings : SettingsActivity() {
        override fun onCreate(savedInstanceState: Bundle?) { setTheme(R.style.Theme_Dictation); super.onCreate(savedInstanceState) }
    }
    class Editor : DictionaryActivity() {
        override val defaultViewModelProviderFactory: ViewModelProvider.Factory
            get() = ViewModelProvider.AndroidViewModelFactory(application)
        override fun onCreate(savedInstanceState: Bundle?) { setTheme(R.style.Theme_Dictation); super.onCreate(savedInstanceState) }
    }

    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var runtimes: FakeRuntimes
    private lateinit var scope: WiringOverride
    private val engine get() = app.wiring.engine

    @Before fun placeTheEngine() {
        val lab = File(app.cacheDir, "synthetic-lab")
        val manifest = JSONObject(app.resources.openRawResource(R.raw.candidate_a).bufferedReader().use { it.readText() })
        val list = manifest.getJSONObject("variant").getJSONObject("hotwords").getString("file")
        val artifacts = manifest.getJSONObject("artifacts")
        for (path in artifacts.keys().asSequence().toList()) {
            val file = File(lab, path).apply { parentFile!!.mkdirs(); writeText(if (path == list) "Original\n" else "synthetic artifact") }
            artifacts.put(path, ArtifactFiles.hash(file))
        }
        manifest.put("dictionary_sha256", artifacts.getString(list))
        val apk = File(app.cacheDir, "synthetic.apk")
        ZipOutputStream(apk.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("lib/arm64-v8a/runtime.so")); zip.write(byteArrayOf(1, 2, 3)); zip.closeEntry()
        }
        manifest.put("runtime_sha256", ArtifactFiles.inventoryHash(mapOf("lib/arm64-v8a/runtime.so" to ArtifactFiles.hash(byteArrayOf(1, 2, 3)))))
        runtimes = FakeRuntimes(lab, manifest, apk)
        scope = app.overrideWiring { wiring ->
            wiring.with(engine = Engine({ DictionaryStore(wiring.storage.dictionaryRoot, File(lab, list)) }, { true }, runtimes))
        }
    }
    @After fun restoreWiring() { scope.close() }

    private fun savedPointer() = File(app.wiring.storage.dictionaryRoot, "current.json")

    @Test fun coldSettingsChangesDoNotInitialiseTheModel() {
        val store = engine.dictionaryStore()
        val original = store.read()
        assertEquals(listOf("Original"), original.entries)
        val saved = engine.saveDictionary(original, original.entries + "Quarzblütenpost").get(10, TimeUnit.SECONDS)
        assertEquals(saved, store.read())
        assertTrue("saved where Storage keeps the Dictionary", savedPointer().isFile)
        assertEquals("editing alone must check that the model is installed", 1, runtimes.availabilityChecks)
        assertEquals("editing alone must not build a runtime", emptyList<DictionarySnapshot>(), runtimes.built)
        assertEquals(0, engine.dictionaryModelLoads)
    }

    @Test fun savedRevisionRefreshesOnceAndAnExistingTakeKeepsItsRuntime() {
        val original = engine.dictionaryStore().read()
        val direct = Executor { it.run() }
        val ended = CompletableFuture<TakeResult>()
        val capture = object : TakeCapture {
            override fun start(accept: (FloatArray) -> Unit) {}
            override fun stop(done: (Result<ByteArray>) -> Unit) = done(Result.success(byteArrayOf()))
            override fun cancel(done: () -> Unit) = done()
        }
        val output = object : TakeOutput {
            override fun preview(text: String) {}
            override fun commit(text: String) = true
            override fun returnToKeyboard() = true
            override fun finished(result: TakeResult, pcm: ByteArray) { ended.complete(result) }
        }
        val old = engine.runtime()
        engine.warmUp().get(10, TimeUnit.SECONDS)
        assertEquals("candidate-a-stream-dictionary", requireNotNull(engine.liveReceipt()).getString("baseline"))
        val take = engine.openTake { runtime, _ -> runtime.open(capture, output, direct).also { it.start() } }
        try {
            assertEquals(listOf(original), runtimes.built)
            val saved = engine.saveDictionary(original, original.entries + "Quarzblütenexpress").get(10, TimeUnit.SECONDS)
            assertEquals("one runtime per saved revision", listOf(original, saved), runtimes.built)
            assertEquals("a warm save needs no availability check", 0, runtimes.availabilityChecks)
            assertTrue(take.active)
            assertEquals(saved, engine.dictionaryStore().read())
            val updated = engine.runtime()
            assertNotSame(old, updated)
            assertSame(updated, engine.runtime())
            val receipt = requireNotNull(engine.liveReceipt())
            assertEquals("candidate-a-user-dictionary", receipt.getString("baseline"))
            assertTrue(receipt.getBoolean("verified"))
            assertEquals(saved.sha256, receipt.getString("dictionary_sha256"))
            assertThrows("the replaced runtime opens no further take", IllegalStateException::class.java) { old.open(capture, output, direct) }
            take.stop(); take.stop()
            assertEquals(TakeOutcome.COMPLETED, ended.get(10, TimeUnit.SECONDS).outcome)
            val edited = engine.saveDictionary(saved, original.entries + "Quarzblütenpost").get(10, TimeUnit.SECONDS)
            assertNotEquals(saved.sha256, edited.sha256)
            val removed = engine.saveDictionary(edited, original.entries).get(10, TimeUnit.SECONDS)
            assertEquals(original.entries, removed.entries)
            assertEquals("an unchanged list is no new revision", removed,
                engine.saveDictionary(removed, original.entries).get(10, TimeUnit.SECONDS))
            val empty = engine.saveDictionary(removed, emptyList()).get(10, TimeUnit.SECONDS)
            assertTrue(empty.entries.isEmpty())
            assertEquals(listOf(original, saved, edited, removed, empty), runtimes.built)
            assertEquals("candidate-a-user-dictionary", requireNotNull(engine.liveReceipt()).getString("baseline"))
            assertSame(engine.runtime(), engine.runtime())
            assertEquals(empty, engine.dictionaryStore().read())
        } finally { if (take.active) take.cancel() }
    }

    @Test fun settingsAndTheDictionaryScreenNeverLoadSpeechRecognition() {
        fun idleUntil(what: String, done: () -> Boolean) {
            val deadline = System.nanoTime() + 5_000_000_000
            while (!done()) {
                assertTrue(what, System.nanoTime() < deadline)
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(5)
            }
        }
        val settings = Robolectric.buildActivity(Settings::class.java).setup()
        val count = settings.get().findViewById<TextView>(R.id.settings_dictionary_count)
        idleUntil("Settings shows the Dictionary's term count") { count.visibility == View.VISIBLE }
        assertEquals(app.resources.getQuantityString(R.plurals.dictionary_count, 1, 1), count.text.toString())
        settings.pause().stop().destroy()

        val editor = Robolectric.buildActivity(Editor::class.java).setup()
        try {
            val model = ViewModelProvider(editor.get())[DictionaryModel::class.java]
            idleUntil("the Dictionary screen loads") { model.state.value?.busy == false }
            editor.get().findViewById<View>(R.id.dictionary_add).performClick()
            val sheet = ShadowDialog.getLatestDialog()
            sheet.findViewById<TextInputEditText>(R.id.dictionary_entry).setText("Bärlauch")
            sheet.findViewById<MaterialButton>(R.id.dictionary_commit).performClick()
            idleUntil("the Dictionary screen saves") { model.state.value?.busy == false }
            assertFalse(model.state.value!!.error)
        } finally { editor.pause().stop().destroy() }

        assertEquals(listOf("Original", "Bärlauch"), engine.dictionaryStore().read().entries)
        assertTrue(savedPointer().isFile)
        assertEquals("neither screen may build a runtime", emptyList<DictionarySnapshot>(), runtimes.built)
        assertEquals(0, engine.dictionaryModelLoads)
    }
}
