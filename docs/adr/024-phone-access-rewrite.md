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

**Harness** (`tools/gate/`, one shared script for Claude and Codex). `phone_guard.py`, two rules only: (1) Bash: tokenize like a shell (heredoc bodies removed, separators split, transparent wrappers such as `env`/`timeout`/`xargs` skipped, `sh -c` recursed); deny when a command word is `adb`/`fastboot` or a Gradle task matches `install|connected|uninstall|device`. Words in quotes, grep patterns and docs never count. (2) Edit/Write/apply_patch: deny paths under `.claude/hooks`, `.claude/settings.json`, `.codex`, `.git`, `tools/git-hooks`, `AGENTS.md`, `CLAUDE.md`. The wrapper source is not protected. Sandbox (`settings.json`): `allowUnsandboxedCommands: false`; `denyRead` on `~/.android` and `~/Android/Sdk/platform-tools` so Gradle device tasks fail even if a hook misses them; `denyWrite` on the harness paths; nothing under `.git` is listed because any entry below `.git` pins `.git` itself read-only (sandbox-runtime ancestor pin) and blocked every commit; `allowWrite: ~/.gradle` so builds and the quality gate run sandboxed. Codex: `config.toml` renders the same read-only set under the installed checkout’s absolute path (not `:workspace_roots`, which also projects rules into unrelated visualization folders), permits writes to `~/.gradle` for host checks, and uses `hooks.json` to enter the same script; `phone.rules` deleted. Both instruction files retain read-only protection. The installer replaces symlinks with regular files: on 26 October, the protected `CLAUDE.md` symlink prevented Codex’s Linux sandbox from starting. `check_installation.py` validates generated files and protected-path symlink conflicts; a fresh non-escalated agent command and host checks must separately establish sandbox readiness. Copied configs require owner installation in the new checkout before agent work; inherited `:workspace` protections alone do not cover all six custom paths.

## Consequences

- Scientific pivots are host-tool edits with tests; wrapper changes are branch edits plus one user command (`apply.sh`).
- Approval prompts only where they discriminate (install, restore, stop, uninstall token).
- Evidence integrity is `meta.json` beside results; experiments judge bytes, the wrapper does not.
- The host sandbox must actually run: this host's AppArmor `bwrap-userns-restrict` profile broke every sandboxed Claude command, and a broken sandbox turns every command into a classifier round. Root cause, vendor positions and the user-run fix options: [docs/research/linux-sandbox-userns.md](../research/linux-sandbox-userns.md).
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

## Status (2026-11-01)

