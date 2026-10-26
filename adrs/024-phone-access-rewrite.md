# ADR-024 — Phone access: one app-scoped module, sandbox as the boundary

2026-10-21 · **Accepted by the user; implemented on `codex/issue-1-fp32-encoder`.** Supersedes [ADR-009](009-phone-recovery.md) and both former guard hooks.

## Context

Six days of phone work (Codex 16–21 Oct, Opus 21 Oct) ran through a 1,560-line protected wrapper whose every change needed a reviewed whole-file proposal and a manual apply by the user. Measured: 295 guard denials, all on read-only commands, zero real adb/fastboot violations caught; 3,375 auto-review verdicts, 100 % allow on phone commands; nine manual applies with idle waits up to 9 h; wrapper plumbing took 37–64 % of assistant turns. Experiment semantics (receipt schemas, hash pins, a one-off incident policy) lived inside the security boundary, so every scientific pivot was a security change; a failed invariant also blocked recovery from it. Policy was duplicated five times. The user asked for "an extreme reduction in complexity" and no more apply loops. Full evidence tables, verbatim quotes and the nine-item flaw list: this ADR at commit `24bc056`.

## Decision

**Seam.** One module, `tools/phone`, app-scoped by construction: every adb argv comes from fixed templates with the package constant `ch.og.dictation`; caller strings reach the device only as validated names (`[A-Za-z0-9_.-]+` path components, no `..`, under `files/`). It carries no experiment knowledge: receipt schemas, frozen manifests and tolerance checks live in host tools (`tools/encoder_probe.py` etc.), editable and tested without approval. Hooks are fast-fails for user experience; the **sandbox is the boundary**.

**Interface.** `phone help` (the `USAGE` string in `tools/phone`) is the authoritative verb list: read-only `status device app logs crash`, host-only `check`, app-scoped `install push pull run stop`, recovery `snapshot restore`, destructive `uninstall`. Output is one JSON object on stdout, also written to `corpus/lab/last.json`. Exit 0 success; 3 `refused: <reason>` (policy, do not retry); 1 `failed: <reason>` (device/transport, retry allowed). Refusal and failure never share a channel.

**Invariants** (asserted by `tools/test_phone.py`):

- Reads outside the app: allowlisted `getprop` keys, `dumpsys thermalservice`, `dumpsys package|meminfo|gfxinfo` for our two packages, FUTO keyboard and voice-input `versionName` (user decision 2026-10-21), `dumpsys input_method` reduced to booleans about our IME. No `settings`, `ime list`, `pm list`, other packages, `/sdcard`, screenshots, backups, reboot, root.
- `check`/`install`: package ∈ {app, `.test`}; one shared signer; permission allowlist per package (app: `RECORD_AUDIO`, `WAKE_LOCK`; test adds `REORDER_TASKS` for the androidx runner); refuse `INTERNET`, `ACCESS_NETWORK_STATE`, `QUERY_ALL_PACKAGES` and any `<queries>` block on **both** APKs (R3, "other information off limits"); test APK must instrument only the app.
- `push` refuses symlinks and non-regular files and sends only changed files; `pull` is limited to `files/lab` and `files/benchmark`; every tar from the device is validated on the host (regular files, no links, no `..`) before extraction. `restore` uploads the archive first, then wipes and extracts; it never streams an agent-writable archive into `tar x`.
- `logs` filters by the app's uid (survives crashes); `crash` filters the crash buffer by package.
- Live dictation is protected: `install restore stop uninstall` refuse while our IME is bound or showing input. `uninstall` also refuses while the IME is enabled.
- Serial, user ids and SDK paths never appear in output. Exactly one authorized device of the permitted model, else refuse before any command.
- One `flock` per invocation. Every mutating verb and `run` write `{verb, ts, app_sha256, test_sha256, lab_sha256}` to `corpus/lab/runs/<ts>-<verb>-<id>/meta.json`: evidence binding is data beside results, not a refusal.

**Gating.**

| Verb | Approval |
|---|---|
| `help check status device app logs crash pull push run snapshot` | none (`allow` rule) |
| `install restore stop` | one permission prompt per call |
| `uninstall` | refused unless the user created `corpus/lab/.allow-uninstall`; deleted after use |

`pull files/benchmark` needs no approval (user decision 2026-10-21); pulled recordings land in gitignored `corpus/` and the pre-commit hook keeps them out of Git. Test code runs as the app's uid and can copy `files/benchmark` into `files/lab`; accepted and stated.

**Recovery.** `snapshot`/`restore` cover the retained APK pair plus `files/`, `shared_prefs/`, `databases/`. Not restorable, and said so in `restore` output: microphone grant, IME enablement/selection, FUTO's "disable built-in voice input" toggle. After `uninstall` the user re-enables the keyboard by hand.

**Trust boundary: the installed copy runs, never the worktree file.** Agents edit `tools/phone` and its tests freely on the branch. The shim `.claude/hooks/phone` (hook-protected) runs `${XDG_DATA_HOME:-~/.local/share}/pixel-phone/phone` with `python3 -I`, a path outside the sandbox write allowlist. The user installs a new copy with `bash tools/gate/apply.sh`, which compiles the wrapper, runs `test_phone` and `test_agent_guard`, installs the copy, the hooks, both harness configs, and equal regular `AGENTS.md`/`CLAUDE.md` files generated from `tools/gate/AGENTS.md`. Only the shim is in the `allow` rules and the sandbox `excludedCommands`; `excludedCommands` matches the exact command line, so the shim must be called alone (no pipe or redirect). Branches, worktrees, `python3 tools/phone` and stray copies are irrelevant: the shim never reads the checkout. No GitHub step, merge or network on the phone path.

