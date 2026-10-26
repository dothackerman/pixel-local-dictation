#!/usr/bin/env bash
# Install the ADR-024 phone gate. The user runs this from the checkout root:
#   bash tools/gate/apply.sh
# Run it again whenever tools/phone or tools/gate/* changed on the branch you
# trust. Agents cannot write any of the target paths.
set -euo pipefail
root=$(git rev-parse --show-toplevel)
cd "$root"
gate=tools/gate
store="${XDG_DATA_HOME:-$HOME/.local/share}/pixel-phone"

python3 -I -m py_compile tools/phone
(cd tools && python3 -m unittest -q test_phone test_agent_guard)

mkdir -p "$store" .claude/hooks .codex/hooks
install -m 0755 tools/phone            "$store/phone"
install -m 0755 "$gate/phone_guard.py"  .claude/hooks/phone_guard.py
install -m 0755 "$gate/phone-guard.sh"  .claude/hooks/phone-guard.sh
install -m 0755 "$gate/phone"           .claude/hooks/phone
install -m 0755 "$gate/phone_guard.py"  .codex/hooks/phone_guard.py
install -m 0755 "$gate/phone-guard.sh"  .codex/hooks/phone-guard.sh
install -m 0644 "$gate/hooks.json"      .codex/hooks.json
install -m 0644 "$gate/settings.json"   .claude/settings.json
install -m 0644 "$gate/config.toml"     .codex/config.toml
for instructions in AGENTS.md CLAUDE.md; do
  # Protected symlinks prevent Codex's Linux sandbox from starting.
  rm -f "$instructions"
  install -m 0644 "$gate/AGENTS.md" "$instructions"
done
rm -f .codex/rules/phone.rules
rmdir .codex/rules 2>/dev/null || true
chmod 0755 tools/phone
python3 "$gate/check_installation.py"
git add -A .claude/hooks .claude/settings.json .codex AGENTS.md CLAUDE.md tools/phone
echo "installed $(sha256sum "$store/phone" | cut -c1-12)… to $store/phone; harness files staged. Commit when ready."
echo "Next: in a fresh agent session, run python3 tools/gate/check_installation.py and python3 tools/quality.py without escalation."
