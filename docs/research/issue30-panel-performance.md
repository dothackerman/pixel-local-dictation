# Issue 30 — review and timing check

2026-10-27. Review base: `main`, `7a260aafc0ab94091e4609332b2dae5efa2d205b`.
Owner approved the final panel in a Signal screen recording. Recognition configuration,
dictionary, native runtime and permissions remain unchanged.

## Two-axis review

- **Standards:** no production findings or baseline smells. The added measurement helper
  initially discarded failed/partial measurements; fixed before measurement, then independently
  re-reviewed against ADR-011/017. No outstanding Standards findings.
- **Spec:** no code defects or scope creep. The owner-reviewed take began with an unready
  model, committed successfully and returned to usable FUTO. The remaining manual acceptance
  item is the new arrow's Cancel path: no insertion and return to FUTO. Existing cancellation
  unit tests do not substitute for that live demonstration.

## Observed startup and real take

Compare only the accepted baseline APK `a24061054046…` with final UI APK `fb9740614367…`.
Other historical experimental APKs in the receipts are excluded. Baseline: three model-loading
entries; feature: one. These are observations, not a matched startup experiment.

| Endpoint | Baseline (ms, range) | Final panel (ms) |
|---|---:|---:|
| Process start-request → panel draw | 1043–1188 | 1071 |
| App panel request → draw | 44.576–49.820 | 59.085 |
| App panel request → capture start | 31.149–36.864 | 42.707 |
| App panel request → first audio | 231.355–245.157 | 233.848 |
| App panel request → model ready | 4797.227–5197.419 | 5392.782 |

No clear process-to-draw regression appears in this small sample. App-side draw is 11.441 ms
above the baseline median; capture start is 9.710 ms above it. Model readiness is 195.363 ms
beyond the observed baseline maximum. These differences cannot establish causation or a tail
latency bound. The final build has no observed warm live sample. Model readiness still follows
capture and draw; neither waits for it. App draw is not physical display time, and process
request is not the FUTO microphone touch (README/ADR-026).

The final 37.376 s real take has Stop callback → accepted commit **2775.527 ms**, with accepted
keyboard return. The accepted baseline's retained live takes were cancelled, so there is no
matched baseline live Stop wait. Do not compare this directly to unrelated historical replay
inputs or claim a live speedup.

## Matched component replay

`PanelPerformanceTest`, opt-in with `panelPerformance=true` and a fresh `label`, ran the same
two retained clips against both installed APKs using the **same test APK** (`ef383429db1b…`).
Each arm verifies Candidate A identities, warms the model and first inference identically,
then feeds 640 samples at a time at real-time pace. Reports contain timings and hashes only;
failed/partial attempts are preserved. The original APK was installed first; the final panel
was reinstalled afterward and remains installed. No microphone or real editor is used.

The feature arm includes the actual loudness observer and ring code, recording the panel
into an in-memory **software bitmap** on the main thread at a 16 ms scheduling interval.
The baseline panel is static. Both arms use simulated commit and keyboard return. This is
a CPU rendering/load diagnostic, not GPU frame timing or end-to-end IME evidence.

| Input | Duration (s) | Baseline Stop → simulated commit (ms) | Feature (ms) | Difference |
|---|---:|---:|---:|---:|
| take-1 | 34.08 | 3050.938 | 3237.195 | +186.257 ms / +6.10% |
| take-3 | 31.68 | 1801.486 | 1798.532 | −2.954 ms / −0.16% |

Both outputs match their frozen expected hashes and each other. Thermal status remained 0.
One pair per input is insufficient for a statistical no-regression claim. The positive
186 ms difference is retained, not relabelled as a pass against an invented tolerance.
Software rendering averaged 20.603/19.394 ms per draw (maximum 51.258/50.746 ms), adding CPU
work that a GPU-rendered panel does not perform in the same way.

Component inflate/measure/software-draw: first sample **201.818 → 221.152 ms**; median of
12 consecutive samples **32.605 → 32.240 ms**. These include bitmap allocation/rasterization
inside an instrumented process; they are not live cold/warm startup timings.

## Signal height trade-off

The owner-reviewed video is in Signal. Version **8.32.3** is the last recorded version;
current version was not independently re-read under the app-only phone boundary.
App geometry records **446 → 915 px** panel height at density 2.4375: **183.0 → 375.4 dp**,
or 192.4 dp less editor space. Each observed panel is stable during the initial two-second
collection, with unchanged zero exported content/visible top insets. The larger panel is
owner accepted. This documents the trade-off; it establishes no Signal height-cache fix.

## Evidence

Private, gitignored; no recordings, transcripts or frames are published:

- Baseline snapshot: `corpus/lab/snapshots/issue30-before-panel-20261027/`.
- Startup/live/geometry receipts: `corpus/lab/pull/20261027T145217/`.
- Baseline component run: `corpus/lab/runs/20261027T150627-run-02cecb/`;
  report: `corpus/lab/pull/20261027T150821/`.
- Feature component run: `corpus/lab/runs/20261027T150951-run-7c8050/`;
  report: `corpus/lab/pull/20261027T151358/`.
- Analysis: `corpus/lab/issue30-perf/`. Recovery snapshot remains available.
