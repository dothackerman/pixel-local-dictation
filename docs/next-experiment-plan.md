# CURRENT EXPERIMENT — Allocation and measurement

**Plan approved for one loop with three stages on 2026-10-14. Execution has not started.** Stage 1 is prepared; stages 2–3 and cross-window continuation still need executable contracts. This documentation revision does not launch measurements or authorize phone access.

**Question:** can allocation changes make the FP32 Parakeet and single-instance Cohere quality controls cheaper on the phone without losing their demonstrated development-set accuracy?

**Deliverable:** one decision table separating quality, memory, load/Stop latency, heat and evidence completeness, with all repetitions and a recommendation for the next experiment. Completing the loop does not mean a candidate passes; this is diagnostic work, not deployment or R4 acceptance.

## Scope and readiness

| Stage | Measurements | Preparation status |
|---|---|---|
| **1. Characterize measurement** (legacy artifact name `release-1a`) | Frozen laptop controls; stock FP32 phone control in fresh unpaced, fresh paced, persistent and historical-full modes, twice each | Prepared. [Frozen stage-1 runbook](batch-1a-v1.md) and private manifest own exact commands, barriers and limits; [setup evidence](setup-release-1a.md) records tested modes. Launch prerequisites remain. |
| **2. Compare FP32 allocations** (formerly 1b) | FP32 beam-8 dictionary: stock, arena-off only, pattern-off only in the selected mode, twice each; app baseline twice | Variants/adapters prepared; exact run manifests, quality controls, comparison barriers and transition checks are not frozen. |
| **3. Compare Cohere allocations** (formerly 1c) | Single-instance Cohere with the same three settings and selected mode, twice each; app baseline twice | Same remaining preparation as stage 2. |

