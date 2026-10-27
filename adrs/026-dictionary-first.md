# ADR-026 — Accuracy over the shipped default; dictionary first; WebGPU stopped

2026-10-25 · Accepted by the user. Supersedes ADR-013 "Next decisions" 1–3 and README R4 (2026-10-09 wording).
Planned work for this decision lives in GitHub: spec #11 and its tickets. No plan document exists in the repo.

## Context

#1 asked whether the FP32 encoder on WebGPU cuts the Stop wait at equal text. ADR-025 (one take per language,
one run per setup): exact text, every chunk ~3.5× slower; the full suite and live gates were not run.
The only measured accuracy gain is supplied-vocabulary biasing (ADR-007: laptop DE/EN 45/24 greedy, 42/26 beam 8,
35/11 beam 8 + dictionary). Re-scored retained text (decoded before the 2026-10-12 chunker fix, six scripts, feed
640): listed terms are 16 of 39 German and 8 of 17 English errors; "4.80" against "4,80" style differences were
counted as errors. October 9–22 cost about 75 agent hours: ~25% wrapper and sandbox, ~17% accelerators ending
slower, ~15% docs; 25 ADRs in 13 days.

## Decision

1. R4 becomes: fewer wrong words than the shipped default, in each language, at a Stop wait the user accepts.
   Comparison with FUTO stops. The speed claim against FUTO rests on the 2026-10-10 non-matched takes (README).
2. A median Stop wait of 2–3 s is acceptable for a clear accuracy gain; the UI may show a waiting state.
3. R8 (new): the panel and recording start at once; model loading never delays them. Measure before fixing.
4. Scoring: number, spacing and hyphen forms count as equal; word accuracy is the target (extends R7).
5. The next spec is dictionary biasing (#11). Laptop inference on `corpus/` and phone replay visits are authorized
   for it; each visit still prompts for install and restore (ADR-024). One supervised live check per ADR-017
   precedes adoption. Candidate A (existing FP32 beam-8 + dictionary variant) has no phone Stop-wait number yet;
   ADR-025's 2.0–2.5 s was beam 4 without a dictionary.
6. Process: no frozen comparison plan for laptop screening; phone visits keep a snapshot and `meta.json` receipts;
   a frozen plan is required only for the adoption-deciding comparison (ADR-017 as amended today). One ADR per
   decision, not per step. Gates, thresholds and schedules live only in tickets.
7. "Dictionary" is the term (GLOSSARY); "word list", "lexicon" and "hotwords" are avoided in prose.

## Parked and rejected

Parked: WebGPU, reopen only when encoder-only time per chunk is located (ADR-025 limits); per-channel INT8
quantization (ADR-013) if candidate A needs less memory. Rejected now: a laptop copy of FUTO with whisper.cpp
(its fork cannot be reproduced), FLEURS (download, proxy), LLM post-correction (Stop wait, R5), chunk-overlap
decoding (8 of 56 errors near cuts on the shipped chunker, below its own firing rule).

Review when #11 closes with an adoption verdict, or when the R8 measurement shows the panel delay is not startup work.

## Candidate A phone result — 2026-10-26

The authorized #15 replay rejects FP32 beam 8 with dictionary score 1.5 under the ticket's existing
2.5 s median Stop-wait limit. All 120 frozen measurements validated: 30 default/default pairs, then
30 default/candidate pairs, three rounds with alternating arms. All control pairs produced identical
text; the largest absolute paired Stop difference was 205.83 ms.

Candidate median Stop wait was **3153.69 ms**, maximum **5456.04 ms**; paired slowdown median
**2019.61 ms**, maximum **3880.28 ms**. The endpoint is simulated editor acceptance, not observed
live insertion. Across three repeats, German errors fell 108 → 93 and English 66 → 45, equivalent
to the development-suite totals 36 → 31 and 22 → 15 per round. These remain known-script results.
The earlier held-script German harm (+1/0/+2 across feeds) remains a separate limitation.

| Resource | Default | Candidate A |
|---|---:|---:|
| Median cold load (ms) | 3746.5 | 4084.0 |
| Maximum cold load (ms) | 5281 | 4146 |
| Peak memory (KiB) | 1630812 | 3177308 |
| Maximum thermal status | 0 | 0 |

The default resource column includes both phases' default arms. No meaning-changing false-accept
verdict or supervised live result is claimed; the latency failure alone rejects the candidate.
No dictionary editor or daily-default change advances. Candidate B already stopped without a
passing alpha (#16); #18/#19 remain blocked. Startup measurement #14 and its evidence-dependent
fix #17 remain open.

Private evidence: `corpus/lab/issue11/visit-a-s15/plan.json`,
`report-c8612eb71eb64c7188431bf00a825308.json` and matching CSV, plus `raw/` and `receipts/`.
Snapshot `issue11-20261026-resume` was verified. Install and restore were approved; restoration
initially waited for the owner's final startup takes. Recovery is recorded below. #11 is incomplete.

This records the measured rejection under existing authorization, not a new owner-approved
adoption decision. Revisit Candidate A only with evidence addressing its Stop wait and held-script
harm; do not relax the completed experiment's gate retrospectively.

## Startup observations — 2026-10-27

The owner reported completing ten short takes; nine complete startup traces were captured
(one cold, eight warm). Do not infer a tenth measurement. One process survived across all nine
service instances. These timings start at the app's panel request, not the FUTO microphone tap;
panel draw is a callback, not physical display timing.

| App panel request → | Cold (ms) | Warm median (ms) |
|---|---:|---:|
| Panel draw | 125.156 | 47.179 |
| Recording started | 98.726 | 27.246 |
| First audio | 291.021 | 230.089 |

The cold model became ready 2561.693 ms after the request, after recording and panel draw.
Warm panel draw ranged 32.213–54.471 ms; recording start 18.942–31.179 ms. The measured model
load did not hold recording or drawing until completion. Time before the app callback remains
unknown; no end-to-end startup cause or speculative #17 fix follows from this evidence.

The startup file and retained app logs contain no explicit Stop/commit boundaries. Live Stop
wait cannot be recovered; the assistant's earlier assurance that these takes would measure it
was incorrect. Decode duration and take end are not substitutes. #14 remains partial: nine
app-side rows and process continuity are known; a tenth row and external key timing are absent.

Private raw timing file, app logs, per-take CSV and analysis:
`corpus/lab/issue11/startup-20261027/`. These startup receipts contain no speech or transcript.

Restoration completed after collection. Independent snapshot `issue11-20261027-restored` has
byte-identical app APK, test APK and complete data archive to `issue11-20261026-resume`; grants
and observed IME state also match. Evidence: `startup-20261027/recovery-verification.json` and
the original restore receipt. The benchmark visit is over; #15 is complete with a reject verdict.
#14 remains partial, #17 awaits a measured cause, and #18/#19 remain blocked by the negative
greedy screen. No speculative startup fix or dictionary adoption follows.
