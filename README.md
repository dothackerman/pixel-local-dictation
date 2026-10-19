# Pixel Local Dictation

Goal: faster, more accurate English/German dictation entirely on Oriol's Pixel 10 Pro XL, launched from FUTO Keyboard's existing microphone key. Git remains local-only.

## Start here: experiment status

**[Allocation and measurement completed on 19 October 2026](docs/archive/allocation-2026-10-19.md).** Read its results and limitations first. [Current status](docs/experiments/current.md) records that no successor experiment is commissioned; the roadmap's future directions are not execution authorization.

The completed experiment tested whether allocation changes can make existing quality controls practical on the phone. Product improvement over FUTO in both languages remains unproven; [results](docs/evaluation/results.md) index the measurements and their limits.

| Need | Document |
|---|---|
| Read completed allocation results and current work status | **[Allocation archive](docs/archive/allocation-2026-10-19.md)** / [current status](docs/experiments/current.md) |
| Prepare valid comparisons and preserve evidence | [Shared experiment protocol](docs/experiments/protocol.md) |
| Product constraints | [Requirements](docs/requirements.md) |
| Unfinished product gates and future experiments | [Roadmap](docs/roadmap.md) |
| Design rationale and supersession | [ADRs](docs/decisions.md) |
| Model/runtime evidence, consulted by question | [Research index](docs/research.md) |
| Daily use and rollback | [Setup](docs/setup/daily-use.md) |

## Completed work — reference only

| Record | Execution status | Outcome |
|---|---|---|
| [Milestones 1–2](docs/archive/milestones-2026-10-10.md) | Completed prototype gates | Integration/recognition demonstrated; product superiority not established |
| [Experiment nights 1–3](docs/archive/campaigns-2026-10-13.md) | Completed campaigns | Findings and failures retained; no deployment winner |
| [Allocation and measurement](docs/archive/allocation-2026-10-19.md) | Completed diagnostic loop | Quality/resource tradeoffs and cohort limitations retained; no product adoption |
| [Stage-1 setup](docs/experiments/allocation/stage-1-setup.md) | Completed preparation | Historical setup and smoke evidence; execution results are in the allocation archive |

Historical stage runbooks and preparation evidence remain under `docs/experiments/`. Research, evaluation evidence, user setup and completed history have separate directories.

## Build and host checks

Fetch pinned assets with `tools/fetch-assets.sh` (see `assets.lock`). The shipped selective encoder uses `tools/export-fp32.sh` and `tools/quant_variant.py`; the full export's reproducibility gap is recorded in the roadmap. Configure signing with `tools/set-signing-password.sh`, then build with `ANDROID_HOME=~/Android/Sdk ./gradlew :app:assembleRelease`. [The build file](app/build.gradle.kts) owns signing configuration and failure behavior.

`python3 tools/quality.py` runs Python regressions, Kotlin benchmark unit tests and Android lint offline, without device tests. The local pre-commit gate invokes it with `--staged`: stage tracked edits and new source/test/build inputs first so the checkout matches the index. It rejects index changes during the run.
