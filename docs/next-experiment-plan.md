# Next accuracy experiments: setup and execution

**Status: proposed plan, 2026-10-14. Planning and an isolated Astra review only are authorized now. Neither setup nor the experiment loop has started.**

The objective is to preserve or improve recognition in both languages while making an accuracy winner practical on the phone. [Requirements](requirements.md) remain authoritative. This plan governs the next round; [the old loop brief](autoresearch.md) describes the completed third night and is not its launch instruction. Historical ADRs and results retain their original meaning.

## Ownership and boundaries

| Phase | Owner | Deliverable |
|---|---|---|
| Setup | The primary assistant in this chat | Correct measurement tools, prepared model/runtime variants, verified adapters, and a frozen batch brief |
| Experiment loop | **GPT 6.1 Sol**, in a fresh session | Execute that batch, preserve all evidence, and return a decision table and handback |
| Interpretation and next setup | Primary assistant | Review Sol's evidence, diagnose surprises, and prepare the next bounded batch |
| Independent plan review | **GPT 6 Astra**, isolated from this conversation | Adversarial critique of this document; no setup or experiments |

Setup owns changes to code, schema, scoring implementation, adapters, model downloads/conversions, Android builds and dependency pins. Sol owns measurement with the prepared artifacts. New code, a new model, an unlisted variant, or a changed decision rule returns to setup rather than being improvised inside the loop.

The current request authorizes writing/reviewing this plan only. A later instruction to start setup starts Phase A; it does not implicitly launch Sol or reserve the phone. A later instruction to start the loop must identify a ready batch and its permitted phone window. No automatic overnight scheduling or recursive handoff is created by this plan.

## Evidence to consult

- [Evaluation audit](evaluation-audit-2026-10-14.md): matching phone controls, development-set limitations, and endpoint coverage.
- [Runtime investigation](runtime-options-2026-10-14.md): measured allocation-control lead, selective precision, alternative runtimes and hardware-acceleration feasibility.
- [Model screening proposals](model-options-2026-10-14.md): Moonshine, Whistle, Qwen, and conditional larger-model comparisons.
- [Decision records](decisions.md): why earlier loops had a closed schema and what was subsequently fixed.

Those documents own the measurements and source claims. This document owns sequencing, responsibilities and handoff conditions. The current gains are development evidence; none is a new R4 acceptance result.

## Phase A — setup by the primary assistant

Work in small releases. **Prepare batch 1 first; delivering it must not wait for every model or accelerator to be integrated.** Later batches repeat this setup/handoff process after reviewing the preceding evidence.

Before costly work, write a setup work order naming that release's deliverables, dependencies, download/storage/export limits and smoke fixtures. Proposed ceilings per release are eight hours of active setup work, 50 GB downloaded, 100 GB additional storage, 24 export attempts and two repair attempts per failing dependency or conversion path; the allocation-only release needs no new model downloads or exports. Smoke validation is capped at 12 process measurements and 60 minutes of inference, using public/synthetic fixtures. Lower limits are preferred. At a limit, report ready artifacts and defer the blocked path; do not silently renew the budget. These are setup checkpoints, not permission to begin now.

### A1. Establish a reproducible environment and comparison protocol

Inspect the working tree, preserve existing work, and identify the actual laptop and Android runtime versions. Repair missing local dependencies in a pinned environment when setup is authorized. Record source/build/model checksums and the exact device benchmark build; a sherpa version alone does not identify its embedded ONNX Runtime.

Version the next evaluator and report schema explicitly. Retain historical reports and verdicts. Change comparison provenance so phone candidates use the matching phone baseline under the same feed, timing protocol and build; laptop candidates use matching laptop controls. Dictionary mechanism acceptance requires genuine no-dictionary twins on the same platform. Resource-only diagnostics may retain dictionary settings without re-establishing dictionary benefit, but cannot earn new mechanism or product acceptance. Reuse a twin only when model, build, evaluator, inputs, feed, process mode, search, allocation settings and timing protocol match; the dictionary switch must be the sole difference. The normalizer and existing quality thresholds stay unchanged.

Record quality, resource cost and evidence completeness independently: a memory failure must still produce an accuracy report. Missing evidence is `incomplete`, not an accuracy failure. Old rows cannot be accepted under a new protocol merely because their filenames match.

**Done:** regression checks establish correct control selection, complete evidence validation, full memory accounting and separate quality/resource reporting. Versioned controls can be reproduced; this is not yet a model ranking run.

### A2. Prepare memory and finalization instrumentation

