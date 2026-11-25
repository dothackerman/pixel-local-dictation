package ch.og.dictation.panel

/** Every take state the panel shows, each also in a private field; one list for the panel tests and the gallery. */
object PanelStates {
    fun all(words: String, heldWords: String): List<Pair<String, PanelState>> = listOf(
        "opening" to PanelState(PanelPhase.OPENING, running = true),
        "recording" to PanelState(PanelPhase.RECORDING, running = true, preview = words, ringLive = true),
        "paused" to PanelState(PanelPhase.PAUSED, PanelChoice.RECORDING, running = true, preview = words),
        "preparing" to PanelState(PanelPhase.FINISHING, running = true, finishing = true, preview = words),
        "finishing-choice" to PanelState(PanelPhase.FINISHING_CHOICE, PanelChoice.FINISHING, running = true, finishing = true),
        "stopping" to PanelState(PanelPhase.STOPPING),
        "release-failed" to PanelState(PanelPhase.RELEASE_FAILED),
        "idle" to PanelState(PanelPhase.IDLE),
        "no-permission" to PanelState(PanelPhase.NO_PERMISSION),
        "failed" to PanelState(PanelPhase.FAILED),
        "not-inserted" to PanelState(PanelPhase.NOT_INSERTED),
        "not-inserted-waiting" to PanelState(PanelPhase.NOT_INSERTED, awaitingOutcome = true),
        "idle-waiting" to PanelState(PanelPhase.IDLE, awaitingOutcome = true),
        "held" to PanelState(PanelPhase.NOT_INSERTED, held = HeldPanel(inHistory = true), preview = heldWords),
        "held-unsaved" to PanelState(PanelPhase.NOT_INSERTED, held = HeldPanel(inHistory = false), preview = heldWords),
        "held-uncertain" to PanelState(PanelPhase.NOT_INSERTED, held = HeldPanel(true, unconfirmed = true), preview = heldWords),
        "held-warned" to PanelState(PanelPhase.NOT_INSERTED, held = HeldPanel(true, warned = true), preview = heldWords),
    ).flatMap { (name, state) -> listOf(name to state, "$name-private" to state.copy(privateField = true)) }
}
