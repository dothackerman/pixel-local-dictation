# Worktrees

- Claude Code agent worktrees live under `.claude/worktrees/`; the sandbox lets agents write there.
- In a new worktree, run `python3 tools/prepare_checkout.py` before building; `python3 tools/quality.py` then passes in the sandbox.
- The phone is reached only from the main checkout, through `.claude/hooks/phone`.
