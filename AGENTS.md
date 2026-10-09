# Implementer entry point

Before implementation or planning changes, read [requirements](docs/requirements.md) and [roadmap](docs/roadmap.md). For engine selection or acceleration work, consult [research](docs/research.md) and [decision records](docs/decisions.md).

Keep requirement changes distinct from design changes. Update AS-IS claims only from evidence; label proposals and unverified results. Preserve superseded ADR rationale. Each fact belongs in one authoritative document; link rather than duplicate. Add documentation only when a reader needs it. Keep this repository local-only unless Oriol explicitly requests otherwise.

## Phone boundary

Hard rule, enforced by `.claude/settings.json` and `.claude/hooks/phone-guard.sh`. The phone is Oriol's personal GrapheneOS Pixel 10 Pro XL. The agent reaches it only through `tools/phone <subcommand>`, which is read-only except for installing and uninstalling the package `ch.og.dictation`. Never call the Android debug bridge or fastboot directly, never change phone or GrapheneOS settings, never touch FUTO Keyboard or other apps, never modify the gate files. One-time keyboard setting changes are done by Oriol by hand from written instructions that include the way back. Recordings live in `corpus/` (gitignored; pre-commit refuses audio anywhere). Copy nothing out of it without Oriol's agreement. The hook refuses any shell command whose text mentions the wrapper path, the Android debug bridge, fastboot or platform-tools, including heredocs and commit messages: use the Read and Write tools for those files and keep those words out of shell commands.
