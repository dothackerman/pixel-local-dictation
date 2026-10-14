# Implementer entry point

Before implementation or planning changes, read [requirements](docs/requirements.md) and [roadmap](docs/roadmap.md). For engine selection or acceleration work, consult [research](docs/research.md) and [decision records](docs/decisions.md).

Keep requirement changes distinct from design changes. Update AS-IS claims only from evidence; label proposals and unverified results. Preserve superseded ADR rationale. Each fact belongs in one authoritative document; link rather than duplicate. Add documentation only when a reader needs it. Keep this repository local-only unless Oriol explicitly requests otherwise.

## Phone boundary

Claude and Codex follow the same phone boundary. **Before phone work or safeguard setup**, read [safeguard setup](docs/agent-safeguards.md). Before phone work, complete its applicable activation and verification steps. That document owns harness configuration, checkout-specific approvals and enforcement limits; configured protection is not proof that it is active.

Reach Oriol's personal GrapheneOS Pixel 10 Pro XL only through this checkout's `tools/phone`, invoked by its exact absolute path with one subcommand per shell call. Read-only inspection is the default. Installation, uninstallation and app-scoped lab writes/tests require authorization for the current task and remain restricted to `ch.og.dictation`. Wrapper capabilities and automatic approvals do not authorize phone work; an experiment brief may impose stricter limits.

Never call the Android debug bridge or fastboot directly, weaken phone security, or change phone/GrapheneOS settings, FUTO Keyboard or other apps. Oriol performs one-time keyboard setting changes by hand from written instructions that include rollback. Keep the wrapper, hook/permission configuration and Git guard files unchanged unless Oriol explicitly authorizes a safeguard change; preserve matching protections in both harnesses. Inspect these files with non-shell file tools. Keep wrapper/bridge names out of other shell command text, including heredocs and commit messages. Honor denials. For an explicitly authorized safeguard change, use the harness's reviewed approval or escalation path; otherwise do not retry the denied action through another tool or terminal input.

Recordings stay in gitignored `corpus/`; copy nothing out without Oriol's agreement. Keep personal recordings, transcripts and signing credentials out of Git.
