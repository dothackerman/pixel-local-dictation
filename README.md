# Pixel Local Dictation

Goal: faster, more accurate English/German dictation entirely on Oriol's Pixel 10 Pro XL, launched from FUTO Keyboard's existing microphone key. Git remains local-only.

## Start here: the current experiment

**[Allocation and measurement — one loop, three stages](docs/next-experiment-plan.md)** is the only current experiment plan. Read its scope, readiness and next implementer steps first. A prepared stage is not a completed experiment or permission to use the phone.

The experiment asks whether allocation changes can make existing quality controls practical on the phone. Product improvement over FUTO in both languages remains unproven; [results](docs/results.md) and [the evaluation audit](docs/evaluation-audit-2026-10-14.md) own the measurements and their limits.

| Need | Document |
|---|---|
| Execute or finish preparing the current experiment | **[Current experiment](docs/next-experiment-plan.md)** |
| Prepare valid comparisons and preserve evidence | [Shared experiment protocol](docs/experiment-protocol.md) |
| Product constraints | [Requirements](docs/requirements.md) |
| Unfinished product gates and future experiments | [Roadmap](docs/roadmap.md) |
| Design rationale and supersession | [ADRs](docs/decisions.md) |
| Model/runtime evidence, consulted by question | [Research index](docs/research.md) |
| Daily use and rollback | [Setup](docs/setup.md) |

## Completed work — reference only

| Record | Execution status | Outcome |
|---|---|---|
| [Milestones 1–2](docs/archive/milestones-2026-10-10.md) | Completed prototype gates | Integration/recognition demonstrated; product superiority not established |
| [Experiment nights 1–3](docs/archive/campaigns-2026-10-13.md) | Completed campaigns | Findings and failures retained; no deployment winner |
| [Stage-1 setup](docs/setup-release-1a.md) | Completed preparation | Smoke evidence; the experiment itself has not run |

The frozen `batch-1a-v1.md` is a stage-specific runbook reached from the current plan. Its legacy filename is retained because the executable manifest pins it. It is not a competing project plan.

## Build and host checks

Fetch pinned assets with `tools/fetch-assets.sh` (see `assets.lock`). The shipped selective encoder uses `tools/export-fp32.sh` and `tools/quant_variant.py`; the full export's reproducibility gap is recorded in the roadmap. Configure signing with `tools/set-signing-password.sh`, then build with `ANDROID_HOME=~/Android/Sdk ./gradlew :app:assembleRelease`. [The build file](app/build.gradle.kts) owns signing configuration and failure behavior.

`python3 tools/quality.py` runs Python regressions, Kotlin benchmark unit tests and Android lint offline, without device tests. The local pre-commit gate invokes it with `--staged`: stage tracked edits and new source/test/build inputs first so the checkout matches the index. It rejects index changes during the run.
