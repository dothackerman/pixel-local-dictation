# Pixel Local Dictation

Replace the speech recognizer, not FUTO Keyboard: faster, more accurate English/German dictation entirely on Oriol's Pixel 10 Pro XL.

**AS-IS — 2026-10-09:** GrapheneOS, FUTO Keyboard, Multilingual-244. Press microphone and speak one language per recording; English/German detection is automatic. Oriol reports slow recognition and wants fewer corrections. Installed versions, timings and correction rates have not been measured.

**Current entry point:** read [the next-experiment plan](docs/next-experiment-plan.md), then [release 1a's execution brief](docs/batch-1a-v1.md). The release is prepared locally; a fresh GPT 6.1 Sol session needs separate launch authorization and the brief's launch record before running it. The old autoresearch brief and old plan's preparation steps are historical, not current launch instructions. Git remains local-only.

**Historical milestone checkpoint (2026-10-10):** milestones 1 and 2 were completed (tags `m1`, `m2`). The first measurement against FUTO is retained in [results](docs/results.md); it established faster Stop response but did not establish better accuracy in both languages. Later experiment evidence does not redefine the [requirements](docs/requirements.md).

## Read by need

| Reader need | Source of truth |
|---|---|
| What must remain true? | [Requirements](docs/requirements.md) |
| What should the implementer do next, and when is it done? | [Roadmap](docs/roadmap.md) |
| Why this approach; what was superseded? | [Decision records](docs/decisions.md) |
| Which evidence and upstream projects should be inspected? | [Research](docs/research.md) |
| What should the next experiment session execute? | [Release 1a brief](docs/batch-1a-v1.md), [next-experiment plan](docs/next-experiment-plan.md) |
| How were the first milestones executed? | [Historical plan](docs/plan.md) |
| How is the phone set up, and how is it put back? | [Setup and rollback](docs/setup.md) |
| What was measured against FUTO? | [Results](docs/results.md) |
| Which accuracy experiments are on the table? | [Experiment ideas](docs/experiments.md) |
| What governed the earlier unattended campaigns? | [Historical autoresearch loop](docs/autoresearch.md), [ADR-004](docs/decisions.md#adr-004--experiment-loop-with-a-closed-variant-schema-and-the-phone-as-second-judge) |

Build: `tools/fetch-assets.sh` (pinned downloads, see `assets.lock`; the shipped model is built once with `tools/export-fp32.sh` and `tools/quant_variant.py`), `tools/set-signing-password.sh`, then `ANDROID_HOME=~/Android/Sdk ./gradlew :app:assembleRelease`.

Oriol supplies representative speech and judges correction effort. The implementer owns build tooling, engine integration, measurements and rollback instructions. Current setup evidence and phone state are recorded in [the setup work order](docs/setup-release-1a.md); authorization for setup does not launch an experiment.

Host quality gate: `python3 tools/quality.py` runs the existing Python regressions,
Kotlin benchmark unit tests and Android lint with offline Gradle. The local
pre-commit gate invokes it with `--staged`: stage or stash tracked edits and stage
new source/test/build inputs first so the tested checkout matches the index. It
also detects index changes during the run. This command runs no device tests.
The recorder's host lint annotation documents its existing caller permission
check/exception boundary; saved release APK and setup smoke identities remain
historical and have not been rebuilt from that annotation-only source edit.
