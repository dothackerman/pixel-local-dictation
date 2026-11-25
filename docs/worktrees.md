# Worktrees

1. `gh issue develop N --base main` creates a branch linked to issue N and prints its name.
2. `git fetch origin BRANCH`, then `git worktree add ../pixel-local-dictation-worktrees/BRANCH BRANCH`:
   all worktrees share one folder beside the repository, each named after its branch.
   Start the agent session in that folder; the sandbox writes only inside its own folder.
   A Claude subagent with worktree isolation gets its own under `.claude/worktrees/` instead; skip to step 3.
3. In the worktree, `python3 tools/prepare_checkout.py` before building; `python3 tools/quality.py` then passes in the sandbox.
4. After merge: `git worktree remove ../pixel-local-dictation-worktrees/BRANCH`.
