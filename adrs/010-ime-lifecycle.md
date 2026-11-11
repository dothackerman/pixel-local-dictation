# ADR-010 — Preview privately; commit once

2026-10-09–10 · Implemented; capture/storage behavior approved.

Use Android voice IME discovery through FUTO’s existing microphone key.
Locale-less subtype needs `overridesImplicitlyEnabledSubtype="true"` for FUTO 0.1.30.
Record on panel open; preview only in panel; Stop commits once.
Composing text was rejected: Android can finalize it before focus-change cancellation.
Discard on every input start, including restarting=true with identical attributes.
Tradeoff: an app restarting input loses the take; avoids committing into the wrong field.
Cancel/Keyboard/hide/lock discard; denied microphone permission handled.
Benchmark storage is opt-in, visible and deletable; copying needs per-run agreement.
Release/benchmark share signing; benchmark is debuggable; rollback retains FUTO’s model.
Evidence: tags m1/m2, commits 1853670/cd5a9be; the user verified airplane-mode operation.
Answered calls and longer battery-only model survival remain unverified.
Revisit lifecycle failures while preserving R1/R3/R6.

## Amendment — interruption recovery

2026-11-11 · Branch implementation for #36; host-tested, not Pixel-accepted.

Input restart, input loss and input-view hide revoke the originating editor's
insertion authority and stop capture. Accepted audio may finish into local History;
no recovered text is inserted automatically. Capture release precedes continued
decode and storage. A view reattachment does not start another take; the person
must explicitly start again. Service destruction is best-effort; process death
preserves only durable checkpoints. Screen-off and unexpected microphone end use
the same recovery path. Destination safety remains unchanged except for one
rotation continuity exception (below). An app-controlled Cancel requires confirmation. While the choice is open, recording
continues or a finishing take holds its result before insertion. A system
interruption overrides the choice and uses History recovery. A confirmed discard
revokes insertion and removes the take's checkpoints.
Eligible ordinary-field transcripts remain in no-backup History until deletion;
password, no-learning and unclassifiable editors retain neither History nor
benchmark content. Separately opted-in eligible benchmark Stop keeps its existing
storage contract; interruption salvage never uses it.

Evidence: take/output, recorder and production-service host regressions cover
interruption, release failure/retry, retained prefixes and delivery races. Synthetic
phone UI/process tests and sanitized rotation probes compile but have not been run
on the Pixel for this handoff. No continuous-rotation or recovery-window claim.

## Amendment — rotation continuity (#52)

2026-11-11 · Branch implementation for #36; host-tested and Pixel-measured (#56, #52).

