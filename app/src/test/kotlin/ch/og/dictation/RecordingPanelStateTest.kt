package ch.og.dictation

import android.animation.ValueAnimator
import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RecordingPanelStateTest {
    @Test fun finishingReplacesStopWithNoninteractiveProcessingAndExplicitStatus() {
        val view = LayoutInflater.from(RuntimeEnvironment.getApplication()).inflate(R.layout.ime, null)
        val panel = RecordingPanel(view)
        val take = panel.beginTake()
        panel.finishTake(take)
        assertEquals("Recording stopped · Finishing transcription…", view.findViewById<TextView>(R.id.status).text.toString())
        assertEquals(View.GONE, view.findViewById<ImageButton>(R.id.microphone).visibility)
        assertEquals(View.GONE, view.findViewById<View>(R.id.recording_ring).visibility)
        val processing = view.findViewById<View>(R.id.processing)
        assertEquals(View.VISIBLE, processing.visibility)
        assertFalse(processing.isClickable)
        assertTrue(view.findViewById<ImageButton>(R.id.back).isEnabled)
    }
    @Test fun recordingControlsReturnAfterProcessing() {
        val view = LayoutInflater.from(RuntimeEnvironment.getApplication()).inflate(R.layout.ime, null)
        val panel = RecordingPanel(view)
        val take = panel.beginTake()
        panel.finishTake(take)
        panel.cancelTake()
        panel.beginTake()
        assertEquals(View.VISIBLE, view.findViewById<ImageButton>(R.id.microphone).visibility)
        assertEquals(View.VISIBLE, view.findViewById<View>(R.id.recording_ring).visibility)
        assertEquals(View.GONE, view.findViewById<View>(R.id.processing).visibility)
    }

    @Test fun lateCaptureStartCannotReplaceInterruptionOrFinishing() {
        val view = LayoutInflater.from(RuntimeEnvironment.getApplication()).inflate(R.layout.ime, null)
        val panel = RecordingPanel(view)
        val take = panel.beginTake()
        panel.captureEnded(take)
        panel.captureStarted(take)
        assertEquals("Microphone stopped · tap to finish", view.findViewById<TextView>(R.id.status).text.toString())
        assertEquals("Finish transcription", view.findViewById<ImageButton>(R.id.microphone).contentDescription.toString())
        panel.finishTake(take)
        panel.captureStarted(take)
        assertEquals(View.VISIBLE, view.findViewById<View>(R.id.processing).visibility)
    }

    @Test fun cancelledAndObsoleteCallbacksCannotChangeNewTakeOrRecreatedView() {
        val context = RuntimeEnvironment.getApplication()
        val first = LayoutInflater.from(context).inflate(R.layout.ime, null)
        val panel = RecordingPanel(first)
        val old = panel.beginTake()
        panel.cancelTake()
        panel.captureStarted(old)
        val current = panel.beginTake()
        panel.captureStarted(current)
        assertFalse(panel.completeTake(old))
        val recreated = LayoutInflater.from(context).inflate(R.layout.ime, null)
        panel.bind(recreated)
        assertEquals("Recording…", recreated.findViewById<TextView>(R.id.status).text.toString())
        panel.finishTake(current)
        val finishingView = LayoutInflater.from(context).inflate(R.layout.ime, null)
        panel.bind(finishingView)
        assertEquals(View.VISIBLE, finishingView.findViewById<View>(R.id.processing).visibility)
        assertEquals(View.GONE, finishingView.findViewById<View>(R.id.microphone).visibility)
    }

    @Test fun reducedMotionKeepsAccessibleActionsAndDistinctStaticStates() {
        ReflectionHelpers.callStaticMethod<Void>(ValueAnimator::class.java, "setDurationScale", ClassParameter.from(Float::class.javaPrimitiveType, 0f))
        try {
            assertFalse(ValueAnimator.areAnimatorsEnabled())
            val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
            val view = LayoutInflater.from(activity).inflate(R.layout.ime, null)
            activity.setContentView(view)
            val panel = RecordingPanel(view)
            val take = panel.beginTake()
            panel.captureStarted(take)
            val mic = view.findViewById<ImageButton>(R.id.microphone)
            val back = view.findViewById<ImageButton>(R.id.back)
            val status = view.findViewById<TextView>(R.id.status)
            var discarded = false
            back.setOnClickListener { discarded = true }
            mic.setOnClickListener { panel.finishTake(take) }
            assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, status.accessibilityLiveRegion)
            assertEquals("Stop recording", mic.contentDescription.toString())
            assertTrue(mic.isEnabled && mic.isFocusable)
            assertEquals("Discard recording and return to keyboard", back.contentDescription.toString())
            assertTrue(mic.performClick())
            assertEquals(View.GONE, mic.visibility)
            assertTrue(back.isEnabled && back.isFocusable && back.requestFocus())
            assertTrue(back.performClick())
            assertTrue(discarded)
            assertFalse(view.findViewById<View>(R.id.processing).isClickable)
            assertEquals("Recording stopped · Finishing transcription…", status.text.toString())
        } finally {
            ReflectionHelpers.callStaticMethod<Void>(ValueAnimator::class.java, "setDurationScale", ClassParameter.from(Float::class.javaPrimitiveType, 1f))
        }
    }

}
