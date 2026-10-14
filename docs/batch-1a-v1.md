# Release 1a — measurement characterization

2026-10-14. Setup only is authorized. The experiment loop has not started. This brief implements the first release of [the plan](next-experiment-plan.md), within [the setup work order](setup-release-1a.md). A dated phone window and a separate launch instruction remain required.

## Authoritative artifacts

The private manifest is `corpus/lab/release-1a/manifest.json`. Its source commit, artifact hashes, `execution_steps`, commands, output paths and comparison contracts govern execution. `environment.json` pins packages and native libraries. `smoke-summary.json` records twelve setup processes, including one successful six-result phone smoke. These files and all personal inputs/results remain inside `corpus/`. The review revised instrumentation after that smoke; its local build is prepared, but no further device inference was authorized or performed. Preserve the smoke's original APK identities. Setup evidence is not product acceptance.

Python 3.12.3 and sherpa-onnx 1.13.8 load successfully; the API reports ONNX Runtime 1.28.2. The Android AAR contains the same version string and independently recorded arm64 library hashes. The app version remains 0.2.5: use APK hashes to identify this build. No model downloads or exports were required.

Schema 4 adds independent recognizer/LID CPU arena and memory-pattern controls and profiling. Stock settings preserve production defaults. Baseline, FP32 beam-8 dictionary and single-instance Cohere have prepared stock/arena-off/pattern-off variants. Only stock FP32 is a phone configuration in release 1a. Allocation comparisons belong to subsequent releases.

Evaluator 4 is `tools/evaluate_v4.py`. Historical `evaluate.py`, the normalizer and numerical quality/resource rules remain unchanged. New reports require versioned, matched-platform provenance. Missing evidence is incomplete; resource failure does not suppress quality scoring. Repeated takes are scored separately. This release produces diagnostic results only: no dictionary-mechanism acceptance, deployment acceptance or new recording-trigger decision. Historical reports and ledger rows remain unchanged. Do not launch the old loop from this setup checkout.

## Entry sequence for a fresh GPT 6.1 Sol session

1. Read the project instructions, requirements, roadmap, this brief and the private manifest. The roadmap and old loop describe history/design; `execution_steps` is the current run order. Use this checkout exclusively. Do not run setup generators or modify frozen files.
2. Confirm Oriol explicitly authorized **release 1a execution**, including the package-scoped staged lab operations and dated phone window. The setup authorization and previous hook confirmation are historical evidence, not current launch permission.
3. Complete [fresh-session safeguard activation and verification](agent-safeguards.md). Run its synthetic guard tests. Only then use the permitted read-only status/version commands to confirm the connection and record versions. Sol records what it observed; Oriol supplies hook trust confirmation if needed. A denied action stops the affected work.
4. Create the private `launch.json` described below from Oriol's actual authorization and this session's observations. Only this launch record and new run/report evidence are mutable during execution. Do not edit the frozen manifest to manufacture readiness.
5. Run `python3 tools/check_release_1a.py --session-id SESSION_ID`, using this chat's actual stable ID in both places. A nonzero result stops execution. The checker validates artifacts, a future usable window, session checks and the restoration artifact. It cannot independently prove consent or hook trust: those are explicit human/session attestations.
6. Follow `execution_steps` in order. Complete laptop controls and their barriers before any phone run. Wait until the authorized phone start time. Record start/end times, elapsed budgets, retries and outcomes in a new private `execution-log.json`; do not create another chat or automation.

### Launch record ownership and schema

`corpus/lab/release-1a/launch.json` is deliberately absent after setup. Oriol supplies the authorization, window and restoration arrangement; Sol transcribes them and records the fresh checks. Timestamps are ISO 8601 with the correct Europe/Zurich offset for the date. Replace the illustrative strings with real values; placeholders do not grant permission.

```json
{
  "authorized_by": "Oriol",
  "authorized": true,
  "source_commit": "EXACT_COMMIT_FROM_MANIFEST",
  "session_id": "CURRENT_SOL_CHAT_ID",
  "hook_confirmed": true,
  "guard_tests_passed": true,
  "device_connection_and_versions_checked": true,
  "checked_at": "YYYY-MM-DDTHH:MM:SS+02:00",
  "phone_window": {
    "timezone": "Europe/Zurich",
    "start": "YYYY-MM-DDTHH:MM:SS+02:00",
    "end": "YYYY-MM-DDTHH:MM:SS+02:00"
  },
  "restoration": {
    "owner": "NAMED_OWNER_APPROVED_BY_ORIOL",
    "procedure": "EXACT_APPROVED_RESTORATION_PROCEDURE",
    "artifact_path": "/absolute/path/to/existing/approved.apk",
    "artifact_sha256": "ACTUAL_SHA256"
  }
}
```

