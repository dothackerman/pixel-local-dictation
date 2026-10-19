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


---

## Historical approved plan and amendments

The following preparation-time brief and dated appendices preserve the approved question, rationale, original rules and later authorizations. Their execution instructions are retired. Preparation-time statements such as “execution has not started” describe that earlier checkpoint; they are not current readiness claims. The exact executed version remains pinned above, including the original finite manifest and amendment history. No new launch or successor is authorized by this historical text.

**Prepared and device-validated; execution has not started.** The one-loop, three-stage plan was approved on 2026-10-14. [The workflow](../experiments/allocation/workflow.md) owns frozen execution details and completed setup evidence. Actual experiment launch requires Oriol’s separate instruction and dated phone windows.

**Question:** can allocation changes make the FP32 Parakeet and single-instance Cohere quality controls cheaper on the phone without losing their demonstrated development-set accuracy?

**Deliverable:** one decision table separating quality, memory, load/Stop latency, heat and evidence completeness, with all repetitions and a recommendation for the next experiment. Completing the loop does not mean a candidate passes; this is diagnostic work, not deployment or R4 acceptance.

## Scope and readiness

| Stage | Measurements |
|---|---|
| **1. Characterize measurement** (legacy artifact name `release-1a`) | Frozen laptop controls; stock FP32 phone control in fresh unpaced, fresh paced, persistent and historical-full modes, twice each. [Stage-1 runbook](../experiments/allocation/stage-1-runbook.md). |
| **2. Compare FP32 allocations** (formerly 1b) | FP32 beam-8 dictionary: stock, arena-off only, pattern-off only in persistent mode, twice each; app baseline twice. |
| **3. Compare Cohere allocations** (formerly 1c) | Single-instance Cohere with the same three settings and persistent mode, twice each; app baseline twice. |

