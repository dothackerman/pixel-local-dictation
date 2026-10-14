# Experiment protocol

For the setup implementer preparing a runnable experiment and the executor preserving valid evidence. Scope and readiness live in [the current plan](current.md); future work lives in [the roadmap](../roadmap.md). Requirements and safeguards retain their own authority.

## Roles and setup limits

The **setup implementer** owns code, schemas, adapters, assets, builds and frozen commands. The **executor** (GPT 6.1 Sol in a fresh context) measures prepared configurations. The **reviewer** interprets evidence before new work; Astra supplies an independent adversarial review when requested. Roles do not depend on the originating chat. New code, models, unlisted variants or decision-rule changes return to setup.

Before costly preparation, record one bounded work order with deliverables, dependencies, fixtures and limits. Proposed ceilings per work order are eight active hours, 50 GB downloads, 100 GB additional storage, 24 export attempts, two repair attempts per dependency/conversion path, and 12 public/synthetic smoke processes totaling at most 60 inference minutes. The current experiment's remaining combined preparation shares one work order; stages do not receive separate allowances. Choose lower limits where possible. At a cap, report ready and blocked parts; further setup needs an explicitly authorized work order, not a silent reset. These defaults do not renew the completed stage-1 setup allowance.

### Comparison contract

Pin actual laptop/Android runtimes, source/build/model checksums and the device benchmark build; a sherpa version alone does not identify its embedded ONNX Runtime.

Version evaluator/schema changes explicitly; retain historical reports and verdicts. Phone candidates require a matching phone baseline under the same feed, timing protocol and build; laptop candidates require matching laptop controls. Dictionary mechanism acceptance requires genuine no-dictionary twins on the same platform. Resource-only diagnostics may retain dictionary settings without re-establishing dictionary benefit, but cannot earn new mechanism or product acceptance. Reuse a twin only when model, build, evaluator, inputs, feed, process mode, search, allocation settings and timing protocol match; the dictionary switch must be the sole difference. The normalizer and existing quality thresholds stay unchanged.

Record quality, resource cost and evidence completeness independently: a memory failure must still produce an accuracy report. Missing evidence is `incomplete`, not an accuracy failure. Old rows cannot be accepted under a new protocol merely because their filenames match.

### Measurement contract

For allocation comparisons, record independent recognizer/LID CPU arena and memory-pattern settings on laptop and Android. Profiling distinguishes encoder, decoder/search, language identification, queue wait and transfers where exposed; unavailable components remain explicit. Preserve cold-load and total Stop-to-final timing.

Distinguish fresh unpaced, fresh paced and persistent one-buffer measurements; record current RSS/PSS, high-water memory and available Java/native counters at defined phases. The historical full-run peak and procedure retain the existing memory gate. New modes are diagnostic until a replacement rule is explicitly versioned and, if it changes acceptance, approved by Oriol. Report both for acceptance; applying the old ceiling to a new metric or subtracting the paced increase on assumption cannot preserve the gate.

Freeze a deterministic set of extra Stop positions before candidate results are inspected. Include early, middle and near-boundary endpoints, covering different residual-chunk lengths and both idle and in-flight decoding states; clock position alone is insufficient. Use the same input prefixes for every configuration. Prepare prefix references/check annotations, marking any word cut mid-utterance separately from fully audible words. Record missing final output, tail omissions and duplicated words beside each wait, so losing the tail cannot appear as a speed win. Do not score a partial recording against its full-script reference. Full-take accuracy remains a separate measurement. Report median, maximum and all individual endpoint waits; a small endpoint set does not establish a population p95.

### Adapter validation

Setup smoke tests validate loading, API contracts, complete audio coverage, language alternation, deterministic configuration and output/report generation. They may use public fixtures and the known public collapse case. Label them setup validation; do not use them to quietly tune the personal-corpus ranking. Full development/guard comparisons and resource rankings belong to Sol.

For each new runtime/model, verify a trustworthy reference or publisher runtime, sample rate, preprocessing, chunk/context/output limits, finalization and silence handling. For native streaming models, use their streaming API rather than forcing Parakeet's chunk policy. Record both native-pipeline and matched-input comparisons where needed to separate runtime from pipeline effects.

Setup prepares Android paths only for candidates that have earned them or for explicitly labelled feasibility measurements. An oracle-language laptop model can be screened, but needs an automatic routing implementation and validation before product-path phone acceptance.

Reuse validated adapters and contracts when unchanged. New or changed contracts need regressions for control selection, coverage, full memory accounting and separate quality/resource reporting, plus public/synthetic checks of timing boundaries, process resets and endpoints. Defer unsupported candidates with a reason; preserve readiness of independent work.

### Executable handoff

Maintain one current experiment plan and private stage/run manifests under `corpus/lab/`. Freeze the following before their execution:

