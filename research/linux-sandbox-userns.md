# Claude Code / Codex Linux sandboxes vs Ubuntu's userns restriction

2026-10-21. Sources: Anthropic docs + `sandbox-runtime` source, OpenAI Codex docs + `codex-rs/linux-sandbox`
source, Ubuntu/Canonical/AppArmor upstream, GitHub issues. Host facts read directly:
`/proc/sys/kernel/apparmor_restrict_unprivileged_userns`=1, `…_unconfined`=0; `/etc/apparmor.d/bwrap-userns-restrict`
present and enforced, header comment "Local experiment" (stock noble ships it disabled, §3). Decision context:
[ADR-024](../adrs/024-phone-access-rewrite.md); this note owns the root-cause and fix material.

## 0. Host verification (2026-10-21)

Claude Code: every sandboxed command failed with `apply-seccomp: write /proc/self/setgroups … nested userns is
capability-restricted`; kernel audit at the same timestamps: `apparmor="DENIED" operation="capable"
profile="unpriv_bwrap" capname="sys_admin"`. `bwrap … -- unshare -U` reproduces it. Bubblewrap's outer namespace runs
under profile `bwrap`; its children transition to `unpriv_bwrap` (`allow px /** -> bwrap//&unpriv_bwrap`), whose
`audit deny capability,` blocks the nested namespace Claude's seccomp step needs (§2–3). Consequence: every command needs
`dangerouslyDisableSandbox`, and in auto mode that is a classifier round per command. Verify a fix with a sandboxed
`true` and a quiet `journalctl -k | grep unpriv_bwrap`.

Codex: 376 bubblewrap failures on 19–20 Oct, all `Can't bind mount … ~/.codex/visualizations/<session>/tools: No such
file or directory`: the bundled `visualize` plugin asked the sandbox to bind a directory that did not exist yet. Each
became `require_escalated` (7,694 on the 19th, 11,838 on the 20th), auto-approved. Fixed by `enabled = false` for that
plugin in the user's Codex config; 21 Oct shows 0 bwrap errors. Keep it disabled.

## 1. Common and documented? Yes, for both vendors

