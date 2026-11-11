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
the visible panel is attached to; any session change after the press revokes it
(rotation after the press: #53, until then History). Commit guards unchanged.
Retention stays latched from the starting editor and only tightens on rebind.

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
before and after the rotation inserted once, FUTO restored, both modes. Rotation
during the Stop wait still ends in History (#53). Gap 3 s and settle 1 s kept:
≥ 16× and ≥ 9× the measured times; one probe editor, one device.
Limits: a hide or focus change within the settle time after a rotation cannot be
told apart from the editor reacting to it (held, then History at the gap); the
display callback can still report the old rotation (#56), so a 180° turn without a
configuration change is counted only at the next editor event.
