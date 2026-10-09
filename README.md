# Pixel Local Dictation

Replace the speech recognizer, not FUTO Keyboard: faster, more accurate English/German dictation entirely on Oriol's Pixel 10 Pro XL.

**AS-IS — 2026-10-09:** GrapheneOS, FUTO Keyboard, Multilingual-244. Press microphone and speak one language per recording; English/German detection is automatic. Oriol reports slow recognition and wants fewer corrections. Installed versions, timings and correction rates have not been measured.

**Project state (2026-10-09):** milestone 1 shell `ch.og.dictation` 0.1.1 is built, installed and enabled on the phone; it records, inserts text and returns to FUTO when reached via the system switcher. The FUTO microphone-key path is fixed but not yet re-verified. No recognizer, model assets, benchmark or acceleration work exists. Git is local-only; no remote is configured.

## Read by need

| Reader need | Source of truth |
|---|---|
| What must remain true? | [Requirements](docs/requirements.md) |
| What should the implementer do next, and when is it done? | [Roadmap](docs/roadmap.md) |
| Why this approach; what was superseded? | [Decision records](docs/decisions.md) |
| Which evidence and upstream projects should be inspected? | [Research](docs/research.md) |
| How is the current milestone being executed, and what was found on the phone? | [Plan](docs/plan.md) |
| How is the phone set up, and how is it put back? | [Setup and rollback](docs/setup.md) |

Start with roadmap milestone 1. The first useful delivery is a test APK exercising the real FUTO microphone path, followed by local recognition—not a new keyboard or a promised winner.

Oriol supplies representative speech and judges correction effort. The implementer owns build tooling, engine integration, measurements and rollback instructions. Project setup is authorized; phone changes and SDK enrollment are future actions, not actions already performed.