All stages retain complete quality checks. New models, combined arena/pattern disabling, precision exports, production changes, fresh recordings and mechanism/deployment acceptance are outside this loop. No-dictionary phone twins and historical-gate confirmation for a finalist remain separately budgeted acceptance work. [The roadmap](roadmap.md#future-experiments) owns subsequent experiments and deferred ideas.

One loop means one objective and cumulative evidence, not one night or automatic expansion of a frozen manifest. The legacy 1a/1b/1c names identify artifacts only; they no longer require three independently commissioned experiment loops. [ADR-008](decisions.md#adr-008--one-allocation-experiment-with-three-stages) records why the old split changed.

## Next implementer: finish preparation

Read [the shared protocol](experiment-protocol.md) for preparation and execution rules. Preserve the already prepared stage-1 artifacts. Before claiming the **whole loop** is ready:

1. Freeze stage-2/3 commands, exact fixtures, quality/guard/silence controls, output namespaces, counterbalanced order, repetitions, cooldowns, timeouts and comparison tolerances. Keep the endpoint suite fixed across settings. Itemize all measured subprocesses, audio minutes, decodes and retries inside the budgets below.
2. Implement and validate the stage transition rule below against synthetic valid, incomplete and contradictory reports. The executor must receive a concrete permitted next step or an explicit handback, not interpret research notes as additional tasks.
3. Provide append-only cumulative accounting and a checked continuation record across windows/stages. Retain completed evidence, failed attempts, consumed budgets, ownership and the next run. Validate restoration, fresh-session checks and artifact identity on resumption.
4. Run the protocol's setup checks and report readiness per stage. Advance from a valid stage directly to the next prepared stage; ask for interpretation only at an unresolved decision or when a new authorization is needed.

**AS-IS limitation:** `tools/release_execution.py` executes the frozen stage-1 manifest. Its platform wall clock runs from the first reservation through pauses, and its ledger binds the manifest hash. It does not implement a combined three-stage plan or pause the clock overnight. Editing the manifest, resetting the ledger or changing the window cannot supply that capability. Code/manifest work is required; none is performed by this documentation-only change.

If Oriol launches only the prepared stage 1 before that work is complete, execute its frozen runbook unchanged and hand back at its boundary. Record that as a partial execution of this experiment, not completion of the whole loop.

## Stage transition rule

The intended comparison mode is **persistent**, because the allocation question concerns repeated dictation with one loaded recognizer/executor and one audio buffer at a time. Stage 1 tests whether that measurement is trustworthy; it is not a contest to select whichever mode reports the smallest memory.

After all required stage-1 validation/comparison barriers pass, select persistent mode only if both phone repetitions have complete provenance, expected take/endpoint coverage, valid required memory/timing counters, identical normalized text under the frozen repeat rule, and no unexplained output/coverage failure. Retain fresh-process and historical-full results as diagnostic context. Failing a historical resource ceiling is a measured result, not a reason to reject a valid diagnostic control.

Missing or contradictory evidence, or an unsuitable persistent mode, produces an interpretation handback before allocation comparisons. Do not switch modes or loosen a threshold after seeing results. An alternative needs a documented protocol revision and prepared contracts. This rule is specified here but is **not yet implemented** by the stage-1 helper.

Stage 2 precedes stage 3. Advance after its required evidence is complete, even if allocation changes show no benefit. Unexpected text changes from an allocation setting require diagnosis before treating the setting as transparent. An invalid comparison stops downstream work; adding a model, changing acceptance or repairing an adapter belongs to preparation.

## Budgets and phone windows

The former release caps are retained as stage caps, with one cumulative envelope. Controls, repeats, failures and retries all count; stage transitions and new windows never renew them.

| Stage | Laptop limit | Phone limit |
|---|---|---|
| 1 | Frozen manifest: 10 planned processes + 2 retry slots, 8 hours, 10 GB additional storage | 8 planned + 2 retries; at most 4 hours and within the authorized window minus restoration margin |
| 2 | At most 12 configurations, 40 processes and 8 hours; exact count/storage must be frozen in setup | 8 planned + 2 retries; same 4-hour/window ceiling |
| 3 | At most 12 configurations, 40 processes and 8 hours; exact count/storage must be frozen in setup | 8 planned + 2 retries; same 4-hour/window ceiling |
| **Whole loop ceiling** | **92 processes and 24 hours**, plus an explicit combined storage ceiling before full-loop launch | **30 processes and 12 hours**, within separately authorized windows |

These are ceilings, not an instruction to fill them. Stage 1's stricter retry rules still apply. For stages 2–3, at most one thermal retry per affected configuration fits within their two retry slots; other retry cases must be frozen before execution. Extra endpoint process starts consume the same cap: reduce configurations or leave that coverage unresolved.

The combined runner must define measured work time across windows, including build/push/pull overhead and cooldowns while working, without charging planned overnight pauses as inference or silently resetting elapsed work. Until it exists, stage 1 retains its current wall-time accounting and single-window stop rules. Do not promise all planned processes fit: stage 1's summed timeouts already exceed four hours.

At each window boundary, finish validation, preserve state and hand restoration to the named owner. Resume only with the next authorized dated window, verified artifacts and fresh applicable safeguards. A timeout is not proof that the device process exited; the frozen access path has no guaranteed hard-abort operation.

## Completion and handback

The loop is **completed** when all required comparisons in the prepared three-stage manifest have valid evidence and the decision table answers each hypothesis, including negative results. It is **partial** if a budget/window ends before that point, and **blocked** if invalid evidence or missing preparation prevents continuation. Report which stages completed; never label the whole loop complete because stage 1 ended.

Use the [protocol's handback fields](experiment-protocol.md#handback). Preserve individual waits and quality checks beside each setting, matched-platform deltas, fresh versus persistent memory and the unchanged historical gate. Recommend the smallest next investigation; the [recording policy](experiment-protocol.md#recording-policy) and final FUTO validation remain unchanged.

On completion, move this plan to `docs/archive/` with its execution status, dates, evidence location, outcome and successor link. Completed execution, a supported hypothesis and deployment approval are separate facts.
