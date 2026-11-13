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

*Superseded by the next amendment (2026-11-11): Discard take is final at once again; the last
paragraph (History-only end) still holds.*

The owner approved the Crate B2 direction's Undo window. **Discard take no longer discards at
once.** It opens an 8 s Undo window (`DISCARD_UNDO_MS`) in the panel: status "Take discarded",
a shrinking timer rule, a filled Undo button and Back; no microphone, so nothing ends the window
by accident. During the window nothing is inserted (the recording choice's take stays paused,
the Stop-wait choice keeps its delivery hold), the microphone does not record (the paused
recorder stays stopped and owned) and the take is not listed in History: its saved file is
deleted at once and its latest text stays in memory only (`TakeOutput.suspendHistory`).

**Undo** restores the same take at the choice it came from (Paused, or Still preparing
text): History saves its latest text again under a new id (the old id keeps its deletion
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

## Amendment — Discard take final at once (#36, 2026-11-11)

Owner decision, 2026-11-11: **the panel's Undo window is removed.** In use the owner found the
8 s wait after Discard unnecessary in the panel. Discard take, from the recording choice or the
Still preparing text choice, is final at once — the pre-#36 discard: insertion revoked, saved
text removed from History, capture released, later decoder output suppressed (nothing listed,
nothing inserted). There is no "Take discarded" state: the panel shows its idle state at once
(microphone ready, Back returns to the keyboard). A discard sets the explicit-start rule, as an
interruption does, so a panel shown again (a rotation right after Discard, or the next show) never
starts a take by itself; the microphone tap does. `TakeOutput.suspendHistory`/`resumeHistory`
and the panel's Undo views are gone. Discard stays the dashed error-coloured button set apart
from Stop, now the only protection against a slip; the safe action stays preselected. History's
own Undo after delete is unchanged. Evidence: host lifecycle tests (discard final at once from
both choices, late decoder output after Discard, rotation right after Discard, private field).

## Amendment — a dead connection is a failed insert (#36, 2026-11-13)

- The return value of `commitText` is not trusted: it is a one-way call, and an editor that deactivated its connection (Signal after a rotation) drops the text and still answers true.
- For a take that went through a rotation hold (also a self-requested panel), a null read before the write means the connection is dead: nothing is written, the take is a rejected insert (History keeps it where retention allows), and the panel shows "Unable to insert text" instead of handing back. Other takes are unchanged, since healthy editors may also answer a read with null.
- The result is not carried to a later input start.
- Plan B (owner, 2026-11-13): "Unable to insert text" holds the finished text in memory only while it shows, and offers Copy text and Discard text in the stage (no microphone disk, so no control starts a take or drops the text implicitly); the preview shows the text. Copy uses plain text, no sensitive flag, History's copy code, then the text is dropped, the explicit-start rule cleared and the previous keyboard is shown at once (no "Copied" status). Back is described in the later lines. A rotation keeps the state; Discard text, screen off and another app's editor clear it. Works for every cause and for a retention-ineligible editor; nothing new is written or logged. There is no Insert button and no automatic or delayed insertion into a later input session. A panel shown again never starts a take over held text (the held state is checked directly, not the explicit-start rule); Copy, Discard text and a discarding Back clear the rule, so the next opening records.
- Every outcome that has text and was not inserted holds it the same way: a write that returns false or throws, a dead connection, a failure after decoding and an input change under the take. "Recognition failed" is only for an outcome without text. The held state survives rotating back, the editor restarting input and a tap on the field.
- Our own panel is requested on every rotation-hold entry while input is started and our view is hidden (the editor's input start, and again at its view or window hide), one request pending at a time and bounded by the hold's gap. A hide that lands after the first request ran no longer leaves the take without a panel (Signal order, measured 4 in 12 probe runs).
- Held text is for the app the take started in, and only while the panel shows in that app: the take's package is latched at capture start; an outcome that finishes in another app's editor holds and shows nothing (status only, History where eligible). A lock never keeps it: a take active at screen off refuses to hold its text, after unlock too.
- A rotation never moves a take to another app: an input start from a different package inside the rotation window ends the hold (take interrupted, History where eligible, no panel request, no authority for the other app). A panel is only requested while an input is started, so Home inside the window asks for nothing and the take ends at the gap.
- Back never writes to the clipboard: on held text the first Back only warns ("Press Back again to discard the text."; the arrow then reads "Discard text and go back") and a second discards and leaves. A rotation cancels the warning. The status line says where the text is: "Copy it, or find it in History" when the take's editor keeps History, "Copy it now. It is not saved." when it does not.

## Amendment — same field after a rotation, and the read-back (#36, 2026-11-13)

Signal after a rotation leaves input focus on a view that is not a text editor: Android hands the keyboard its placeholder connection. Reads answer an empty string (not null), there is no extracted text, `commitText` returns true and nothing changes. The earlier null-read refusal cannot see this, and the rotation hold re-granted the take's authority to whatever editor of the same package started input, so the take was reported inserted and the text was lost; with a different real field the text was written into the wrong field.

- **A write's return value and a non-null read prove nothing.** `commitText` is one-way and a placeholder answers reads. Identity is judged from the editor's attributes, and the result from the field's own text.
- **Same-field rule** (`FieldIdentity`, pure and host-tested): after a rotation hold, insert authority returns only to an editor that is the same field the take started in: same package; same field id when both have one; same input class and variation; same IME options with the next/previous navigation flags ignored (a recreated field changes those once a second field exists); the same hint presence. Fields without an id (0 or NO_ID, Compose) also need the same hint. TYPE_NULL or an initial selection below 0 never matches. Hint text is compared in memory and never logged.
- **A non-matching editor of the same app** starting input inside the hold (a search box, a view that is no editor) is not an interruption: the take keeps recording, our panel is shown, but the take has no authority, for the rest of the take. The panel shows no helper line. Stop then ends in the held-text state with Copy; nothing is written into the other field. A different package still interrupts.
- **A mismatch is final when the hold settles** (owner, 2026-11-13): while the rotation hold is still open (the panel not yet settled, the gap not ended), the take's own field starting input returns its authority (the automatic sequence: a transient TYPE_NULL or other start, then the take's field). Once the hold has settled on a mismatch, authority never returns: a later start, even in a field that matches the take's attributes (it may be another conversation's), gets none, and Stop holds the text. A plain hide after that (the window hiding, or the input view or input ending with no hold running) ends the take at once, with no timer and no panel request; a tap on another field is such a leave. A take that kept authority is unaffected: a cursor move in its field changes nothing and Stop inserts.
- **Known false negatives of the identity rule** (fail-safe: the text is held, never written elsewhere): an initial selection below 0 on either field, a field id generated per view (`generateViewId`) that changes on recreation, a hint that appears or changes, an IME action that changes, a field that turns private or changes input variation. A field that is the same one but now private is a different field by this rule: held, not inserted.
- **Read-back** (takes that went through a rotation hold only): after the write the text before the cursor must end with the last up to 64 committed characters (whitespace normalized), or the extracted text must have grown by the committed length; checked at once and once more after about 100 ms, without blocking the main thread. If neither holds, the take is not reported inserted, the keyboard is not handed back as if it were, and the text is held with "Could not confirm the insert. Check the field, or copy the text." A pass at the second look counts as inserted (the History receipt the rejected write left is corrected to inserted, and the trace reports the take as completed). The mark for this held state is "Insertion uncertain". A lock during the second-look wait locks the take like any other. Takes without a rotation are not read back.
- The benchmark trace records only booleans and integers: per input start (`sameField`, `typeNull`, `selValid`, `connectionChanged`) and per read-back (`verified`, `check`). No text, field id or package.
- Not covered: a field that is the same by these attributes but is another instance of the same kind (two identical id-less fields in one app).