Expose CPU arena and memory-pattern settings explicitly on both laptop and Android, keeping model and language-identifier sessions distinguishable. Provide profiling with encoder, decoder/search, language identification, queue wait and transfers separated where the runtime exposes them. Preserve cold-load and total Stop-to-final timing.

Prepare three separate harness modes: fresh-process unpaced-only, fresh-process paced-only, and repeated takes with a persistent recognizer/executor and one recording buffered at a time. Record current RSS/PSS, high-water memory and available Java/native allocation counters at defined phases. The historical full-run peak and its original measurement procedure retain the existing memory gate. New modes are diagnostic until a replacement rule is explicitly versioned and, if it changes acceptance, approved by Oriol. Report both when making an acceptance claim; applying the old numeric ceiling to a different metric does not preserve the old gate. Do not subtract the paced increase on assumption.

Freeze a deterministic set of extra Stop positions before candidate results are inspected. Include early, middle and near-boundary endpoints, covering different residual-chunk lengths and both idle and in-flight decoding states; clock position alone is insufficient. Use the same input prefixes for every configuration. Prepare prefix references/check annotations, marking any word cut mid-utterance separately from fully audible words. Record missing final output, tail omissions and duplicated words beside each wait, so losing the tail cannot appear as a speed win. Do not score a partial recording against its full-script reference. Full-take accuracy remains a separate measurement. Report median, maximum and all individual endpoint waits; a small endpoint set does not establish a population p95.

**Done:** timing boundaries, process-reset mechanics and endpoint manifest are documented and exercised on public/synthetic fixtures; runs can distinguish genuine memory savings from retained benchmark allocations.

### A3. Prepare the next batch and its checks

Setup smoke tests validate loading, API contracts, complete audio coverage, language alternation, deterministic configuration and output/report generation. They may use public fixtures and the known public collapse case. Label them setup validation; do not use them to quietly tune the personal-corpus ranking. Full development/guard comparisons and resource rankings belong to Sol.

For each new runtime/model, verify a trustworthy reference or publisher runtime, sample rate, preprocessing, chunk/context/output limits, finalization and silence handling. For native streaming models, use their streaming API rather than forcing Parakeet's chunk policy. Record both native-pipeline and matched-input comparisons where needed to separate runtime from pipeline effects.

Setup prepares Android paths only for candidates that have earned them or for explicitly labelled feasibility measurements. An oracle-language laptop model can be screened, but needs an automatic routing implementation and validation before product-path phone acceptance.

**Done:** every listed run has available, checksummed artifacts and a callable adapter. Unsupported candidates are deferred with a reason; they cannot block delivery of a ready independent batch.

### A4. Freeze and hand off

Produce one versioned batch brief under `docs/` and its private run manifest under `corpus/lab/`. The brief must contain:

- source commit, environment lock, evaluator/schema version, APK identity, model and configuration hashes;
- exact commands and input/control/reference identifiers for every permitted run, including all output locations;
- comparison rules, laptop-to-phone advancement rules, endpoint list, run order, repetitions, timeout/storage/run-count limits and any predeclared adaptive branches;
- phone window with date and Europe/Zurich times, current build verification, approved access procedure and end-of-window restoration procedure;
- completed smoke-check evidence, expected output/report structure, failure/stop rules, and a handback template;
- a ready-to-paste prompt selecting **GPT 6.1 Sol** and naming this one batch.

All numerical advancement tolerances must be executable and frozen before Sol starts. Keep the existing experimental resource ceilings (including their exact units from the evaluator), quality rules and recording trigger unless Oriol explicitly approves a separately justified protocol revision. A new measurement protocol is reported alongside historical values, never used to retroactively relabel them.

Sol must have an isolated execution context and a stable checkout. Pin the checkout and give the batch exclusive ownership of its output/ledger namespace; setup does not edit shared code, models or ledger while it runs. Isolation must not copy personal corpus content out of `corpus/`. If managed worktree plumbing cannot satisfy that boundary, use the existing checkout with exclusive ownership and a fresh Sol context.

**Ready means every required field is filled, tests pass and all required artifacts exist.** Otherwise hand back `not ready` with a specific missing item. Once ready, the primary assistant reports readiness and waits for the user's instruction to launch the loop. Deployment is a separate decision.

## Batch sequence and purpose

Each row is a separate setup release and Sol loop, not one unbounded overnight search. Setup sets exact run/storage/time budgets within the caps below; stop earlier when the prepared question is answered.