Anthropic's README: Ubuntu 24.04+ sysctl "allows `unshare(CLONE_NEWUSER)` but strips capabilities from the
resulting namespace. Both bubblewrap and the seccomp isolation layer need capability-bearing user namespaces"
([sandbox-runtime README](https://github.com/anthropic-experimental/sandbox-runtime)). Reported strings:

- `apply-seccomp: write /proc/self/setgroups (nested userns is capability-restricted; caller must provide CAP_SYS_ADMIN): Permission denied`
  + audit `apparmor="DENIED" operation="capable" profile="unpriv_bwrap" … capname="sys_admin"` —
  [claude-code#87680](https://github.com/anthropics/claude-code/issues/87680) (Ubuntu 26.04, bwrap 0.11.1, same
  profile as this host; labelled `bug`, no maintainer reply, closed "not planned" by stale bot 2026-09-21, "same issue"
  2026-10-08). With `enableWeakerNestedSandbox`: `apply-seccomp: unshare(CLONE_NEWUSER): Invalid argument`.
- `bwrap: loopback: Failed RTM_NEWADDR: Operation not permitted` (no bwrap profile at all) —
  [sandbox-runtime#74](https://github.com/anthropic-experimental/sandbox-runtime/issues/74) (open, no reply).

Codex: [#15057](https://github.com/openai/codex/issues/15057) (closed 2026-05-05, maintainer → Ubuntu 24.04 prereqs),
[#19285](https://github.com/openai/codex/issues/19285), [#29908](https://github.com/openai/codex/issues/29908) (open,
0.142–0.160), [#38715](https://github.com/openai/codex/issues/38715) (open, `documentation`). Strings:
`bwrap: loopback: Failed RTM_NEWADDR: Operation not permitted`, `bwrap: setting up uid map: Permission denied`,
`bwrap: No permissions to create a new namespace`, audit `operation="userns_create" … target="unprivileged_userns"`.
No Codex report shows the `setgroups`/`unpriv_bwrap` failure: Codex runs fine under Ubuntu's bwrap profile (§2).

## 2. Does each sandbox need a *nested* user namespace?

Claude Code: yes, by default.
[`linux-sandbox-utils.ts`](https://github.com/anthropic-experimental/sandbox-runtime/blob/main/src/sandbox/linux-sandbox-utils.ts)
builds `bwrap --new-session --die-with-parent [--unshare-net --bind <proxy socks>] <mounts> --dev /dev --unshare-pid
--unshare-user --cap-drop ALL --proc /proc … -- <shell> -c <cmd>` and prefixes the command with the `apply-seccomp`
helper (env `SRT_ALLOW_NESTED_USERNS`). Comment: "apply-seccomp obtains CAP_SYS_ADMIN for its nested PID+mount unshare
by creating a nested user namespace"; it "writes /proc/self/setgroups and uid_map".
[`apply-seccomp.c`](https://github.com/anthropic-experimental/sandbox-runtime/blob/main/vendor/seccomp-src/apply-seccomp.c):
path (a) `unshare(CLONE_NEWPID|CLONE_NEWNS)` if caller has CAP_SYS_ADMIN; on EPERM path (b) `unshare(CLONE_NEWUSER)`,
`write_file("/proc/self/setgroups","deny")` → the error. Then a seccomp filter (EPERM on userns/mountns `unshare`/`clone`,
`setns`, `mount`…; `clone3` ENOSYS) and `user.max_user_namespaces=0`, so a command cannot "undo the sandbox's write
denies". `allowNestedUserNamespaces: true` only drops filter+limit; the unshare still runs → no fix. No sysctl
preflight; only comment "See README for the Ubuntu 24.04 sysctl". Helper skipped when `allowAllUnixSockets: true` or
binary absent; bwrap then gets `--disable-userns` (≥0.8, non-setuid) plus a warning. No privileged-helper mode. Claude
Code settings: `sandbox.enableWeakerNestedSandbox` (bind host `/proc`; "considerably weakens security"),
`sandbox.network.allowAllUnixSockets`, `failIfUnavailable`, `allowUnsandboxedCommands`, `excludedCommands`;
`allowNestedUserNamespaces` is not in the index ([settings-reference](https://code.claude.com/docs/en/settings-reference),
[sandboxing](https://code.claude.com/docs/en/sandboxing)).

Codex: no. [`bwrap.rs`](https://github.com/openai/codex/blob/main/codex-rs/linux-sandbox/src/bwrap.rs): `bwrap
--new-session --die-with-parent <ro-bind / | tmpfs /> … --unshare-user --unshare-pid --unshare-ipc [--unshare-net]
--proc /proc --cap-drop ALL -- <cmd>`; helper applies `PR_SET_NO_NEW_PRIVS` + seccomp in-process, no second userns, no
`setgroups` ([linux-sandbox README](https://github.com/openai/codex/blob/main/codex-rs/linux-sandbox/README.md)).
Legacy Landlock via `features.use_legacy_landlock`. Bundled `codex-resources/bwrap` fallback when `bwrap` is off PATH;
Ubuntu's profile matches only `/usr/bin/bwrap`.

## 3. Fixes each party recommends

Anthropic ([docs](https://code.claude.com/docs/en/sandboxing)): if sysctl is 1, install
`profile bwrap /usr/bin/bwrap flags=(unconfined) { userns, include if exists <local/bwrap> }`; "The profile applies
only to `bwrap` itself". README: `sysctl -w kernel.apparmor_restrict_unprivileged_userns=0` "or add an AppArmor
profile that grants `userns` to the relevant binaries". No mention of `bwrap-userns-restrict`/`unpriv_bwrap`.

OpenAI ([docs](https://learn.chatgpt.com/docs/sandboxing)): "prefer loading the `bwrap` AppArmor profile so `bwrap`
can keep working without disabling the restriction globally": `apt install apparmor-profiles apparmor-utils; install
-m 0644 /usr/share/apparmor/extra-profiles/bwrap-userns-restrict /etc/apparmor.d/; apparmor_parser -r …`. Sysctl 0
only as fallback. 25.04+ ships it enabled.

Ubuntu ([24.04 notes](https://documentation.ubuntu.com/release-notes/24.04/),
[Canonical blog](https://canonical.com/blog/ubuntu-23-10-restricted-unprivileged-user-namespaces),
[discourse](https://discourse.ubuntu.com/t/understanding-apparmor-user-namespace-restriction/58007)): option 1 is a
per-binary `flags=(unconfined) { userns, }` profile (Chrome example); sysctl 0 (one boot, or
`/etc/sysctl.d/60-apparmor-namespace.conf`) "do[es] not mitigate against kernel exploits that abuse the unprivileged
user namespaces feature". `bwrap-userns-restrict` was enabled in noble 4.0.1-0ubuntu0.24.04.2, broke Flatpak saves
([LP#2072811](https://bugs.launchpad.net/ubuntu/+source/apparmor/+bug/2072811)), dropped, re-added disabled in
4.0.1really4.0.1-0ubuntu0.24.04.3 ([changelog](https://changelogs.ubuntu.com/changelogs/pool/main/a/apparmor/apparmor_4.0.1really4.0.1-0ubuntu0.24.04.4/changelog)).
Stock 24.04 therefore accepts Anthropic's profile; this host cannot load it beside the enabled Ubuntu one: two profiles
attaching `/usr/bin/bwrap` → "neither profile attaches" ([codex#38715](https://github.com/openai/codex/issues/38715), #87680).

Why Ubuntu's profile kills Claude but not Codex
([upstream profile](https://gitlab.com/apparmor/apparmor/-/blob/master/profiles/apparmor/profiles/extras/bwrap-userns-restrict),
same rules on this host): `profile bwrap` = `allow capability, allow userns, allow px /** -> bwrap//&unpriv_bwrap`;
`profile unpriv_bwrap` = `allow userns, allow pix /** -> &unpriv_bwrap, audit deny capability,`. Comment: "its children
do not have capabilities, blocking bwrap from being able to be used to arbitrarily by-pass the user namespace
restrictions". Stacking ([AppArmorStacking](https://gitlab.com/apparmor/apparmor/-/wikis/AppArmorStacking)): "a given
rule must exist in each profile in the stack to be available"; stacking with unconfined "results in the stack
unconfined//&A", not A. So bwrap's children may create a userns but never hold CAP_SYS_ADMIN: `unshare -U` works, the
`setgroups`/`uid_map` write does not.

A profile on the *claude* binary does not help. `unconfined` mode "allows a task confined by the profile to behave as
though it is unconfined" ([apparmor.d(5) 4.0](https://gitlab.com/apparmor/apparmor/-/raw/apparmor-4.0/parser/apparmor.d.pod));
unconfined tasks exec with implicit attachment ("The exec attachment conditional will also be used by tasks that are
unconfined as they use a pix transition rule"), so exec of `/usr/bin/bwrap` still attaches `bwrap`, children still land
in `bwrap//&unpriv_bwrap`. Anthropic's unconfined bwrap profile works only because
bwrap's children have no matching attachment and inherit the unconfined-with-userns label.

Editing `unpriv_bwrap`: "Within a given priority level deny rules have priority over all other rule modes"
([rule-prefixes wiki](https://gitlab.com/apparmor/apparmor/-/wikis/rule-prefixes-and-modes)); a `local/unpriv_bwrap`
allow cannot beat `audit deny capability,`. The `priority=1 capability sys_admin,` override from #87680 needs the
`priority=` qualifier, present in the master man page but absent from the apparmor-4.0 branch man page → not usable on
AppArmor 4.0. Only deleting/replacing the deny line in the conffile works here.

Setuid bwrap: Debian/Ubuntu "no longer installs a setuid root /usr/bin/bwrap"
([README.Debian](https://metadata.security.debian.org/changelogs/main/b/bubblewrap/stable-security_README.Debian)); upstream
removed setuid mode ([bubblewrap README](https://github.com/containers/bubblewrap)); attachment is by path anyway. Not an option.

Claude-only workaround (inferred from source, not vendor-documented): `sandbox.network.allowAllUnixSockets: true` skips
`apply-seccomp` → no nested userns; bwrap 0.11.1 gets `--disable-userns`. Costs Unix-socket blocking (ssh-agent, D-Bus,
docker.sock reachable) and the seccomp namespace filter; Anthropic lists it among settings that "weaken the sandbox".

## 4. Security trade-offs stated by sources

- Ubuntu: restriction "does not stop kernel exploits directly" but shrinks attack surface; sysctl 0 "do[es] not
  mitigate against kernel exploits"; "unconfined profiles that allow the use of user namespaces provide a trivial
  bypass" (discourse). Qualys: three bypasses via such profiles (`aa-exec`, busybox, LD_PRELOAD into nautilus); Ubuntu:
  not vulnerabilities, "an imperfect protection remains better than no protection at all"
  ([oss-sec](https://seclists.org/oss-sec/2025/q1/253)). `bwrap` is on default Desktop, so an unconfined bwrap profile is
  the same bypass class.
- Canonical: sysctl is "all or nothing"; exploits target specific apps → restrict per app; vendors should ship profiles.
- Anthropic: nested userns exists so a command cannot "undo the sandbox's write denies"; `enableWeakerNestedSandbox`
  "considerably weakens security".
- OpenAI: load the profile "without disabling the restriction globally"; #15057 debate: targeted profile > sysctl.
- #87680 reporter: lifting the deny "re-opens the nested-userns escalation path for all bwrap consumers on the machine".

## 5. Best practice per source

Ubuntu/AppArmor: keep sysctl 1; per-app `userns` profiles; for bwrap, `bwrap-userns-restrict` with capability-less
children. OpenAI follows that exactly and is compatible with it. Anthropic's nested-userns design is incompatible with
Ubuntu's bwrap profile by construction; its documented remedies (sysctl 0, or unconfined bwrap profile) both hand
full-capability user namespaces to every bwrap child on the machine. No Anthropic response on #87680/#74; the ask
"Avoid the nested user namespace entirely" is unanswered.

## Recommendation for this host (TUXEDO OS 24.04.5, kernel 7.0, AppArmor 4.0, bwrap 0.11.1, Claude Code 2.1.304, Codex 0.159.3)

1. Keep sysctl=1 and the enabled `bwrap-userns-restrict`: OpenAI's recommended setup, Codex 0.159.3 needs no nested userns, and it is the only variant Ubuntu calls non-bypassable.
2. For Claude Code first try `sandbox.network.allowAllUnixSockets: true` in `~/.claude/settings.json` (skips `apply-seccomp`; bwrap gets `--disable-userns`); verify with a sandboxed `true` and a quiet `journalctl -k | grep unpriv_bwrap`. Accept losing Unix-socket blocking, or stop here.
3. If that fails or the socket loss is unacceptable, use Ubuntu's documented sysctl (`/etc/sysctl.d/60-apparmor-namespace.conf`, `=0`) and disable the bwrap profile; do not install Anthropic's unconfined bwrap profile beside it (attachment conflict) and do not edit `unpriv_bwrap` (same security result as sysctl 0, conffile drift, no `priority=` on 4.0).
4. Do not set `enableWeakerNestedSandbox`, wildcard `excludedCommands` or routine `dangerouslyDisableSandbox`; keep `allowUnsandboxedCommands: false` per ADR-024.
5. Upstream: comment on claude-code#87680 with this host's audit line and the §3 stacking analysis, asking for a non-nested-userns mode or an `unpriv_bwrap` preflight; recheck after each sandbox-runtime update.
