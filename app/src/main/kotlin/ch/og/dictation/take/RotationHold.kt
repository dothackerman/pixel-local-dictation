package ch.og.dictation.take

/**
 * The rotation hold (ADR-010): everything a rotation decided for the current take, in one place.
 * The take controller owns the one instance, asks it what a rotation means for an editor event and
 * tells it what happened; what happens to the take is the controller's decision. The three timers
 * (gap, settle, panel request) run on the take scheduler and come back to the controller.
 *
 * - **Hold**: authority revoked, preview hidden, capture continues until the panel returns.
 * - **Gap**: from the first hold of a rotation, [ROTATION_HOLD_GAP_MS] long, once, never extended.
 * - **Settle**: the returned panel stayed visible for [ROTATION_SETTLE_MS]; the rotation is over.
 * - **Field judgement**: an input start inside the hold that is not the take's field withholds
 *   authority (mismatch); the take's own field starting again inside the hold returns it; a hold
 *   that settled on a mismatch is final.
 * - **Authority move**: Stop's authority, revoked by the hold, moves to the session the panel returns to.
 *
 * The rotation count is the service's ([RotationCounter]); it is only compared here, never read anew.
 */
internal class RotationHold(
    private val scheduler: TakeScheduler,
    private val rotations: RotationCounter,
    /** The gap ended; runs only when [inEpisode] could still be true (the controller checks). */
    private val gapExpired: Runnable,
    /** The panel may have settled; the controller checks that it is still visible on the take's session. */
    private val panelSettled: Runnable,
    /** Asks for the panel, after what is queued now. */
    private val panelRequest: Runnable,
) {
    /** The rotation count when the panel was last visible and settled; a higher count identifies a rotation since. */
    private var rotationsWhenVisible = 0L
    /** Authority revoked, preview hidden, capture continues until the panel returns. */
    var holding = false
        private set
    /** From the first hold of a rotation until the panel has settled; one gap for all of it. */
    var inEpisode = false
        private set
    /** Stop granted authority and a hold revoked it: it moves to the session the panel returns to. */
    private var authorityMoves = false
    /**
     * A hold's input start was not the take's field (same app): the take keeps recording but has no
     * authority, and Stop writes nothing (the text is held).
     */
    var fieldMismatch = false
        private set
    /** The hold settled on a mismatch: it is final, the take's field never regains authority. */
    private var mismatchFinal = false
    private var gapExpiredPosted: TakeScheduler.Cancellable? = null
    private var panelSettledPosted: TakeScheduler.Cancellable? = null
    private var panelRequestPosted: TakeScheduler.Cancellable? = null

    /** What an input start inside a hold did to the take's authority ([judge]). */
    enum class Judgement { UNCHANGED, WITHHELD, RETURNED }

    /**
     * A rotation since the panel was last visible, or the take is without authority for another field.
     * The take's field back inside the hold stays identified by the count alone: a judgement happens
     * only inside a hold, a hold only on an identified rotation, and the count is compared with the
     * same [rotationsWhenVisible] until the panel settles.
     */
    fun identified(event: EditorEvent? = null): Boolean {
        if (rotations.changes > rotationsWhenVisible) return true
        if (!fieldMismatch) return false
        if (!mismatchFinal) return true // The hold is still open: the editor is still reacting to the rotation.
        // Settled on a mismatch: a start is still judged, but a plain hide is not a rotation (ADR-010: no timer after it).
        return when (event) {
            EditorEvent.INPUT_VIEW_FINISH, EditorEvent.INPUT_FINISH, EditorEvent.WINDOW_HIDDEN -> holding
            else -> true
        }
    }

    /** A take starts with its panel visible: only rotations counted after now are its own, and nothing is left of an earlier one. */
    fun takeStarted() {
        rotationsWhenVisible = rotations.changes
        forget()
    }

    /**
     * An input started inside a hold of the take; [same]: it is the take's own field. Only that field
     * keeps the take's authority. [stopWait]: a Stop pressed meanwhile gets its authority back with
     * the panel on the returned field's session.
     */
    fun judge(same: Boolean, stopWait: Boolean): Judgement = when {
        !same && !fieldMismatch -> { fieldMismatch = true; Judgement.WITHHELD }
        same && fieldMismatch && !mismatchFinal -> {
            fieldMismatch = false
            if (stopWait) authorityMoves = true
            Judgement.RETURNED
        }
        else -> Judgement.UNCHANGED
    }

    /** The hold revokes authority; [granted]: Stop had granted it, so it moves with the panel. */
    fun authorityRevoked(granted: Boolean) {
        if (granted) authorityMoves = true
    }

    /** Stop's authority goes to the session the panel returned to: a hold revoked it and the field is the take's. */
    val authorityReturnsWithPanel: Boolean get() = authorityMoves && !fieldMismatch

    /** Stop grants authority now, unless the take is without it for another field; nothing is left to move. */
    fun stopGrantsAuthority(): Boolean {
        authorityMoves = false
        return !fieldMismatch
    }

    /** Enters (or stays in) the hold; true when it was entered now. */
    fun enter(): Boolean {
        panelSettledPosted?.cancel()
        if (holding) return false
        holding = true
        return true
    }

    /** The gap starts with the first hold of a rotation, once, and is never extended. */
    fun startGapOnce() {
        if (inEpisode) return
        inEpisode = true
        gapExpiredPosted = scheduler.postDelayed(ROTATION_HOLD_GAP_MS, gapExpired)
    }

    /** One panel request is pending at a time. */
    fun askForPanel() {
        panelRequestPosted?.cancel()
        panelRequestPosted = scheduler.postDelayed(0, panelRequest)
    }

    /** The panel is visible on the live session again; true when that ended a hold. */
    fun panelReturned(): Boolean {
        if (!holding) return false
        holding = false
        return true
    }

    /** The visible panel settles [ROTATION_SETTLE_MS] from now, unless something hides it first. */
    fun awaitSettle() {
        panelSettledPosted?.cancel()
        panelSettledPosted = scheduler.postDelayed(ROTATION_SETTLE_MS, panelSettled)
    }

    /** The panel stayed visible after the rotation: later editor events are judged without it. */
    fun settle() {
        end()
        rotationsWhenVisible = rotations.changes
        if (fieldMismatch) mismatchFinal = true
        authorityMoves = false
    }

    /** Ends hold, gap and settling without deciding anything about the take. */
    fun end() {
        holding = false
        inEpisode = false
        gapExpiredPosted?.cancel()
        panelSettledPosted?.cancel()
    }

    /** Take start, end or interruption: ends hold, gap and settling, and forgets the moving authority and the field judgement. */
    fun forget() {
        end()
        fieldMismatch = false
        mismatchFinal = false
        authorityMoves = false
    }
}
