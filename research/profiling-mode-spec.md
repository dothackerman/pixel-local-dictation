# Profiling mode: draft spec

2026-11-12. Source inspection and issue reading only, at `7af0c39`+`2f0f3f9`
(branch `codex/spec-36-interruption-history`, PR #55). Nothing was compiled,
run or measured; nothing touched the phone. Produced by two independent
designs and three isolated adversarial reviews (measurement validity, Android
feasibility, scope and value). Line numbers are as of that commit; verify
before relying on them. Draft: becomes a GitHub issue once the owner decisions
in section 5 are made.

## 1. Problem

Owner: the app feels slow and very variable, in two places: (a) how fast
preview text appears while speaking, (b) how fast text lands in the field
after Stop. Wish: a switch, like benchmark mode, that measures every real take
so bottlenecks, and limits of device or model, can be found from data.

## 2. What is already known

- **Preview (a) is chunk policy, not a hidden bottleneck.** Preview updates
  only when a chunk closes at a pause after ≥ 20 s (`candidate_a.json:52-58`,
  `Recognizer.kt:67-101`). First text ≈ 25–33 s into speech (#54, ADR-025).
  Only planned fix: streaming (#23–#27, none started). #54 forbids changing
  chunking without an approved comparison.
- **Stop wait (b) is the undecoded tail at Stop.** 1.8–2.8 s live (n=3),
  2.2–4.3 s on synthetic 1 min fixtures (#54). Chunks close only at a pause, so
  tail length is effectively random: the likeliest cause of "very variable".
  Untested.
- **Startup:** ≈ 1.3 s cold / 0.25 s warm, ≈ 76 % before our service is
  entered (#17). Owner-accepted (ADR-026). The app cannot see the FUTO key tap.
- Thermal status was 0 in every recorded run. Run-to-run spread ≤ 330 ms.
- **Gaps:** live Stop wait distribution; live first-preview time; Stop wait
  split on real takes; tail length vs Stop wait; which phase the owner
  actually perceives.
- Daily build records nothing: `StartupTrace` and `RotationTrace` exist only
  with `R.bool.benchmark_receipts`; the `TimelineObserver` is null otherwise
  (`DictationImeService.kt:815`). Benchmark JSON already holds `eventsNs`,
  `recordingMs`, `stopToFinalMs` per saved take (`DictationImeService.kt:897-913`).

## 3. Proposed approach (recommendation, not yet owner-approved)

**Step 1, no app change.** Benchmark mode on for a few days of normal use;
pull `files/benchmark`; host script `tools/analyze_take_timing.py` reads only
timing keys (allow-list; never `text`) and reports Stop wait against
`recordingMs` and tail length. Tests whether tail length explains the
variance. Cost: benchmark mode stores audio and transcripts while on.

**Step 2, only if step 1 leaves questions open.** Trimmed profiling mode as
the implementation of #25 (per-take live receipts), not a parallel mechanism.

### Step 2 design

- Module `TakeProfiler`. Interface: `panelRequested(modelReady)`,
  `begin(): TakeProbe`; `TakeProbe` is a `TimelineObserver`
  (`TakeTranscriber.kt:16`) plus `recordingStarted()`, `finished(TakeResult)`.
  Never blocks, never throws, no I/O on the caller thread. Hidden: event
  accumulation, pure `ProfileRow.build`, environment sampler, writer.
  Output seam: row sink with a file adapter and an in-memory test adapter.
- One metadata-only JSON row per take, plus a tiny begin row so takes that
  never finish are countable. Durations are integer ms from `System.nanoTime`
  differences; null when an event did not occur (never 0).
- Fields:
  - identity: `v`, `processId`, `takeSeq`, `appVersion`, `variant`,
    `runtimeGeneration` (detects the rebuild-per-take defect, ADR-026).
  - outcome, `interrupted`, `rotated`.
  - start: `modelReadyAtStart`, `modelLoadMs`, `panelToRecMs`,
    `msSincePrevTakeEnd`.
  - take: `audioMs`, `chunks`.
  - preview: `firstPreviewMs`, `firstPreviewAudioMs` (policy share),
    `previewDecodeLagMs` median/max (fixable share), `previewShownMs`
    (frame drawn after text set).
  - stop: `stopMs` (glossary Stop wait), `drainMs`, `flushMs`,
    `queuedAtStop`, `unqueuedAudioMs`, `tailAudioMs`, `tailDecodeMs`,
    `stageMs` (History write), `commitCallMs`, `heldMs`, `pausedMs`,
    `commitShownMs`, `residualMs` (Stop wait minus the parts).
  - environment at begin and end: thermal status, power-save.
  - self-check: `probeNs`, `lostRows`.
- Storage: `files/profile/takes.jsonl`, process-static writer thread, rotation
  with an explicit discard marker, write failures counted in the next row.
  Settings: switch, row count, delete button (below the benchmark section;
  `SettingsScreenTest.kt:72-81` asserts order).
- Host: `tools/analyze_take_profile.py`, strict key allow-list. Reports counts
  (completed / cancelled / interrupted / no end row) first, then medians with
  bootstrap intervals, share of takes above 3 s and 5 s, Stop wait per audio
  second. Quantiles only at n ≥ 100.
- Alongside: a controlled replay series (same fixture, several lengths, cold
  and warm) to get the decode floor; daily-use rows alone cannot attribute cause.

### Review findings folded into the design

1. `previewReady` (`TakeSession.kt:116`) and `commitAccepted` (`:321`) are
   marked before anything is drawn: lower bounds. Hence `previewShownMs`,
   `commitShownMs` via a frame callback.
2. Stop wait includes rotation hold (`deliveryHeld`, `TakeSession.kt:292`),
   the History stage (`:277`) and an open Cancel choice. `pause()`/`resume()`
   (`:210-224`) and `holdDelivery()` (`:227`) emit no events: new marks needed.
3. Only the first preview emits an event (`TakeTranscriber.kt:124`); later
   deliveries need `previewDelivered(chunkId)`.
4. Model load time is `Log.i` only (`Engine.kt:133`); `Engine.build` passes no
   timeline. Needs a load-time field and an atomic generation counter latched
   in `openTake`.
5. `Debug.threadCpuTimeNanos` on the decode thread is meaningless with
   `threads: 4`; use process CPU time deltas, or drop the field.
6. Attaching an observer enables queue accounting (`TakeTranscriber.kt:82-85`)
   and the `firstAudio` mark (`TakeSession.kt:130`): a profiled take is not
   exactly a daily take. Measure probe cost directly; an n=10 A/B proves nothing.
7. `finished` is skipped on process death, on a throwing `openTake` or
   capture (`DictationImeService.kt:850-861`) and must run before the
   generation check at `:719`.
8. Observer calls arrive on audio, main and decode threads, some under locks:
   lock-free append with a sealed flag; a composite observer must catch per
   child (`TakeTranscriber.kt:19-21`).
9. Service touch points are about 8 sites, not "6 lines". Keep them one-line
   delegations.
10. `files/lab` is unsuitable: `phone push` on `lab` deletes what the host
    lacks (`tools/phone:437`).

## 4. Rule changes step 2 would need

- DEVELOPMENT.md:93-97: "release collection is off" → amended for an opt-in switch.
- ADR-024 and `tools/phone:42` `PULL_ROOTS`, `tools/test_phone.py`: add
  `files/profile`; owner installs via `bash tools/gate/apply.sh`.
- GLOSSARY.md: terms for first-preview time and the Stop wait parts.

## 5. Open owner decisions

1. Step 1 first, or build step 2 directly?
2. Switch in the daily build, or benchmark variant only?
3. Private-field takes: skip (recommended; a row would record the length of a
   dictated password) or log?
4. Retention: expiry in days (recommended 7) besides the size cap?
5. One-tap "fast / ok / slow" rating after a take, to learn which number
   matches the feeling? Adds a panel element.
6. Fold into #25, or a new issue?

## 6. Open steps

1. PR #55: owner re-visit #61 on the Pixel (A2, A3, A7, A8, B9, B10 on
   `7af0c39`), then merge. Not done; needs the Linux machine.
2. Decide section 5.
3. Step 1 script with tests; collect; read the result.
4. If step 2: slices in order. Small model: settings key, `ProfileRow.build`
   and analyzer allow-list; `ProfileLog`; Settings UI. Stronger model: new
   session/transcriber events and Engine load time; `TakeProbe`; IME wiring
   and lifecycle edges. Owner: gate apply, on-device overhead check. Opus
   for code review only.
5. Not done from the original request: no `arena` skill was available; no
   wiki location agreed (chief-of-staff).

Branch `feat/profiling-mode` is cut from the PR #55 tip, not from `main`.
