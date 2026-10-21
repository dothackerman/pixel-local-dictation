# ADR-024 — Rewrite phone access as one deep, app-scoped module

2026-10-21 · **Accepted by the user; implementation on `codex/issue-1-fp32-encoder`.** Supersedes ADR-009 and both guard hooks.
Evidence: Codex transcripts 16–21 Oct, Opus 5.5 session 21 Oct, this session.
Revision 2 after an isolated adversarial review (Fable); its findings are folded in below.

## Implementation state (2026-10-21)

Done on the branch: `tools/phone.new` (new wrapper, 13 fake-device tests in `tools/test_phone.py`),
`tools/gate/` (shim, shared guard, Claude/Codex settings, AGENTS.md, `apply.sh`), guard tests,
`encoder_probe.py` no longer pins a wrapper file, proposals and their tests deleted, docs updated.
Agents cannot write the harness paths, so the user runs `bash tools/gate/apply.sh`
(installs the wrapper copy, copies the gate into place, symlinks CLAUDE.md) and commits.
Not yet done: the first live round-trip (migration step 5).

## What the transcripts show

| Signal | Codex 16–19 Oct | Codex 20–21 Oct | Opus 5.5 21 Oct |
|---|---:|---:|---:|
| Guard denials | 208 | 79 | 8 |
| …of which read-only (`cat`, `grep`, `git log`, docs heredocs) | 208 | ~77 | 8 |
| …of which caught a real adb/fastboot/edit violation | 0 | 0 | 0 |
| Wrapper calls | 161 | 29 | 0 |
| Wrapper refusals | ~31 | 7 | 1 |
| Auto-reviewer verdicts on phone commands | 1,281 allow / 0 deny | 2,094 allow / 3 deny (none phone) | n/a |
| Manual wrapper applies by the user | 7 (+1 hash paste) | 2 | 0 (session ended in the apply loop) |
| Longest idle wait for an apply | ~7 h | ~9 h | — |
| Assistant turns on wrapper plumbing | 37 % | ~64 % in the one phone session | 24 % of wall clock, 0 phone commands |

Denial counts are what the guard saw; deterred attempts are unobservable.

User, verbatim: "do NOT ask me again for another window"; "the most recent chat was a nuisance with all the ceremony"; "i want an extreme reduction in complexity"; "give me the copy pasteable command and remove the additional doc"; "i do not get. what is the blocker and how can i unblock"; "how do i do that?"; "i only ran the latest command"; "give me the commands to update the wrapper so you are unblocked".

Phone outcomes still happened (allocation, profiling, GPU/TPU proof, CPU probe) but each campaign degenerated into copy → patch → hash → user applies → new refusal.

## Semantic flaws, by severity

