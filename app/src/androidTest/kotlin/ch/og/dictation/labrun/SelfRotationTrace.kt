package ch.og.dictation.labrun

import ch.og.dictation.SelfRotationProbe
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #56, the trace capture: runs one self-rotation case and writes the sanitized summary of its trace
 * (event names, booleans, rotation integers and millisecond differences) to
 * files/lab/issue56/probe-CASE-RUN.json. No inputs. Whether the keyboard came back is the test
 * SelfRotationProbeTest; here an incomplete trace only makes the run incomplete.
 */
@RunWith(AndroidJUnit4::class)
class SelfRotationTrace {
    @Test fun keepFocusDuringCapture() = LabRun.measure("SelfRotationTrace.keepFocusDuringCapture") { capture(it, "self-keep-capture", keep = true, finishing = false) }
    @Test fun keepFocusDuringStopWait() = LabRun.measure("SelfRotationTrace.keepFocusDuringStopWait") { capture(it, "self-keep-finishing", keep = true, finishing = true) }
    @Test fun recreatedHiddenDuringCapture() = LabRun.measure("SelfRotationTrace.recreatedHiddenDuringCapture") { capture(it, "self-hidden-capture", keep = false, finishing = false) }
    @Test fun recreatedHiddenDuringStopWait() = LabRun.measure("SelfRotationTrace.recreatedHiddenDuringStopWait") { capture(it, "self-hidden-finishing", keep = false, finishing = true) }

    private fun capture(lab: File, case: String, keep: Boolean, finishing: Boolean) {
        val seen = SelfRotationProbe(InstrumentationRegistry.getInstrumentation()).probe(case, keep, finishing)
        File(lab, "issue56").mkdirs()
        File(lab, "issue56/probe-${seen.case}-${seen.run}.json").writeText(seen.summary.toString(2))
        check(seen.traceComplete) { "trace incomplete" }
    }
}