**Harness** (`tools/gate/`, one shared script for Claude and Codex). `phone_guard.py`, two rules only: (1) Bash: tokenize like a shell (heredoc bodies removed, separators split, transparent wrappers such as `env`/`timeout`/`xargs` skipped, `sh -c` recursed); deny when a command word is `adb`/`fastboot` or a Gradle task matches `install|connected|uninstall|device`. Words in quotes, grep patterns and docs never count. (2) Edit/Write/apply_patch: deny paths under `.claude/hooks`, `.claude/settings.json`, `.codex`, `.git`, `tools/git-hooks`, `AGENTS.md`, `CLAUDE.md`. The wrapper source is not protected. Sandbox (`settings.json`): `allowUnsandboxedCommands: false`; `denyRead` on `~/.android` and `~/Android/Sdk/platform-tools` so Gradle device tasks fail even if a hook misses them; `denyWrite` on the harness paths; nothing under `.git` is listed because any entry below `.git` pins `.git` itself read-only (sandbox-runtime ancestor pin) and blocked every commit; `allowWrite: ~/.gradle` so builds and the quality gate run sandboxed. Codex: `config.toml` with the same read-only set, `hooks.json` entering the same script; `phone.rules` deleted. Both instruction files retain read-only protection. The installer replaces symlinks with regular files: on 26 October, the protected `CLAUDE.md` symlink prevented Codex’s Linux sandbox from starting. `check_installation.py` validates generated files and protected-path symlink conflicts; a fresh non-escalated agent command and host checks must separately establish sandbox readiness.

## Consequences

- Scientific pivots are host-tool edits with tests; wrapper changes are branch edits plus one user command (`apply.sh`).
- Approval prompts only where they discriminate (install, restore, stop, uninstall token).
- Evidence integrity is `meta.json` beside results; experiments judge bytes, the wrapper does not.
- The host sandbox must actually run: this host's AppArmor `bwrap-userns-restrict` profile broke every sandboxed Claude command, and a broken sandbox turns every command into a classifier round. Root cause, vendor positions and the user-run fix options: [research/linux-sandbox-userns.md](../research/linux-sandbox-userns.md).
- Operating guidance lives in `AGENTS.md`, README's phone section and `phone help`, not here.

## Rejected

- Keep the wrapper read-only and formalize the proposal flow: the flow was the problem (nine applies, 7–9 h waits, review without the real file).
- Compare the worktree wrapper's bytes with local `main` before running (revision 1): local `main` is agent-writable and copies pass the wrapper's own path check.
- Anchor on `main:tools/phone` fetched from GitHub (revision 2, dropped by user decision 2026-10-21): forced a merge for every wrapper change on a long-lived spec branch; in a solo repository branch protection adds a GitHub detour without a second reviewer.
- Hash-pin everything harder: pins protect evidence integrity, which now lives in `meta.json`.
- Approval prompts per phone command: 3,375 verdicts, 100 % allow, no information.
- Give agents raw adb: the scope guarantee is exactly what the wrapper is for.

## Status (2026-10-21)

Done: `tools/phone` (646 lines, 19 fake-device tests), `tools/gate/` (shim, guard with 8 tests, Claude/Codex settings, `AGENTS.md`, `apply.sh`), proposals and `phone.rules` deleted, `encoder_probe.py` no longer pins a wrapper file, ADR-009 superseded, `CLAUDE.md` symlinked. First live use happened (ADR-025): snapshot, install, push, run, pull; `restore` verified byte-identical after the `fb881a5` fix, again after the #4 runs (full round-trip, ADR-025 Recovery). `pull` of single files fixed in `e176bec`. Added after the first session: `check` (host-only APK acceptance before the phone stage), incremental `push`, `allowWrite ~/.gradle`.

Decided since: the manifest-hash check of the old wrapper is dropped on purpose; `check`/`install` verify package, signer, permission allowlist and `<queries>`, and Android itself refuses a signer change on update. Codex runs the shared guard (its `hooks.json` enters `.codex/hooks/phone-guard.sh`; Codex transcripts 16–20 Oct show the guard's denials). The sandbox was repaired with the user-level setting `sandbox.network.allowAllUnixSockets: true`, not the sysctl route; Unix sockets are therefore reachable from sandboxed commands.

Open:
- Agents still cannot `git commit` inside the sandbox: Claude Code 2.1.304 mounts the whole `.git` read-only although its docs protect only `.git/hooks` and `.git/config` (verified via `/proc/self/mountinfo`, 21 Oct evening, with no `.git` entry in our `denyWrite`). User requirement 21 Oct: all git operations must work for Claude Code and Codex. Interim: `git *` in Claude `excludedCommands` (runs outside the sandbox), compensated by the guard refusing `git -c/-C/--exec-path/--git-dir/--work-tree/--config-env` and `GIT_*` environment; Codex `config.toml` no longer marks `.git` read-only. Verified for Claude Code on 21 Oct evening (bare `git commit`/`git reset` from an agent session succeed; `cd … && git …` stays sandboxed and fails, so git must be the whole command line). Codex still to verify; report the Claude sandbox defect upstream.
- Lesson from the first session not encoded anywhere: state the recommended default together with a question, instead of idling on it (45 min lost).
