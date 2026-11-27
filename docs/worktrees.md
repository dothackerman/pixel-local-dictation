# Worktrees

- Claude Code agent worktrees live under `.claude/worktrees/`; the sandbox lets agents write there.
- An agent worktree starts from the spawning session's current branch (`worktree.baseRef: "head"`), unpushed commits included, uncommitted edits not.
- In a new worktree, run `python3 tools/prepare_checkout.py` before building; `python3 tools/quality.py` then passes in the sandbox.
- Each checkout builds with its own Gradle home, `.gradle/home` (gitignored), set by `gradlew` (ADR-028). `prepare_checkout.py` seeds it from the read-only copy `~/.gradle/ro-dep-cache`, building that copy if missing. The first build in a new home takes minutes and about 680 MB.
- After a dependency change, in the main checkout, outside the sandbox: `python3 tools/prepare_checkout.py --fill` downloads into `~/.gradle` and rebuilds the copy. `--refresh` only rebuilds it: safe while checkouts build, not while a Gradle uses `~/.gradle`.
- The phone is reached through the main checkout's `.claude/hooks/phone`, from any checkout or worktree; relative paths resolve from the caller's directory. One call at a time: the shim names the main checkout from its own path, and that checkout's `corpus/lab` holds the lock, state, `last.json` and the kept model copy.
- The wrapper is installed only from the main checkout (`bash tools/gate/apply.sh`, any branch) and never without the marker guard.
