# Pixel Local Dictation

Replace the speech recognizer, not FUTO Keyboard: faster, more accurate English/German dictation entirely on Oriol's Pixel 10 Pro XL.

**AS-IS — 2026-10-09:** GrapheneOS, FUTO Keyboard, Multilingual-244. Press microphone and speak one language per recording; English/German detection is automatic. Oriol reports slow recognition and wants fewer corrections. Installed versions, timings and correction rates have not been measured.

**Project state:** documentation-only handoff. No app, model assets, phone benchmark, installation or hardware-acceleration validation exists here. Git is local-only; no remote is configured.

## Read by need

| Reader need | Source of truth |
|---|---|
| What must remain true? | [Requirements](docs/requirements.md) |
| What should the implementer do next, and when is it done? | [Roadmap](docs/roadmap.md) |
| Why this approach; what was superseded? | [Decision records](docs/decisions.md) |
| Which evidence and upstream projects should be inspected? | [Research](docs/research.md) |

Start with roadmap milestone 1. The first useful delivery is a test APK exercising the real FUTO microphone path, followed by local recognition—not a new keyboard or a promised winner.

Oriol supplies representative speech and judges correction effort. The implementer owns build tooling, engine integration, measurements and rollback instructions. Project setup is authorized; phone changes and SDK enrollment are future actions, not actions already performed.
