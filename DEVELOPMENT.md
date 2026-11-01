# Development reference

For anyone changing, building, measuring or operating the app. [README](README.md)
covers use; [AGENTS.md](AGENTS.md) covers agent conduct and phone access;
[ADRs](adrs/) record decisions and their evidence; [GLOSSARY](GLOSSARY.md) fixes terms.
Planned work lives only in [GitHub Issues](https://github.com/dothackerman/pixel-local-dictation/issues);
a plan, gate, schedule or checklist in a tracked file is a defect. The `ready-for-agent`
label marks a prepared spec; it does not authorize a phone run.

## Current status

As of 2026-11-01 (from merged PRs and closed issues):

- Installed baseline: **Candidate A** (Parakeet v3 FP32, CPU, beam 8, dictionary score 1.5).
  The owner selected it on 2026-10-27 for its error/Stop-wait trade-off although the frozen
  #15 comparison rejected it on the 2.5 s median gate; the rejection stays on record
  ([ADR-026](adrs/026-dictionary-first.md)).
- Fallback: the historical INT8 pipeline ([ADR-003](adrs/003-working-engine.md)) when
  provisioning is missing or blank and no dictionary was saved. A deliberately empty saved
  dictionary keeps FP32 without vocabulary help ([ADR-027](adrs/027-editable-dictionary.md)).
- Settings dictionary editor (#31, PR #33) merged; the owner accepted its smoke test (ADR-027).
- Recording-panel ring (#30, PR #32) merged; the owner approved the panel on video. Status
  unverified: [research/issue30](research/issue30-panel-performance.md) left the live Cancel
  path as the remaining manual acceptance item, and no later record closes it.
- Startup: the owner accepts roughly 1.3 s cold / 0.25 s warm handoff for now; #14 partial,
  #17 deferred (ADR-026).
- Status unverified: the installed APK identity after the #31 smoke build is not recorded in
  a tracked file. ADR-026's `0efa5b2` pair and issue30's final UI APK both predate it.

Closed campaigns (allocation, Moonshine, profiling, accelerators, WebGPU encoder) and
their outcomes: ADR-004 to ADR-025. [ADR-013](adrs/013-future-investigations.md) lists
parked hypotheses.

## Requirements

Confirmed 2026-10-09; R7 added 10-10; R4 revised and R8 added 10-25 ([ADR-026](adrs/026-dictionary-first.md)).
They apply to every engine.

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

A single-language engine is acceptable if routing is automatic. Mixed-language
conversation and diarization are out of scope. Dialect support, Stop behavior
preferences and battery/heat limits are undecided. Stop-wait and scoring targets:
ADR-026 decisions 2 and 4.

Status unverified (2026-11-01): R4's "shipped default" was the INT8 pipeline when written;
Candidate A is now installed. ADR-026 only requires future comparisons to name their
actual control. Which control R4 now means is undecided.

## Current design

FUTO microphone → voice IME → capture → chunks cut at pauses → serial local decoding →
panel preview → Stop inserts text once and returns to FUTO. Each chunk is decoded
alone, without state from earlier chunks (carrying state was rejected, ADR-026).

| Concern | Where | Decision |
|---|---|---|
| IME lifecycle: record on open; Cancel, Keyboard, any input restart, hide or lock discard | [DictationImeService](app/src/main/kotlin/ch/og/dictation/DictationImeService.kt), [method.xml](app/src/main/res/xml/method.xml) | [ADR-010](adrs/010-ime-lifecycle.md) |
| Take lifecycle shared by IME and phone replay | [TakeSession](app/src/main/kotlin/ch/og/dictation/TakeSession.kt), [DictationRuntime](app/src/main/kotlin/ch/og/dictation/DictationRuntime.kt) | [ADR-017](adrs/017-shared-take-experiments.md) |
| Runtime selection: Candidate A, edited dictionary, INT8 fallback | [Engine](app/src/main/kotlin/ch/og/dictation/Engine.kt) `build` | ADR-026, ADR-027 |
| Candidate A identities (plan, model, dictionary, VAD, runtime) | [ProvisionalBaseline](app/src/main/kotlin/ch/og/dictation/ProvisionalBaseline.kt), [candidate_a.json](app/src/main/res/raw/candidate_a.json) | ADR-026 |
| Dictionary revisions and one leased loaded model | [DictionaryStore](app/src/main/kotlin/ch/og/dictation/DictionaryStore.kt), [DictionaryModels](app/src/main/kotlin/ch/og/dictation/DictionaryModels.kt) | ADR-027 |
| Lab engines (Cohere one recognizer, Canary two) | [Engine](app/src/main/kotlin/ch/og/dictation/Engine.kt), [pipeline.py](tools/pipeline.py) | [ADR-007](adrs/007-fp32-cohere.md) |
| Allocation settings for recognizer and LID | [RuntimeConfig](app/src/main/kotlin/ch/og/dictation/RuntimeConfig.kt), [runtime_config.py](tools/runtime_config.py) | [ADR-012](adrs/012-allocation-outcome.md) |

Not visible in the code:

- Provisioned model and dictionary live in private `files/lab`; nonempty altered files
  fail verification. Restart after changing them. Selection ignores `variant.json`;
  the benchmark-only `files/lab/live.json` override is retired. A future candidate is
  live-checked by packaging its acceptance record.
- An input restart with identical attributes still discards the take (ADR-010).
- Old takes keep their dictionary lease until queued work ends; later takes use the
  saved revision. Edited receipts carry their own configuration hash; the frozen
  acceptance record stays intact (ADR-027).
- `NormalBaselineTest` checks retained DE/EN output hashes, runtime fingerprint and reuse.
- `tools/promote_baseline.py` stages only frozen candidate artifacts into the private
  corpus for the phone wrapper; it authorizes no installation.

## Benchmark diagnostics

Benchmark builds append timing and geometry receipts under `files/lab/`; release
collection is off. Receipts hold timing, identity and geometry only, never audio,
text or editor identity. Each file is append-only, capped at 2 MiB, never overwritten.

| File | Protocol | Schema source |
|---|---|---|
| `startup.jsonl` | `ime-startup-v1` | [StartupTrace](app/src/main/kotlin/ch/og/dictation/StartupTrace.kt), DictationImeService |
| `geometry.jsonl` | `ime-geometry-v1` | StartupTrace `geometry` |
| `live-results.jsonl` | `ime-candidate-live-v1` | StartupTrace `liveResult` |
| `editor-geometry.jsonl` | `editor-geometry-v1` | [EditorProbeActivity](app/src/benchmark/kotlin/ch/og/dictation/EditorProbeActivity.kt) |

Reading rules (frozen meanings):

- Join rows on `processId`/`serviceId`/`takeId`; `takeId` is null before the first panel request.
- `ns` is `CLOCK_MONOTONIC` nanoseconds. `uptimeMs` is millisecond
  [uptime](https://developer.android.com/reference/android/os/SystemClock#uptimeMillis())
  that excludes deep sleep. Never subtract across the two clocks; adjacent `ns` and
  `uptimeMs` reads are not simultaneous. Historical `ns` events (e.g. `modelReady`) carry
  `uptimeMs` only when captured at the event.
- `serviceCreate` adds `processStartRequestedUptimeMs` (before zygote fork) and
  `processStartUptimeMs` (before app code) from
  [Process](https://developer.android.com/reference/android/os/Process#getStartRequestedUptimeMillis()).
  Neither is the microphone touch; nonpositive values are unavailable. Use uptime differences for
  process request → process start → service entry → draw.
- `serviceCreateSuperReturned`, `warmUpReturned`, `serviceCreateEnd` split framework
  creation, warm-up setup and callback completion; warm-up return is not model readiness.
  `inputViewCreateBegin` → `inputViewInflated` is layout inflation; `inputViewCreateEnd`
  adds listener setup. `panelDraw` is a callback, not physical display; the app cannot see
  FUTO's key touch.
- Geometry distances are physical pixels; `*WindowPx` is relative to the IME window,
  `*ScreenPx` to Android screen coordinates; insets are the framework's editor-facing values.
  Geometry covers two seconds after service/view creation, input start and each panel
  request, at most 256 rows per observation.
- True cold needs our process absent before the FUTO tap; launching the editor probe or
  instrumentation first prewarms it.

Editor handoff probe: after building and `phone check`, run
`phone run ch.og.dictation.EditorGeometryTest#collectManualKeyboardHandoffs` (or
`…WithAnimationCallback`). Keep the phone awake with the probe editor visible; within the
90 s window tap FUTO's microphone, wait for our panel, Cancel; twice. Then pull
`files/lab/editor-geometry.jsonl` and the IME receipts into a `corpus/` visit directory.
A passed test only validates collection: the owner's handoff confirmation and two matching
panel requests are required, never inferred from geometry. The callback variant changes
Android's animation routing, so compare the two modes as separate hosts. Polling observes
framework geometry, not compositor motion; the probe is not the original editor and cannot
alone establish a framework cause or a fix. Host unit tests cannot exercise this
cross-window behavior.

## Build and host checks

1. `tools/fetch-assets.sh` fetches pinned assets ([assets.lock](assets.lock)). The
   shipped selective encoder comes from `tools/export-fp32.sh` and `tools/quant_variant.py`;
   a clean full FP32 export is unverified.
2. `tools/set-signing-password.sh` configures signing ([app/build.gradle.kts](app/build.gradle.kts)).
   The host gate's Gradle step also needs it, so a fresh worktree cannot commit until signing is set.
3. `ANDROID_HOME=~/Android/Sdk ./gradlew :app:assembleRelease`.

`python3 tools/quality.py` is the host gate (no device tests). The pre-commit hook runs it
with `--staged`: tracked edits and new source, test and build inputs must be staged, so
`git commit PATH` with other changes pending fails, and index changes during the run fail it.

## Daily use and rollback

Enable the IME, grant microphone permission, enable FUTO’s “Disable built-in voice input”.
Roll back by disabling that toggle before removing the app. Production audio stays in
memory and is discarded; benchmark storage is opt-in, visibly marked and deletable
(ADR-010). The first take loads the model. Release and benchmark builds share signing;
the benchmark build is debuggable.

## Phone lab operations

[AGENTS.md](AGENTS.md) gives the shim path and approval rules; `phone help` lists verbs;
[ADR-024](adrs/024-phone-access-rewrite.md) is the contract.

- Every call, even `status`, overwrites `corpus/lab/last.json`; save it if later work needs it.
- Typical visit: `snapshot NAME` → `check APP.apk TEST.apk` → `install` → `push DIR` →
  `run CLASS k=v` → `pull files/lab/<file>` → `restore NAME`.
- `push DIR` makes `files/lab` equal DIR: it deletes device files missing from DIR,
  including receipts. Pull receipts before pushing.
- Mutating verbs and `run` write `corpus/lab/runs/<ts>-<verb>-<id>/meta.json` with the
  installed APK and lab hashes; experiments bind their receipts to it.
- Wrapper changes: edit `tools/phone` and `tools/test_phone.py`; the owner runs
  `bash tools/gate/apply.sh`. Then, in a fresh unescalated agent session, run
  `python3 tools/gate/check_installation.py` and `python3 tools/quality.py`. The installer
  generates regular `AGENTS.md`/`CLAUDE.md` from `tools/gate/AGENTS.md`; each checkout or
  worktree needs its own installation before agent work (ADR-024).

## Evaluation

Rules: [ADR-004](adrs/004-closed-loop.md), [ADR-005](adrs/005-dictionary.md),
[ADR-006](adrs/006-adapter-evaluator.md), [ADR-011](adrs/011-measurement-contract.md),
[ADR-017](adrs/017-shared-take-experiments.md) as amended by ADR-026.

- Laptop screening needs no freeze; an adoption-deciding phone comparison needs a frozen
  plan in `corpus/lab/<id>/`. Deployment is a separate decision.
- Report quality, resource use and evidence completeness separately. Phone accuracy needs
  phone controls; dictionary benefit needs a matching no-dictionary run.
- Ten development takes cover eight scripts, four per language. The three feeds test
  chunk-boundary sensitivity; they are not independent samples. Repeatedly inspected
  public guard clips are development evidence. Laptop guard and silence results do not
  measure phone hallucinations. WER does not replace meaning, correction-effort and formatting checks.
- Changed evaluation rules need spec review before full inference; passing regressions
  alone do not establish correctness.

Tools (`--help` gives each interface; none runs phone commands):

| Tool | Purpose |
|---|---|
| `tools/lab.py` | prepare, check, freeze, reserve, record, compare one frozen comparison |
| `tools/dictionary_score.py` | versioned word scoring; historical scorers keep their rules |

Completed one-off tools are retired from the tree: the greedy-boosting screen
(`dictionary_screen.py`, `dictionary_boost.py`, `tdt_decoder.py`, `tdt-runtime/`;
candidate B stopped, evidence names commit 94600aa) and the Candidate A paired visit
(`dictionary_visit.py`, whose frozen plan binds its source revision). Reproduce their
reports from those revisions.

## Known gaps

- Answered calls, model survival across long battery-only or >11 min USB-powered gaps,
  and a clean full FP32 export are unverified.
- Arbitrary new dictionary entries are not accuracy-validated; unlisted-name recall is unmeasured.
- Candidate A shows negation and quantity/unit regressions versus phone INT8, and +1/0/+2
  German held-script harm; typical live Stop wait is unmeasured (ADR-026).
- #14's ten-take/key-timing protocol is partial; no startup fix is claimed. Signal 8.32.3's
  cached-height jump is a separate deferred issue.

## Evidence

Dated decisions go in ADRs with status, context, decision, evidence, limits and a
revisit condition. Link private evidence; never copy it. Personal recordings, transcripts
and signing credentials stay out of Git; copying out of gitignored `corpus/` needs the
owner's agreement.

- `corpus/retained/evidence.json` indexes selected reports for ADR-003 to ADR-016.
- `corpus/lab/issue11/` holds #11 evidence; `HANDOFF.md` indexes it.
- `corpus/references/baseline-v1.json` is the scoring reference; its hash identifies it.
- Retired orchestration and runbooks are in Git history; [ADR-008](adrs/008-three-stage-loop.md)
  explains source recovery.
