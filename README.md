# Pixel Local Dictation

Local English and German dictation on the GrapheneOS Pixel 10 Pro XL.
Open it with FUTO Keyboard's existing microphone key. The goal is less waiting
and fewer corrections than FUTO's installed Multilingual-244 model, in each
language. Source and issue tracking use a private GitHub repository.

This README defines the requirements, current design, operation and acceptance
gaps. [AGENTS.md](AGENTS.md) defines agent conduct, phone access and private-data
rules. The [ADRs](adrs/) record decisions and past experiment conclusions.
Experiment specifications and tickets live only in GitHub Issues. The next
experiment is [#1](https://github.com/dothackerman/pixel-local-dictation/issues/1).
The `ready-for-agent` label marks a prepared spec; it does not authorize a phone run.

## Current status

The app can record, recognize speech locally and insert text through FUTO's
microphone workflow. Tags `m1` and `m2` record these prototype gates.
Improvement over FUTO in both languages remains unproven. The current default
remains provisional. #4 closed October 21 on one take per language
([ADR-025](adrs/025-issue4-one-case-finding.md)): the WebGPU encoder keeps exact
text but every chunk decodes ~3.5× slower than CPU, so Stop wait grows ~5×.
#5 needs a re-plan; its full-suite and #8's live gates are unstarted.

Read the results and their limits before selecting the next question:

| Completed work | Result |
|---|---|
| [Milestones 1–2](adrs/010-ime-lifecycle.md) | Integration and local recognition demonstrated. On October 10, six matched-live scripts gave FUTO Stop waits of 79.9–99.7 s and app waits of 0.01–1.04 s. The takes differed; some comparisons used laptop replays. |
| [Experiment nights 1–3](adrs/004-closed-loop.md) | Findings and failures retained; no deployment winner. |
| [Allocation and measurement](adrs/012-allocation-outcome.md) | 66 measurements completed October 19; no production change. Quality and resource tradeoffs remain subject to the recorded comparison limits. |
| [Moonshine Small](adrs/014-moonshine-screen.md) | Did not advance. |
| [CPU profiling](adrs/015-cpu-profiling.md) | Eight phone runs on October 20 found substantial encoder work, but failed timing comparability checks. |
| [GPU/TPU access](adrs/016-accelerator-access.md) | Physical access proven. GPU FP32 passed the numerical smoke checks; TPU failed one check. Speech acceleration remains unimplemented. |
| [FP32 encoder on WebGPU](adrs/019-encoder-conversion.md), [ADR-020](adrs/020-onnx-webgpu-investigation.md)–[025](adrs/025-issue4-one-case-finding.md) | LiteRT conversion route unsupported; ONNX WebGPU chosen; strict fallback replaced by a placement check; CPU upgrade parity passed; WebGPU accurate but slower (one case). |

Campaigns are closed. The [next decisions](#remaining-acceptance-gaps-and-next-decisions)
are proposals. They authorize no new runs or recordings.

## Requirements

Confirmed October 9, 2026; R7 added October 10. These requirements apply to every engine.

| ID | Requirement |
|---|---|
| R1 | Keep FUTO’s microphone workflow. Do not add a picker or confirmation for each take. |
| R2 | Select English or German automatically, with one language per take. Allow ordinary names and loanwords. |
| R3 | Process speech only on the phone. No cloud fallback, Internet permission or telemetry. |
| R4 | Be faster and more accurate than the installed FUTO Multilingual-244 model, in each language. |
| R5 | Preserve meaning. Transcribe without translation or rewriting. Check names, numbers, omissions, invented text and formatting. |
| R6 | Follow the security rules in [AGENTS.md](AGENTS.md). Support return to FUTO and retain its baseline model. |
| R7 | Use Swiss spelling: replace ß with ss. The scorer treats both forms equally. |

A take is one recording session, from panel open to Stop or cancellation.
An engine that supports only one language is acceptable if routing is automatic.
Mixed-language conversation and diarization are outside scope.
Dialect support, Stop behavior preferences and battery/heat limits remain undecided.
About 3× less waiting, warm finalization below one second and 10–20% fewer corrections
are goals. They are not approved acceptance thresholds.

## Current design

FUTO microphone → voice input method editor (IME) → capture → consecutive chunks
split at pauses → serial local decoding → panel preview → Stop inserts text once
and returns to FUTO.

- [DictationImeService](app/src/main/kotlin/ch/og/dictation/DictationImeService.kt)
  starts recording when the panel opens. Cancel, Keyboard, input restart, hide or lock
  discard the take. Text stays in the panel until Stop.
  [ADR-010](adrs/010-ime-lifecycle.md) explains this behavior and FUTO discovery.
  [method.xml](app/src/main/res/xml/method.xml) declares a voice subtype without a
  locale and overrides implicit subtype selection. Even an input restart with
  identical attributes discards the take.
- [TakeSession](app/src/main/kotlin/ch/og/dictation/TakeSession.kt) owns
  each take, including capture drain, cancellation and editor acceptance.
  The IME and phone replay construct it through the same runtime factory.
  [ADR-017](adrs/017-shared-take-experiments.md) defines the seam and timing receipts.
  Its internal [TakeTranscriber](app/src/main/kotlin/ch/og/dictation/TakeTranscriber.kt) and
  [Engine](app/src/main/kotlin/ch/og/dictation/Engine.kt) decode closed chunks
  while recording continues. Stop queues the remaining audio. Each chunk is decoded
  independently, without encoder state from earlier chunks.
- [Variant](app/src/main/kotlin/ch/og/dictation/Variant.kt) selects the default:
  Parakeet v3 with a selective MatMul INT8 encoder and FP32 decoder/joiner.
  It runs on the CPU through sherpa-onnx. This choice is provisional:
  [ADR-003](adrs/003-working-engine.md).
- Lab alternatives use the same schema semantics in Python and Kotlin.
  Cohere uses one recognizer, sets the language for each stream and loads models
  from filesystem paths. Canary retains two recognizers:
  [ADR-007](adrs/007-fp32-cohere.md).
- [RuntimeConfig](app/src/main/kotlin/ch/og/dictation/RuntimeConfig.kt) and
  [runtime_config.py](tools/runtime_config.py) configure memory allocation for
  the recognizer and language identification (LID) independently. Stock settings
  remain the product default. Bounded counters for native processing roles and
  offline profile analysis support diagnosis:
  [ADR-012](adrs/012-allocation-outcome.md), [ADR-015](adrs/015-cpu-profiling.md).

## Build and host checks

1. Fetch pinned assets with `tools/fetch-assets.sh`. See [assets.lock](assets.lock).
   The shipped selective encoder uses `tools/export-fp32.sh` and
   `tools/quant_variant.py`. A clean full FP32 export remains unverified.
2. Configure signing with `tools/set-signing-password.sh`.
   [app/build.gradle.kts](app/build.gradle.kts) defines signing configuration
   and failure behavior. Keep signing credentials out of Git.
3. Build with `ANDROID_HOME=~/Android/Sdk ./gradlew :app:assembleRelease`.

`python3 tools/quality.py` runs Python regression tests, Kotlin benchmark unit
tests, instrumentation compilation and Android lint offline. It runs no device tests.
The local pre-commit gate runs it with `--staged`. Stage tracked edits and new
source, test and build inputs first so the checkout matches the index; a path-limited
`git commit PATH` with other changes pending therefore fails.
The gate rejects index changes during the run.

## Daily use and rollback

Recording requires an explicit take. The app releases the microphone afterwards.
Production audio stays in memory and is then discarded. Benchmark storage is
opt-in, visibly marked and deletable: [ADR-010](adrs/010-ime-lifecycle.md).
Models are provisioned locally; downloads are separate from inference.

The user enables the IME, grants microphone permission and enables FUTO’s
“Disable built-in voice input” toggle. For rollback, disable that toggle before
removing the app. The first take loads the model. Retention across a USB-powered
gap longer than 11 minutes is unverified. Release and benchmark builds share
signing; the benchmark build is debuggable. Lock files and build files define versions and hashes.

## Phone lab operations

Reach the phone only through the shim `.claude/hooks/phone <verb> …` (absolute
path in [AGENTS.md](AGENTS.md)), called alone on its command line; the result is
also in `corpus/lab/last.json`, which every call (even `status`) overwrites: save
it first when later work depends on it. `phone help` lists the verbs and exit codes;
[ADR-024](adrs/024-phone-access-rewrite.md) is the contract (scope, gating, what
`restore` cannot bring back).

Typical run: `snapshot NAME` → `check APP.apk TEST.apk` right after building →
`install` → `push DIR` → `run CLASS k=v` → `pull files/lab/result.json …` (single
files; `files/lab` holds the 2.4 GB model) → `restore NAME`. Each mutating verb
and `run` write `corpus/lab/runs/<ts>-<verb>-<id>/meta.json` with the installed
APK and lab hashes; experiments bind receipts to that. Pulled files stay in
gitignored `corpus/`.

To change the wrapper, edit `tools/phone` and `tools/test_phone.py` on the branch;
the user installs it with `bash tools/gate/apply.sh`. `python3 tools/test_phone.py`
and `python3 tools/test_agent_guard.py` run without a phone.

## Evaluation

For new work, use [the shared take and comparison interface](adrs/017-shared-take-experiments.md).
`python3 tools/lab.py --help` lists preparation, frozen comparisons and private
evidence commands. The tool never executes experiments. A preparation snapshot
with present artifacts still needs correctness checks. Old lab execution commands
are retired.

Prepare a fixed set of variants, freeze laptop controls, then collect matched
phone measurements. Report quality, resource use and evidence completeness
separately. The method is defined in [ADR-004](adrs/004-closed-loop.md),
[ADR-005](adrs/005-dictionary.md), [ADR-006](adrs/006-adapter-evaluator.md) and
[ADR-011](adrs/011-measurement-contract.md).

Ten development takes cover eight scripts, four per language. Three feeds test
sensitivity to chunk boundaries; they are not independent speech samples.
Public guard clips that have been inspected repeatedly are development evidence.
Phone accuracy requires phone controls. Dictionary benefit requires a matching
run without the dictionary. Laptop guard and silence results do not measure
phone hallucinations. Word error rate (WER) does not replace checks of correction
effort, meaning or formatting.

Private comparison plans and evidence stay in `corpus/lab/`. Record source,
runtime, APK, model and input identities; exact commands; controls; repeats and
endpoints; decision thresholds; failure rules; and restoration steps. Freeze the
comparison before its measured runs. The tracked experiment manifest lists host
artifacts and links to its GitHub issue; it does not duplicate the specification.
Deployment is a separate decision.

## Remaining acceptance gaps and next decisions

R4 requires comparison of the complete automatic phone path with installed FUTO,
for each language separately. Freeze settings and vocabulary before reserved
validation. Cover short, spontaneous and noisy speech; ambiguous starts;
correction effort; names, numbers, meaning and formatting; wrong-language output;
cold/warm latency; memory; and heat/battery use across repeated sessions.
Use identical audio where possible. Disclose comparisons that use different takes.
Answered calls, longer battery-only model survival and a clean full FP32 export
remain unverified. Dictionary entry and editing remain undecided.

[ADR-013](adrs/013-future-investigations.md) defines the next decisions and stop
conditions: one Parakeet FP32 conversion/GPU optimization track, bounded diagnosis
of Cohere’s English-prefix failures, then independent FUTO validation.
Allocation and parameter-sweep campaigns are closed. No new runs or recordings
are authorized.

## Evidence and history

Keep dated decisions in ADRs, with approval status, context, decision, evidence,
limits and a condition for review. Link private evidence instead of copying it.
Keep personal recordings, transcripts and signing credentials out of Git.
Recordings stay in gitignored `corpus/`; copying them needs the user's agreement.

Private evidence is in `corpus/retained/`. Its `evidence.json` indexes selected
reports, original identities and pruning limits for ADR-003 to ADR-016. Retain evidence that changes a
decision, calibration/evaluation inputs and recovery state needed for operation.
Closed campaign directory trees are not documentation.

Scoring uses `corpus/references/baseline-v1.json`, with provenance pinned to Git
source. The file hash identifies the current reference. Historical identities
and verdicts stay unchanged.

Completed orchestration and runbooks remain in pinned Git history.
[ADR-008](adrs/008-three-stage-loop.md) explains source recovery and offline
analysis. Active tools retain scoring, matched evidence checks, measurement
adapters and the daily app. New comparisons use the lab interface above.
