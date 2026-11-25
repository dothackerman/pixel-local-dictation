package ch.og.dictation

import ch.og.dictation.take.ROTATION_HOLD_GAP_MS
import android.view.Surface
import android.view.View
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/**
 * Read-back guard (ADR-010, #84): a write on a take that went through a rotation hold and was not
 * seen in the field at once is looked at once more, 100 ms later and not a millisecond earlier.
 * Until that look the panel decides nothing and the microphone stays off.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReadBackSecondLookTest {
    private val bench = TakeBench()

    @After fun tearDown() = bench.close()

    /** A take through a rotation hold whose write the editor answers without showing it. */
    private fun writeNotSeenAtOnce() = with(bench) {
        step("open on a text field") { open() }
        step("speech") { speak() }
        step("rotate to landscape") { rotate(Surface.ROTATION_90) }
        step("rotation settled") { advance(ROTATION_HOLD_GAP_MS * 2) }
        shows = false
        step("Stop") { press(R.id.microphone) }
        step("microphone released") { releaseMicrophone() }
        step("text prepared") { finish() }
        assertEquals(1, commits())
    }

    private fun framesOf(step: String) = bench.lines.takeLastWhile { it != "> $step" }
        .also { assertTrue("step $step not run", "> $step" in bench.lines) }

    private fun microphoneEnabled() = bench.panel.findViewById<View>(R.id.microphone).isEnabled

    @Test fun aWriteThatNeverShowsIsHeldAsUnconfirmedAt100MsAndNotAt99() {
        writeNotSeenAtOnce()
        bench.step("99 ms") { advance(99) }
        assertEquals("nothing is decided before the second look", emptyList<String>(), framesOf("99 ms"))
        assertFalse(microphoneEnabled())
        assertEquals(View.GONE, bench.panel.findViewById<View>(R.id.choice).visibility)
        bench.step("100 ms") { advance(1) }
        assertTrue(framesOf("100 ms").last().startsWith("status_unconfirmed |"))
        assertEquals(View.VISIBLE, bench.panel.findViewById<View>(R.id.choice).visibility)
    }

    @Test fun aWriteThatShowsMeanwhileCountsAsInsertedAt100MsAndNotAt99() {
        writeNotSeenAtOnce()
        bench.shows = true
        bench.step("99 ms") { advance(99) }
        assertEquals("the field is not read before the second look", emptyList<String>(), framesOf("99 ms"))
        assertFalse(microphoneEnabled())
        bench.step("100 ms") { advance(1) }
        assertTrue(framesOf("100 ms").last().startsWith("status_idle | microphone on: start_recording |"))
        assertEquals(View.GONE, bench.panel.findViewById<View>(R.id.choice).visibility)
    }
}
