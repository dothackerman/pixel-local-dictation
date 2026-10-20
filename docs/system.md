# Local dictation

This app provides local English and German dictation through FUTO’s microphone key.
This file defines the requirements, current design and acceptance gaps.
[AGENTS.md](../AGENTS.md) defines agent conduct, phone access and private-data rules.
Dated decisions and experiment results are in [the ADRs](adrs/).

## Requirements

Confirmed October 9, 2026; R7 added October 10. These requirements apply to every engine.

| ID | Requirement |
|---|---|
| R1 | Keep FUTO’s microphone workflow. Do not add a picker or confirmation for each take. |
| R2 | Select English or German automatically, with one language per take. Allow ordinary names and loanwords. |
| R3 | Process speech only on the phone. No cloud fallback, Internet permission or telemetry. |
| R4 | Be faster and more accurate than the installed FUTO Multilingual-244 model, in each language. |
| R5 | Preserve meaning. Transcribe without translation or rewriting. Check names, numbers, omissions, invented text and formatting. |
| R6 | Follow the security rules in [AGENTS.md](../AGENTS.md). Support return to FUTO and retain its baseline model. |
| R7 | Use Swiss spelling: replace ß with ss. The scorer treats both forms equally. |

An engine that supports only one language is acceptable if routing is automatic.
Mixed-language conversation and diarization are outside scope.
Dialect support, Stop behavior preferences and battery/heat limits remain undecided.
About 3× less waiting, warm finalization below one second and 10–20% fewer corrections
are goals, not approved acceptance thresholds.

## Current design

FUTO microphone → voice IME → capture → consecutive chunks split at pauses →
serial local decoding → panel preview → Stop inserts text once and returns to FUTO.

- [DictationImeService](../app/src/main/kotlin/ch/og/dictation/DictationImeService.kt)
  starts recording when the panel opens. Cancel, Keyboard, input restart, hide or lock
  discard the take. Text stays in the panel until Stop.
  [ADR-010](adrs/010-ime-lifecycle.md) explains this behavior and FUTO discovery.
  [method.xml](../app/src/main/res/xml/method.xml) declares a voice subtype without a
  locale and overrides implicit subtype selection. Even an input restart with
  identical attributes discards the take.
- [TakeTranscriber](../app/src/main/kotlin/ch/og/dictation/TakeTranscriber.kt) and
  [Engine](../app/src/main/kotlin/ch/og/dictation/Engine.kt) decode closed chunks
  while recording continues. Stop queues the remaining audio. Each chunk is decoded
  independently, without encoder state from earlier chunks.
- [Variant](../app/src/main/kotlin/ch/og/dictation/Variant.kt) selects the default:
  Parakeet v3 with a selective MatMul INT8 encoder and FP32 decoder/joiner,
  running on the CPU through sherpa-onnx. This choice is provisional:
  [ADR-003](adrs/003-working-engine.md).
- Lab alternatives use the same schema semantics in Python and Kotlin.
  Cohere uses one recognizer, sets the language for each stream and loads models
  from filesystem paths. Canary retains two recognizers:
  [ADR-007](adrs/007-fp32-cohere.md).
- [RuntimeConfig](../app/src/main/kotlin/ch/og/dictation/RuntimeConfig.kt) and
  [runtime_config.py](../tools/runtime_config.py) configure memory allocation for
  the recognizer and language identification (LID) independently. Stock settings
  remain the product default. Bounded counters for native processing roles and
  offline profile analysis support diagnosis:
  [ADR-012](adrs/012-allocation-outcome.md), [ADR-015](adrs/015-cpu-profiling.md).

## Audio and operation

Recording requires an explicit take. The app releases the microphone afterwards.
Production audio stays in memory and is then discarded. Benchmark storage is
opt-in, visibly marked and deletable: [ADR-010](adrs/010-ime-lifecycle.md).
Models are provisioned locally; downloads are separate from inference.

Oriol enables the IME, grants microphone permission and enables FUTO’s
“Disable built-in voice input” toggle. For rollback, disable that toggle before
removing the app. The first take loads the model. Retention across a USB-powered
gap longer than 11 minutes is unverified. Release and benchmark builds share
signing; the benchmark build is debuggable. [README](../README.md) has build commands;
lock files and build files define versions and hashes.