The owner performs restoration; Sol does not improvise it or install a daily build. The existing lab command's normal installation of the frozen benchmark/test APKs is part of the explicitly authorized lab operation. A fresh instrumented process verifies both actual APK hashes before inference; a displayed version name alone does not establish build identity. Missing/changed builds or assets return to setup. If the artifact or restoration owner is unavailable, do not launch.

## Measurement protocol

Each unpaced-only or paced-only command starts a fresh process and loads one recognizer. Persistent mode makes two passes with one recognizer and one serial executor, loading one recording at a time. Android rejects a reused diagnostic process-start identity. Reports include unique run/process identifiers. One second of silence warms the recognizer; its decode is included in the budget.

Without a staged `measurement.json`, the Android harness retains its original full-run allocation/timing sequence, including preloading the complete corpus. Release 1a's historical manifest adds hash verification of the exact full-take set, models, dictionary and other staged artifacts before that sequence. The raw result records measured hashes; `tools/wrap_historical_measurement.py` rejects absent/mismatched measurements. This preflight can warm filesystem pages and affect pre-existing process high-water state; it is not a filesystem-cold experiment. Old saved reports cannot be relabelled. Keep both original high-water readings and the legacy 3,500,000 kB rejection threshold, 2,000,000 kB warning, median chunk RTF 0.30, maximum Stop delay 2,000 ms and maximum thermal status 2. New diagnostic metrics do not replace this historical gate.

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

### Execution and validation barriers

Every run entry has `validate_command`, `validation_output` and `expected_manifest`. Run validation immediately after saving that run, before the next inference process. It checks that run independently against the frozen contract; it is explicitly not an independent repetition. Each comparison step follows as soon as both inputs exist. A nonzero validation/comparison exit stops downstream work; only the failure table's explicitly bounded transient-process retry can resume it. Never skip a barrier. Successful diagnostic resource/accuracy failures are reportable outcomes, not invalid evidence.

| Ordered group | Required barrier before advancing |
|---|---|
| Baseline laptop quality, repetitions 1 and 2 | Validate each; repeat comparison must have complete evidence and identical normalized text |
| FP32 laptop quality, repetitions 1 and 2 | Validate each; scheduled quality deltas and repeat comparison must complete |
| FP32 laptop unpaced, paced, persistent, two processes per mode | Validate each; finish each mode's repeat comparison before the next mode |
| Phone 01–08 in manifest order | Save and validate each immediately; execute each repeat comparison when the second matching mode completes |

The phone's reversed order balances sequence effects; it does not defer individual-run validation until its late repeat partner. Baseline or FP32 control evidence failure prevents the entire downstream phone stage. Timing/accuracy values may fail historical thresholds while remaining complete diagnostic evidence; report that failure without inventing an advancement rule.

`retry_options` enumerates two alternative slots per platform, each with commands, bounds, validation and replacement comparison commands. At most one option per slot may run. Retain failed reports and use only the chosen retry's unique paths. The quality input hash includes every public guard WAV and deterministic silence/noise parameters.

## Phone stage — separate launch required

Eight planned processes plus two retries: unpaced, paced, persistent, historical, historical, persistent, paced, unpaced. Cool down five minutes between processes. Permit at most one thermal retry per affected configuration within the two-retry total. Stop earlier if the question is answered or evidence is invalid. Each process must fit its declared timeout plus the 15-minute restoration margin. The overall ceiling is the smaller of four hours and the authorized phone window minus that margin.

The sum of process timeouts exceeds four hours even before cooldowns. This schedule may therefore stop incomplete at the cap; completion of all eight processes is not guaranteed. Never start a process whose timeout and restoration margin exceed the remaining window.

Run the following as separate invocations, substituting only the staged directory from the manifest:

```text
/home/oriol/Projects/private/pixel-local-dictation/tools/phone push-variant /home/oriol/Projects/private/pixel-local-dictation/corpus/lab/release-1a/push/MODE
/home/oriol/Projects/private/pixel-local-dictation/tools/phone lab
/home/oriol/Projects/private/pixel-local-dictation/tools/phone pull-results
```

