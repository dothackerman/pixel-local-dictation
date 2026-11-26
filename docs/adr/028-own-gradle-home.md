# ADR-028 — Each checkout's Gradle has its own home

2026-11-26 · Implementation for #106.

## Context

All checkouts shared one writable `~/.gradle`. Gradle hands a cache lock to a waiter by pinging the holder over local UDP; the Claude Code and Codex sandboxes give each command its own network namespace, so the ping never arrives and the waiter dies after 60 s (`Timeout waiting to lock journal cache`). Seen four times on 2026-11-26, three of them in the unsandboxed pre-commit hook. Gradle documents the limit: <https://docs.gradle.org/current/userguide/dependency_caching.html>.

## Decision

Nothing writable in a Gradle home is shared between processes that cannot reach each other.

- **One place sets it: `gradlew`.** It exports `GRADLE_USER_HOME=<checkout>/.gradle/home` and `GRADLE_RO_DEP_CACHE=~/.gradle/ro-dep-cache` unless already set. The quality gate, the pre-commit hook (through the gate) and agents' direct `./gradlew` calls all start there, for Claude and Codex alike, with no harness change. `gradle.properties` cannot choose the user home, and harness env would miss the owner's own shell. `.gradle/` is already gitignored.
- **Read-only copy.** `python3 tools/prepare_checkout.py` builds it when missing; `--refresh` rebuilds it. It holds `~/.gradle`'s `caches/modules-2` and `wrapper/dists` without `*.lock`, `*.lck`, `gc.properties`: downloads hardlinked (written once), `metadata-*` copied (rewritten in place, 18 MB). Each build is a new directory; the `ro-dep-cache` link is swapped by one rename, the previous copy kept for running builds, older ones deleted.
- **Fresh home.** `prepare_checkout.py` seeds it with the wrapper distribution, hardlinked from the copy without its `.lck`, and finds Robolectric there. The gate refuses a checkout without its home.
- **Filling `~/.gradle`.** New dependencies: in the main checkout, outside the sandbox, `GRADLE_USER_HOME=~/.gradle ./gradlew testBenchmarkUnitTest`, then `--refresh`.

## Consequences

Each home costs about 680 MB and a first build of several minutes; warm builds are as fast as before. `./gradlew wrapper` regenerates `gradlew` and would drop the two lines; `tools/test_prepare_checkout.py` fails if it does. Android Studio keeps using `~/.gradle`, now shared with nothing but deliberate fills.