For phone lab preparation, APK compatibility and recovery, read
[ADR-009](adrs/009-phone-recovery.md). It defines the recovery boundary and the
unresolved commitment to return to a release build. Wrapper `help` defines the
command grammar.

If recovery fails or is interrupted, keep its journal. Inspect the state and
recover before staging again. Keep lab and profile JSON in `corpus/`.
To change the protected wrapper, prepare a proposal outside its path and have it
reviewed. Oriol applies the change manually; then verify it.

At the start of a fresh session, verify trusted hooks and filesystem protections.
Approvals apply to one checkout. Hooks are guardrails, not isolation: terminal
input and specialized tools can bypass them, and escalation bypasses the sandbox.
Claude’s live filesystem enforcement remains unverified.
`python3 tools/test_agent_guard.py` runs synthetic guard checks.

## Evaluation

Prepare a fixed set of variants, freeze laptop controls, then collect matched
phone measurements. Report quality, resource use and evidence completeness
separately. The method is defined in [ADR-004](adrs/004-closed-loop.md),
[ADR-005](adrs/005-dictionary.md), [ADR-006](adrs/006-adapter-evaluator.md) and
[ADR-011](adrs/011-measurement-contract.md).

Ten development takes cover eight scripts, four per language. Three feeds test
sensitivity to chunk boundaries; they are not independent speech samples.
Repeatedly inspected public guard clips are development evidence.
Phone accuracy requires phone controls. Dictionary benefit requires a matching
run without the dictionary. Laptop guard and silence results do not measure
phone hallucinations. Word error rate (WER) does not replace checks of correction
effort, meaning or formatting.

Each future experiment needs one private plan and manifest in `corpus/lab/`.
Record source, runtime, APK, model and input identities; exact commands; controls;
repeats and endpoints; thresholds and budgets; failure rules; a dated
Europe/Zurich window; and restoration steps. Freeze these during setup.
A fresh GPT 6.1 Sol executes only after explicit launch. Continuation preserves
budgets, evidence and exclusive ownership of the experiment namespace.
Deployment is a separate decision. No experiment is currently commissioned.

## Results and acceptance gaps

Tags `m1` and `m2` demonstrate integration and local recognition.
On October 10, six matched-live scripts gave FUTO Stop waits of 79.9–99.7 s and
app waits of 0.01–1.04 s. The takes differed, and some comparisons used laptop
replays. Accuracy superiority remains unproven.

Later results:

- Allocation: 66 measurements completed October 19; no production change.
  [ADR-008](adrs/008-three-stage-loop.md) defines the completed loop;
  [ADR-012](adrs/012-allocation-outcome.md) records the outcome.
- Moonshine Small did not advance: [ADR-014](adrs/014-moonshine-screen.md).
- Eight CPU profile runs on October 20 found substantial encoder work, but
  failed timing comparability checks: [ADR-015](adrs/015-cpu-profiling.md).
- Physical GPU and TPU access is proven. GPU FP32 passed the numerical smoke
  checks; TPU failed one check. Speech acceleration is still unimplemented:
  [ADR-016](adrs/016-accelerator-access.md).

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

## Evidence and document ownership

Keep dated decisions in ADRs, with approval status, context, decision, evidence,
limits and a condition for review. Link private evidence instead of copying it.

Private evidence is in `corpus/retained/`. Its `evidence.json` indexes selected
reports, original identities and pruning limits. Retain evidence that changes a
decision, calibration/evaluation inputs and recovery state needed for operation.
Closed campaign directory trees are not documentation. Future work starts with
a new bounded plan.

Scoring uses `corpus/references/baseline-v1.json`, with provenance pinned to Git
source. The file hash identifies the current reference. Historical identities
and verdicts stay unchanged.

Documentation before compression is at `925ef8c87e054855246a6105a95778c73961ecef`.
Retired controllers at `d0e984641d9d247bd6dd4f500abfbba1091b7b43` are for offline
reading only. Git retains source and documentation, not deleted private runs.
Generated HTML is a review artifact; it is not an authoritative document.