1. **The guard matches words, not effects.** It denies any command whose text contains `AGENTS.md`, `tools/phone`, `.claude/hooks`, a heredoc `python3 -`, or `adb` inside a grep pattern. 295 denials, zero real violations. It blocks reading the rules it enforces and the wrapper it protects, so agents spawn subagents or copy the wrapper to `/tmp` to read it.
2. **Experiment semantics live inside the security boundary.** `tools/phone` (1,560 lines, 67 functions, 173 refusal sites, 17 pinned SHA-256 constants) validates `short-encoder-v1` receipts, a one-off `ENCODER_PROBE_RECOVERY` incident, a hard-coded ORT profile filename, and a digest of its own bytes. Every scientific pivot (strict fallback off, a new output file, a driver manifest line) is therefore a security change: 90 KB proposal copy, "independent review", human apply. The auto-mode classifier then flags the test edits as "Security Weaken".
3. **The human is the deployment pipeline, not the reviewer.** Nine manual applies in six days, pasted hashes, a failed `git apply`, five stray wrapper copies ("which one did you use?"). Review of a whole-file copy by a subagent that cannot read the real wrapper is not review.
4. **Integrity means "the lab looks exactly as I predicted", not "only our app was touched".** Closed inventories, run identities, owner symlinks, phases. Any extra file is "drift"; recovery is refused by the invariant that failed, leaving diagnostic builds installed for 43 min with no restore path except editing the guarded file.
5. **Approval layers that never discriminate on phone risk.** 3,375 auto-reviews, 100 % allow on phone commands. Most escalations came from broken sandboxes (see below), not from the phone.
6. **Refusal and crash share one channel.** `phone: Expecting value: line 1 column 1` and raw `CalledProcessError` reprs (leaking the device serial and adb path) look like policy refusals, so agents stop and escalate instead of retrying.
7. **Policy is duplicated five times** (Claude deny list, Claude sandbox denyWrite, hook regex, Codex `config.toml`, Codex `phone.rules`) plus `AGENTS.md` = `CLAUDE.md` text. "One subcommand per shell call" blocks `inspect > file`.
8. **Observability is starved by the gate that demands evidence.** `logs` whitelists five tags with `*:S` and a 30 s budget, so ORT output is silenced; `pull-profile` refused for short runs; `versions`/`subtypes` dump every IME on the phone (6.7 k tokens, other apps' metadata).
9. **Network check is one-sided.** `apk_info` rejects extra permissions only for the app package; the test APK may carry INTERNET or `QUERY_ALL_PACKAGES`.

## Design

**Seam**: app-scoped phone access. One module, `tools/phone`, deep: small interface, scope enforced by construction, no experiment knowledge. Hooks are user-experience fast-fails; the **sandbox is the boundary** (so its repair is migration step 0).

### Interface (everything a caller must know)

```
phone status                      device present, model, authorized; serial redacted
phone device                      allowlisted ro.* props, thermal status, GPU/TPU driver lib names
phone app                         our app + test package: installed, version, sha256, pid/uid,
                                  IME enabled/selected/bound, FUTO keyboard + voice-input versions
phone install APP.apk [TEST.apk]  package ∈ {ch.og.dictation, .test}; same signer; permission allowlist
phone push DIR [NAME]             → files/lab/NAME (default lab); regular files only, replaces
phone pull PATH... [--to DIR]     from files/lab or files/benchmark → corpus/lab/pull/<ts>/
phone run CLASS[#method] [-e k=v] am instrument on the test package; app logcat saved beside results
phone logs [--since] | crash      logcat by our app's uid (survives crashes) | crash buffer, our package
phone stop                        force-stop our app only
phone snapshot NAME               retained APK pair + files/ + shared_prefs/ + databases/ + grants + IME state
phone restore NAME                reinstall retained APKs if different, replace app data, report what it could not restore
phone uninstall                   destructive; only with a user-created token (see gating)
```

Output: one JSON object on stdout. Exit 0 success; `refused: <reason>` exit 3 (policy); `failed: <reason>` exit 1 (device/transport, retryable). `phone help` lists the exact strings. Size target ≈ 500–600 lines (APK inspection via aapt2/apksigner, tar handling, logcat and instrument are irreducible) plus a fake-device test file.

### Invariants (inside the implementation, not the caller's problem)

- Every adb argv is assembled from fixed templates with the package constant. Caller strings reach the device only as quoted names matching `[A-Za-z0-9_.-]+(/[A-Za-z0-9_.-]+)*`, no `..` component, under `files/`.
- `push DIR` refuses symlinks and non-regular files (today's `inventory` check stays). `pull` checks `test ! -L` on the device. `snapshot NAME` refuses an existing name.
- `restore` extracts the tar on the host, validates every member (regular file, no `..`, under the app data dir), and re-pushes; it never runs `tar x` on the device from an agent-writable archive.
- Reads outside the app: allowlisted `getprop` keys (model, OS/SDK/build id, SoC, GPU/Vulkan/EGL), `dumpsys thermalservice`, `dumpsys package|meminfo|gfxinfo` for our two packages, `dumpsys package` versionName for the FUTO keyboard and voice-input packages (user decision 2026-10-21), `dumpsys input_method` reduced to booleans about **our** IME. No `settings`, `ime list -a`, `pm list`, other packages, `/sdcard`, screenshots, backups, reboot, root.
- `install`: per-package permission allowlist. App: `RECORD_AUDIO`, `WAKE_LOCK`. Test: nothing beyond the app's. Refuse `INTERNET`, `ACCESS_NETWORK_STATE`, `QUERY_ALL_PACKAGES`, any `<queries>` block, on **both** APKs (R3 and "other information off limits"). Keep today's manifest-hash check for ordinary same-version installs.
- `logs` uses `--uid <app uid>`, not `--pid`; `crash` filters the crash buffer by package.
- Live dictation is protected: `install`, `restore`, `stop`, `uninstall` refuse while `dumpsys input_method` shows our IME bound or input shown. Nothing else stops the app.
- Serial, user ids and absolute SDK paths never appear in output.
- One `flock` per invocation. Every mutating verb and `run` write `{verb, ts, app_sha256, test_sha256, lab_inventory_sha256}` to `corpus/lab/runs/<ts>/meta.json`: this is where evidence binding lives now, as data beside results, not as refusals.

### Gating (what still asks)

| Verb | Approval |
|---|---|
| `status device app logs crash pull push run snapshot` | none (`allow` rule) |
| `install restore stop` | one permission prompt per call (not in `allow`) |
| `uninstall` | refused unless `corpus/lab/.allow-uninstall` exists, created by the user; wrapper deletes it after use |

`pull files/benchmark` needs no approval (user decision 2026-10-21). Pulled recordings land in gitignored `corpus/`; the pre-commit hook keeps them out of Git; AGENTS.md keeps "copy nothing out of corpus/ without agreement". Test code runs as the app's uid and can copy `files/benchmark` into `files/lab`; this is accepted and stated.

### What leaves the wrapper

- Receipt schemas, frozen manifests, hash freezes, protocol names, tolerance checks → host tools (`tools/encoder_probe.py` etc.), editable and tested without approval. The wrapper returns bytes plus `meta.json`; experiments judge them.
- Run identities, owner symlinks, phases, closed inventories, per-incident policies → gone. `snapshot`/`restore` is the recovery story for app data and APKs. **Not** restorable from a snapshot and said so in `restore` output: microphone grant, IME enablement/selection, FUTO's "disable built-in voice input" toggle. After `uninstall` the user re-enables the keyboard by hand; `uninstall` refuses while the IME is enabled.

### Trust boundary: execute the installed copy, never the worktree file

Agents edit `tools/phone` and its tests freely on the branch. What runs against the phone is a copy at `~/.local/share/pixel-phone/phone`, a path agents cannot write (outside the sandbox write allowlist). The shim `.claude/hooks/phone` (hook-protected directory) runs that copy with `python3 -I`. The user installs a new copy by running `bash tools/gate/apply.sh`, which first compiles the wrapper and runs its tests. Only the shim is in the `allow` rules and the sandbox `excludedCommands`. Local branches, worktrees, `python3 tools/phone` and stray copies are irrelevant because the shim never reads the checkout. No GitHub step, no merge, no network for the phone path.

Revision 2 had anchored on `main:tools/phone` fetched from GitHub. Dropped (revision 3, user decision 2026-10-21): it forced a merge for every wrapper change while the spec branch is long-lived, and in a solo repository branch protection adds a GitHub detour without a second reviewer.

### Harness (Claude and Codex, one shared script)

Hook `phone-guard.py`, ~60 lines, fast-fail only:
1. Bash: split into simple commands (shlex on `;`, `&&`, `||`, `|`); deny if any argv element's basename is `adb`/`fastboot`, or a `gradlew` task matches `install|connected|uninstall|device`. Mentions in strings, greps, heredocs and docs pass.
2. Edit/Write/apply_patch: deny paths under `.claude/hooks`, `.codex`, `tools/git-hooks`, `.git`, and `AGENTS.md`. The wrapper source is **not** protected.

The real boundary is the sandbox: `allowUnsandboxedCommands: false`; `denyRead` on `~/.android` and `~/Android/Sdk/platform-tools`; `excludedCommands` = the shim only, which matches the exact command line, so the shim must be called alone (no pipe, no redirect; the result is also written to `corpus/lab/last.json`). Gradle's `connected*`/`install*` tasks then fail inside the sandbox even if a hook misses them. `denyWrite` covers `.git/config` and `.git/hooks`, not `.git` itself, so agents can still commit.

Claude `settings.json`: keep `deny` for adb/fastboot/sudo; `allow` the shim for the no-prompt verbs; drop the Edit-deny on `tools/phone`. Codex `config.toml`: same read-only set; delete `phone.rules` (duplicate of the hook); verify once that Codex runs the shared script. `CLAUDE.md` becomes a symlink to `AGENTS.md` and both are listed in `denyWrite`. `AGENTS.md` shrinks to: shim path, "app-scoped only", "recordings stay in corpus/", "the user installs wrapper changes with apply.sh".

### Host sandbox root causes (verified 2026-10-21)

**Claude Code.** TUXEDO OS 24.04 ships Ubuntu's `kernel.apparmor_restrict_unprivileged_userns = 1`. Bubblewrap creates the outer user namespace under profile `bwrap`, but its children transition to `unpriv_bwrap` (`allow px /** -> bwrap//&unpriv_bwrap`), which has `audit deny capability`. Claude's seccomp step creates a nested namespace, needs `CAP_SYS_ADMIN` inside it, and fails: `apply-seccomp: write /proc/self/setgroups … nested userns is capability-restricted`. Kernel audit at this session's timestamps: `apparmor="DENIED" operation="capable" profile="unpriv_bwrap" capname="sys_admin"`. `bwrap … -- unshare -U` reproduces it. Consequence: every sandboxed command fails, every command then needs `dangerouslyDisableSandbox`, and in auto mode that is a classifier round per command.

The blocking rule is `audit deny capability,` inside `unpriv_bwrap`. An AppArmor `deny` beats any `allow`, so a `local/` include cannot lift it, and a profile on the Claude binary does not help because bwrap still attaches by path and its children stack into `unpriv_bwrap`. Stock Ubuntu 24.04 ships this profile disabled; this host's copy is enabled and marked "Local experiment". Sources and vendor positions: [research/linux-sandbox-userns.md](../research/linux-sandbox-userns.md). Fix options, user-run, in order:
1. User-level Claude setting `sandbox.network.allowAllUnixSockets: true` in `~/.claude/settings.json`: skips the seccomp step that needs the nested namespace (inferred from sandbox-runtime source; loses Unix-socket blocking). No sudo.
2. Ubuntu's documented path: `kernel.apparmor_restrict_unprivileged_userns=0` in `/etc/sysctl.d/` and disable the `bwrap-userns-restrict` profile. Do not edit `unpriv_bwrap` (conffile drift, same security result as the sysctl) and do not install Anthropic's unconfined bwrap profile beside Ubuntu's (attachment conflict).
Codex needs no nested namespace and keeps working either way.

Verify with a sandboxed `true` from Claude Code and `journalctl -k | grep unpriv_bwrap` staying quiet.

**Codex.** 376 bubblewrap failures on 19–20 Oct, all `Can't bind mount … ~/.codex/visualizations/<session>/tools: No such file or directory`: the bundled `visualize` plugin asked the sandbox to bind a directory that did not exist yet. Each failure became `require_escalated` (7,694 on the 19th, 11,838 on the 20th), auto-approved. The plugin is now `enabled = false` in the user's Codex config; 21 Oct shows 0 bwrap errors and 136 escalations. Already fixed; keep it disabled.

### Testing

The adapter seam already exists (`test_phone.py` fakes the device). Keep it; the fake shrinks to: install, push, pull, instrument, props, logcat, dumpsys. Tests assert the invariants (no argv outside templates, serial redacted, permission allowlist on both APKs, `..` refused, symlink refused, restore idempotent and host-validated, IME-bound refusals), not lab phase choreography. `test_agent_guard.py` gains: `cat AGENTS.md` passes, `grep adb tools/x` passes, `adb devices` denied, `foo && adb shell` denied, `./gradlew installDebug` denied, shim allowed.

## Rejected

- Keep the wrapper read-only and formalize the proposal flow: the flow is the problem; nine applies, 7–9 h idle waits, review without the real file.
- Compare the worktree wrapper's bytes with `main` before running (revision 1): local `main` is agent-writable, `python3 tools/phone` has another argv[0], copies pass the wrapper's own path check. Replaced by executing the remote anchor.
- Hash-pin everything harder: pins protect evidence integrity, which now lives in `meta.json` beside results.
- Approval prompts per phone command: 3,375 verdicts, 100 % allow on phone work, no information.
- Give agents raw adb: the scope guarantee (only `ch.og.dictation`) is exactly what the wrapper is for.

## Migration

0. **Blocking**: user applies the AppArmor fix; a sandboxed `true` succeeds. Without it the treadmill only moves (and with `allowUnsandboxedCommands: false` no shell command runs at all).
1. New `tools/phone` + fake tests on the branch (the old wrapper remains in Git history at `74519c3^`).
2. Shim + shared `phone-guard.py`; update `settings.json`, `config.toml`; delete `phone.rules`, `tools/proposals/phone-*.py`, the `.diff`. User runs `bash tools/gate/apply.sh`.
3. Move receipt validation from the old wrapper into `tools/encoder_probe.py`.
4. Shrink `AGENTS.md`; symlink `CLAUDE.md`; mark ADR-009 superseded.
5. First real use: `snapshot baseline` → `install` diagnostic pair → `push` → `run` → `pull` → `restore baseline` → `app` shows original hashes and IME state. One PR.
