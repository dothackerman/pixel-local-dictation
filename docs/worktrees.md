# Worktrees

- Claude Code agent worktrees live under `.claude/worktrees/`; the sandbox lets agents write there.
- In a new worktree, run `python3 tools/prepare_checkout.py` before building; `python3 tools/quality.py` then passes in the sandbox.
- Each checkout builds with its own Gradle home, `.gradle/home` (gitignored), set by `gradlew` (ADR-028). `prepare_checkout.py` seeds it from the read-only copy `~/.gradle/ro-dep-cache`, building that copy if missing. The first build in a new home takes minutes and about 680 MB.
- After a dependency change, in the main checkout, outside the sandbox: `python3 tools/prepare_checkout.py --fill` downloads into `~/.gradle` and rebuilds the copy. `--refresh` only rebuilds it: safe while checkouts build, not while a Gradle uses `~/.gradle`.
- The phone is reached only from the main checkout, through `.claude/hooks/phone`.
