package ch.og.dictation

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The owner operates the host and any other app; this test does not automate gestures. */
@RunWith(AndroidJUnit4::class)
class RotationProbeTest {
    @Test fun observeUntilOwnerFinishes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        RotationTrace.beginProbe()
        RotationProbeActivity.done = CountDownLatch(1)
        try {
            instrumentation.startActivitySync(Intent(context, RotationProbeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            assertTrue("owner did not finish probe within five minutes",
                RotationProbeActivity.done.await(5, TimeUnit.MINUTES))
            assertTrue("view teardown or take terminal acknowledgement missing; trace unavailable or full",
                RotationTrace.finishProbe(10_000))
        } finally {
            RotationTrace.abortProbe()
        }
    }
}
