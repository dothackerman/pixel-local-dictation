package ch.og.dictation

import org.json.JSONArray
import org.json.JSONObject

/** Owner's answer per expected outcome. Anything not answered stays "not observed". */
enum class Answer(val code: Char, val word: String, val label: String) {
    PASSED('P', "passed", "Passed"), FAILED('F', "failed", "Failed"), NOT_OBSERVED('N', "not observed", "Not observed");
    companion object { fun of(code: Char) = entries.firstOrNull { it.code == code } ?: NOT_OBSERVED }
}

enum class Outcome { DONE, SKIPPED, RETRY, END, INTERRUPTED }

/** What a step screen offers besides text: a way into the real Settings, or a private-field editor. */
enum class Aid { NONE, SETTINGS, NO_LEARNING_FIELD, PASSWORD_FIELD }

data class Expectation(val id: String, val text: String)

/** One screen of the owner visit. [traceCase] names the sanitized rotation trace for the step, if any. */
data class VisitStep(
    val id: String, val title: String, val where: String, val doItems: List<String>, val expect: List<String>,
    val traceCase: String? = null, val traceMinutes: Int = 6, val aid: Aid = Aid.NONE,
) {
    val expectations: List<Expectation> get() = expect.mapIndexed { i, text -> Expectation("$id.${i + 1}", text) }
    val privacyCheck: Boolean get() = aid == Aid.NO_LEARNING_FIELD || aid == Aid.PASSWORD_FIELD
}

data class StepResult(val outcome: Outcome, val answers: List<Answer> = emptyList()) {
    /** Stored in [RotationProbeActivity.result]: "done:PFN" (one letter per expectation) or the bare outcome. */
    fun encode(): String = if (outcome == Outcome.DONE) "done:" + answers.joinToString("") { it.code.toString() } else outcome.name.lowercase()

    companion object {
        fun parse(step: VisitStep, text: String): StepResult {
            val outcome = Outcome.entries.firstOrNull { it.name.lowercase() == text.substringBefore(':') } ?: return StepResult(Outcome.INTERRUPTED)
            if (outcome != Outcome.DONE) return StepResult(outcome)
            val letters = text.substringAfter(':', "")
            return StepResult(outcome, List(step.expect.size) { Answer.of(letters.getOrElse(it) { 'N' }) })
        }
    }
}

/** Which step is next: Retry repeats, Skip and Done advance, End and a missing answer stop the visit. */
class GuidedVisitProgress(private val steps: List<VisitStep>, start: Int) {
    var index = start
        private set
    var finished = start >= steps.size
        private set
    val current: VisitStep? get() = steps.getOrNull(index).takeUnless { finished }

    fun record(outcome: Outcome) {
        when (outcome) {
            Outcome.RETRY -> Unit
            Outcome.DONE, Outcome.SKIPPED -> { index++; finished = index >= steps.size }
            Outcome.END, Outcome.INTERRUPTED -> finished = true
        }
    }
}

object GuidedVisit {
    private const val SIGNAL = "Do this in an unsent Signal draft with non-sensitive speech. Come back here through Recents."
    private const val APP = "Do this on the screen below. Hide the keyboard before you answer."
    private const val SETTINGS = "Open Settings from the button below; return here through Recents."
    private const val MIC = "Tap FUTO's microphone key."
    private const val BACK_ARROW = "Tap the back arrow (Cancel) on our panel."

