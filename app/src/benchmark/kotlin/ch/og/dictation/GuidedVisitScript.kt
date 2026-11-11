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
    private const val FIELD = "appears in the message field, not duplicated."
    /** Every Signal take starts with its step, so History (B9) can be checked take by take. */
    private fun say(step: String, rest: String = "then one sentence") = "Say \"step $step\", $rest."

    /**
     * #61 re-visit changes: A4 is system Back (it opens the choice; a real hide has no separate
     * control in Signal: the navigation bar's keyboard-down chevron sends the same Back), C12b
     * (password field) is gone because FUTO shows no microphone key there; the password path is
     * covered by host tests and the automated privacy probe.
     */
    val script = listOf(
        VisitStep("A1", "Ordinary Stop", SIGNAL,
            listOf("Tap the message field in the Signal draft.", MIC, say("A1"), "Tap Stop."),
            listOf("The sentence $FIELD", "FUTO's keyboard is back.")),
        VisitStep("A2", "Rotate while recording", SIGNAL,
            listOf(MIC, say("A2", "then \"one\""), "Rotate to landscape.", "Say \"two\".", "Rotate back to portrait.", "Say \"three\".", "Tap Stop."),
            listOf("After each rotation our panel comes back and is still recording.", "\"Step A2 one two three\" $FIELD",
                "The panel never shows \"History off for this field\".", "FUTO's keyboard is back."),
            traceCase = "signal-capture-rotation", traceMinutes = 8),
        VisitStep("A3", "Stop, then rotate", SIGNAL,
            listOf(MIC, say("A3", "then speak for about 10 seconds"), "Tap Stop.", "Rotate the phone right away."),
            listOf("Our panel comes back showing Finishing.", "The text $FIELD", "FUTO's keyboard is back."),
            traceCase = "signal-finishing-rotation", traceMinutes = 8),
        VisitStep("A4", "System Back while recording", SIGNAL,
            listOf(MIC, say("A4"), "Press system Back (or the keyboard-down arrow in the navigation bar).",
                "Choose Continue recording.", "Say one more sentence, then tap Stop."),
            listOf("The Continue / Stop / Discard choice opens in our panel and it says Paused.",
                "Continue: recording goes on, and Stop inserts the text.", "The text $FIELD")),
        VisitStep("A5", "Leave the field", SIGNAL,
            listOf(MIC, say("A5"), "Tap Signal's search field, or leave Signal with Home."),
            listOf("The microphone stops.", "Nothing is inserted.")),
        VisitStep("A6", "Lock and unlock", SIGNAL,
            listOf(MIC, say("A6"), "Lock the phone.", "Unlock it."),
            listOf("The microphone is stopped.", "Recording does not resume.")),
        VisitStep("A7", "Cancel while recording", SIGNAL,
            listOf("Take 1: $MIC", say("A7 continue"), BACK_ARROW, "Look at the status bar's microphone dot.", "Choose Continue recording.", "Say more, then tap Stop.",
                "Take 2: $MIC", say("A7 stop"), BACK_ARROW, "Choose Stop and insert.",
                "Take 3: $MIC", say("A7 discard"), BACK_ARROW, "Choose Discard take."),
            listOf("While the choice is open the panel says Paused and the microphone dot goes away.",
                "Continue: the take goes on, and Stop inserts the text.", "Stop and insert: the text $FIELD",
                "Stop and insert: FUTO's keyboard is back.", "Discard: nothing is inserted."),
            traceCase = "signal-cancel-recording", traceMinutes = 12),
        VisitStep("A8", "Cancel during the Stop wait", SIGNAL,
            listOf("Take 1: $MIC", say("A8 keep", "then speak for about 10 seconds"), "Tap Stop.", "While it says Finishing, tap the back arrow (Cancel).", "Choose Keep finishing.",
                "Take 2: $MIC", say("A8 discard", "then speak for about 10 seconds"), "Tap Stop.", "While it says Finishing, tap the back arrow (Cancel).", "Choose Discard take."),
            listOf("Keep finishing: the text $FIELD", "Discard: nothing is inserted."),
            traceCase = "signal-cancel-finishing", traceMinutes = 12),
        VisitStep("B9", "History shows the right statuses", SETTINGS,
            listOf("Tap Open Settings below.", "Tap History.", "Find each take by the step it starts with."),
            listOf("Takes starting with step A1, A2, A3, A4, A7 continue, A7 stop and A8 keep show no status (inserted).",
                "Takes starting with step A5 and A6 show \"Not inserted\".", "No take starts with step A7 discard or A8 discard."),
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
        VisitStep("C12", "No-learning field", APP,
            listOf("Tap the field below.", MIC, "Say a harmless phrase.", "Tap Stop."),
            listOf("The text is inserted into the field.", "The panel showed \"History off for this field\"."),
            aid = Aid.NO_LEARNING_FIELD),
    )

    /** The default run: one traced rotation step. */
    val defaultScript = script.filter { it.id == "A2" }

    /**
     * The steps a run shows: [steps] (comma-separated ids, kept in script order) or the whole
     * script ([extended]) or the default; then from [start] on. Unknown ids are refused.
     */
    fun select(steps: String?, start: String?, extended: Boolean): List<VisitStep> {
        val chosen = steps?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
        chosen?.forEach { id -> require(script.any { it.id == id }) { "unknown step $id" } }
        val base = when {
            chosen != null -> script.filter { it.id in chosen }
            extended || start != null -> script
            else -> defaultScript
        }
        if (start == null) return base
        require(script.any { it.id == start }) { "unknown step $start" }
        val from = script.indexOfFirst { it.id == start }
        return base.filter { script.indexOf(it) >= from }
    }

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