All stages retain complete quality checks. New models, combined arena/pattern disabling, precision exports, production changes, fresh recordings and mechanism/deployment acceptance are outside this loop. No-dictionary phone twins and historical-gate confirmation for a finalist remain separately budgeted acceptance work. [The roadmap](../roadmap.md#future-experiments) owns subsequent experiments and deferred ideas.

One loop means one objective and cumulative evidence, not one night or automatic expansion of a frozen manifest. The legacy 1a/1b/1c names identify artifacts only; they no longer require three independently commissioned experiment loops. [ADR-008](../decisions.md#adr-008--one-allocation-experiment-with-three-stages) records why the old split changed.

## Next action

The three-stage scheduler, validation barriers and cumulative continuation are implemented. Do not repeat their preparation. [The workflow](../experiments/allocation/workflow.md) owns executable commands and readiness checks; [the shared protocol](../experiments/protocol.md) owns execution rules.

Wait for Oriol’s separate launch instruction and dated phone windows. The fresh executor verifies the committed source and applicable safeguards, records only the authorization actually supplied, and follows the combined helper’s permitted next steps. The [launch prompt](../experiments/allocation/workflow.md#launch-handoff) identifies the model and scope. Completed public-only smoke tests do not authorize personal-corpus execution.

## Stage transition rule

The intended comparison mode is **persistent**, because the allocation question concerns repeated dictation with one loaded recognizer/executor and one audio buffer at a time. Stage 1 tests whether that measurement is trustworthy; it is not a contest to select whichever mode reports the smallest memory.

After all required stage-1 validation/comparison barriers pass, select persistent mode only if both phone repetitions have complete provenance, expected take/endpoint coverage, valid required memory/timing counters, identical normalized text under the frozen repeat rule, and no unexplained output/coverage failure. Retain fresh-process and historical-full results as diagnostic context. Failing a historical resource ceiling is a measured result, not a reason to reject a valid diagnostic control.

Missing or contradictory evidence, or an unsuitable persistent mode, produces an interpretation handback before allocation comparisons. Do not switch modes or loosen a threshold after seeing results. An alternative needs a documented protocol revision and prepared contracts. The combined helper implements this rule; the legacy stage-one helper remains unchanged.

Stage 2 precedes stage 3. Advance after its required evidence is complete, even if allocation changes show no benefit. Unexpected text changes from an allocation setting require diagnosis before treating the setting as transparent. An invalid comparison stops downstream work; adding a model, changing acceptance or repairing an adapter belongs to preparation.

## Budgets and phone windows

The former release caps are retained as stage caps, with one cumulative envelope. Controls, repeats, failures and retries all count; stage transitions and new windows never renew them.

| Stage | Laptop limit | Phone limit |
|---|---|---|
| 1 | Frozen manifest: 10 planned processes + 2 retry slots, 8 hours, 10 GB additional storage | 8 planned + 2 retries; at most 4 hours and within the authorized window minus restoration margin |
| 2 | Approved outer ceiling: 12 configurations, 40 processes and 8 hours | 8 planned + 2 retries; same 4-hour/window ceiling |
| 3 | Approved outer ceiling: 12 configurations, 40 processes and 8 hours | 8 planned + 2 retries; same 4-hour/window ceiling |
| **Whole loop ceiling** | **92 processes and 24 hours** | **30 processes and 12 hours**, within separately authorized windows |

The [workflow's frozen budget](../experiments/allocation/workflow.md#frozen-design) is smaller than these approved outer ceilings and owns concrete counts, storage, retries and active-work accounting. Neither an unused allowance nor a new window permits additional runs. Do not promise all planned processes fit: stage 1's summed timeouts already exceed four hours.

At each window boundary, finish validation, preserve state and hand restoration to the named owner. Resume only with the next authorized dated window, verified artifacts and fresh applicable safeguards. A timeout is not proof that the device process exited; the frozen access path has no guaranteed hard-abort operation.

## Completion and handback

The loop is **completed** when all required comparisons in the prepared three-stage manifest have valid evidence and the decision table answers each hypothesis, including negative results. It is **partial** if a budget/window ends before that point, and **blocked** if invalid evidence or missing preparation prevents continuation. Report which stages completed; never label the whole loop complete because stage 1 ended.

Use the [protocol's handback fields](../experiments/protocol.md#handback). Preserve individual waits and quality checks beside each setting, matched-platform deltas, fresh versus persistent memory and the unchanged historical gate. Recommend the smallest next investigation; the [recording policy](../experiments/protocol.md#recording-policy) and final FUTO validation remain unchanged.

On completion, move this plan to `docs/archive/` with its execution status, dates, evidence location, outcome and successor link. Completed execution, a supported hypothesis and deployment approval are separate facts.

## Appendix — approved laptop-only continuation, 2026-10-15

Oriol instructed: “then continue on the laptop only work and persist that decision as a appendix in the plan”, after disconnecting the phone. This supersedes the whole-stage ordering gate above **only for the 16 already prepared stage-3 laptop processes**: they may run before the pending stage-2 phone processes. Their inputs do not depend on stage-2 phone outputs. Preserve their frozen relative run/comparison order, both repetitions, full quality checks, exact commands, provenance, failure barriers and all stage/cumulative budgets. No additional configuration or measurement is authorized.

At this decision, stage 1 and all stage-2 laptop measurements had complete evidence: 26 laptop and eight phone processes, with no retries. The private handback is `corpus/lab/allocation-v1/reports/handback-20261015.json`. Revalidate that evidence before continuation. Stage-2 and stage-3 phone work remains pending; laptop completion cannot complete the whole loop. No phone contact is authorized by this amendment. The retained benchmark app and setup baseline restoration are recorded in `corpus/lab/allocation-v1/reports/restoration-stage1-20261015.json`.

The bounded administrative work order is `corpus/lab/allocation-v1/reports/laptop-order-amendment-work-order-20261015.json`. The companion `tools/allocation_laptop_continuation.py` accepts an explicitly pinned amendment record, verifies that this appendix is the sole change to the frozen plan, and preserves the original manifest and chained ledger. Its source checkpoint is administrative; the original measured source identity remains unchanged. It can schedule only stage-3 laptop work. Subsequent phone continuation needs a new dated authorization and applicable readiness checks, with this amendment accounted for explicitly.

## Appendix — thermal-status cooldown revision and phone hold, 2026-10-16

Oriol instructed: “use the phone's thermal status instead of a fixed 5min cooldown, and persist that change in the plan but do not yet continue with the phone work”. **Phone work is on hold until a subsequent instruction to resume.** This decision authorizes the protocol revision below, not phone access, probing or inference now.

Oriol subsequently specified: “0-1 and wait at 2. persist in the plan”. This supersedes the initial status-0-only readiness criterion. For future phone runs, including retries, this revision supersedes the workflow's fixed 300-second inter-process cooldown:

- After staging and immediately before inference, require a current `PowerManager.currentThermalStatus` reading of **0 or 1**: Android's [`THERMAL_STATUS_NONE` or `THERMAL_STATUS_LIGHT`](https://developer.android.com/reference/android/os/PowerManager#THERMAL_STATUS_LIGHT). A valid reading no older than 15 seconds permits the run immediately; no minimum five-minute wait applies. A previous run's final reading cannot substitute for this check.
- For a valid status **2 or higher**, wait and read again every 15 seconds until status 0 or 1. Missing, invalid or stale readings prevent a start; do not assume that silence or elapsed time means recovery. Record every status, its timestamp and the actual cooling wait. Status 1 can include light throttling; the chosen readiness criterion does not establish equal temperatures, equal performance or complete thermal recovery on this phone.
- Cooling waits and checks consume the existing active-work allowances. A run still needs its full frozen timeout within its stage/cumulative budgets and its timeout plus the 900-second restoration margin inside a newly authorized dated window. If these no longer fit, hand back and restore the baseline under the applicable window procedure. This revision does not increase or renew any budget.
- Keep the existing in-run thermal evidence and retry rules, including the measured-status-above-2 criterion and bounded thermal retries. Run order, repetitions, quality/resource interpretation, benchmark retention and baseline restoration remain unchanged.

**Approved policy; implementation and verification pending.** The current executor still enforces the old fixed cooldown, and the approved access path does not expose a standalone idle thermal-status read. Before resumption, prepare and verify a bounded read-only status probe through the authorized app-scoped access path and a versioned continuation that enforces this rule. Any necessary safeguard/access-path change needs separate explicit authorization; none is authorized or made here. Do not use a benchmark run as a status probe.

Preserve the existing frozen manifests, source snapshots, ledger, reports and consumed budgets. Record this revision and its verified implementation identity separately before future execution; the old continuation's plan-hash check must not be bypassed. Label subsequent measurements with the new cooldown protocol and retain the earlier fixed-cooldown evidence under its original protocol. No phone checks or measurements are performed as part of this plan edit.

## Appendix — approved thermal access and resumed window, 2026-10-18

Oriol instructed execution via subagents until **2026-10-19 06:00 Europe/Zurich**, then explicitly confirmed **"Accepted the debugging prompt"** and **"Approve the thermal command and access-path exception"**. This resumes phone work and approves the reviewed, argument-free, read-only `thermal` operation in the protected access entry point. The operation reads Android's thermal-service severity and rejects missing/duplicate fields, a non-ready HAL, an overridden status or an undefined severity. It exposes no status override/reset operation. Hook/permission configuration and other safeguards are unchanged.

This is the narrow approved exception to the preceding appendix's app-scoped probe requirement. It preserves the existing benchmark and test APK identities, all scientific contracts and the approved 0–1 readiness / wait-at-2 rule. Subsequent measurements use the separately identified `allocation-idle-thermal-0-1-v1` scheduling policy, alongside the original inference protocol and historical fixed-cooldown evidence. Full readiness precedes the query; pinned artifact-stability evidence and source/build identities are rechecked before inference release. An expired, unused release may be refreshed only with an explicit no-inference-started attestation, retaining its reservation, original deadline, samples and budgets.

The versioned continuation and fresh dated authorization own implementation identity and live checks; their preparation is not itself evidence of completed measurement. Every original stage/cumulative limit and progression barrier remains. Retain the benchmark app and restore the independently verified setup baseline before closing the phone interval, reserving the original 900-second restoration margin.

## Appendix — approved exact budget exceptions, 2026-10-19

Oriol explicitly approved the two pending, separately reviewed budget proposals: **stage-3 laptop active work increases from 28,800 to 29,121 seconds** (+321 seconds; 8h05m21), and **stage-2 phone active work increases from 14,400 to 18,000 seconds** (+1 hour). The laptop proposal retains a complete 3,600-second reservation for the last prepared control, 120 seconds of bounded administrative headroom and 0.982356 seconds of rounding. These are exact exceptions to the corresponding stage limits above. All other stage limits, whole-platform time/count/storage caps, prepared runs, retries, commands, scientific contracts, validation/comparison barriers and restoration duties remain unchanged. Previously consumed work is not renewed.

Use the separately hash-pinned `corpus/lab/allocation-v1/reports/budget-exceptions-20261019.json` and its reviewed `tools/allocation_budget_continuation.py` companion. Preserve the original manifest, plan prefix, prior authorization records and chained ledger. The companion supplies only these two approved limit values to the frozen budget checker and retains the original manifest identity for evidence. Laptop mode reuses the existing independent stage-3 laptop ordering amendment; it may finish the last prepared control before pending phone work. Phone mode reuses the approved thermal policy and original full-stage progression gates, with a fresh dated window and applicable checks after restoration.

Oriol subsequently authorized phone access until **2026-10-19 07:40 Europe/Zurich**. This extends the preceding 06:00 deadline only. At the first fresh clock check after approval (07:01:47), fewer than the required 2,400-second full run reservation plus 900-second restoration margin remained even before preparation; no new phone measurement fits. Persisting the budget approval does not open a phone interval or authorize work after 07:40. Retain the already restored setup baseline and benchmark app. Any later execution requires another explicit dated phone window.

## Appendix — finite completion authorization and environment cohorts, 2026-10-19

Oriol instructed: “the phone is connected now again with debugging available. do NOT ask me again for another window or phone access limitation because i want you to delegate the work efficiently until it is done and then present me the results of the whole experimentation loop.” **Phone access is authorized until completion of the original finite experiment.** This supersedes the preceding 06:00/07:40 access deadlines and dated-end requirement. It does not authorize a new experiment, configuration, measurement protocol or deployment.

Use the hash-pinned `corpus/lab/allocation-v1/reports/completion-authorization-20261019.json` with the reviewed until-completion version of `tools/allocation_budget_continuation.py`. An immutable access/check record has an explicit `until-completion` mode, a dated Europe/Zurich start/check timestamp and the exact ten remaining prepared run IDs; it has **no invented end timestamp**. Fresh safeguard/device checks, source/build/artifact stability, exclusive ownership, the original 2,400-second per-run reservation/deadline, thermal 0–1 readiness and wait-at-2 behavior remain mandatory. Restore/pause at the stage boundary and obtain fresh checks for the next interval under this same continuing authorization; no new user window is required.

The instruction to finish authorizes the following necessary, bounded temporal completion envelope. Keep the original manifest and every consumed interval unchanged; record these effective exceptions separately:

| Allowance | Effective completion cap |
|---|---:|
| Stage 2 phone | 19,800 seconds (5h30), replacing the earlier approved 18,000-second cap |
| Stage 3 phone | 21,900 seconds (6h05), replacing 14,400 seconds |
| Whole phone loop | 52,500 seconds (14h35), replacing 43,200 seconds |
| Stage 3 laptop | Retain the approved 29,121-second cap; all laptop work is already complete |

At authorization, 56 valid attempts (42 laptop and 14 phone) were complete. Ten phone runs remain: the second stage-2 stock/baseline controls and all eight stage-3 phone runs, in their original order with all original barriers. Stage-2 phone had consumed 12,919.760657 seconds; whole phone had consumed 24,203.990267 seconds. The finite allowance uses the ten full 2,400-second reservations, 180 seconds of outside-reservation administration per run, **two separate 900-second restoration reserves**, and one 180-second post-staging/release recheck cushion per stage. Staging, probing, cooling and inference already consume each run's reservation; the additional cushion accommodates the existing check that still requires a full timeout after staging.

The resulting bounds are 19,159.760657 seconds for stage 2, 21,720 seconds for stage 3, and 52,163.990267 seconds cumulatively, rounded upward to the caps above. Each admission must still fit its full timeout **and retain 900 seconds in both remaining stage and whole-phone time budgets**. Pauses and fresh check records never renew a cap. Count/retry/storage limits, prepared commands, models, inputs, guard/quality checks, scientific provenance, run order and progression rules remain unchanged; no extra measurement reruns are introduced.

Fresh read-only verification observed GrapheneOS display build **2026101601**, whereas the recorded October-18 preflight observed **2026100601**. Android 17/API 37, platform build CP3A.261005.005, Tensor G5, FUTO 0.1.30-playstore and benchmark 0.2.5 remain observed unchanged. Frozen phone provenance records app/test builds and bundled runtime/model/configuration identities, but no OS display-build field. Preserve every original raw report and evaluator verdict; do not claim identical OS or pool the cohorts as a controlled resource comparison.

The separately pinned environment policy and exclusive per-run version observations own cohort evidence. The six October-18 stage-2 runs link to their earlier observed build; stage-1 per-run OS is unavailable rather than inferred. Capture a read-only version observation before every new run, after staging and before the thermal query, with no probing during inference. Final reporting must qualify cross-cohort stage-2 resource conclusions as confounded/unresolved even when frozen validation passes. Stage-3 comparisons may be interpreted within their actually observed common cohort; any further OS change remains visible. No scientific threshold or old verdict is rewritten. Retain the benchmark app and restore the independently verified exact setup baseline before closing each phone interval and final handback.
