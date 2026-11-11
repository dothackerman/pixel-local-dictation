package ch.og.dictation

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.RadioButton
import android.widget.TextView
import java.util.concurrent.CountDownLatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class GuidedVisitScriptTest {
    @Test fun scriptFollowsTicketSixtyOneInOrder() {
        assertEquals(listOf("A1", "A2", "A3", "A4", "A5", "A6", "A7", "A8", "B9", "B10", "B11", "C12a", "C12b"),
            GuidedVisit.script.map { it.id })
        assertTrue(GuidedVisit.script.all { it.title.isNotBlank() && it.doItems.isNotEmpty() && it.expect.isNotEmpty() })
        assertEquals(GuidedVisit.script.flatMap { s -> s.expectations.map { it.id } }.size,
            GuidedVisit.script.flatMap { s -> s.expectations.map { it.id } }.toSet().size)
    }

    @Test fun signalStepsAreMarkedAndOnlyRotationAndControlStepsAreTraced() {
        val signal = GuidedVisit.script.filter { it.id.startsWith("A") }
        assertTrue(signal.all { it.where.contains("unsent Signal draft") && it.where.contains("Recents") })
        assertEquals(listOf("A2", "A3", "A7", "A8"), GuidedVisit.script.filter { it.traceCase != null }.map { it.id })
        assertTrue(GuidedVisit.script.mapNotNull { it.traceCase }.all { it in RotationTrace.allowedCases })
        assertTrue(GuidedVisit.script.filter { it.traceCase != null }.all { it.traceMinutes in 6..15 })
    }

    @Test fun resultsRoundTripAndUnknownTextIsNotObserved() {
        val a2 = GuidedVisit.script.first { it.id == "A2" }
        val result = StepResult(Outcome.DONE, listOf(Answer.PASSED, Answer.FAILED, Answer.NOT_OBSERVED))
        assertEquals("done:PFN", result.encode())
        assertEquals(result, StepResult.parse(a2, "done:PFN"))
        assertEquals(Outcome.SKIPPED, StepResult.parse(a2, "skipped").outcome)
        assertEquals(Outcome.INTERRUPTED, StepResult.parse(a2, "interrupted").outcome)
        assertEquals(Outcome.INTERRUPTED, StepResult.parse(a2, "garbage").outcome)
        assertEquals(List(3) { Answer.NOT_OBSERVED }, StepResult.parse(a2, "done:P").answers.drop(1) + Answer.NOT_OBSERVED)
    }

    @Test fun retryRepeatsSkipAdvancesEndAndInterruptionStop() {
        val p = GuidedVisitProgress(GuidedVisit.script, 1)
        assertEquals("A2", p.current?.id)
        p.record(Outcome.RETRY); assertEquals("A2", p.current?.id)
        p.record(Outcome.SKIPPED); assertEquals("A3", p.current?.id)
        p.record(Outcome.DONE); assertEquals("A4", p.current?.id)
        assertFalse(p.finished)
        p.record(Outcome.END); assertTrue(p.finished)
        val q = GuidedVisitProgress(GuidedVisit.script, 0)
        q.record(Outcome.INTERRUPTED); assertTrue(q.finished)
        val last = GuidedVisitProgress(GuidedVisit.script, GuidedVisit.script.size - 1)
        last.record(Outcome.DONE); assertTrue(last.finished); assertEquals(null, last.current)
    }

    @Test fun rowHoldsStepAnswersAndTraceStateWithoutFreeText() {
        val a2 = GuidedVisit.script.first { it.id == "A2" }
        val row = GuidedVisit.row("v", "r", a2, StepResult(Outcome.DONE, listOf(Answer.PASSED, Answer.FAILED, Answer.NOT_OBSERVED)), true)
        assertEquals("A2", row.getString("step"))
        assertEquals("signal-capture-rotation", row.getString("case"))
        assertEquals("done", row.getString("observation"))
        assertEquals(true, row.getBoolean("traceComplete"))
        val checks = row.getJSONArray("answers")
        assertEquals(listOf("passed", "failed", "not observed"), (0 until 3).map { checks.getJSONObject(it).getString("answer") })
        assertEquals(listOf("A2.1", "A2.2", "A2.3"), (0 until 3).map { checks.getJSONObject(it).getString("id") })
    }

    private fun screen(step: String, extra: Intent.() -> Unit = {}) = Robolectric.buildActivity(RotationProbeActivity::class.java,
        Intent().putExtra("step", step).putExtra("stepCount", GuidedVisit.script.size).apply(extra)).setup().get()

    private fun View.all(): List<View> = listOf(this) + ((this as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { g.getChildAt(it).all() } } ?: emptyList())
    private fun RotationProbeActivity.texts() = window.decorView.all().filterIsInstance<TextView>().map { it.text.toString() }
    private fun RotationProbeActivity.button(label: String) = window.decorView.all().filterIsInstance<Button>().first { it.text.toString() == label }

    @Test fun guideShowsTitleNumberedDoAndExpectWithNotObservedDefault() {
        val a2 = GuidedVisit.script.first { it.id == "A2" }
        val activity = screen("A2")
        val texts = activity.texts()
        assertTrue(texts.any { it.startsWith("Step 2 of 13") })
        assertTrue(texts.contains(a2.title))
        assertTrue(texts.containsAll(a2.doItems.mapIndexed { i, d -> "${i + 1}. $d" }))
        assertTrue(texts.contains("Do")); assertTrue(texts.contains("Expect"))
        assertTrue(texts.containsAll(a2.expect))
        assertTrue(texts.contains(a2.where))
        val radios = activity.window.decorView.all().filterIsInstance<RadioButton>()
        assertEquals(9, radios.size)
        assertEquals(listOf("A2.1:N", "A2.2:N", "A2.3:N"), a2.expectations.map { e ->
            e.id + ":" + radios.filter { it.tag.toString().startsWith(e.id + ":") }.single { it.isChecked }.tag.toString().substringAfter(":") })
    }

    @Test fun answersAreSavedAsDonePerExpectation() {
        RotationProbeActivity.done = CountDownLatch(1)
        val activity = screen("A2")
        val radios = activity.window.decorView.all().filterIsInstance<RadioButton>()
        radios.first { it.tag == "A2.1:P" }.performClick()
        radios.first { it.tag == "A2.2:F" }.performClick()
        activity.button("Save and next").performClick()
        assertEquals("done:PFN", RotationProbeActivity.result)
        assertTrue(activity.isFinishing)
    }

    @Test fun skipRetryAndEndAreOfferedAndReportTheirOutcome() {
        for ((label, value) in listOf("Skip step" to "skipped", "Retry step" to "retry", "End visit" to "end")) {
            RotationProbeActivity.done = CountDownLatch(1)
            val activity = screen("A5")
            activity.button(label).performClick()
            assertEquals(value, RotationProbeActivity.result)
        }
    }

    @Test fun everyTouchTargetIsAtLeast48dp() {
        val activity = screen("B9")
        val density = activity.resources.displayMetrics.density
        val targets = activity.window.decorView.all().filter { it is Button || it is RadioButton }
        assertTrue(targets.isNotEmpty())
        assertTrue(targets.all { it.minimumHeight >= 48 * density - 0.5f })
    }

    @Test fun screenStaysOnAndAnswersSurviveRecreation() {
        val controller = Robolectric.buildActivity(RotationProbeActivity::class.java,
            Intent().putExtra("step", "A2").putExtra("stepCount", 13)).setup()
        assertTrue(controller.get().window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
        controller.get().window.decorView.all().filterIsInstance<RadioButton>().first { it.tag == "A2.3:F" }.performClick()
        val recreated = controller.recreate().get()
        val checked = recreated.window.decorView.all().filterIsInstance<RadioButton>().first { it.tag == "A2.3:F" }
        assertTrue(checked.isChecked)
    }

    @Test fun settingsAidAndPrivateFieldsAppearOnlyOnTheirSteps() {
        assertNotNull(screen("B9").button("Open Settings"))
        val noLearning = screen("C12a")
        assertNotNull(noLearning.findViewById<View>(R.id.probe_no_learning))
        assertEquals(null, noLearning.findViewById<View>(R.id.probe_password))
        assertNotNull(screen("C12b").findViewById<View>(R.id.probe_password))
        assertEquals(null, screen("A1").findViewById<View>(R.id.probe_password))
    }

    @Test fun finalStepOffersFinish() {
        assertNotNull(screen("C12b").button("Save and finish"))
    }
}
