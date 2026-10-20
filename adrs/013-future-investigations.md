# ADR-013 — Converge on affordable vocabulary gains

2026-10-20 · Prioritized proposals; no inference or deployment authorized.

Keep the working engine until a complete automatic phone path wins both languages against FUTO.
The strongest measured lead is FP32 dictionary decoding’s development quality gain (ADR-007), at excessive cost.

## Next decisions

1. **Primary track — rescue the measured gain:** Parakeet original ONNX CPU → converted LiteRT CPU → explicit FP32 GPU. First establish encoder numerical and beam/dictionary quality parity; then measure unprofiled, transfer-inclusive full-path costs (ADR-016). The experiment specification and acceptance criteria are in GitHub issue #1.
2. **Bounded diagnosis:** inspect Cohere’s saved English-prefix failures before further inference. Correct routing did not prevent 90/115 errors in two prefixes. Advance only with a specific, falsifiable cause; acceleration cannot repair a quality failure.
3. **Product validation:** only a surviving candidate earns frozen automatic-path comparison with FUTO: each language, correction effort/meaning, short and spontaneous speech, ambiguous starts, cold/warm Stop, memory and sustained heat/battery. Existing repeated scripts nominate candidates; they cannot establish R4. Recording-policy authorization remains separate (ADR-005).

Keep one primary optimization track; the Cohere diagnosis stays bounded. A GPU kernel win alone earns no adoption.
Cohere follows only after diagnosing its saved English-prefix failures and establishing a suitable conversion reference; its installed INT8 export is not an FP32 reference.
Earlier automatic LID with complete opening replay addresses its observed ~21.7 s first-chunk delay separately from arithmetic speed (ADR-015).

## Closed or deferred

Whistle stays parked unless new evidence justifies reopening a challenger screen. If reopened, pin runtime/model, prove input/output coverage, then compare unbiased and dictionary twins before Android work.
Allocation and broad beam/chunk sweeps are exhausted; same-build memory confirmation is useful only if decision-changing.
Moonshine Small failed advancement; Medium/router remain unknown, not the next sweep.
Qwen’s corrected 0.6B export already lost; a future reference/native/1.7B comparison must isolate conversion loss from capacity.
Canary 1B/full Whisper large-v3, selective/per-channel precision and supported CPU providers remain fallback hypotheses, not parallel campaigns.
TPU access is established; numerical mismatch and custom compiler acquisition precede speech work. Repeating access probes adds no value.
Overlap repeats computation: establish queue feasibility and missing/duplicate-tail behavior before promising low finalization delay.
Dictionary UI, per-name boosts, phonetic matching and loading/warm-up follow a surviving mechanism.

Evidence/proposal provenance: `925ef8c` research notes; newer measured outcomes in ADR-014–016.
Recheck upstream and freeze identities, thresholds and recovery inputs during preparation.
