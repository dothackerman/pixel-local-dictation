# Pixel Local Dictation

Goal: faster, more accurate English/German dictation entirely on Oriol's Pixel 10 Pro XL, launched from FUTO Keyboard's existing microphone key. Git remains local-only.

## Start here: evidence and next decision

**[Allocation and measurement completed on 19 October 2026](docs/adrs/012-allocation-outcome.md).** Read its results and limitations first. Select the next question from existing evidence before preparing code or measurements. [Current status](docs/system.md#status-and-acceptance-gaps) records that no successor experiment is commissioned; the roadmap's future directions are not execution authorization.

Later evidence: [Moonshine Small](docs/adrs/014-moonshine-screen.md), [CPU profiling](docs/adrs/015-cpu-profiling.md), [GPU/TPU access](docs/adrs/016-accelerator-access.md). Campaigns closed; no successor commissioned.

The completed experiment tested whether allocation changes can make existing quality controls practical on the phone. Product improvement over FUTO in both languages remains unproven; [results](docs/system.md#status-and-acceptance-gaps) index the measurements and their limits.

| Need | Document |
|---|---|
| Read completed allocation results and current work status | **[Allocation archive](docs/adrs/012-allocation-outcome.md)** / [current status](docs/system.md#status-and-acceptance-gaps) |
| Prepare valid comparisons and preserve evidence | [Shared experiment protocol](docs/system.md#evaluation-architecture) |
| Product constraints | [Requirements](docs/system.md#requirements) |
| Unfinished product gates and future experiments | [Roadmap](docs/system.md#status-and-acceptance-gaps) |
| Design rationale and supersession | [ADRs](docs/adrs/) |
| Model/runtime evidence, consulted by question | [Research index](docs/adrs/013-future-investigations.md) |
| Daily use and rollback | [Setup](docs/system.md#privacy-and-operation) |
| Stage and recover an authorized phone experiment | [Phone lab operations](docs/system.md#phone-lab-boundary) |

## Completed work — reference only

| Record | Execution status | Outcome |
|---|---|---|
| [Milestones 1–2](docs/adrs/010-ime-lifecycle.md) | Completed prototype gates | Integration/recognition demonstrated; product superiority not established |
| [Experiment nights 1–3](docs/adrs/004-closed-loop.md) | Completed campaigns | Findings and failures retained; no deployment winner |
| [Allocation and measurement](docs/adrs/012-allocation-outcome.md) | Completed diagnostic loop | Quality/resource tradeoffs and cohort limitations retained; no product adoption |

Completed orchestration and runbooks are recoverable at the pinned execution checkpoint; [source recovery and offline analysis](docs/adrs/008-three-stage-loop.md) explains how. Active tools retain scoring, matched evidence checks, measurement adapters and the daily app. No successor runner exists.

## Build and host checks

Fetch pinned assets with `tools/fetch-assets.sh` (see `assets.lock`). The shipped selective encoder uses `tools/export-fp32.sh` and `tools/quant_variant.py`; the full export's reproducibility gap is recorded in the roadmap. Configure signing with `tools/set-signing-password.sh`, then build with `ANDROID_HOME=~/Android/Sdk ./gradlew :app:assembleRelease`. [The build file](app/build.gradle.kts) owns signing configuration and failure behavior.

`python3 tools/quality.py` runs Python regressions, Kotlin benchmark unit tests and Android lint offline, without device tests. The local pre-commit gate invokes it with `--staged`: stage tracked edits and new source/test/build inputs first so the checkout matches the index. It rejects index changes during the run.

Documentation proposal: requirements/architecture in `docs/system.md`; decisions in `docs/adrs/`. Scoring-fixture migration remains required before execution from this branch; see the documentation boundary.
