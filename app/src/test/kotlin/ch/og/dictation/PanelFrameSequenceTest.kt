package ch.og.dictation

import ch.og.dictation.panel.GoldenFrames
import ch.og.dictation.take.ROTATION_HOLD_GAP_MS
import ch.og.dictation.take.ROTATION_SETTLE_MS
import android.view.Surface
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/**
 * Golden panel-frame sequences (#73, #77): each scripted take scenario runs through the real
 * service and must show exactly the frames of its golden file, in order.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PanelFrameSequenceTest {
    private val bench = TakeBench()

    @After fun tearDown() = bench.close()

    private fun scenario(name: String, script: TakeBench.() -> Unit) {
        bench.script()
        bench.close()
        GoldenFrames.check(name, bench.lines)
    }

    @Test fun ordinaryStop() = scenario("ordinary-stop") {
        step("open on a text field") { open() }
        step("speech") { speak() }
        step("Stop") { press(R.id.microphone) }
        step("microphone released") { releaseMicrophone() }
        step("text prepared") { finish() }
        assertEquals(1, commits())
    }

    @Test fun rotateWhileRecording() = scenario("rotate-while-recording") {
        step("open on a text field") { open() }
        step("speech") { speak() }
        step("rotate to landscape") { rotate(Surface.ROTATION_90) }
        step("rotation settled") { advance(ROTATION_HOLD_GAP_MS * 2) }
        step("more speech") { speak(second = true) }
        step("rotate back to portrait") { rotate(Surface.ROTATION_0) }
        step("rotation settled") { advance(ROTATION_HOLD_GAP_MS * 2) }
        step("Stop") { press(R.id.microphone) }
        step("microphone released") { releaseMicrophone() }
        step("text prepared") { finish() }
        assertEquals(1, commits())
    }

    @Test fun stopThenRotate() = scenario("stop-then-rotate") {
        step("open on a text field") { open() }
        step("speech") { speak() }
        step("Stop") { press(R.id.microphone) }
        step("rotate to landscape") { rotate(Surface.ROTATION_90) }
        step("microphone released") { releaseMicrophone() }
        step("text prepared") { finish() }
        step("rotation settled") { advance(ROTATION_SETTLE_MS); finish() }
        assertEquals(1, commits())
    }

    @Test fun back() = scenario("back") {
        step("open on a text field") { open() }
        step("speech") { speak() }
        step("Back arrow") { press(R.id.back) }
        step("Back key") { systemBack() }
        step("Back key") { systemBack() }
        step("Back arrow") { press(R.id.back) }
        step("more speech") { speak(second = true) }
        step("Stop") { press(R.id.microphone) }
        step("microphone released") { releaseMicrophone() }
        step("text prepared") { finish() }
        step("Back arrow on the idle panel") { press(R.id.back) }
        assertEquals(1, commits())
    }

    @Test fun leaveTheField() = scenario("leave-the-field") {
        step("open on a text field") { open() }
        step("speech") { speak() }
        step("tap another field") { leaveTheField() }
        step("microphone released") { releaseMicrophone() }
        step("text prepared") { finish() }
        assertEquals(0, commits())
    }

    @Test fun lock() = scenario("lock") {
        step("open on a text field") { open() }
        step("speech") { speak() }
        step("screen off") { lock() }
        step("microphone released") { releaseMicrophone() }
        step("text prepared") { finish() }
        step("unlock") { unlock(); finish() }
        assertEquals(0, commits())
    }

    @Test fun cancelChoiceStop() = scenario("cancel-choice-stop") {
        step("open on a text field") { open() }
        step("speech") { speak() }
        step("Back arrow") { press(R.id.back) }
        step("Stop and insert") { press(R.id.choice_stop) }
        step("microphone released") { releaseMicrophone() }
        step("text prepared") { finish() }
        assertEquals(1, commits())
    }

    @Test fun cancelChoiceDiscard() = scenario("cancel-choice-discard") {
        step("open on a text field") { open() }
        step("speech") { speak() }
        step("Back arrow") { press(R.id.back) }
        step("Discard take") { press(R.id.choice_discard) }
        step("microphone released") { releaseMicrophone() }
        step("late output") { finish() }
        assertEquals(0, commits())
    }

    @Test fun finishingChoiceKeep() = scenario("finishing-choice-keep") {
        step("open on a text field") { open() }
        step("speech") { speak() }
        step("Stop") { press(R.id.microphone) }
        step("microphone released") { releaseMicrophone() }
        step("Back arrow") { press(R.id.back) }
        step("text prepared") { finish() }
        assertEquals(0, commits())
        step("Keep preparing text") { press(R.id.choice_safe); finish() }
        assertEquals(1, commits())
    }

    @Test fun finishingChoiceDiscard() = scenario("finishing-choice-discard") {
        step("open on a text field") { open() }
        step("speech") { speak() }
        step("Stop") { press(R.id.microphone) }
        step("microphone released") { releaseMicrophone() }
        step("Back arrow") { press(R.id.back) }
        step("text prepared") { finish() }
        step("Discard take") { press(R.id.choice_discard); finish() }
        assertEquals(0, commits())
    }

    @Test fun heldTextBackTwice() = scenario("held-text-back-twice") {
        accepts = false
        step("open on a text field that refuses the write") { open() }
        step("speech") { speak() }
        step("Stop") { press(R.id.microphone) }
        step("microphone released") { releaseMicrophone() }
        step("text prepared") { finish() }
        copy("copied earlier")
        step("rotate to landscape") { rotate(Surface.ROTATION_90) }
        step("Back arrow") { press(R.id.back) }
        step("Back arrow") { press(R.id.back) }
        assertEquals(1, commits())
        assertEquals("Back never copies", "copied earlier", copied())
    }

    @Test fun heldTextCopy() = scenario("held-text-copy") {
        accepts = false
        step("open on a text field that refuses the write") { open() }
        step("speech") { speak() }
        step("Stop") { press(R.id.microphone) }
        step("microphone released") { releaseMicrophone() }
        step("text prepared") { finish() }
        copy("copied earlier")
        step("Copy text") { press(R.id.choice_safe) }
        assertEquals(1, commits())
        assertEquals("Copy puts exactly the take's text on the build's clipboard", "first words", copied())
    }

    @Test fun privateField() = scenario("private-field") {
        step("open on a password field") { open(editor(privateField = true)) }
        step("speech") { speak() }
        step("Back arrow") { press(R.id.back) }
        step("Continue recording") { press(R.id.choice_safe) }
        step("Stop") { press(R.id.microphone) }
        step("microphone released") { releaseMicrophone() }
        step("text prepared") { finish() }
        assertEquals(1, commits())
    }

    @Test fun privateFieldHeldText() = scenario("private-field-held-text") {
        accepts = false
        step("open on a password field that refuses the write") { open(editor(privateField = true)) }
        step("speech") { speak() }
        step("Stop") { press(R.id.microphone) }
        step("microphone released") { releaseMicrophone() }
        step("text prepared") { finish() }
        copy("copied earlier")
        step("Discard text") { press(R.id.choice_discard) }
        assertEquals(1, commits())
        assertEquals("Discard text never copies", "copied earlier", copied())
    }
}
