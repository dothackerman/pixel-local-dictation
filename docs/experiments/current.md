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