| Batch | Setup prepares | Sol measures | Purpose / next decision |
|---|---|---|---|
| **1. Allocation and measurement** | A1–A2; baseline, FP32 beam-8 dictionary control, single-instance Cohere; stock, arena-off only, pattern-off only settings | Complete laptop quality/guard comparisons, memory modes, varied endpoints; eligible phone configurations with repeat measurements | Determine whether allocation changes reduce the deployment cost while retaining the demonstrated quality; identify the actual latency bottleneck |
| **2. Small-model challengers** | Whistle and Moonshine adapters, pinned full models, unbiased and dictionary twins; Moonshine oracle-language screen distinguished from automatic routing | Per-language quality, terms, false insertions, silence and native finalization; phone only for a prepared complete automatic path | Find a simpler model/runtime path with useful accuracy and phone headroom |
| **3. Precision and execution alternatives** | A finite set of per-channel/selective-layer Parakeet exports; relevant CPU-provider/version comparison; a hardware feasibility sub-batch | Public collapse regression plus full development/guard quality, size, memory, speed and backend assignment | Preserve FP32 beam quality with lower costs, or establish whether a different execution path is worth a port |
| **4. Larger-model quality screen** | Qwen 1.7B reference/native pair and a 0.6B reference diagnostic; conditional Canary 1B v2/full Whisper large-v3 | Complete transcription and per-language quality first; phone work only after a quality result and prepared adapter | Determine whether model capacity or conversion is limiting accuracy before expensive deployment work |

A **process measurement** is one measured process lifetime in one harness mode with a frozen, finite input suite. A wrapper call that starts three measured processes costs three measurements. Repeated takes within a persistent process have a fixed count and count toward the audio/time budget; they are not fresh-process repeats. Every brief itemizes subprocesses, decodes, input minutes and elapsed-time limits, including controls and retries.

Batch 1 is split into three independently handed-off releases; it is not promised to fit one night:

| Release | Phone allocation | Maximum |
|---|---|---|
| **1a: characterize measurement** | One preselected stock quality control in the three new modes, twice each (6), plus its historical full-run protocol twice (2) | 8 planned + 2 retries = **10 process measurements** |
| **1b: allocation comparison** | FP32 beam-8 dictionary control: stock, arena-off and pattern-off in one mode chosen from 1a, twice each (6), plus the app baseline twice (2) | 8 planned + 2 retries = **10 process measurements** |
| **1c: second quality control** | Single-instance Cohere: the same three settings in the selected mode, twice each (6), plus the app baseline twice (2) | 8 planned + 2 retries = **10 process measurements** |

Releases 1b/1c are resource diagnostics with full quality checks, not new dictionary-mechanism or deployment acceptance. No-dictionary phone twins and historical-gate confirmation for a finalist require a separately budgeted follow-up release before acceptance. The endpoint suite is fixed within the selected mode; if it requires extra process starts, reduce the listed configurations or move that coverage to another release. Combined arena/pattern disabling is deferred until individual effects are understood.

Each release, including 1a–1c, has a ceiling of **12 laptop configurations, 40 total laptop process measurements and eight hours of laptop inference**, with controls/repeats/retries included. The phone elapsed-time ceiling is the smaller of four hours and its authorized window minus restoration margin. Later releases allow **eight total phone process measurements** each, including controls/repeats/retries. Setup may choose smaller limits; incomplete coverage produces an unresolved result, not relaxed checks. Fine-grained layer sweeps use a predeclared grouped search within the export/run caps. A new hypothesis after a cap requires another setup release. Laptop inference benchmarks are serial unless a batch explicitly studies concurrency; model preparation may be parallel only while it cannot contaminate measurements.

Hardware feasibility preparation can proceed alongside batch 2 **after batch 1 profiling is reviewed**; it does not wait for all larger models. Setup prepares an official Tensor G5 sample within the permitted benchmark package. Sol performs the frozen phone feasibility measurements. If actual accelerator execution and correct output are established, setup prepares one fixed-shape encoder plus the existing CPU search, and Sol measures it in the next hardware sub-batch. GPU or CPU alternatives likewise need a named supported backend, not a generic availability flag. Every sub-batch counts as a release with its own cap and handoff.

Quantization and acceleration comparisons keep weights/search/chunks fixed wherever possible and explain any unavoidable conversion/precision difference. Allocation changes should preserve output; unexpected text changes trigger diagnosis before accepting them as transparent runtime optimizations. Different model architectures are ranked through their documented complete paths. The Unsloth/Qwen artifact overlap is already covered in the runtime research; identical files are one experiment.

## Phase B — frozen experiment loop by GPT 6.1 Sol

