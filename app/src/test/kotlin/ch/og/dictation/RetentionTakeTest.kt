package ch.og.dictation

import android.text.InputType
import android.view.inputmethod.EditorInfo
import java.nio.file.Files
import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RetentionTakeTest {
    @Test fun originatingEditorControlsRealTakeAndBenchmarkArtifactsAcrossModes() {
        fun editor(type: Int, options: Int = 0) = EditorInfo().apply { inputType = type; imeOptions = options }
        val text = InputType.TYPE_CLASS_TEXT
        val cases = listOf(
            "ordinary" to editor(text),
            "short" to editor(text or InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE),
            "long" to editor(text or InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE),
            "web" to editor(text or InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT),
            "password" to editor(text or InputType.TYPE_TEXT_VARIATION_PASSWORD),
            "visible password" to editor(text or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD),
            "web password" to editor(text or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD),
            "number password" to editor(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD),
            "no learning" to editor(text, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING),
            "missing" to null,
            "unknown" to editor(InputType.TYPE_NULL),
        )
        for ((name, original) in cases) for (enabled in listOf(false, true)) for (live in listOf(false, true)) {
            val dir = Files.createTempDirectory("retention-take").toFile()
            try {
                val store = BenchmarkStore(dir)
                val direct = Executor { it.run() }
                val segments = { object : Segmenter {
                    override fun accept(samples: FloatArray) = listOf(Chunk(samples, true))
                    override fun flush() = emptyList<Chunk>()
                    override fun reset() {}
                } }
                val runtime = DictationRuntime(Variant.HISTORICAL_INT8, direct, { Recognizer { "synthetic words" } }, segments)
                var feed: ((FloatArray) -> Unit)? = null
                var drain: ((Result<ByteArray>) -> Unit)? = null
                val capture = object : TakeCapture {
                    override fun start(accept: (FloatArray) -> Unit) { feed = accept }
                    override fun stop(done: (Result<ByteArray>) -> Unit) { drain = done }
                    override fun cancel(done: () -> Unit) = done()
                }
                val inserted = mutableListOf<String>()
                val output = object : TakeOutput {
                    override fun preview(text: String) {}
                    override fun commit(text: String): Boolean { inserted.add(text); return true }
                    override fun returnToKeyboard() = true
                    override fun finished(result: TakeResult, pcm: ByteArray) {
                        store.saveIfEligible(BenchmarkStore.recordingEnabled(enabled, live), result, pcm, 16000,
                            "{\"text\":\"${result.text}\"}")
                    }
                }
                val eligible = EditorRetentionPolicy.isEligible(original)
                val take = runtime.open(capture, output, direct, retentionEligible = eligible)
                take.start()
                // A newly focused editor must not change the already opened take.
                assertEquals(!eligible, EditorRetentionPolicy.isEligible(if (eligible) null else editor(text)))
                feed!!(floatArrayOf(0.25f))
                take.stop()
                drain!!(Result.success(byteArrayOf(1, 2)))
                assertEquals("$name optIn=$enabled live=$live", listOf("synthetic words"), inserted)
                val files = dir.listFiles()!!.toList()
                val expected = name in setOf("ordinary", "short", "long", "web") && enabled && !live
                assertEquals("$name optIn=$enabled live=$live", if (expected) setOf("wav", "json") else emptySet<String>(), files.map { it.extension }.toSet())
                if (expected) assertTrue(files.single { it.extension == "json" }.readText().contains("synthetic words"))
                runtime.close().join()
            } finally { dir.deleteRecursively() }
        }
    }
}
