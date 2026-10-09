#!/usr/bin/env bash
# PreToolUse hook for Bash: the only way to reach the phone is tools/phone.
# Enforced by Claude Code before any command runs. Exit 2 = block.
set -euo pipefail
cmd=$(jq -r '.tool_input.command // empty')
[ -z "$cmd" ] && exit 0

# Allowed: a single invocation of the wrapper, no chaining, no substitution.
if [[ "$cmd" =~ ^(\./)?tools/phone[[:space:]]+[a-z-]+([[:space:]]+[A-Za-z0-9_./-]+)*[[:space:]]*$ ]]; then
  exit 0
fi

# Blocked: any other mention of adb/fastboot, the platform-tools binaries,
# or anything that could rewrite the gate itself.
if grep -Eiq '(^|[^[:alnum:]_./-])(adb|fastboot)([^[:alnum:]_-]|$)|platform-tools|tools/phone|\.claude/(hooks|settings)|core\.hooksPath' <<<"$cmd"; then
  echo "phone-guard: blocked. Phone access is only allowed through 'tools/phone <subcommand>' and the gate files are read-only for the agent. Ask Oriol." >&2
  exit 2
fi
exit 0