- source commit, environment lock, evaluator/schema version, APK identity, model and configuration hashes;
- exact commands and input/control/reference identifiers for every permitted run, including all output locations;
- comparison rules, laptop-to-phone advancement rules, endpoint list, run order, repetitions, timeout/storage/run-count limits and any predeclared adaptive branches;
- phone window with date and Europe/Zurich times, current build verification, approved access procedure and end-of-window restoration procedure;
- completed smoke-check evidence, expected output/report structure, failure/stop rules, and a handback template;
- a launch prompt selecting **GPT 6.1 Sol** and naming the experiment and prepared stages.

All numerical advancement tolerances must be executable and frozen before the executor starts. Keep the existing experimental resource ceilings (including their exact units from the evaluator), quality rules and recording trigger unless Oriol explicitly approves a separately justified protocol revision. A new measurement protocol is reported alongside historical values, never used to retroactively relabel them.

The executor must have an isolated execution context and a stable checkout. Pin the checkout and give the experiment exclusive ownership of its output/ledger namespace; setup does not edit shared code, models or ledger while it runs. Isolation must not copy personal corpus content out of `corpus/`. If managed worktree plumbing cannot satisfy that boundary, use the existing checkout with exclusive ownership and a fresh Sol context.

**Ready means every required field is filled, tests pass and all required artifacts exist.** Otherwise hand back `not ready` with a specific missing item. Once ready, the setup implementer reports readiness and waits for the user's instruction to launch the loop. Deployment is a separate decision.

## Execution rules

- Reproduce matched controls within frozen tolerances before comparisons. Expected control resource/guard failures are valid diagnostic evidence; missing or mismatched control evidence stops the affected stage. Independent laptop work continues only where the manifest permits it.
- Validate each run and each comparison barrier before downstream inference. Complete guard/silence checks precede acceptance. Keep raw reports, failures and repeats under unique identities; never overwrite evidence or reset a ledger to resume.
- Freeze repetitions, cooldowns and counterbalanced order. At most one thermal retry per affected configuration fits inside the declared total retry cap. Benchmarks run serially unless concurrency is the explicit hypothesis; parallel model preparation must not contaminate measurements.
- Stop the affected stage on invalid provenance/coverage, reproducible truncation, missing output, inaccessible phone, permission refusal, unexpected build/config or unhandled decoder failure. Hand back at exhausted budgets or window closure. A denied action is not retried through another access path.
- Use the approved phone path within a dated Europe/Zurich window. No run begins unless its full timeout and restoration margin fit. Setup/user provisioning precedes execution; the executor neither independently installs/uninstalls, changes settings/safeguards, obtains new permissions, merges branches nor installs a daily build. Intrinsic instrumentation provisioning must be explicit in the authorized operation.
- A **process measurement** is one measured process lifetime in one harness mode with a finite input suite. Count subprocesses separately, including controls/retries. Persistent takes have a fixed count and consume the audio/time budget; they are not fresh-process repeats. Freeze decodes, input minutes, elapsed-time/storage bounds and any adaptive branches.
- Keep weights/search/chunks fixed for runtime/precision comparisons where possible; disclose unavoidable changes. Unexpected text changes from allocation settings require diagnosis. Different architectures use documented complete paths; identical artifact hashes are one experiment.

## Handback

Return artifact/protocol identities, every run/deviation and consumed budget, matched per-language quality and term/non-term errors, false insertions, memory/load/endpoint/heat readings with repetitions, actual backend/fallback evidence, recording-trigger status, and final build/device/restoration state. Preserve failures and explicitly unavailable diagnostics. Label each hypothesis supported on development data, rejected by its test or unresolved; recommend the smallest next setup change without performing it.

Use the frozen experiment's completion criteria. Execution completion is separate from evidence quality, candidate acceptance and deployment approval. Finishing one stage does not finish a multi-stage experiment. A continuation preserves consumed budgets, ownership and existing evidence; it requires current launch checks and does not authorize a new phone window.

## Recording policy

The pre-registered dictionary trigger from [ADR-005](../decisions.md#adr-005--schema-2-range-finding-a-dictionary-as-mechanism-test-and-two-new-model-families) and the [historical campaign](../archive/campaigns-2026-10-13.md) remains: **at least ten terms recovered, zero public-guard dictionary insertions, guard WER no worse than experiment 0 by more than one percentage point, and historical stage-2 phone acceptance passed.** That last condition is not stage 2 of the allocation experiment. This is a manual policy check, distinct from the evaluator's five-term dictionary mechanism rule; the complete trigger is not enforced by that evaluator. The allocation experiment is diagnostic and cannot make a new trigger decision.

The four reserved scripts stay outside the repository during development. Oriol supplies recordings and reviews references once a candidate earns validation; a different recording policy requires his explicit approval. The older approximately two-point corpus-expansion agreement remains recorded in [results](../evaluation/results.md#approved-measurement-protocol). Its sample expansion and the later restricted recording policy have not been explicitly reconciled. Neither authorizes a new collection during the loop.