Use each run's `record_command`, substituting the result path actually returned by the preceding pull. It preserves raw bytes under the unique run ID and rejects reused run/process identities; it wraps historical output with its exact staged manifest. Then run `validate_command` immediately. Guards and silence from the laptop must remain explicitly labelled as laptop evidence.

The previous setup authorization was consumed by benchmark provisioning and one public/synthetic smoke. It reserves no loop window. `launch_requirements` is the authoritative remaining prerequisite list; the old setup checkpoint is not a live checklist. Readiness remains false without the separate launch record.

Run `python3 tools/check_release_1a.py --setup-only` to verify prepared artifacts. Without that option, supply the current `--session-id` and the launch record. The setup smoke's measured identities remain unchanged by review revisions.

## Failure handling and handback

Stop the affected stage on missing evidence/output, reproducible truncation, unexpected artifacts, permission refusal, decoder failure, exhausted budgets or insufficient time. Preserve failed/partial reports and all repeats. Never overwrite a completed local result, reset a ledger, request new recordings or launch another batch. The recording trigger is unchanged and is not opened by this diagnostic release.

| Condition | Action |
|---|---|
| Invalid provenance/coverage, changed text between valid repeats, reproducible truncation, or denied action | Stop the batch; no retry can repair evidence by relabelling it |
| One transient laptop process failure before complete output | Use one unused laptop retry slot for that exact run; validate immediately; a second failure stops the batch |
| Phone report with thermal status above 2 and otherwise complete evidence | Preserve it, cool down at least five minutes, and use at most one thermal retry per mode, within two total slots and the remaining window |
| Valid but slow/high-memory/inaccurate result without thermal contamination | Keep it as diagnostic failure; do not spend a retry chasing a better result |
| A comparison needs a retry replacement | Execute the chosen retry's prewritten `comparisons` command instead of the original command. Its counterpart must be the original result; if both sides need replacing, stop and return to setup |
| Timeout, exhausted count/time/storage, or insufficient restoration margin | Stop scheduling work, preserve available partial evidence, and hand back to the named owner; do not improvise device termination/settings commands |

Count a failed or timed-out process against its platform budget. The run deadline starts before its command; phone wall time includes push/build/pull overhead and cooldowns. The manifest's timeout is a scheduling deadline, not permission to start another process while the preceding command still runs. The current command interface has no approved device-abort operation: if a process overruns, report it immediately and hand control to the restoration owner. Do not claim guaranteed hard termination at the window boundary.

Use the named validation/comparison outputs under `reports/`, plus `execution-log.json`, for the handback. The evaluator supplies per-language full-take/term counts, non-term errors against the pinned scoring dictionary, guard dictionary insertions, and silence. Missing guard/silence evidence is null/unavailable, not zero. Phone results cite the two matching FP32 laptop quality run IDs for those companion diagnostics; those results remain laptop evidence. Historical paced hypotheses are scored separately. The scoring dictionary is the same for candidate and control and does not imply a mechanism-acceptance decision.

Also include artifact/protocol identities; all failures/deviations and consumed budgets; every endpoint wait and prefix check; memory/load/thermal repetitions; backend/profile availability; the historical gate beside diagnostic readings; unchanged recording-trigger status; final build/device state and restoration handoff; and supported/rejected/unresolved conclusions with the smallest next setup change. No mandatory field may be filled by an invented result.

The existing checkout is the intended exclusive execution context. Do not copy the corpus to a worktree. Keep source/models stable while Sol runs and give its output namespace exclusive ownership. No Sol chat or automation has been created.

## Launch prompt template

Send this only after setup-only checks pass and Oriol explicitly authorizes launch. The new Sol session then completes fresh-session readiness before inference:

> Use GPT 6.1 Sol in a fresh chat on this existing checkout. Read the project instructions, docs/requirements.md, docs/roadmap.md, docs/batch-1a-v1.md and its private manifest. Complete the brief's fresh-session entry sequence and record only the launch authorization/window/restoration details I explicitly provide. Execute `execution_steps` with immediate validation and comparison barriers, bounded retries and budgets. Phone work requires the dated Europe/Zurich window; the approved lab command's benchmark instrumentation setup is included. Do not edit frozen code/models/contracts, install a daily build, change settings, launch another batch or request recordings. Preserve every result and return the specified handback. A missing prerequisite or failed evidence barrier means stop and return to setup.
