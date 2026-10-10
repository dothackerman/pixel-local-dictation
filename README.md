# Pixel Local Dictation

Replace the speech recognizer, not FUTO Keyboard: faster, more accurate English/German dictation entirely on Oriol's Pixel 10 Pro XL.

**AS-IS — 2026-10-09:** GrapheneOS, FUTO Keyboard, Multilingual-244. Press microphone and speak one language per recording; English/German detection is automatic. Oriol reports slow recognition and wants fewer corrections. Installed versions, timings and correction rates have not been measured.

**Project state (2026-10-10, end of session):** milestones 1 and 2 are done (tags `m1`, `m2`). `ch.og.dictation` 0.2.2 recognises German and English on the phone with Parakeet TDT 0.6B v3 through sherpa-onnx ([ADR-003](docs/decisions.md)), decoding during the recording. First measurement against FUTO ([results](docs/results.md)): the wait after Stop fell from 80–100 s to about 1 s; accuracy is level with FUTO, not better, so R4 is half met. Next: the unattended accuracy experiment loop is set up ([autoresearch](docs/autoresearch.md), [ADR-004](docs/decisions.md)) and starts once Oriol has done the four preparation steps in [plan](docs/plan.md), "Next session". No acceleration work exists. Git is local-only; no remote is configured.

## Read by need

| Reader need | Source of truth |
|---|---|
| What must remain true? | [Requirements](docs/requirements.md) |
| What should the implementer do next, and when is it done? | [Roadmap](docs/roadmap.md) |
| Why this approach; what was superseded? | [Decision records](docs/decisions.md) |
| Which evidence and upstream projects should be inspected? | [Research](docs/research.md) |
| How is the current milestone being executed, and what was found on the phone? | [Plan](docs/plan.md) |
| How is the phone set up, and how is it put back? | [Setup and rollback](docs/setup.md) |
| What was measured against FUTO? | [Results](docs/results.md) |
| Which accuracy experiments are on the table? | [Experiment ideas](docs/experiments.md) |
| How does the unattended experiment loop work, and what are its rules? | [Autoresearch loop](docs/autoresearch.md), [ADR-004](docs/decisions.md#adr-004--experiment-loop-with-a-closed-variant-schema-and-the-phone-as-second-judge) |

Build: `tools/fetch-assets.sh` (pinned downloads, see `assets.lock`; the shipped model is built once with `tools/export-fp32.sh` and `tools/quant_variant.py`), `tools/set-signing-password.sh`, then `ANDROID_HOME=~/Android/Sdk ./gradlew :app:assembleRelease`.

Oriol supplies representative speech and judges correction effort. The implementer owns build tooling, engine integration, measurements and rollback instructions. Project setup is authorized; phone changes and SDK enrollment are future actions, not actions already performed.
