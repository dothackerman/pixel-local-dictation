# Worktrees

1. `gh issue develop N --base main` creates a branch linked to issue N and prints its name.
2. `git fetch origin BRANCH`, then `git worktree add ../pixel-local-dictation-worktrees/BRANCH BRANCH`:
   all worktrees share one folder beside the repository, each named after its branch.
   Start the agent session in that folder; the sandbox writes only inside its own folder.
3. In the worktree, `python3 tools/prepare_checkout.py` before building; `python3 tools/quality.py` then passes in the sandbox.
4. After merge: `git worktree remove ../pixel-local-dictation-worktrees/BRANCH`.

Laptop work in a Claude worktree needs no gate installation. The phone is reached only from the main
checkout: in a worktree the wrapper refuses every verb but `help` and the host-only `check`
(`python3 tools/phone check APP.apk TEST.apk`). The wrapper is installed only from the main checkout
(`bash tools/gate/apply.sh`, any branch) and never without the marker guard. Codex sessions stay in the
main checkout: in a Codex worktree the harness files are writable (ADR-024 amendment).
