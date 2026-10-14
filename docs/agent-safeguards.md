# Claude and Codex safeguard setup

Both harnesses use the same pre-tool policy in
[phone_guard.py](../.claude/hooks/phone_guard.py). Codex's hook directory links to
Claude's; registration and permission configuration remain harness-specific.
The wrapper and existing Git pre-commit check are unchanged. [AGENTS.md](../AGENTS.md)
owns the shared phone boundary; this document covers setup and enforcement limits.

## Activation

Start a fresh session in the repository root. In Codex, use `/hooks` to review
and trust the new pre-tool hook before any phone work. Project trust does not
replace hook trust; untrusted hooks are skipped. In Claude, inspect `/hooks` and
`/sandbox` to confirm the project registration and write restrictions loaded.
See the official [Codex hooks](https://learn.chatgpt.com/docs/hooks) and
[Claude sandbox](https://code.claude.com/docs/en/sandboxing) documentation.

Invoke the wrapper with its **exact absolute checkout path**, followed by one
subcommand. Relative calls are rejected because Codex's execution directory can
be overridden independently of its session directory. No chaining, redirects,
substitution, or option-style arguments are accepted. Returning success from
the guard does not itself grant permission to run the command.

The existing automatic lab approvals cover `push-variant`, `lab`, and
`pull-results` in both harnesses. They do not authorize other phone activity or
override the experiment brief. Claude exempts only this checkout's absolute
wrapper command from its sandbox: Linux sandbox localhost cannot reach the host
service. Codex's corresponding rules permit those three wrapper operations
outside the sandbox. Other approvals remain subject to each harness's normal
permission process.

The local Codex user configuration was migrated from `sandbox_mode` and
`sandbox_workspace_write` to an equivalent workspace permission profile with
network access retained. This lets the project's `dictation` profile take
effect. On another installation, remove any loaded legacy sandbox selector
before using this profile; those selectors override `default_permissions`.
See [Codex permissions](https://learn.chatgpt.com/docs/permissions).

For another checkout or worktree, review its absolute-path lab approvals and
Claude wrapper exemption separately. The guard derives its allowed wrapper
from its own checkout; it will not accept another checkout's wrapper.

## Verification and limits

Run `python3 tools/test_agent_guard.py` for synthetic shell, native edit, patch,
symlink, malformed-input, and launcher-failure checks. These checks never
execute the wrapper. Filesystem profiles also protect the gate files against
subprocess writes. The existing instruction symlink is protected by edit rules
and the shared guard; its target is read-only in both filesystem configurations.

On 2026-10-14, all tools unit tests passed and the installed Codex sandbox denied
non-mutating write-open probes on the gate files while allowing a temporary
ordinary project file. Claude's configuration loaded, but its live filesystem
probe did not execute under the narrow tool allowlist. The standalone Anthropic
sandbox runtime 0.0.79 probe failed on this environment's nested-user-namespace
restriction. Claude's live filesystem enforcement therefore remains unverified;
confirm it through the installed harness before unattended phone work.

These are matching project guardrails, not device isolation. Codex does not run
the pre-tool hook again for input sent to an existing terminal session, and
some specialized tools skip hooks. The shared guard blocks bare interactive
interpreters, but cannot inspect arbitrary program behavior or external tool
servers. Hooks can also be disabled or left untrusted. Escalated commands and
Claude's wrapper exemption run outside their filesystem sandbox; approvals must
preserve the same phone boundary. Command-prefix rules supplement the shared
guard and only govern execution outside Codex's sandbox.