One editor-event policy decides input start, input view start/finish, input finish,
window hidden and screen off: nothing, revoke authority, or interrupt (revoke and
stop capture). **Identification:** a rotation is identified only when the service
itself counted a display or configuration rotation since the panel was last
visible, at or before the event decided; rotating there and back counts. Package,
field ID and editor attributes are never used. "Visible" means visible on the
take's live session for `ROTATION_SETTLE_MS` (1 s): Android can show the panel
inside the configuration change and the editor hide it ~95 ms later.
**Rotation hold:** during capture, an identified rotation revokes authority, hides
the preview and keeps capture; the app requests its own panel when a new editor
starts input without showing it. It ends with the take continuing when the panel
is shown on a live session, or in History when `ROTATION_HOLD_GAP_MS` (3 s, start
value, never extended, one per rotation) ends, the screen turns off, the
microphone ends or capture is reported silenced. No hold without an identified
rotation; no timer after a plain hide. **Authority:** Stop grants it to the session
the visible panel is attached to; any session change after the press revokes it,
except an identified rotation (#53, below). Commit guards unchanged.
Retention stays latched from the starting editor and only tightens: at once for a
positively private editor seen during the hold (password variation, no-personalized-learning
flag), or when the take settles on an excluded editor (password, no-learning, unclassifiable:
the panel settled after the rotation, or Stop). An input start without an editor (null or
`TYPE_NULL`, as while an app recreates its activity) is not a private editor and never
tightens by itself (#61: Signal lost History eligibility on every rotation). The rotation
trace records the fixed class (ELIGIBLE, PASSWORD, NO_LEARNING, UNCLASSIFIABLE, NO_EDITOR) on
`INPUT_START_DONE`, nothing else about the editor.

**Stop wait (#53):** the hold also covers the Stop wait. An identified rotation
after the press revokes Stop's authority and holds the result before dispatch; the
authority moves to the session the panel returns to, and the held result is
released only once the panel has stayed visible there for `ROTATION_SETTLE_MS`
(Android's own reset panel can be hidden again ~95 ms later). It is inserted there
once and FUTO returns. A panel that does not return within the same gap, or any
other session change, sends the result to History only. A commit already
dispatched or accepted is never held, undone or repeated. **Cancel choices:** see
the #61 amendment (inline, take state; rotation never chooses).

Evidence: host lifecycle tests for both editor modes, the Pixel callback order,
gap expiry, real hide after rotation, repeated rotation, lock, focus change,
same-field restart, microphone loss and silencing. Pixel (#56 probe, 4 cases × 2
runs; #52 demo, 2 modes): rotation counted at CONFIGURATION +25–51 ms, before the
first interrupting event in every case; Android restarts input inside the
configuration change on every rotation, also with `resourcesUnused` declared
(not preventable from `method.xml`); service not destroyed by rotation. Panel
visible again +38–45 ms (kept editor) and, for the recreated editor, hidden at
+119–147 ms and back by our request at +160–178 ms (hidden ≤ 46 ms); drawn ≤ 183 ms.
Capture was never reported silenced (longest hidden interval 46 ms, so silencing
of a long-hidden client stays untested). Demo: one take, one capture, speech
before and after the rotation inserted once, FUTO restored, both modes. Gap 3 s and settle 1 s kept:
≥ 16× and ≥ 9× the measured times; one probe editor, one device.
#53 evidence: host lifecycle tests for rotation before the final decode, before the
History save acknowledgement, after the accepted commit, Stop on Android's reset
panel, the gap without a returning panel, a real hide after the settled panel, each
choice open (shown again; Continue, Finish, Discard, Keep, Discard), the unattachable
fallback, and lock over a moved choice. Pixel (`RotationFinishingDemoTest`, seven
cases, one run, synthetic speech; History-eligible cases write to an isolated test
History root): all completed with one take, one recorder start, exactly one
insertion into the field the panel returned to (recreated editor: the new field),
FUTO restored. Rotation before the final decode: panel back as Finishing (last
shown +151–253 ms), inserted when the decode ended (+1.8–1.9 s). Decoded with the
History save held: inserted +1.17 s, 1.0 s after the panel's last show (settle).
After the accepted commit: no further event. Recording choice: shown again, not
made; Continue kept the take. Finishing choice (before decode and after the save):
shown again, nothing dispatched under it, inserted once after Keep finishing. The
#56 probe's Stop-wait cases (room audio, empty result) now end completed instead of
in History. The unattachable-prompt fallback and lock over a moved choice are
host-only.
Limits: a hide or focus change within the settle time after a rotation cannot be
told apart from the editor reacting to it (held, then History at the gap); the
display callback can still report the old rotation (#56), so a 180° turn without a
configuration change is counted only at the next editor event.

## Amendment — inline Cancel choice and pause (#61)

2026-11-11 · Branch implementation for #36; host-tested, Pixel run pending.

The owner's Signal visit (A7) showed that closing the choice *dialog* made Signal restart
input within 5 ms of Stop (`STOP_REQUESTED` → `INPUT_VIEW_FINISH`/`INPUT_FINISH`/`INPUT_START`,
no rotation): the session change revoked Stop's authority and the text went to History only.
**The choice is now inline in the recording panel**, part of the take's state: no window, so
no focus change and no input restart is caused by us, and nothing to re-attach after a
rotation (the recreated panel renders the same choice; the #53 moved/pending prompt
machinery is gone). Actions and safe defaults are unchanged: recording — Continue recording
(default), Stop and insert, Discard take; Stop wait — Keep finishing (default), Discard take.
System Back or our Back arrow on an open choice is its safe default. Stop and insert is
exactly Stop: insert once where the take runs, then FUTO returns. An editor that restarts
input by itself right after Stop, without a rotation, is still a session change: History only.
**Pause:** while the recording choice is open the take is paused. Its platform recorder is
stopped (`AudioRecord.stop`; no audio captured, the microphone indicator goes off) but not
released, and the microphone stays owned by the take, so "one microphone" means never two at
once and none recording while paused. Continue restarts the same recorder inside the same
take (`AudioRecord.startRecording`): one take, speech before and after in the result. A
failed pause or restart is a system interruption (History). Pause and restart run on the IME
main thread (milliseconds). A system interruption (hide, lock, focus change, microphone loss,
gap) still overrides an open choice; audio captured before the pause finishes into History.
The finishing choice keeps the delivery hold as before. The rotation trace records
`CAPTURE_PAUSED`/`CAPTURE_RESUMED`. After a History-only end the panel clears the preview,
says "Not inserted · saved to History" (or why not) and offers Back to keyboard.
Evidence: host lifecycle tests (pause/resume, Back = safe default, Stop and insert, Discard,
the Signal order, self-restart after Stop, rotation with each choice open, lock and hide over
an open choice, History-only end, layout at font 2 portrait/landscape light/dark).


## Amendment — Undo after Discard take (#36)

The owner approved the Crate B2 direction's Undo window. **Discard take no longer discards at
once.** It opens an 8 s Undo window (`DISCARD_UNDO_MS`) in the panel: status "Take discarded",
a shrinking timer rule, a filled Undo button and Back; no microphone, so nothing ends the window
by accident. During the window nothing is inserted (the recording choice's take stays paused,
the Stop-wait choice keeps its delivery hold), the microphone does not record (the paused
recorder stays stopped and owned) and the take is not listed in History: its saved file is
deleted at once and its latest text stays in memory only (`TakeOutput.suspendHistory`).

**Undo** restores the same take at the choice it came from (Paused · choose, or Preparing text ·
choose): History saves its latest text again under a new id (the old id keeps its deletion
marker, so a late write can never revive it) and later checkpoints follow
(`resumeHistory`). **The discard becomes final** — exactly the pre-#36 discard: insertion
revoked, saved text removed, capture released, later decoder callbacks suppressed — when the
window ends (then the panel returns to the keyboard), on Back (system or arrow; also returns),
on any system interruption (hide, lock, focus change, microphone loss, the rotation gap
expiring, service destroyed: these never route a pending discard to History) or when the panel
is hidden. A rotation inside the window is not an interruption: the recreated panel renders the
same Undo state and the window keeps its original deadline. Process death inside the window
leaves nothing in History (the file was already deleted).

After a History-only end the panel now says only "Unable to insert text" with its marker (no
Back to keyboard button, no detail line); the back arrow is the way out and the microphone stays
for a new take. Evidence: host lifecycle tests for each exit path (window end, arrow Back,
system Back, hide, lock, destroy), rotation inside the window (Undo after it, and the window
ending after it), late decoder output during the window, and Undo from both choices.