    val script = listOf(
        VisitStep("A1", "Ordinary Stop", SIGNAL,
            listOf("Tap the message field in the Signal draft.", MIC, "Say one sentence.", "Tap Stop."),
            listOf("The sentence appears once in the draft.", "FUTO's keyboard is back.")),
        VisitStep("A2", "Rotate while recording", SIGNAL,
            listOf(MIC, "Say \"one\".", "Rotate to landscape.", "Say \"two\".", "Rotate back to portrait.", "Say \"three\".", "Tap Stop."),
            listOf("After each rotation our panel comes back and is still recording.", "\"One two three\" appears once in the draft.", "FUTO's keyboard is back."),
            traceCase = "signal-capture-rotation", traceMinutes = 8),
        VisitStep("A3", "Stop, then rotate", SIGNAL,
            listOf(MIC, "Speak for about 10 seconds.", "Tap Stop.", "Rotate the phone right away."),
            listOf("Our panel comes back showing Finishing.", "The text appears once in the draft.", "FUTO's keyboard is back."),
            traceCase = "signal-finishing-rotation", traceMinutes = 8),
        VisitStep("A4", "Hide the keyboard", SIGNAL,
            listOf(MIC, "Say a sentence.", "Hide the keyboard with the system Back."),
            listOf("The microphone stops at once.", "Nothing is inserted.")),
        VisitStep("A5", "Tap another field", SIGNAL,
            listOf(MIC, "Say a sentence.", "Tap Signal's search field."),
            listOf("The microphone stops.", "Nothing is inserted.")),
        VisitStep("A6", "Lock and unlock", SIGNAL,
            listOf(MIC, "Say a sentence.", "Lock the phone.", "Unlock it."),
            listOf("The microphone is stopped.", "Recording does not resume.")),
        VisitStep("A7", "Cancel while recording", SIGNAL,
            listOf("Take 1: $MIC", "Say a sentence.", BACK_ARROW, "Choose Continue recording.", "Say more, then tap Stop.",
                "Take 2: $MIC", "Say a sentence.", BACK_ARROW, "Choose Finish transcription.",
                "Take 3: $MIC", "Say a sentence.", BACK_ARROW, "Choose Discard."),
            listOf("Continue: the take goes on, and Stop inserts the text.", "Finish: the text is inserted once.", "Discard: nothing is inserted."),
            traceCase = "signal-cancel-recording", traceMinutes = 12),
        VisitStep("A8", "Cancel during the Stop wait", SIGNAL,
            listOf("Take 1: $MIC", "Speak for about 10 seconds.", "Tap Stop.", "While it says Finishing, tap the back arrow (Cancel).", "Choose Keep finishing.",
                "Take 2: $MIC", "Speak for about 10 seconds.", "Tap Stop.", "While it says Finishing, tap the back arrow (Cancel).", "Choose Discard."),
            listOf("Keep finishing: the text is inserted once.", "Discard: nothing is inserted."),
            traceCase = "signal-cancel-finishing", traceMinutes = 12),
        VisitStep("B9", "History shows the right statuses", SETTINGS,
            listOf("Tap Open Settings below.", "Tap History.", "Look at the takes from steps A1 to A8."),
            listOf("Takes that inserted text (A1, A2, A3, A7 Continue and Finish, A8 Keep finishing) show as inserted.",
                "The takes from A4, A5 and A6 show \"Not inserted\".", "The two Discard takes are not listed."),
            aid = Aid.SETTINGS),
        VisitStep("B10", "Copy and delete", SETTINGS,
            listOf("Tap Open Settings below, then History.", "Open one take and tap Copy.", "Go back.", "Delete one take.", "Tap Undo.",
                "Delete another take.", "Do not tap Undo. Wait 8 seconds."),
            listOf("Copy confirms that it copied.", "With Undo, the take comes back.", "Without Undo, the take is gone after 8 seconds."),
            aid = Aid.SETTINGS),
        VisitStep("B11", "Clear History", SIGNAL,
            listOf("Open Settings from the button below.", "Tap History.", "Tap Clear History.",
                "Read the dialog, then confirm.", "Make one new take in Signal and tap Stop.", "Open History again."),
            listOf("The dialog names the number of saved transcripts.", "History is empty right after the clear.", "The new take is listed after the clear."),
            aid = Aid.SETTINGS),
        VisitStep("C12a", "No-learning field", APP,
            listOf("Tap the field below.", MIC, "Say a harmless phrase.", "Tap Stop."),
            listOf("The text is inserted into the field.", "The panel showed \"History off for this field\"."),
            aid = Aid.NO_LEARNING_FIELD),
        VisitStep("C12b", "Password field", APP,
            listOf("Tap the field below.", MIC, "Say a harmless phrase.", "Tap Stop."),
            listOf("The text is inserted into the field.", "The panel showed \"History off for this field\"."),
            aid = Aid.PASSWORD_FIELD),
    )

    /** The default run: one traced rotation step. */
    val defaultScript = script.filter { it.id == "A2" }

    fun row(visit: String, run: String, step: VisitStep, result: StepResult, traceComplete: Boolean): JSONObject {
        val answers = JSONArray()
        if (result.outcome == Outcome.DONE) step.expectations.forEachIndexed { i, e ->
            answers.put(JSONObject().put("id", e.id).put("text", e.text).put("answer", result.answers.getOrElse(i) { Answer.NOT_OBSERVED }.word))
        }
        return JSONObject().put("visit", visit).put("run", run).put("step", step.id).put("title", step.title)
            .put("case", step.traceCase ?: step.id).put("observation", result.outcome.name.lowercase())
            .put("answers", answers).put("traceComplete", traceComplete)
    }
}
