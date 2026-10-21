#!/usr/bin/env bash
# One-time migration to the ADR-024 phone gate. Run by the user from the checkout root:
#   bash tools/gate/apply.sh
# Agents cannot write these paths; everything else on the branch is already in place.
set -euo pipefail
root=$(git rev-parse --show-toplevel)
cd "$root"
gate=tools/gate

install -m 0755 "$gate/phone_guard.py"  .claude/hooks/phone_guard.py
install -m 0755 "$gate/phone-guard.sh"  .claude/hooks/phone-guard.sh
install -m 0755 "$gate/phone"           .claude/hooks/phone
install -m 0755 "$gate/phone_guard.py"  .codex/hooks/phone_guard.py
install -m 0755 "$gate/phone-guard.sh"  .codex/hooks/phone-guard.sh
install -m 0644 "$gate/settings.json"   .claude/settings.json
install -m 0644 "$gate/config.toml"     .codex/config.toml
install -m 0644 "$gate/AGENTS.md"       AGENTS.md
git rm -q --cached .codex/rules/phone.rules 2>/dev/null || true
rm -f .codex/rules/phone.rules
rmdir .codex/rules 2>/dev/null || true
rm -f CLAUDE.md && ln -s AGENTS.md CLAUDE.md
git mv -f tools/phone.new tools/phone
chmod 0755 tools/phone
git add .claude/hooks .claude/settings.json .codex AGENTS.md CLAUDE.md tools/phone
echo "gate applied. Remaining user steps:"
echo "  1. protect main on GitHub (PR required):"
echo "     gh api -X PUT repos/dothackerman/pixel-local-dictation/branches/main/protection \\"
echo "       --input - <<'EOF'"
echo '       {"required_pull_request_reviews":{"required_approving_review_count":0},"enforce_admins":true,"required_status_checks":null,"restrictions":null}'
echo "     EOF"
echo "  2. commit, open the PR, merge; then .claude/hooks/phone status"
