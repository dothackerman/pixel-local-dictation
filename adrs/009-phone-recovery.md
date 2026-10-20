# ADR-009 — Bounded phone recovery

2026-10-19 · Approved/implemented in d86f527; diagnostic support extended October 20. Supersedes ADR-004 staging mechanics.

Keep fixed capabilities in the protected main-checkout wrapper; prepare artifacts on host, run verified installed instrumentation.
One owner/run retains the first lab baseline; inspect unknown storage as unknown, never empty.
Explicit stopping requires separate authorization and IME/instrumentation checks; never implicit.
Ordinary replacement requires matching package/version/signer/payload and narrowly compatible manifests.
Authorized diagnostic transactions retain both original APP/TEST APKs and lab, with bounded profile retrieval.
Recovery covers lab/supported APK replacements, not preferences, permissions, recordings, caches or benchmark diagnostics.
Initial provisioning, broader restoration or destructive uninstall needs its own decision; absent guarantees mean not ready.
October 20 diagnostic compatibility permits four optional driver libraries and instrumentation extractNativeLibs differences; ordinary installation stays strict.
The user applied the reviewed protected-wrapper patch manually; no OS/security changes.
Successful later profile/access recoveries matched both original APKs and all 11 lab file hashes.
Remaining gaps: absent-original recovery, some interruption paths, ordinary compatible rollback and next daily session.
Release-return promise from ADR-004 remains unresolved against the benchmark baseline retained by allocation; establish final build before launch.
Evidence: private `corpus/retained/evidence.json` indexes retained reports and original run identities.
Revisit when required preservation exceeds the wrapper contract; never weaken R6 to fit it.