- "`CLAUDE.md` symlinked" above is superseded: the installer writes regular `AGENTS.md`/`CLAUDE.md` copies (Harness, 26 October).
- Operating guidance lives in `AGENTS.md` and `phone help` (`DEVELOPMENT.md`, which also held it, was removed 2026-11-24); README no longer has a phone section.
- `tools/encoder_probe.py`, named above as an example host tool, is retired ([ADR-017](017-shared-take-experiments.md#retirements-and-preservation)).
- Status unverified: the git interim (`git *` unsandboxed) is still the operating rule in `AGENTS.md`; Codex verification and the upstream report are not recorded.

## Amendment (2026-11-24, ADR-029): owner data, wrapper guard (#75)

- `check`/`install` read the marker of both APKs (layout, commit, build hash): debuggable ⇔ test,
  non-debuggable ⇔ owner, test marker = app marker; no marker is refused for install and run. `install`
  records hash → marker in `corpus/lab/markers.json`; an installed hash nobody recorded is read from the
  pulled APK (no `run-as`) and then recorded.
- App data is fingerprinted as a whole (names, sizes, digests computed on the phone; one digest and a file
  count leave it): everything except the lab folder (speech model, test output) and the test folders,
  one constant `EXCLUDED` that the host check compares with the test layout. Also left out, as a second
  constant: `cache/`, `code_cache/` and `files/profileInstalled`. Android and androidx write these on
  their own (the update clears `code_cache/`, the first frame refills it, the profile installer notes the
  update time), so with them no fingerprint would survive an install; none is owner data.
- Visit record: `corpus/lab/visits/NAME/visit.json` with `name`, `started`, `ended` (null while open),
  `release` (the pulled release APK, or null), `copy` (archive name and digest), `fingerprint` and `model`
  (inventory) at start, `lab_builds` (app hash, test hash, marker of each lab build installed). Kept
  until `forget`. An unreadable record refuses instead of being skipped.
- `run`, `push` and `stop` refuse outside an open Visit, on a build that is not that Visit's lab build
  with equal app and test markers, and when the fingerprint differs from the Visit's; `run` also while
  our keyboard is in use. `run` fingerprints again afterwards; a difference fails the call and names the
  Visit copy. `run` refuses classes in the Lab run package `ch.og.dictation.labrun`; the wrapper holds
  the package name only, never a class list.
- Every device verb refuses unless called from the main checkout: the shim passes the caller's directory,
  and a worktree, another clone, a folder outside the checkout or no caller directory are refused. `help`
  and the host-only `check` are exempt; `check` takes no lock and writes no state (not `last.json`
  either), so the repository copy runs it from any worktree. State is always the main checkout's
  `corpus/lab`.
- `apply.sh` installs only from the main checkout, on any branch: anchoring to `main` stays dropped
  (revision 2, it forced a merge per wrapper change); instead it refuses to install a wrapper without the
  marker guard, so no stale branch can downgrade it. Laptop work in a Claude worktree needs no gate
  installation; "Copied configs require owner installation in the new checkout before agent work" is
  withdrawn for it. Codex works in the main checkout.
- Asking stays a harness rule: `install`, `restore`, `stop`, `forget` are not in the allow list
  (asserted by `tools/test_gate_installation.py`).
- Supersedes above: "`install restore stop uninstall` refuse while our IME is bound" now includes `run`;
  "`python3 tools/phone` … irrelevant" still holds for device verbs, while `python3 tools/phone check`
  is the supported host-only call.

## Amendment (2026-11-25, ADR-029): Visit lifecycle (#76)

- Visit start is `install APP.apk TEST.apk` over release: the release APK is pulled into the Visit
  record's folder, the lab build installed, owner data copied at once (everything the fingerprint sees,
  History in `no_backup/` included; lab folder and test folders excluded) with the fingerprint and the
  model inventory, and only then the test folders are emptied. The host recomputes the fingerprint from
  the archive; a copy that does not equal it is no Visit copy. Until the copy exists the record holds
  null for copy, fingerprint and model, nothing runs, and only the lab build again or the pulled release
  goes on. An install that replaced nothing leaves no record.
- Lab over lab keeps the Visit: only on a lab build the Visit lists and with an equal fingerprint before
  and after; no pull, no copy; test folders emptied again; the record lists every build. Lab over release
  always opens a new record with its own pull and copy, whatever older records exist; pulled APKs and
  copies stay per record until `forget`.
- Visit end is `install` of a release over the lab build: refused until the fingerprint equals the copy
  and every inventoried speech-model file is as at Visit start. The pulled release needs no flag; any
  other release, and any release when none was pulled, needs `--promote`. The test package stays
  installed beside release (#73: uninstalling needs the owner's token; the wrapper refuses `run` on an
  owner marker). Output: uid, first-install time, microphone grant,
  keyboard enabled and selected, before and after, and which changed. Release over release is a
  promotion unless it is the installed one. Release never goes straight over an unmarked build.
- Speech-model files are the lab-folder paths that exist in the kept model copy `corpus/lab/model`
  (laid out as in `files/lab`); everything else in the lab folder is test output. The wrapper holds no
  model name. No Visit starts and no switch copy is taken without that folder. Reason: the lab build
  appends to its own traces in the lab folder, so "everything there at Visit start" would block every
  Visit end.
- `app`, `install`, `logs`, `crash` and `meta.json` never use `run-as` on a non-debuggable app
  (`lab_sha256` is then null); `pull`, `snapshot`, `restore`, `push`, `run`, `stop` refuse on release.
- `snapshot NAME` (lab build only) and `restore NAME` (a Visit's copy or a snapshot) cover owner data
  only: restore deletes and rewrites what the fingerprint sees and nothing in the lab folder, the test
  folders or the caches; it checks the archive against its fingerprint before deleting and the phone
  against it afterwards. Snapshots hold no APK. Snapshots from before this amendment are refused.
- `push DIR [--model]` writes only the lab folder (no target argument). Without `--model` an inventoried
  speech-model file is kept when DIR lacks it and the push is refused when DIR would change it; test
  output absent from DIR is deleted as before. Recovery from a changed model:
  `push corpus/lab/model --model`.
- `forget NAME` deletes one ended Visit's folder (record, pulled APK, copy); a Visit ends only through
  the after-check of its release install.
- Switch mode: `snapshot --switch NAME` on an unmarked debuggable build keeps all app data except the
  speech-model files and the caches, plus both APKs, and is refused unless the archive holds History,
  Dictionary, History notices and settings. A lab install over an unmarked build needs a switch copy of
  that APK whose fingerprint is the current one, and the fingerprint must equal it again after the
  update; the Visit it starts pulled no release, so its end needs `--promote`. `restore --switch NAME`
  reinstalls the retained APKs when another build is installed (`install -r -d`), then puts the data back
  without touching speech-model files. Nothing deletes a switch copy.
- `pull` reads `files/lab`, `files/benchmark`, `files/timing-receipts` and `files/test`, and only
  members under the paths asked for are accepted.
- Asking stays a harness rule: `install`, `restore`, `stop`, `forget` are not in the allow list. `push`
  is, with or without `--model`.
- Withdrawn: "Test code runs as the app's uid and can copy `files/benchmark` into `files/lab`; accepted
  and stated." The lab build has no path to owner recordings (ADR-029). Superseded above: `push … NAME`,
  "`pull` is limited to `files/lab` and `files/benchmark`", and Recovery's "`snapshot`/`restore` cover the
  retained APK pair plus `files/`, `shared_prefs/`, `databases/`".
- Not built: later Visit copies skipping the legacy recordings while their fingerprint is unchanged;
  every Visit copy holds them.

## Amendment (2026-11-25, ADR-029): `measure`, Lab runs (#81)

- `measure CLASS[#method][,...] [inputs=PATH[,PATH...]] [k=v]` runs only classes under
  `ch.og.dictation.labrun`; `run` refuses those. The wrapper holds the package name, never a class list,
  so a new measurement needs no wrapper change.
- Refusals as for `run`: an open Visit on its lab build, owner data equal to the Visit copy, keyboard
  not in use, main checkout.
- Inputs are paths under `files/benchmark` or `files/timing-receipts`. They are copied on the phone,
  inside app data, into `files/lab/labrun-inputs/` (`files/benchmark/a.wav` → `benchmark/a.wav`); nothing
  leaves the phone. A missing path, a symlink inside one, a path named twice or inside another is
  refused. The folder is emptied before the copy and removed after the run, also when the run breaks
  off. Refused when a file of the kept model copy lies in that folder.
- Owner data is fingerprinted before the copy and after the removal; a difference fails the call and
  names the Visit copy.
- Outcome: `completed` when the instrumentation ended in order and every Lab run's harness line says
  completed (at least one); otherwise `incomplete`. Never passed or failed. The result lists the
  lab-folder files the run wrote or changed, speech-model files left out; they are pulled like any test
  output.
- Allow list: `measure` is in it, like `run`. `install`, `restore`, `stop`, `forget` still ask.
- Not built: a way to pass a timeout (two hours fixed); when the call times out the copies are removed
  while the instrumentation may still run on the phone.

## Amendment (2026-11-26, #99): walk-back after the lab app got its own package

The lab app is `ch.og.dictation.lab` beside release (#98); its data is test data, so what guarded
owner data inside one shared package goes from the wrapper.

- Gone: the Visit record and copy (`corpus/lab/visits/`), `forget`, the whole-data fingerprint around
  `run` and `measure`, the refusals that need an open Visit (`run`, `push`, `measure`, `stop`), the
  emptying of test folders at install, plain `snapshot NAME` and `restore NAME`, the copies `measure`
  placed in `files/lab/labrun-inputs` (`inputs=` is refused), the uninstall token, and the
  main-checkout refusal with the shim's `--caller`.
- Kept: the wrapper as the only path to the phone; its lock, taken by callers from any checkout or
  worktree in the `corpus/lab` of the main checkout that the shim names with `--home`, found from the
  shim's own path with GIT_* variables cleared, never from the caller's directory; the build check before install and run; the
  model-safe `push`; Lab runs, on what `push` put in `files/lab` (inputs under `labrun-inputs/`); the
  release package facts around a lab install and `uninstall` (#98's proof); the one-time switch
  verbs (`snapshot --switch`, `pull --switch`, `restore --switch`, `install --promote`) with the
  fingerprint they use, until #83's switch is done.
- Asking stays a harness rule: `install`, `restore`, `stop`, `uninstall` are not in the allow list.
  Without the token, `uninstall` relies on Claude's ask alone; Codex has no per-verb approval. The
  harm is bounded: `uninstall` removes only the lab app and test packages, so at worst lab data and the
  lab app's model copy (pushed again from the kept copy).
- `apply.sh` still installs only from the main checkout.
