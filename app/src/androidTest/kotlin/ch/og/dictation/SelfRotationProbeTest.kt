package ch.og.dictation

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #56, the assertions: after a self-rotating editor turned the phone while our panel recorded or
 * finished, the rotation trace is whole and FUTO is the selected keyboard again. What the trace
 * showed is measured by the Lab run labrun.SelfRotationTrace.
 */
@RunWith(AndroidJUnit4::class)
class SelfRotationProbeTest {
    private val probe = SelfRotationProbe(InstrumentationRegistry.getInstrumentation())

    @Test fun keepFocusDuringCapture() = holds(probe.probe("self-keep-capture", keep = true, finishing = false))
    @Test fun keepFocusDuringStopWait() = holds(probe.probe("self-keep-finishing", keep = true, finishing = true))
    @Test fun recreatedHiddenDuringCapture() = holds(probe.probe("self-hidden-capture", keep = false, finishing = false))
    @Test fun recreatedHiddenDuringStopWait() = holds(probe.probe("self-hidden-finishing", keep = false, finishing = true))

    private fun holds(seen: SelfRotationProbe.Observation) {
        assertTrue("trace incomplete", seen.traceComplete)
        assertTrue("keyboard not restored to FUTO (${seen.selection})", seen.futoRestored)
    }
}