1. **Preflight:** read the batch brief and project boundaries; verify source/config/artifact hashes, exclusive ownership, free disk, build identity and permitted window. Check the run manifest rather than interpreting the research notes as permission to expand the batch. A missing prerequisite produces a handback to setup.
2. **Controls first:** reproduce the batch's laptop controls. Phone runs begin with the matching phone control. Control failure means failing to reproduce the predeclared expected evidence/configuration within a frozen tolerance; it stops the affected stage as an invalid comparison. Reproducing a control's expected resource or guard failure is valid diagnostic evidence, not control failure. Independent laptop work can continue only if the brief permits it.
3. **Execute the listed comparisons:** vary one declared factor at a time. Run the complete guard and silence checks before a candidate can earn acceptance. Accuracy controls already failing resource gates can still be measured when explicitly labelled as such; their historical false-insertion or other failures remain visible.
4. **Phone stage:** serial runs only, through the existing authorized wrapper, within the reserved window. Sol does not install/uninstall apps, change settings, edit the wrapper/gates or obtain new phone permissions. Setup/user provisioning precedes the loop. Pin a repetition/cooldown and counterbalanced order in the brief; temperature alone is not a causal explanation for timing changes. Record freshness identifiers so a stale result cannot become a new repeat.
5. **Record every outcome:** append uniquely identified raw reports and summaries, including failures and all repeat readings. No overwriting a first run with a second, retroactive report edits, ledger resets or cherry-picking the better repetition. Personal audio/transcripts and derived personal reports remain under `corpus/`.
6. **Return the handback:** end when the batch is complete, a stop condition fires, its budget is exhausted or the phone window closes. Do not launch another batch, change production defaults, merge branches, install a daily build or request fresh recordings.

Stop the affected stage on failed evidence validation, reproducible truncation, missing output, inaccessible phone, permission refusal, unexpected build/config or unhandled decoder failure. A permission refusal is not retried through another access path. The brief specifies at most one thermal retry per affected configuration, inside the overall cap; otherwise record the limit and stop. No phone run starts unless its declared timeout plus restoration margin fits the window.

The handback contains: artifact/protocol identities; all runs and deviations; matched per-language quality deltas; terms/non-term errors and false insertions; memory/cold-load/endpoint/heat readings with repetitions; actual backend/fallback evidence; unchanged recording-trigger verdict; and a decision per hypothesis: supported on development data, rejected by the defined test, or unresolved. Include paths to private evidence and the final phone/build state. Recommend the smallest next setup change without implementing it.

## Primary assistant's review and final validation

Review the full handback and raw evidence before choosing the next setup batch. Keep confidence proportional to eight distinct scripts, repeated takes and an already inspected public guard. Use script-level paired sensitivity summaries as descriptive evidence, not proof of generalization. A routed German-only winner remains a design hypothesis until the complete automatic path improves both languages.

Once one candidate is technically plausible, freeze its settings and vocabulary and design one small reserved validation session covering the missing short/spontaneous use. Compare the complete automatic phone path against the installed FUTO Multilingual-244, establishing speed and accuracy separately in English and German as R4 requires. Use identical audio where feasible within the phone boundary; otherwise label matched live trials and their different-take limitation. Include correction effort and meaning/formatting errors alongside normalized word errors and latency. Leave R4 unresolved if these comparisons cannot establish it; winning against the app baseline alone is insufficient.

Oriol supplies speech, operates FUTO manually and checks references; the assistant owns evaluation design and interpretation. Any new recordings remain subject to the existing recording trigger or an explicitly approved revision. No new recording request is part of this planning task.

## Adversarial review record

An isolated **GPT 6 Astra** subagent reviewed the draft against repository documents without inheriting this conversation or executing setup/experiments. Its initial verdict was **revise**, with seven findings: missing phone dictionary twins; infeasible first-batch measurement arithmetic; absent explicit final FUTO comparison; missing prefix-completeness checks; ambiguous control failure; changed memory-gate semantics; and insufficient setup/laptop limits.

The revision addresses these with diagnostic-only allocation releases, explicit process budgets and later acceptance twins, final FUTO validation, annotated endpoint checks, expected-control tolerances, preserved historical memory gates and bounded setup/laptop work. Astra's read-only follow-up verdict was **ready to present as a proposed plan**: all seven findings resolved, with no remaining actionable blocker. Execution readiness still requires the concrete frozen brief, artifacts, tolerances, suite sizes and authorized phone window during future setup. No setup or experiment execution is authorized by either review.
