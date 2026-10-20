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
