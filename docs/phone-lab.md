# Phone lab operations

For the operator staging an authorized experiment or restoring its baseline. [AGENTS.md](../AGENTS.md) owns the phone boundary; activate [harness safeguards](agent-safeguards.md#activation) before contact. This guide grants no phone authorization.

Use [the wrapper's](../tools/phone) `help` for command grammar. Subcommands below are arguments to its exact absolute path. [ADR-009](decisions.md#adr-009--bounded-recovery-through-the-protected-phone-wrapper) records the design and validation limits; [the experiment protocol](experiments/protocol.md#executable-handoff) owns launch and restoration requirements.

## Stage, measure, recover

One operator owns the run through recovery. Build artifacts on the host first.

1. `inspect`: identify the installed APK and private lab. Inaccessible storage is unknown, not empty; stop if the required baseline cannot be established.
2. Prepare a candidate directory and retain the exact APK of the already-installed instrumentation targeting `ch.og.dictation`. The wrapper neither builds nor provisions instrumentation. The historical `tools/lab.py phone-prepare` helper prints obsolete phone commands; use only its staged directory, then follow this workflow.
3. Ensure the app process is stopped. Closing the keyboard UI is insufficient; see [explicit stopping](#explicit-stopping). Use one fresh run ID throughout: 1–64 letters/digits/underscores/hyphens, starting with a letter or digit.
4. `push-variant DIR RUN`: stage a candidate while retaining the first lab baseline. Repeated candidates preserve that same baseline.
5. `lab RUN INSTALLED-TEST.apk`: run `LabTest` against the verified installed artifact. `test` additionally runs pipeline/replay tests and writes benchmark diagnostics.
6. `pull-results RUN`: collect the result JSON under ignored `corpus/lab/phone-operations/results/` before recovery.
7. `recover RUN`: restore and verify the original lab and any supported APK replacement. If instrumentation restarted the app process, explicitly stop it first.

After interruption or verification failure, use `inspect` and recover the owning run. Preserve its journal and retained artifacts; do not erase them or blindly restage. External edits, changed device/build identity or a different owner can prevent recovery. Repeating a completed recovery verifies the retained receipt.

## Explicit stopping

`stop-app` requires separate current-task authorization and is never called implicitly. It can interrupt a take, discard in-memory work and leave the package stopped. Use one operator and keep dictation unused during the command; inspection and stopping are not atomic.

Only active primary user 0 is supported. The command refuses selected/bound dictation keyboards, active instrumentation and unrecognized state. It verifies termination and unchanged IME settings; a verification failure does not authorize settings repair. A later app launch or instrumentation can restart the process.

## Compatible APK replacement

`install CANDIDATE.apk ORIGINAL.apk RUN` requires a retained original matching the installed bytes. Package, version code, signer and payload must match between APKs; manifest differences are limited to the debuggable flag and wake-lock permission. Build-mode names alone do not establish compatibility. Initial installation, changed code/models, split APKs and app-data migrations need a separately authorized provisioning decision.

Verified APK copies and receipts stay under ignored `corpus/lab/phone-operations/`. A following lab push can join the installation run. Recovery restores the lab before the original APK. If restoring that APK removes private access, `lab_verification: recorded-before-apk-restore` identifies the earlier verified milestone, rather than claiming a fresh read.

## Preservation limits

Recovery covers the original `files/lab` and supported APK replacements. Preferences, permissions, recordings, caches and benchmark diagnostics are outside that contract. Daily recognition uses bundled assets and `Variant.DEFAULT`; a lab candidate does not select the daily engine.

`uninstall` is destructive and has no recovery promise. `pull-report` exports recordings only with separate explicit consent; exports remain in ignored `corpus/`. If an experiment requires broader restoration than the wrapper provides, it is not ready: resolve provisioning and restoration before launch.
