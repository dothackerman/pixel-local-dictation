# ADR-026 — Accuracy over the shipped default; dictionary first; WebGPU stopped

2026-10-25 · Accepted by the user. Supersedes ADR-013 "Next decisions" 1–3 and README R4 (2026-10-09 wording).
2026-10-27 · Outcome: candidate B stopped; the owner selected Candidate A as the provisional baseline
despite its #15 gate rejection ([Outcomes](#outcomes)). PR #29 merged; #11 and #28 closed the same day.
Planned work lives in GitHub (spec #11 and its tickets), never in the repo.

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
(#11 closed 2026-10-27 with the owner selection below; the R8 measurement #14 remains partial.)

## Outcomes

Compressed 2026-11-01 from the dated sections of this ADR; full original wording at commit `6d4b6be`.
Private evidence paths are cited, never reproduced.

### 2026-10-26 — #15 frozen phone comparison rejects Candidate A

120 frozen measurements validated: 30 default/default pairs, then 30 default/candidate pairs,
three rounds, alternating arms. Control pairs had identical text; largest absolute paired
control difference 205.83 ms. Candidate median Stop wait **3153.69 ms**, maximum **5456.04 ms**;
paired slowdown median **2019.61 ms**, maximum **3880.28 ms**. The endpoint is simulated editor
acceptance, not live insertion. Errors over three repeats: German 108 → 93, English 66 → 45
(per round 36 → 31, 22 → 15), on known scripts. Held-script German harm +1/0/+2 (feeds 640/512/800)
is a separate limit.

| Resource | Default | Candidate A |
|---|---:|---:|
| Median cold load (ms) | 3746.5 | 4084.0 |
| Maximum cold load (ms) | 5281 | 4146 |
| Peak memory (KiB) | 1630812 | 3177308 |
| Maximum thermal status | 0 | 0 |

Verdict: reject on the ticket's 2.5 s median limit alone; not relaxed retrospectively.
Evidence: `corpus/lab/issue11/visit-a-s15/` (`plan.json`, `report-c8612eb71eb64c7188431bf00a825308.json`
and CSV, `raw/`, `receipts/`); snapshot `issue11-20261026-resume` verified.

### 2026-10-27 — Candidate B (greedy boosting on the INT8 engine) stopped

#16 used canonical token paths only (26 emitted listed-term paths unboostable) and an absolute
guard gate. #22 reran with all 26 paths, generic words removed from the listed set and a gate on
false accepts introduced against the zero-bonus control (byte-equal to stock).

| alpha | EN listed Δ (640/512/800) | DE listed Δ | DE non-listed Δ | Introduced guard false accepts |
|---|---|---|---|---|
| 0.75 | 0 / −2 / 0 | −2 / −3 / −4 | 0 | 0 |
| 1 | −2 / −2 / −3 | −2 / −3 / −4 | +2 / +2 / +2 | 1 |
| 1.5 | −5 / −6 / −5 | −3 / −5 / −2 | +2 / +2 / +3 | 6 |
| 2 | −5 / −7 / −5 | 0 / −3 / −2 | +3 / +3 / +3 | 12 |

No alpha passes in either language (alpha 0 and 0.5 also run; 3 skipped). Carrying decoder state
across chunks was rejected in #16 (English errors 18 → ~135 without a bonus). #18–#20 closed
**not planned**. Evidence: `corpus/lab/issue11/greedy-screen-rerun/`; tools from `94600aa`;
scorer `dictionary-words-v2`.

### 2026-10-27 — Startup observations (#14)

Ten takes reported, nine complete traces (one cold, eight warm), one process throughout.
Timing starts at the app's panel request, not the FUTO tap; panel draw is a callback.

| App panel request → | Cold (ms) | Warm median (ms) |
|---|---:|---:|
| Panel draw | 125.156 | 47.179 |
| Recording started | 98.726 | 27.246 |
| First audio | 291.021 | 230.089 |

Cold model ready at 2561.693 ms, after recording and draw: loading did not hold them. No
Stop/commit boundaries were recorded, so live Stop wait is unrecoverable. #14 stays partial.
Evidence: `corpus/lab/issue11/startup-20261027/`. Restoration: snapshot `issue11-20261027-restored`
byte-identical to `issue11-20261026-resume` (`recovery-verification.json`).

### 2026-10-27 — Owner selects Candidate A as the provisional baseline

After the trade-off review and B's stop, the owner selected Candidate A (Parakeet v3 FP32,
beam 8, dictionary score 1.5) as the app baseline for now, accepting the 3153.69 ms median and
5456.04 ms maximum for the known-script gain. This supersedes the rejection solely for exceeding
2500 ms; the frozen comparison and report keep their reject verdict.

Limits kept explicit: held-script German harm +1/0/+2. Retained-output meaning review: no observed
false accepts of listed names or terms; negation and quantity/unit regressions versus phone INT8,
partly present in the no-dictionary FP32 host control, so dictionary causation is unsupported.
No perfect-meaning claim or blanket safety waiver. Review: `corpus/lab/issue11/promotion-a28/meaning-review.md`.

Future comparisons must name their actual control. Preserve the INT8 baseline and all frozen
results under their original identities. Review this choice when daily-use evidence or a new
candidate changes the trade-off.

### 2026-10-27 — Integration, installation and live check (#28)

`app/src/main/res/raw/candidate_a.json` binds the #15 plan, configuration, dictionary, model/VAD
and native runtime. Missing or blank dictionary returns the historical INT8 pipeline; a nonempty
changed dictionary, model or runtime fails closed. The A silent-chunk filter stays on.

The first normal-path check failed before inference: Android's JSON encoder escaped slashes in
the native-library inventory digest (`corpus/lab/runs/20261027T080413-run-6cea46/instrumentation.txt`);
both platforms now use the frozen Python encoding. Build `cbead7a` installed: app
`ace6fa094259b15ce4621f7ee3ac943afcbf9492c6b90b72bcf10b0fe7da8bd1`, test
`ca7c76e19a503fcfbb7da2c578b7decb692d0e8f5ef1f27b959625bbad9612e3`. Receipts under
`corpus/lab/runs/`: `20261027T080126-install-494811`, `20261027T080356-push-4f81f0`,
`20261027T081141-install-0ddfb5`, parity run `20261027T081239-run-007ac9` (one DE and one EN
take matched frozen A output hashes). Recovery snapshot `issue11-20261027-before-a28`.

Supervised live check: two Stop takes inserted correctly (Stop → commit **6082.646 ms**,
**8280.698 ms**), a cancelled take inserted nothing, all returned to the keyboard. The cancelled
receipt lacks verified identity. Evidence: `corpus/lab/issue11/promotion-a28/live-check/` and
`live-check-summary.json`.

### 2026-10-27 — Runtime retention correction

`f81a8eb` bound `.also { runtime = it }` to a nested conditional, so the normal A arm built a
new runtime (model load and verification) on every panel request while process-wide
`modelReadyNs` still reported ready. Fix `a9a4b3e` assigns after the whole conditional; an
Android regression checks runtime identity across warm-up and panel requests. Installed app
`a95a05cf7c3dce2672b1d0fa3b12d28184ed964132220f199357f72e59a9074c`, test
`beb196742948d63e850e61755d7286b694b61c290871b1a940b9f7d2ae04752a`
(`corpus/lab/issue11/promotion-a28/build-a9a4b3e/`; receipts `20261027T082821-install-e82944`,
`20261027T082928-run-7395a5`). All three `NormalBaselineTest` checks passed.

Two new live takes: Stop → commit **2253.113 ms** and **1757.637 ms** (recordings 10.294 s,
7.764 s; capture drain 90.669/80.534 ms; final → commit 1.731/0.705 ms); model ready ~4.946 s
after service creation. Evidence: `corpus/lab/issue11/promotion-a28/runtime-fix-live-final/`.
The earlier 6–8 s waits measure the defective integration. Different recordings: no controlled
speedup or typical live Stop wait follows; the 3.154 s replay median is not a live estimate.

### 2026-10-27 — Delivery and accepted startup limits

Benchmark candidate `70df5d7` moved model warm-up to a task posted from first draw; the visual
cold/warm comparison showed no gain, and `1b2a04d` (same tree as `0efa5b2`) restored eager
warm-up. The retained `0efa5b2` pair was installed: app
`a240610540469f27335a11f8251bc2dedf7bbc91a00feae6e56e0d7fa9efe8ad`, test
`d6ebad1d6c1a0d618d47a1963033181a7bdaebe5d21fe854a52a553db11cc11a`, receipt
`corpus/lab/runs/20261027T125120-install-964943/meta.json`.

The owner accepts roughly **1.3 s cold / 0.25 s warm** handoff for now: a temporary acceptance,
not an R8 fix. Most of the cold interval preceded service entry; capture started before draw and
model readiness gated neither. #14 stays partial; #17 is deferred and nonblocking. Signal 8.32.3's
cached-height jump is separate. Revisit startup when the owner prioritizes it or new evidence
justifies a specific change.

Later installs (#30 panel, #31 dictionary editor, ADR-027) replaced this pair. Status unverified
(2026-11-01): the current installed APK identity is not recorded in a tracked file.
