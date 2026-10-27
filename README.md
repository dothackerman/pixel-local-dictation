# Pixel Local Dictation

Local English and German dictation on the GrapheneOS Pixel 10 Pro XL.
Open it with FUTO Keyboard's existing microphone key. The goal is fewer wrong
words than the shipped default, at a Stop wait the user accepts; speed against
FUTO was won on 2026-10-10 ([ADR-026](adrs/026-dictionary-first.md)). Source and issue tracking use a private GitHub repository.

This README defines the requirements, current design, operation and acceptance
gaps. [AGENTS.md](AGENTS.md) defines agent conduct, phone access and private-data
rules. The [ADRs](adrs/) record decisions and past experiment conclusions.
Planned work lives only in GitHub Issues, as specs and tickets; a plan, gate,
schedule or checklist in a tracked file is a defect. The open spec is
[#11](https://github.com/dothackerman/pixel-local-dictation/issues/11).
The `ready-for-agent` label marks a prepared spec; it does not authorize a phone run.

## Current status

The app records, recognizes speech locally and inserts text through FUTO's
microphone workflow. Tags `m1` and `m2` record the prototype gates. The default
engine currently installed is Candidate A: Parakeet v3 FP32 on the CPU, beam 8,
dictionary score 1.5. On October 27 the owner selected and installed A as the
provisional baseline for its error/Stop-wait trade-off. The historical INT8 pipeline
([ADR-003](adrs/003-working-engine.md)) remains the empty-dictionary fallback.
Normal-app promotion [#28](https://github.com/dothackerman/pixel-local-dictation/issues/28) is complete;
UI/UX and dictionary editing are deferred. The WebGPU track stopped at
one case ([ADR-025](adrs/025-issue4-one-case-finding.md)). Direction since
2026-10-25: [ADR-026](adrs/026-dictionary-first.md); open spec #11.

Local implementation and screening for #11 completed on 2026-10-26 on
`codex/issue-11-dictionary`. The versioned
scorer reproduces the historical totals, and the Python greedy decoder matches
the stock wheel on all 210 public clips. Greedy dictionary boosting
failed the reviewed screen (#16) and its rerun with every emitted token path and strict
gates (#22): no alpha passes in either language, so candidate B is stopped and the native
greedy dictionary patch is not implemented ([ADR-026](adrs/026-dictionary-first.md#greedy-boosting-rerun-result--2026-10-27)).
B-only follow-ups #18–20 are closed as not planned; A's promotion completed in #28.
FP32 beam-8 with dictionary score 1.5 reduced primary-feed German/English errors
from 36/22 to 31/15, with gains on all three feeds and zero dictionary-word false
accepts on the public guards. Its held-script check added 1/0/2 German errors at
feeds 640/512/800 against the matching no-dictionary control, so it is not a clean
screening pass. The 120-run frozen phone comparison subsequently rejected
Candidate A: median Stop wait 3.154 s exceeds the ticket's 2.5 s limit, despite
the same accuracy gain. All 30 default/default pairs matched text; observed
timing variability was 206 ms. The owner subsequently chose A despite that original
timing-gate result; the frozen measurements remain unchanged. A is selected, with
normal-app implementation installed and the corrected normal-path replay check passed:
one retained take per language matched A exactly, with one simulated insertion each.
The owner confirmed two Stop takes inserted correctly, Cancel inserted nothing, and all
three returned to the normal keyboard. Measured live Stop waits were 6.083 s and 8.281 s;
these fresh takes used the defective normal-app integration described below. They do not
represent the intended A runtime with reuse. The historical 3.154 s replay median is
not an estimate of typical live Stop wait.
See the
decision and evidence in [ADR-026](adrs/026-dictionary-first.md#owner-selects-candidate-a-as-the-provisional-baseline--2026-10-27).
A runtime retention defect introduced by `f81a8eb` recreated Candidate A's runtime on
each panel request, repeating verification and model loading. Process-wide readiness
could still report true. Fix `a9a4b3e` retains the runtime across warm-up and takes;
the corrected APK is installed and all three normal-path phone checks passed, including
runtime reuse and retained DE/EN output parity. Two corrected-build live Stop waits were
2.253 s and 1.758 s, with verified A identity and accepted insertion/keyboard return.
These different recordings support the runtime reload diagnosis; they do not establish a
controlled speedup or typical live wait. The earlier live measurements
remain unchanged. See [the correction](adrs/026-dictionary-first.md#candidate-a-runtime-retention-correction--2026-10-27).
The retained-output review found no observed false acceptance of specific listed names/content
terms for exact A. It also found negation and quantity/unit transcription regressions versus
phone INT8; the matching FP32 host control already contains some errors, so dictionary-only
causation is unsupported. This specific listed-term review does not certify perfect meaning
preservation. These errors remain limits of the owner-selected provisional baseline, not a
claim that the owner waived every safety requirement. Private review:
`corpus/lab/issue11/promotion-a28/meaning-review.md`.
Private evidence is indexed by `corpus/lab/issue11/HANDOFF.md`.

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

Campaigns are closed ([ADR-013](adrs/013-future-investigations.md)); the
current decision is [ADR-026](adrs/026-dictionary-first.md).

## Requirements

Confirmed October 9, 2026; R7 added October 10; R4 revised and R8 added
October 25 ([ADR-026](adrs/026-dictionary-first.md)). These requirements apply to every engine.

| ID | Requirement |
|---|---|
| R1 | Keep FUTO’s microphone workflow. Do not add a picker or confirmation for each take. |
| R2 | Select English or German automatically, with one language per take. Allow ordinary names and loanwords. |
| R3 | Process speech only on the phone. No cloud fallback, Internet permission or telemetry. |
| R4 | Fewer wrong words than the shipped default, in each language, at a Stop wait the user accepts. |
| R5 | Preserve meaning. Transcribe without translation or rewriting. Check names, numbers, omissions, invented text and formatting. |
| R6 | Follow the security rules in [AGENTS.md](AGENTS.md). Support return to FUTO and retain its baseline model. |
| R7 | Use Swiss spelling: replace ß with ss. The scorer treats both forms equally. |
| R8 | The panel and recording start at once; model loading never delays them. |

A take is one recording session, from panel open to Stop or cancellation.
An engine that supports only one language is acceptable if routing is automatic.
Mixed-language conversation and diarization are outside scope.
Dialect support, Stop behavior preferences and battery/heat limits remain undecided.
Stop-wait and accuracy targets: [ADR-026](adrs/026-dictionary-first.md).

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
- [ProvisionalBaseline](app/src/main/kotlin/ch/og/dictation/ProvisionalBaseline.kt) selects
  the packaged Candidate A configuration: Parakeet v3 FP32, CPU, four threads,
  beam 8, dictionary score 1.5, including the tested silent-chunk filter.
  Its [acceptance record](app/src/main/res/raw/candidate_a.json) binds the frozen
  replay, model, dictionary, VAD, configuration and native runtime identities.
  Normal release selection ignores `files/lab/live.json` and `variant.json`.
  Provisioned model/dictionary files stay in private app storage under `files/lab`;
  nonempty altered files fail verification. Missing/blank dictionary selects the
  unchanged `Variant.HISTORICAL_INT8` pipeline from APK assets (ADR-003), including
  its original greedy decoder and postprocessing. Restart after changing provisioned files.
  Historical parsing defaults and frozen plans remain unchanged.
- Lab alternatives use the same schema semantics in Python and Kotlin.
  Cohere uses one recognizer, sets the language for each stream and loads models
  from filesystem paths. Canary retains two recognizers:
  [ADR-007](adrs/007-fp32-cohere.md).
- Benchmark builds can explicitly select the frozen Candidate A for a supervised
  live check through [BenchmarkLiveConfig](app/src/main/kotlin/ch/og/dictation/BenchmarkLiveConfig.kt).
  APK, model, dictionary and VAD identities are verified on the decode executor.
  Release builds ignore this override. Benchmark builds without it exercise the same
  normal baseline path; `NormalBaselineTest` checks retained DE/EN output hashes.
  `tools/promote_baseline.py --root ORIGINAL_CHECKOUT CORPUS_STAGE APK` stages
  only frozen candidate artifacts and focused parity inputs inside the original
  private corpus for the protected phone wrapper. It does not authorize installation
  or waive meaning/safety checks. Benchmark startup/live receipts contain
  timing and identity metadata, not audio or recognized text; they are independent
  of saved-take recording. Panel draw is not physical display timing, and the app
  cannot observe FUTO's microphone-key touch.
  Benchmark `files/lab/geometry.jsonl` adds `ime-geometry-v1` receipts from the
  real IME `onComputeInsets` (after unchanged `super`) and pre-draw callbacks.
  No layout requests or animation callbacks are added. Collection covers the first
  two seconds after service/view creation, input start and each panel request; ends at input
  finish, deduplicates per callback, and caps each observation at 256 rows and the
  file at 2 MiB without overwriting. Release collection is disabled.
  `processId`/`serviceId`/`takeId` join startup receipts; `takeId` is null before
  the first panel request. `ns` uses the same `CLOCK_MONOTONIC` clock.
  Every geometry distance is physical pixels: `panelMeasuredHeightPx` is measured
  size; `panelHeightPx`/`panelWidthPx` are laid-out size; `panelTopWindowPx` is
  relative to the IME window; `panelTopScreenPx`/`decorTopScreenPx` use Android
  screen coordinates; `decorHeightPx` is laid-out decor height.
  `contentTopInsetsPx`/`visibleTopInsetsPx` are the framework's editor-facing top
  insets relative to the IME window top; `touchableInsets` is Android's mode enum.
  Pre-draw retains the most recent computed insets, or null before one is observed
  in that panel request. Missing panel geometry is null. `bannerVisible` is only a
  visibility boolean. No editor identity, text or audio is collected.
  Compare size/window-top changes against exported inset changes, then align with
  startup events and the supervised video. Stable size and exported insets through
  visible reversal rule out a panel-height explanation; screen coordinates alone
  do not measure compositor motion or another app's editor animation.
  Geometry regression requires Android's real visible IME callback seam: record
  a supervised keyboard handoff in warm and loading states; verify initial and
  changed geometry receipts, unchanged returned insets and stable final dimensions.
  Host unit tests cannot exercise this cross-window behavior. A synthetic view or
  formula test would not establish the observed handoff's cause.
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

After applying a gate change, start a fresh agent session and run
`python3 tools/gate/check_installation.py`, then `python3 tools/quality.py`,
without escalation. The installer generates regular `AGENTS.md` and `CLAUDE.md`
files from one source; protected symlinks can prevent Codex sandbox startup.
The installation check verifies files only; an escalated pass is not a sandbox check.
Codex's six checkout protections are rendered for that checkout alone, so auxiliary
visualization folders do not inherit nonexistent repository paths. A copied config
does not protect another worktree: the owner must apply the gate there before agent
work starts. The installation check rejects a config copied from another checkout.
Both harnesses permit writes to `~/.gradle` for the host quality gate.

## Evaluation

For new work, use [the shared take and comparison interface](adrs/017-shared-take-experiments.md).
`python3 tools/lab.py --help` lists preparation, frozen comparisons and private
evidence commands. The tool never executes experiments. A preparation snapshot
with present artifacts still needs correctness checks. Old lab execution commands
are retired.

Keep identified variants and matching laptop controls, then collect matched
phone measurements. Laptop screening requires no freeze; adoption-deciding phone
comparisons do ([ADR-017](adrs/017-shared-take-experiments.md), amended by ADR-026).
Report quality, resource use and evidence completeness
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
comparison before an adoption-deciding phone run ([ADR-017](adrs/017-shared-take-experiments.md)
amendment); laptop screening needs no freeze. Deployment is a separate decision.

Dictionary experiments use `tools/dictionary_score.py` for versioned word scoring;
historical scorers retain their original rules. `tools/tdt_decoder.py` reproduces
the pinned greedy decoder through the wheel's own runtime, built with
`tools/tdt-runtime/build.py`; its requirements file pins host dependencies.
`tools/dictionary_screen.py` runs the fast scorer and held-script exclusion
regressions before creating evidence or loading models, then records results in
a new private run directory. Changed evaluation rules also need focused spec
review before full inference; passing regressions alone cannot establish their correctness.
`tools/dictionary_visit.py` prepares and validates
paired replay and explicit benchmark-only live checks; it never executes phone
commands. Each tool's `--help` describes its interface. Replay and local screens
do not establish adoption or real keyboard usability.

## Known gaps

Answered calls, longer battery-only model survival and a clean full FP32 export
remain unverified. Dictionary entry and editing ship only with an adopt verdict
([ADR-005](adrs/005-dictionary.md)). Unlisted-name recall is unmeasured. Planned
work: spec #11 and its tickets. Startup instrumentation and paired/live comparison
tools are implemented; the owner accepts Candidate A's measured wait and selects it
as the provisional baseline. Normal-app promotion #28 is complete; dictionary editing and
UI/UX are deferred. The owner completed
ten short startup takes on October 27; nine complete traces were captured. Warm
app-request-to-panel draw had median 47 ms and recording start 27 ms. External
microphone-key timing and live Stop timestamps are absent from those startup traces;
#14 remains partial and #17 has no established end-to-end cause to fix. The subsequent
Candidate A live check and retained-output meaning review have the limits above; the
runtime reuse correction passed phone validation and two fresh live takes, with the timing limits above.
The earlier #15 visit restored the original app, test APK and data, independently verified
byte-identical to its pre-visit snapshot. Its original gate rejection is preserved alongside
the owner's subsequent selection. The October 27 promotion installed A and passed focused
normal-path replay parity and the supervised insertion/cancellation/FUTO check. Recovery snapshot
`issue11-20261027-before-a28` preserves the prior installation.

## Evidence and history

Keep dated decisions in ADRs, with approval status, context, decision, evidence,
limits and a condition for review. Link private evidence instead of copying it.
Keep personal recordings, transcripts and signing credentials out of Git.
Recordings stay in gitignored `corpus/`; copying them needs the user's agreement.

Private evidence is in `corpus/retained/`. Its `evidence.json` indexes selected
reports, original identities and pruning limits for ADR-003 to ADR-016. Retain evidence that changes a
decision, calibration/evaluation inputs and recovery state needed for operation.
Closed campaign directory trees are not documentation.
The #11 evidence is in `corpus/lab/issue11/`: final scorer and held-script reports,
decoder parity, reviewed greedy-screen results, final APK/check receipts, and
the validated 120-run phone comparison. `visit-20261026-resume/` records device,
snapshot and installation identities; `visit-a-s15/` preserves the frozen plan
and every measurement. `startup-20261027/` contains nine real startup traces,
their timing table, the restoration receipt and independent recovery verification.

Scoring uses `corpus/references/baseline-v1.json`, with provenance pinned to Git
source. The file hash identifies the current reference. Historical identities
and verdicts stay unchanged.

Completed orchestration and runbooks remain in pinned Git history.
[ADR-008](adrs/008-three-stage-loop.md) explains source recovery and offline
analysis. Active tools retain scoring, matched evidence checks, measurement
adapters and the daily app. New comparisons use the lab interface above.
