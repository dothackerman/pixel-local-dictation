package ch.og.dictation

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Collection check only. Owner handoffs and their interpretation require the protocol. */
@RunWith(AndroidJUnit4::class)
class EditorGeometryTest {
    @Test fun collectManualKeyboardHandoffs() = collect(false)

    @Test fun collectManualKeyboardHandoffsWithAnimationCallback() = collect(true)

    private fun collect(animationCallback: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val intent = Intent(instrumentation.targetContext, EditorProbeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (animationCallback) intent.putExtra(EditorProbeActivity.EXTRA_ANIMATION_CALLBACK, true)
        val activity = instrumentation.startActivitySync(intent) as EditorProbeActivity
        try {
            val receipt = activity.receipt.get(100, TimeUnit.SECONDS)
            assertEquals("collection failed", null, receipt.failure)
            assertTrue("no laid-out editor geometry captured", receipt.geometryRows > 0)
            assertTrue("current keyboard never became visible; collection insufficient", receipt.sawIme)
            assertEquals("collection must reach its bounded observation deadline", "duration", receipt.reason)
            val rows = File(instrumentation.targetContext.filesDir, "lab/editor-geometry.jsonl")
                .readLines().map { JSONObject(it) }
            val start = rows.last { it.getString("phase") == "start" }.getLong("runStartNs")
            val run = rows.filter { it.getLong("runStartNs") == start }
            assertTrue("every row must identify the selected experimental mode", run.all {
                it.getString("mode") == if (animationCallback) "animationCallback" else "plain"
            })
            assertTrue("combined geometry/callback budget exceeded", run.size <= 4098)
            val animations = run.filter { it.getString("phase").startsWith("animation") }
            if (animationCallback) {
                assertTrue("no IME animation observed; callback collection insufficient", animations.isNotEmpty())
                assertTrue("no IME animation progress observed", animations.any {
                    it.getString("phase") == "animationProgress"
                })
            } else {
                assertTrue("plain host must not collect animation callbacks", animations.isEmpty())
            }
            assertEquals("start", run.first().getString("phase"))
            assertEquals("end", run.last().getString("phase"))
            assertTrue("receipt file exceeds byte budget", File(instrumentation.targetContext.filesDir,
                "lab/editor-geometry.jsonl").length() <= 2 * 1024 * 1024)
            val geometry = run.filter { it.getString("phase") == "geometry" }
            assertEquals(receipt.geometryRows, geometry.size)
            assertTrue(geometry.size <= 4096)
            assertTrue("timestamps must preserve sample order", run.zipWithNext().all {
                (a, b) -> a.getLong("ns") <= b.getLong("ns")
            })
            assertTrue("editor must have real screen bounds", geometry.any {
                val bounds = it.getJSONObject("values").getJSONObject("editor")
                bounds.getInt("width") > 0 && bounds.getInt("height") > 0
            })
            // Deliberately no handoff count/pass: geometry cannot identify another IME.
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
