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
the same recovery path. Destination safety remains unchanged; no rotation
continuity exception is enabled. Deliberate Cancel still discards immediately.
Eligible ordinary-field transcripts remain in no-backup History until deletion;
password, no-learning and unclassifiable editors retain neither History nor
benchmark content. Separately opted-in eligible benchmark Stop keeps its existing
storage contract; interruption salvage never uses it.

Evidence: take/output, recorder and production-service host regressions cover
interruption, release failure/retry, retained prefixes and delivery races. Synthetic
phone UI/process tests and sanitized rotation probes compile but have not been run
on the Pixel for this handoff. No continuous-rotation or recovery-window claim.
