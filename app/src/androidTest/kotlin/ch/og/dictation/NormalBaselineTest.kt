package ch.og.dictation

import ch.og.dictation.wiring
import ch.og.dictation.recognition.ArtifactFiles
import ch.og.dictation.recognition.Engine
import ch.og.dictation.take.TakeCapture
import ch.og.dictation.take.TakeOutcome
import ch.og.dictation.take.TakeOutput
import ch.og.dictation.take.TakeResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Focused normal Engine replay. Stores identities/hashes only; never audio or recognized text. */
@RunWith(AndroidJUnit4::class)
class NormalBaselineTest {
    @Test fun runtimeInventoryMatchesFrozenPythonIdentityOnAndroid() {
        // Android org.json escapes slashes differently from the laptop implementation.
        assertEquals("99d4d9ed446a0c93f7a8752e1d0727511fed0bc4071629a2144adbc677b48a6c",
            ArtifactFiles.inventoryHash(mapOf("lib/arm64-v8a/runtime.so" to "abc")))
    }

    @Test fun normalRuntimeSurvivesWarmUpAndRepeatedPanelRequests() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val first = context.wiring.engine.runtime()
        assertSame("repeated panels must retain the runtime", first, context.wiring.engine.runtime())
        context.wiring.engine.warmUp().get(10, TimeUnit.MINUTES)
        val verified = requireNotNull(context.wiring.engine.liveReceipt())
        assertEquals("candidate-a-stream-dictionary", verified.getString("baseline"))
        assertTrue(verified.getBoolean("verified"))
        assertFalse(verified.isNull("build_sha256"))
        repeat(2) {
            assertSame("warm-up and panel requests must share the runtime", first, context.wiring.engine.runtime())
            val receipt = requireNotNull(context.wiring.engine.liveReceipt())
            assertTrue("panel request must retain verified identity", receipt.getBoolean("verified"))
            assertEquals(verified.getString("build_sha256"), receipt.getString("build_sha256"))
        }
    }

    /** The lab build reads the test Dictionary; with no revision saved there, the frozen Candidate A list applies. */
    @Test fun normalPathMatchesRetainedCandidateAOutputs() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertFalse("the test Dictionary holds a saved revision; the frozen Candidate A list must apply",
            context.wiring.engine.dictionaryStore().read().edited)
        val lab = File(context.filesDir, "lab")
        val cases = JSONObject(File(lab, "baseline-parity.json").readText())
        require(cases.length() == 2) { "one retained take per language required" }
        val runtime = context.wiring.engine.runtime()
        runtime.warmUp().get(10, TimeUnit.MINUTES)
        val receipt = requireNotNull(context.wiring.engine.liveReceipt())
        assertEquals("candidate-a-stream-dictionary", receipt.getString("baseline"))
        assertTrue(receipt.getBoolean("verified"))
        val rows = JSONArray()
        val delivery = Executors.newSingleThreadExecutor()
        try {
            for (id in cases.keys()) {
                val item = cases.getJSONObject(id)
                val file = File(lab, item.getString("path")).canonicalFile
                require(file.toPath().startsWith(lab.canonicalFile.toPath()))
                val bytes = file.readBytes()
                require(ArtifactFiles.hash(bytes) == item.getString("sha256"))
                val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).apply { position(44) }
                val samples = FloatArray(buffer.remaining() / 2) { buffer.short / 32768f }
                val finished = CompletableFuture<TakeResult>()
                var commits = 0
                val capture = object : TakeCapture {
                    override fun start(accept: (FloatArray) -> Unit) {
                        for (offset in samples.indices step 640) accept(samples.copyOfRange(offset, minOf(offset + 640, samples.size)))
                    }
                    override fun stop(done: (Result<ByteArray>) -> Unit) = done(Result.success(ByteArray(0)))
                    override fun cancel(done: () -> Unit) = done()
                }
                val output = object : TakeOutput {
                    override fun preview(text: String) {}
                    override fun commit(text: String): Boolean { commits++; return true }
                    override fun returnToKeyboard() = true
                    override fun finished(result: TakeResult, pcm: ByteArray) { finished.complete(result) }
                }
                val take = runtime.open(capture, output, delivery)
                delivery.submit { take.start(); take.stop() }.get(1, TimeUnit.MINUTES)
                val result = finished.get(10, TimeUnit.MINUTES)
                assertEquals(TakeOutcome.COMPLETED, result.outcome)
                assertEquals(1, commits)
                val outputHash = ArtifactFiles.hash(requireNotNull(result.text).toByteArray())
                assertEquals("normal output differs: $id", item.getString("output_sha256"), outputHash)
                rows.put(JSONObject().put("id", id).put("output_sha256", outputHash).put("commits", commits))
            }
            File(lab, "normal-baseline-result.json").writeText(JSONObject().put("protocol", "normal-baseline-parity-v1")
                .put("identity", receipt).put("takes", rows).put("passed", true).toString(2))
        } finally { delivery.shutdown() }
    }
}
