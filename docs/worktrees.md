# Worktrees

- Claude Code agent worktrees live under `.claude/worktrees/`; the sandbox lets agents write there.
- In a new worktree, run `python3 tools/prepare_checkout.py` before building; `python3 tools/quality.py` then passes in the sandbox.
- The phone is reached only from the main checkout, through `.claude/hooks/phone`. In a worktree the wrapper refuses every verb but `help` and the host-only `check` (`python3 tools/phone check APP.apk TEST.apk`).
- The wrapper is installed only from the main checkout (`bash tools/gate/apply.sh`, any branch) and never without the marker guard.
