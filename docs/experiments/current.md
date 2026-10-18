# CURRENT EXPERIMENT — Allocation and measurement

**Prepared and device-validated; execution has not started.** The one-loop, three-stage plan was approved on 2026-10-14. [The workflow](allocation/workflow.md) owns frozen execution details and completed setup evidence. Actual experiment launch requires Oriol’s separate instruction and dated phone windows.

**Question:** can allocation changes make the FP32 Parakeet and single-instance Cohere quality controls cheaper on the phone without losing their demonstrated development-set accuracy?

**Deliverable:** one decision table separating quality, memory, load/Stop latency, heat and evidence completeness, with all repetitions and a recommendation for the next experiment. Completing the loop does not mean a candidate passes; this is diagnostic work, not deployment or R4 acceptance.

## Scope and readiness

| Stage | Measurements |
|---|---|
| **1. Characterize measurement** (legacy artifact name `release-1a`) | Frozen laptop controls; stock FP32 phone control in fresh unpaced, fresh paced, persistent and historical-full modes, twice each. [Stage-1 runbook](allocation/stage-1-runbook.md). |
| **2. Compare FP32 allocations** (formerly 1b) | FP32 beam-8 dictionary: stock, arena-off only, pattern-off only in persistent mode, twice each; app baseline twice. |
| **3. Compare Cohere allocations** (formerly 1c) | Single-instance Cohere with the same three settings and persistent mode, twice each; app baseline twice. |

All stages retain complete quality checks. New models, combined arena/pattern disabling, precision exports, production changes, fresh recordings and mechanism/deployment acceptance are outside this loop. No-dictionary phone twins and historical-gate confirmation for a finalist remain separately budgeted acceptance work. [The roadmap](../roadmap.md#future-experiments) owns subsequent experiments and deferred ideas.

One loop means one objective and cumulative evidence, not one night or automatic expansion of a frozen manifest. The legacy 1a/1b/1c names identify artifacts only; they no longer require three independently commissioned experiment loops. [ADR-008](../decisions.md#adr-008--one-allocation-experiment-with-three-stages) records why the old split changed.

## Next action

The three-stage scheduler, validation barriers and cumulative continuation are implemented. Do not repeat their preparation. [The workflow](allocation/workflow.md) owns executable commands and readiness checks; [the shared protocol](protocol.md) owns execution rules.

Wait for Oriol’s separate launch instruction and dated phone windows. The fresh executor verifies the committed source and applicable safeguards, records only the authorization actually supplied, and follows the combined helper’s permitted next steps. The [launch prompt](allocation/workflow.md#launch-handoff) identifies the model and scope. Completed public-only smoke tests do not authorize personal-corpus execution.

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

The [workflow's frozen budget](allocation/workflow.md#frozen-design) is smaller than these approved outer ceilings and owns concrete counts, storage, retries and active-work accounting. Neither an unused allowance nor a new window permits additional runs. Do not promise all planned processes fit: stage 1's summed timeouts already exceed four hours.

At each window boundary, finish validation, preserve state and hand restoration to the named owner. Resume only with the next authorized dated window, verified artifacts and fresh applicable safeguards. A timeout is not proof that the device process exited; the frozen access path has no guaranteed hard-abort operation.

## Completion and handback

The loop is **completed** when all required comparisons in the prepared three-stage manifest have valid evidence and the decision table answers each hypothesis, including negative results. It is **partial** if a budget/window ends before that point, and **blocked** if invalid evidence or missing preparation prevents continuation. Report which stages completed; never label the whole loop complete because stage 1 ended.

Use the [protocol's handback fields](protocol.md#handback). Preserve individual waits and quality checks beside each setting, matched-platform deltas, fresh versus persistent memory and the unchanged historical gate. Recommend the smallest next investigation; the [recording policy](protocol.md#recording-policy) and final FUTO validation remain unchanged.

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
