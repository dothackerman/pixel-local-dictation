# Agent phone safeguards

For activating or troubleshooting Claude/Codex access. [AGENTS.md](../AGENTS.md) owns the phone boundary; [phone lab operations](phone-lab.md) owns usage and preservation limits. Permission rules permit tool execution, not experiments: current-task authorization is still required.

## Activation

Start a fresh session in the repository root. In Codex, review and trust this repository's pre-tool hook through `/hooks` before phone contact; project trust is separate. In Claude, check `/hooks` and `/sandbox` for the project registration and filesystem restrictions.

Both harnesses share [the pre-tool policy](../.claude/hooks/phone_guard.py). Check [Claude settings](../.claude/settings.json), [the Codex profile](../.codex/config.toml) and [Codex rules](../.codex/rules/phone.rules) for protected paths, sandbox exceptions and automatic approvals. Review those exact-checkout paths again for a worktree. `stop-app` remains separately approved.

The guard accepts one exact absolute wrapper invocation, without chaining, redirects, substitution or option-style arguments. Claude's wrapper sandbox exemption and Codex's permitted escalations exist because sandbox-local connections cannot reach the host phone service; they retain the same authorization boundary.

If Codex ignores the project permission profile, check for legacy user-level `sandbox_mode` or `sandbox_workspace_write` selectors overriding it. See [Codex permissions](https://learn.chatgpt.com/docs/permissions), [hooks](https://learn.chatgpt.com/docs/hooks) and [Claude sandboxing](https://code.claude.com/docs/en/sandboxing).

## Verification and limits

Run `python3 tools/test_agent_guard.py` for host-only policy checks; these establish neither hook trust nor live filesystem enforcement. Claude's live filesystem enforcement remains unverified in the recorded checks; verify it in the installed harness before unattended phone work.

Hooks cannot inspect arbitrary subprocess behavior or external tool servers, and some tools skip hooks. Input to an existing terminal session is not rechecked by Codex's hook. Filesystem restrictions and command-prefix rules supplement hooks; none provides device isolation.

The wrapper and harness rules are protected from agent edits. There is no scoped maintenance switch in the current guard. Prepare and review changes outside protected paths, have Oriol apply the patch manually, then verify the applied content; do not bypass a rejection through another tool.
