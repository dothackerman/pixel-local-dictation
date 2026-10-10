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
            val segmenter = Engine.segmenter(ctx).also { it.reset() }
            val take = TakeTranscriber({ Engine.recognizer(ctx) }, segmenter, Engine.decodeQueue, { it.run() }) { previews.add(it) }
            var i = 0
            while (i < x.size) { val n = minOf(640, x.size - i); take.feed(x.copyOfRange(i, i + n)); i += n }
            val stopped = SystemClock.elapsedRealtime()
            val latch = CountDownLatch(1)
            var result: Result<String>? = null
            take.finish { result = it; latch.countDown() }
            assertTrue("no final text within two minutes", latch.await(2, TimeUnit.MINUTES))
            val text = result!!.getOrThrow()
            Log.i("ReplayTest", "pipeline ${f.name}: ${previews.size} previews, ${text.length} characters, " +
                "${text.split(Regex("\\s+")).size} words")
            assertTrue("empty transcript for ${f.name}", text.isNotBlank())
            out.put(JSONObject().put("file", f.name).put("text", text).put("previews", previews.size)
                .put("finishMs", SystemClock.elapsedRealtime() - stopped))
        }
        File(dir, "pipeline.json").writeText(JSONObject().put("takes", out).toString(2))
    }
}
