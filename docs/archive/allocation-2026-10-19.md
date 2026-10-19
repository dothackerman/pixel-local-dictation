# Allocation and measurement — completed 19 October 2026

**Status:** completed diagnostic experiment. The three-stage plan was approved on 2026-10-14, executed on 2026-10-14–19, and closed on 2026-10-19 after verified restoration and ownership release. No successor experiment is commissioned; [current status](../experiments/current.md) and [future directions](../roadmap.md#future-experiments) own subsequent work.

This archive owns the public aggregate interpretation. Exact private metrics, identities, accounting and evidence links remain in [the complete handback](../../corpus/lab/allocation-v1/reports/handback-complete-20261019.json), its [readable view](../../corpus/lab/allocation-v1/reports/handback-complete-20261019.md), and the [391-entry integrity snapshot](../../corpus/lab/allocation-v1/reports/completion-integrity-20261019.json). Independent Standards and Spec audits verified all 66 measurements, 95 barriers, authorization/history, 10 observed-OS sidecars, final restoration and release.

**Provenance:** scientific source `d59c715c61133c61c86fb26f71b716a954467653`; final execution/admin checkpoint `d0e984641d9d247bd6dd4f500abfbba1091b7b43`. The [immutable manifest](../../corpus/lab/allocation-v1/manifest.json), Git snapshots and [exact executed plan](../../corpus/lab/allocation-v1/reports/executed-plan-d0e984-20261019.md) identify executed bytes. This archive adds closure results and repairs navigation; it is not a refreeze or an inference build. No personal transcripts, recordings or signing data are included here.

## Completed results

All **66 planned measurements (42 laptop, 24 phone)** and **95 comparison barriers (11/42/42 by stage)** have complete frozen evidence. All three stage gates have complete evidence; stage 1 supported the intended persistent mode. There were66 starts, 66 complete finishes and **zero retries**. Evidence completion includes negative resource outcomes and tradeoffs; it does not mean every performance criterion passed.

Allocation settings retained exact normalized development-set quality. Memory savings generally traded against load or Stop latency. Cohere pattern-off had observed dominance in the frozen laptop summary metrics, but both Cohere phone settings were tradeoffs. FP32 pattern-off has a positive frozen phone summary label, yet its second allocation comparison crosses OS builds, leaving a controlled phone benefit unresolved. There is no overall winner or production change.

## Decision table

Matched changes below are against the same model's stock setting, independent process repetitions1/2; negative timing deltas are faster. RSS/PSS/high-water counters retain their reported **kB** units, with no ambiguous GB conversion. Timings are milliseconds. Full absolute values, all waits, memory phases and comparisons against the app baseline remain in the linked machine evidence and resource tables.

| Model / allocation | Platform | RSS reduction r1 / r2 | Load delta ms | Stop median delta ms | Stop maximum delta ms | Frozen summary outcome / qualification |
|---|---|---:|---:|---:|---:|---|
| FP32 Parakeet dictionary arena-off | laptop | 23.33% / 23.48% | +48.56 / +64.03 | +62.78 / +15.50 | +103.06 / +270.84 | tradeoff |
| FP32 Parakeet dictionary pattern-off | laptop | 14.74% / 14.80% | +66.27 / -47.85 | +31.91 / +1.24 | +927.16 / -9.19 | tradeoff |
| FP32 Parakeet dictionary arena-off | phone | 24.06% / 37.88% | -3181 / +101 | -366.50 / -905 | +1153 / +529 | tradeoff; causal inference unresolved (cross-OS r2) |
| FP32 Parakeet dictionary pattern-off | phone | 12.66% / 28.61% | -3424 / -8 | -150.50 / -1081 | -88 / -2653 | observed dominance; causal inference unresolved (cross-OS r2) |
| Cohere arena-off | laptop | 20.16% / 22.27% | -85.45 / -8.57 | +799.43 / +914.03 | +2027.09 / +2028.58 | tradeoff |
| Cohere pattern-off | laptop | 11.11% / 12.60% | -131.48 / -44.83 | -163.04 / -113.13 | -185.56 / -242.80 | observed dominance |
| Cohere arena-off | phone | 20.43% / 20.17% | +620 / -118 | +3987 / +3811 | +9523 / +7919 | tradeoff |
| Cohere pattern-off | phone | 4.35% / 4.32% | +487 / +750 | +486 / +25.50 | +86 / +50 | tradeoff |

All phone allocation quality comparisons preserve stock normalized take/endpoint output across both process repetitions and both internal passes. Laptop quality also includes all three feeds, 210 public guards and silence/noise companions. Allocation transparency is supported on this development set; semantic benefit, dictionary mechanism benefit and generalization were not isolated.

Cohere arena-off on the phone saved20.43%/20.17% sampled RSS but made all 12 endpoint waits slower in each repetition (median+3987/+3811 ms). Pattern-off saved4.35%/4.32%, with 9/12 then 4/12 waits slower. Laptop Cohere pattern-off's better summary metrics do not imply uniform endpoint improvement:1/12 and 4/12 individual waits still regressed. FP32 pattern-off's same-old-cohort repetition also had5/12 slower waits despite better summary median/maximum. The frozen summary rule and every endpoint observation are both preserved.

## Quality

Direct phone results below use the primary persistent640 feed, 584 German and 632 English reference words, per internal pass; matched repetitions retain these results. Terms use the 130-term primary set. They are separate from desktop three-feed means and desktop guard/silence companions.

| Direct phone model | German errors / WER | English errors / WER | Terms missed /130 | Non-term errors total |
|---|---:|---:|---:|---:|
| App baseline |46 /7.88%|22 /3.48%|25|63|
| FP32 Parakeet dictionary, all allocations |35 /5.99%|11 /1.74%|11|44|
| Cohere, all allocations |24 /4.11%|23 /3.64%|18|44|

Cohere improved German but had one more English error than the app baseline on this phone feed. Do not substitute its laptop English score. Laptop three-feed mean German/English WER was baseline 7.88%/4.01%, FP32 6.05%/1.85%, Cohere 4.17%/3.38%; primary640 terms missed were24/11/17. Laptop primary640 English baseline and Cohere each had22 errors (3.48%), while FP32 had11 (1.74%). These different scopes are retained in the full tables.

The laptop guard suite contains90 German and 120 English cases. Guard WER was baseline 4.81%/2.01%, FP32 4.62%/1.75%, Cohere 2.75%/1.36%; one FP32 English and one Cohere German scoring-vocabulary insertion occurred. Silence/noise output was empty in the companion tests, but FP32/Cohere silence was VAD-gated. The210-guard/silence pack was **not measured on the phone**. Desktop companions are evidence about those desktop checks, not phone hallucination rates or bare-model silence behavior.

## Measurement and environment

Persistent mode was supported by provenance, required counters, expected coverage and normalized output across independent phone repetitions and internal passes. It uses one loaded recognizer/executor and one audio buffer at a time; it was not selected for the smallest memory. Fresh unpaced, fresh paced and historical-full controls remain available. The two historical-full phone controls still fail their original RTF,Stop and historical memory gates; their complete diagnostic evidence does not erase those failures. Fresh recognizer load is checksum-warmed filesystem context, not a cold disk-cache measurement.

Stage 1 per-run OS identity is unavailable. The first six stage 2 phone runs share the old observed GrapheneOS display build2026100601. The remaining ten actual run-matching version sidecars all observed2026101601. Final phone comparison cohorts are22 same-observed-build, 6 cross-build and 4 unavailable; cross-build resource comparisons remain confounded even where frozen evidence is complete.

OS alone does not explain the controls. Stage 2 final baseline sampled RSS was 3,475,728 kB versus2,177,452 kB and 2,173,028 kB in the two stage 3 baseline controls on the same observed new OS. Frozen model/build/runtime/config/input identities match. The extra stage 2 footprint appeared well before the host interruption and retained substantial native allocation after recognizer release; no cause is assigned. Process/session variation is an additional limitation on causal conclusions.

Stage 1 historical fixed 300 s cooldown evidence is retained. Stages2/3 use separately authorized adaptive admission: fresh severity 0–1 permitted, wait at >=2,15 s polls, no fixed cooldown. All eight new stage 3 measurements had maximum reported severity0; the two new stage 2 measurements had maximum1. Severity is not equal temperature or proof of full recovery. Laptop heat, component energy and long-session battery endurance remain unavailable; battery percentage was coarse and does not establish energy cost.

## Execution, budgets and final device state

The [full final gate](../../corpus/lab/allocation-v1/reports/final-next-completion-20261019.json) returned kind=complete,status=completed, all three stage evidence complete and no restoration pending. The owner then released at ledger sequence199, with no active process/work. [Exact final ledger](../../corpus/lab/allocation-v1/reports/execution-events-complete-20261019.jsonl) and [structured handback](../../corpus/lab/allocation-v1/reports/handback-complete-20261019.json) retain the accounting, authorizations and deviations.

| Active work | Consumed seconds | Effective cap seconds |
|---|---:|---:|
| Stage 1 laptop |8200.665478|28800|
| Stage 2 laptop |21864.825224|28800|
| Stage 3 laptop |27201.188213|29121|
| Whole laptop |57266.678915|86400|
| Stage 1 phone |11284.229610|14400|
| Stage 2 phone |17250.490436|19800|
| Stage 3 phone |17230.178409|21900|
| Whole phone |45764.898455|52500|

The original manifest and consumption were never reset. Versioned amendments record the approved time envelopes and until-completion access for the fixed remaining ten attempts. Full per-run timeouts, 900 s restoration reserve, original attempt/retry counts, models/order/contracts/scoring and storage limits remained in force. Stage 3 laptop's cap exception enabled a full3600 s admission reservation although actual work remained below the original28800 s. Final-gate additional output growth was 54,445,336 bytes under the 30,000,000,000-byte whole limit.

One stage 2 baseline host command actually exited143 while its original device measurement continued. Same original UUID/process identity progressed to complete expected coverage and final recognizer release before the original deadline. Worker queue termination and recognizer cleanup were verified; literal Android PID death and MeasurementRun return were not observed. This was recovered original evidence, not a retry or new attempt. The host cancellation cause remains unresolved. Earlier controller-output limitations, one explicitly unused thermal refresh, and a refused redundant immutable validation are retained separately; no invalid evidence was overwritten.

The **exact setup baseline was restored at 15:57:17 Europe/Zurich**, and benchmark/test apps retained. [Final restoration](../../corpus/lab/allocation-v1/reports/restoration-20261019-final-completion.json) records fresh SHA-256 for all 11 host baseline files, both APK identities, successful app-scoped restoration and matching complete device byte-size inventory. Independent on-device content hashes are unavailable and are not claimed. Work was paused before final reporting; no later phone calls were made.

## Evidence and next investigation

- [Exact executed plan bytes](../../corpus/lab/allocation-v1/reports/executed-plan-d0e984-20261019.md), original scientific Git source and [immutable manifest](../../corpus/lab/allocation-v1/manifest.json) remain historical execution authority; later archived documentation is closure commentary.
- [Laptop full quality, feed/repetition/guard/silence tables](../../corpus/lab/allocation-v1/reports/final-analysis-20261019/laptop-quality-analysis.md) and [laptop absolute resources, phases and all endpoints](../../corpus/lab/allocation-v1/reports/final-analysis-20261019/standards-reviewer-laptop-resources.md).
- [All 24 direct-phone quality results](../../corpus/lab/allocation-v1/reports/final-analysis-20261019/phone-fixed-quality-final-20261019.md), [stage 2 phone resources/cohorts](../../corpus/lab/allocation-v1/reports/final-analysis-20261019/standards-reviewer-stage2-complete-phone-resources.md), [paired Cohere phone allocation results](../../corpus/lab/allocation-v1/reports/final-analysis-20261019/standards-reviewer-cohere-phone-paired-resources.md), [baseline identity/variation](../../corpus/lab/allocation-v1/reports/final-analysis-20261019/standards-reviewer-baseline-identity-variation.md).

The smallest justified successor is a separately scoped same-build FP32 pattern-off confirmation after investigating baseline/session memory variation. No new measurements are commissioned here. Dictionary mechanism isolation, no-dictionary phone twins, historical-gate finalist acceptance, R4/deployment/automatic FUTO-path validation, short/spontaneous/noisy/ambiguous-language and correction/meaning/battery evaluation remain separate work. No weighted winner, population p95, significance or meaningful-gain claim is made. The recording trigger and product configuration remain unchanged.


## Source recovery and offline analysis

The completed setup, execution, continuation and readiness tools are retired from the active tree. Their dated exceptions and launch instructions are history, not a foundation or authorization for another experiment. The stage runbook, workflow and setup notes remain recoverable at final execution/admin checkpoint `d0e984641d9d247bd6dd4f500abfbba1091b7b43`; the exact executed plan and amendments remain in the private evidence linked above. ADR-008 retains the rationale for the combined experiment.

Read a historical file without changing this checkout, for example:

```sh
git show d0e984641d9d247bd6dd4f500abfbba1091b7b43:tools/allocation_execution.py
git show d0e984641d9d247bd6dd4f500abfbba1091b7b43:docs/experiments/allocation/workflow.md
```

Keep historical analysis offline. Do not run recovered launch, preparation, readiness or device commands. Do not regenerate manifests or migrate evidence. The private immutable manifest pins absolute paths, frozen source identities and old commands; those remain evidence of the executed experiment even when a historical source file is absent from this checkout.

The retained `tools/allocation_report.py` validates raw report envelopes and matched pairs, checks stage evidence, and recomputes the scientific aggregate using the existing versioned evaluator. Its CLI supports `validate`, `compare`, `gate` and `aggregate`; new JSON outputs are exclusively created. Put private outputs only under `corpus/`, choose an unused output path, and supply the historical effective replacement mapping if a retry was used. It performs no inference or phone access. Completed allocation used zero retries.

For example, from the repository root:

```sh
python3 tools/allocation_report.py aggregate --manifest corpus/lab/allocation-v1/manifest.json --output corpus/lab/allocation-v1/reports/offline-scientific-review.json
```

This scientific summary preserves frozen quality/resource verdicts and their limitations. It does not reconstruct execution ownership, time budgets, environment sidecars or restoration. Their authoritative records are the immutable complete handback, final ledger and integrity snapshot above. For exact historical accounting recomputation, inspect the pinned source with those original inputs; retain the original path context and keep all private data under `corpus/`. No measurement rerun is authorized.
