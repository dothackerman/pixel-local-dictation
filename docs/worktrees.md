# Worktrees

- Claude Code agent worktrees live under `.claude/worktrees/`; the sandbox lets agents write there.
- In a new worktree, run `python3 tools/prepare_checkout.py` before building; `python3 tools/quality.py` then passes in the sandbox.
- The phone is reached through the main checkout's `.claude/hooks/phone`, from any checkout or worktree; relative paths resolve from the caller's directory. One call at a time: the shim names the main checkout from its own path, and that checkout's `corpus/lab` holds the lock, state, `last.json` and the kept model copy.
- The wrapper is installed only from the main checkout (`bash tools/gate/apply.sh`, any branch) and never without the marker guard.
