# Release 1a — measurement characterization

2026-10-14. Setup only is authorized. The experiment loop has not started. This brief implements the first release of [the plan](next-experiment-plan.md), within [the setup work order](setup-release-1a.md). A dated phone window and a separate launch instruction remain required.

## Authoritative artifacts

The private manifest is `corpus/lab/release-1a/manifest.json`. Its source commit, artifact hashes, exact run commands, output paths and comparison manifests govern execution. `environment.json` pins packages and native libraries. `smoke-summary.json` records setup validation. These files and all personal inputs/results remain inside `corpus/`. Setup is complete: twelve public/synthetic processes, including one successful six-result phone smoke. Both APK identities and full sample coverage were verified; this is harness evidence, not product acceptance.

Python 3.12.3 and sherpa-onnx 1.13.8 load successfully; the API reports ONNX Runtime 1.28.2. The Android AAR contains the same version string and independently recorded arm64 library hashes. The app version remains 0.2.5: use APK hashes to identify this build. No model downloads or exports were required.

Schema 4 adds independent recognizer/LID CPU arena and memory-pattern controls and profiling. Stock settings preserve production defaults. Baseline, FP32 beam-8 dictionary and single-instance Cohere have prepared stock/arena-off/pattern-off variants. Only stock FP32 is a phone configuration in release 1a. Allocation comparisons belong to subsequent releases.

Evaluator 4 is `tools/evaluate_v4.py`. Historical `evaluate.py`, the normalizer and numerical quality/resource rules remain unchanged. New reports require versioned, matched-platform provenance. Missing evidence is incomplete; resource failure does not suppress quality scoring. Repeated takes are scored separately. This release produces diagnostic results only: no dictionary-mechanism acceptance, deployment acceptance or new recording-trigger decision. Historical reports and ledger rows remain unchanged. Do not launch the old loop from this setup checkout.

## Measurement protocol

Each unpaced-only or paced-only command starts a fresh process and loads one recognizer. Persistent mode makes two passes with one recognizer and one serial executor, loading one recording at a time. Android rejects a reused diagnostic process-start identity. Reports include unique run/process identifiers. One second of silence warms the recognizer; its decode is included in the budget.

Without a staged `measurement.json`, the Android harness retains its original full-run allocation/timing sequence, including preloading the complete corpus. Additive build/configuration/freshness fields allow `tools/wrap_historical_measurement.py` to wrap newly produced evidence; old saved reports cannot be relabelled through that command. Keep both original high-water readings and the legacy 3,500,000 kB rejection threshold, 2,000,000 kB warning, median chunk RTF 0.30, maximum Stop delay 2,000 ms and maximum thermal status 2. New diagnostic metrics do not replace this historical gate.

Memory snapshots cover loading, warm-up, buffer loading, chunks, buffer release and recognizer release. Android supplies Java/native allocation counters; Python marks unavailable counters null. Load timing means a fresh recognizer in a fresh process, not a cold filesystem cache: checksum verification may warm file pages. Stop timing includes final text joining. Endpoint reports include input/covered samples, residual length and an atomic queued/in-flight snapshot.

ORT profiles distinguish encoder, decoder and joiner for the pinned Parakeet graph names; LID has a separate profile prefix. Unknown graphs remain unassigned. Native search and transfer time are not separately exposed by this CPU binding. Node-duration sums are not wall time. Profiling is off for the frozen 1a comparisons; the public profiling smoke establishes the callable diagnostic path without changing those comparisons.

The six endpoints in `endpoint-suite.json` are deterministic German/English public prefixes: early, near-boundary and later positions. They contain complete source clips followed by known silence. Prefix references exclude unheard words; cut-word annotations are null. Setup observations cover both idle and in-flight decoding and varied residual lengths. Position names are identifiers, not asserted decoder states. Report individual, median and maximum waits beside prefix errors, missing output, tail match and adjacent duplicates. These lexical checks flag cases for inspection; they do not prove that a specific word was omitted. Full personal-take accuracy remains separate. Do not claim a population p95.

## Frozen laptop stage

The manifest enumerates ten planned processes plus two retry slots: baseline quality twice, stock FP32 quality twice, and stock FP32 in each of three diagnostic modes twice. All inference is serial. The cap is eight inference hours and 10 GB additional storage. The manifest itemizes source audio and conservative decode bounds, including warm-up, guard, silence, repeats and retries. The hold-out remains excluded.

Use only the manifest's concrete argument arrays and unique output paths under `corpus/lab/release-1a/runs/`. The callable adapters are:

- `tools/quality_measurement.py`: complete existing three-feed personal development suite, 210 public guard clips and silence checks.
- `tools/measurement.py`: the named diagnostic mode and finite input manifest; persistent mode uses exactly two passes.
- `tools/evaluate_v4.py`: matched diagnostic comparison using frozen expected provenance, coverage and prefix references.
- `tools/summarize_profiles.py`: per-session kernel/provider evidence, preserving unassigned graphs.

Controls run first. Identical repetitions must reproduce normalized text exactly; mismatches stop the affected comparison for diagnosis. Expected resource or guard failures remain valid diagnostic evidence. There is no model advancement decision in 1a: only the already preselected stock FP32 quality control is eligible for the phone. The existing quality thresholds remain authoritative; this release reports measured deltas and failures without granting new acceptance. In particular, dictionary mechanism acceptance still requires a genuine same-platform no-dictionary twin in a separately budgeted release.

`retry_options` lists the concrete command/output/audio/decode bound for every eligible original run in each of two slots per platform. Select at most one option per slot, only after failure; these are alternatives, not additional planned runs. Compare the chosen retry output using the original run's comparison contract and a unique report output suffixed with its retry ID. Retain original evidence. All retries consume the same overall time/storage cap. The quality input hash includes every public guard WAV and deterministic silence/noise parameters.

## Phone stage — separate launch required

Eight planned processes plus two retries: unpaced, paced, persistent, historical, historical, persistent, paced, unpaced. Cool down five minutes between processes. Permit at most one thermal retry per affected configuration within the two-retry total. Stop earlier if the question is answered or evidence is invalid. Each process must fit its declared timeout plus the 15-minute restoration margin. The overall ceiling is the smaller of four hours and the authorized phone window minus that margin.

The sum of process timeouts exceeds four hours even before cooldowns. This schedule may therefore stop incomplete at the cap; completion of all eight processes is not guaranteed. Never start a process whose timeout and restoration margin exceed the remaining window.

Run the following as separate invocations, substituting only the staged directory from the manifest:

```text
/home/oriol/Projects/private/pixel-local-dictation/tools/phone push-variant /home/oriol/Projects/private/pixel-local-dictation/corpus/lab/release-1a/push/MODE
/home/oriol/Projects/private/pixel-local-dictation/tools/phone lab
/home/oriol/Projects/private/pixel-local-dictation/tools/phone pull-results
```

Use each run's `record_command`, substituting the result path actually returned by the preceding pull. It preserves raw bytes under the unique run ID and rejects reused run/process identities; it wraps historical output with its exact staged manifest. Verify build/config hashes before scoring. Guards and silence from the laptop must remain explicitly labelled as laptop evidence.

The current setup authorization permits benchmark provisioning and one public/synthetic device smoke only. It does not reserve a loop window. Before launch, record the date and Europe/Zurich start/end times, verify the actual build again, and complete [the activation procedure](agent-safeguards.md) in the fresh Sol session. The manifest must name the restoration owner and approved restoration artifact/procedure. Sol cannot install a daily build, change settings or improvise restoration. Until those launch fields are filled, readiness remains false.

Run `python3 tools/check_release_1a.py --setup-only` to verify prepared artifacts. Without that option, the check also requires launch readiness. The setup smoke's measured identities remain unchanged even if later evaluator-only fixes change the batch evaluator hash.

## Failure handling and handback

Stop the affected stage on missing evidence/output, reproducible truncation, unexpected artifacts, permission refusal, decoder failure, exhausted budgets or insufficient time. Preserve failed/partial reports and all repeats. Never overwrite a completed local result, reset a ledger, request new recordings or launch another batch. The recording trigger is unchanged and is not opened by this diagnostic release.

The handback contains artifact/protocol identities; all runs, failures and deviations; matched per-language full-take deltas; terms/non-term errors, guard insertions and silence; every endpoint wait and prefix check; memory/load/thermal repetitions; backend/profile availability; the historical gate beside diagnostic readings; unchanged recording-trigger status; final build/device state; and supported/rejected/unresolved conclusions with the smallest next setup change.

The existing checkout is the intended exclusive execution context. Do not copy the corpus to a worktree. Keep source/models stable while Sol runs and give its output namespace exclusive ownership. No Sol chat or automation has been created.

## Launch prompt template

Do not send this until the manifest passes readiness and Oriol explicitly authorizes launch:

> Use GPT 6.1 Sol in a fresh isolated chat. Read docs/batch-1a-v1.md, its private manifest, AGENTS.md and the safeguard procedure. Execute only release 1a's frozen commands inside the authorized Europe/Zurich window and budgets. Use the stable checkout exclusively. Do not edit code/models, install apps, change settings, launch another batch or request recordings. Preserve every result and return the specified handback. A missing prerequisite means stop and return to setup.
