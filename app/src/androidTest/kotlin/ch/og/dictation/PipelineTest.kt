package ch.og.dictation

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the takes saved in benchmark mode through the classes the keyboard service uses
 * (Engine, VadSegmenter, TakeTranscriber), fed in recorder-sized chunks without pacing.
 * Writes files/benchmark/pipeline.json. No UI, no microphone.
 */
@RunWith(AndroidJUnit4::class)
class PipelineTest {
    private val ctx: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(ctx.filesDir, "benchmark")

    @Test fun savedTakesThroughTheServicePipeline() {
        val takes = dir.listFiles { f -> f.name.endsWith(".wav") }.orEmpty().sortedBy { it.name }
        assumeTrue("no takes in files/benchmark", takes.isNotEmpty())
        Engine.warmUp(ctx)
        val out = JSONArray()
        for (f in takes) {
            val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN).apply { position(44) }
            val x = FloatArray(b.remaining() / 2) { b.short / 32768f }
            val previews = java.util.Collections.synchronizedList(ArrayList<String>())
            val result = replaySession(Engine.runtime(ctx), x, paced = false) { previews.add(it) }
            assertTrue("session failed: ${result.outcome}", result.outcome == TakeOutcome.COMPLETED)
            val text = requireNotNull(result.text)
            Log.i("ReplayTest", "pipeline ${f.name}: ${previews.size} previews, ${text.length} characters, " +
                "${text.split(Regex("\\s+")).size} words")
            assertTrue("empty transcript for ${f.name}", text.isNotBlank())
            out.put(JSONObject().put("file", f.name).put("text", text).put("previews", previews.size)
                .put("finishMs", (result.eventsNs.getValue("finalReady") - result.eventsNs.getValue("stopRequested")) / 1e6))
        }
        File(dir, "pipeline.json").writeText(JSONObject().put("takes", out)
            .put("timingProtocol", "dictation-session-replay-v1").put("editor", "simulated").toString(2))
    }
}
