# Pixel Local Dictation

Goal: faster, more accurate English/German dictation entirely on Oriol's Pixel 10 Pro XL, launched from FUTO Keyboard's existing microphone key.

**Next execution: [release 1a](docs/batch-1a-v1.md), in a fresh GPT 6.1 Sol session.** Setup is complete; launch still requires Oriol's instruction, a dated phone window and the brief's fresh-session checks. Follow its frozen manifest, not the historical milestone or night-loop recipes. Git remains local-only.

The Parakeet app passed the first integration milestones. [Measured results](docs/results.md) establish faster Stop response, but R4's accuracy improvement over FUTO in both languages remains unproven. [Night-three audit](docs/evaluation-audit-2026-10-14.md) separates development-set accuracy gains from resource failures. Latest recorded build/setup evidence is in [the setup record](docs/setup-release-1a.md); it is not a live phone-state check.

| Reader need | Start here |
|---|---|
| Product constraints | [Requirements](docs/requirements.md) |
| Remaining milestones and validation gaps | [Roadmap](docs/roadmap.md) |
| Execute the prepared experiment | [Release 1a brief](docs/batch-1a-v1.md) |
| Prepare later batches; check the recording policy | [Experiment plan](docs/next-experiment-plan.md) |
| Understand design choices and supersession | [ADRs](docs/decisions.md) |
| Investigate a model, runtime or evaluation question | [Research index](docs/research.md) |
| Use the app or restore FUTO | [Setup and rollback](docs/setup.md) |
| Understand early implementation or campaign evidence | [Milestone history](docs/plan.md), [campaign history](docs/autoresearch.md) |

## Build and host checks

Fetch pinned assets with `tools/fetch-assets.sh` (see `assets.lock`). The shipped selective encoder uses `tools/export-fp32.sh` and `tools/quant_variant.py`; the full export's reproducibility gap is recorded in the roadmap. Configure signing with `tools/set-signing-password.sh`, then build with `ANDROID_HOME=~/Android/Sdk ./gradlew :app:assembleRelease`. Signing configuration lives in gitignored `local.properties`; [the build file](app/build.gradle.kts) owns its keys and failure behavior.

`python3 tools/quality.py` runs Python regressions, Kotlin benchmark unit tests and Android lint offline, without device tests. The local pre-commit gate invokes it with `--staged`: stage tracked edits and new source/test/build inputs first so the checkout matches the index. It rejects index changes during the run.
