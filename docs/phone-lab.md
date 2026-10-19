# App-scoped phone operations

Use only this checkout's exact absolute `tools/phone` path, one subcommand per shell call. Current-task authorization and safeguard activation still apply. One operator owns the complete run. No command changes phone settings, grants permissions, writes to FUTO, installs another package, or exports recordings implicitly.

The protected executable contains its implementation and uses isolated Python with standard-library imports only. Neither harness receives a general phone shell. `inspect`, `recover`, and the separately approved `stop-app` are the new phone capabilities; existing mutation commands now require explicit run identities and verification.

## Run a candidate

1. Build/prepare artifacts on the host. No phone command invokes connected Gradle tasks. Existing instrumentation must already be installed and target `ch.og.dictation`; retain its exact test APK locally. Installing the instrumentation package is outside this wrapper's install capability.
2. Use `inspect` to observe the installed APK hash/version and app-private lab inventories. Inaccessible release-private storage is reported, never bypassed by installing a debug build.
3. Choose a fresh run identity, 1–64 letters/digits/underscores/hyphens starting with a letter or digit. Never reuse a completed identity or share it with a different operator.
4. `push-variant DIR RUN` retains the initial `files/lab` on-device, stages beside it, compares content hashes, then promotes the candidate. Repeated candidates in the same run retain the first baseline. A disconnect can leave intermediate directories; use `recover RUN`, not a blind retry.
5. `lab RUN INSTALLED-TEST.apk` checks the installed test APK against the retained local artifact and runs only `LabTest`. It does not build or install anything. `test RUN INSTALLED-TEST.apk` additionally runs the fixed pipeline/replay tests and writes their benchmark diagnostic reports.
6. `pull-results RUN` copies JSON only into gitignored `corpus/lab/phone-operations/results/`. This operation never copies audio. Collect useful results before recovery.
7. `recover RUN` verifies the physical device, installed build, original lab and expected active files before restoring. It moves the active candidate into a journaled disposal directory before restoring the original; interruption during later recursive cleanup is retryable. Unexpected external edits cause refusal. Recovery material remains on any verification failure. Repeating a completed recovery checks the retained receipt rather than replaying the experiment.

All mutation/execution commands serialize through a host lock. A durable journal and on-device owner identify the active run between calls. Host locks do not stop app writers: the wrapper refuses staging, installation, execution and recovery while the app process is running. Closing the UI does not terminate a cached keyboard process. Use the separately authorized `stop-app` operation below; staging, testing, installation and recovery never stop it implicitly.

The old frozen `tools/lab.py` preparation helper still prints historical phone commands. Its staged directory remains usable; use the run-bearing commands above instead of those printed commands. Historical freezes and evaluator sources remain unchanged.

## Explicitly stop the app

`stop-app` accepts no arguments and is never automatically approved or called by another operation. Obtain current-task authorization: stopping can interrupt a take, discard its in-memory work, and changes the package's stopped state. Keep one operator and do not use dictation during the command.

The command supports only active primary user 0. It refuses if dictation is the selected or bound keyboard, if Android reports any active instrumentation, or if the fixed IME/process report format cannot be verified. It never switches keyboards. Successful stopping verifies termination and unchanged IME selection, enabled keyboards, selected subtype and subtype history. Read-only checks and stopping are not atomic; a postcondition failure is reported without attempting settings changes. Existing recovery receipts remain intact. The next explicitly requested instrumentation/app launch may start the process again; approve and invoke `stop-app` again before recovery if necessary.

The IME parser recognizes AOSP method names and Android 17's renamed IME fields, verified against [GrapheneOS 17](https://github.com/GrapheneOS/platform_frameworks_base/blob/17/services/core/java/com/android/server/inputmethod/InputMethodBindingController.java). These checks follow AOSP's [IME binding/selection fields](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/core/java/com/android/server/inputmethod/InputMethodBindingController.java) and [process dump ordering](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/core/java/com/android/server/am/ProcessList.java). Unsupported device formatting fails closed. Force-stopping a selected IME can change Android's keyboard choice; it is deliberately refused.

## Replace a compatible build

`install CANDIDATE.apk ORIGINAL.apk RUN` requires the original local APK to match the installed bytes. Both APKs must have the same package, version code, signer and application payload. The manifest must match apart from the benchmark's debuggable flag and wake-lock permission. Installation uses replacement only: no downgrade flags, uninstall/reinstall workaround, automatic grants, or fallback to another package.

The wrapper retains checked original and candidate APK snapshots under gitignored `corpus/lab/phone-operations/apks/` before installation; it installs the verified snapshot, never mutable build output. A following candidate push may join the same run. Recovery verifies lab restoration and saves that milestone while private access is still available, then restores and verifies the original APK. If that original removes private access, retry completes using the saved milestone and matching original APK identity; its report labels lab verification `recorded-before-apk-restore` rather than claiming a fresh private read. A currently inaccessible lab without that milestone is not treated as an empty lab.

This deliberately supports a limited compatible-build path, not app-data migrations, arbitrary new code/model builds, initial installation, or split APKs. Matching a version name is insufficient. The current retained release/benchmark pair differs in version and payload and is correctly refused; no compatible installation or phone rollback has been demonstrated by the host-only tests.

## Scope and verification

Daily recognition uses bundled assets and `Variant.DEFAULT`; lab variants do not select the daily engine. Preferences, recordings, permissions, caches and all other app data are outside the recovery contract. Full test writes `files/benchmark/pipeline.json` and `spike-*.json`; diagnostic measurement may write its cache marker. Those diagnostic outputs are not promised byte-for-byte restoration. Original lab models/configuration/results and supported APK replacements are the declared preservation scope.

`uninstall` remains explicitly destructive, refuses an active recovery run, and makes no recovery promise. `pull-report` still requires separate explicit consent for every recording export; files remain in gitignored `corpus/`. Existing local exports are not overwritten.

Host TDD tests enter the same command-dispatch interface, use real temporary files/journals, and substitute only the external device adapter. They do not establish actual Android execution. Run `python3 -I -B tools/test_phone.py` and the existing guard suite; then validate a finite disposable on-device lab exercise before claiming live readiness: present/absent original lab, interruption before/after promotion, recovery retry, and stale-identity refusal. Use synthetic fixtures, never personal recordings. Keep the phone result explicitly pending until that exercise runs.

### Verified on 2026-10-19

All 100 host tests passed, including 32 wrapper tests. A fresh isolated adversarial Astra review found no remaining P1/P2 issues after the reproduced failures were fixed.

On the Pixel running GrapheneOS Android 17, an explicitly approved stop verified termination and unchanged IME settings. Two synthetic candidates staged successfully; a third was interrupted before promotion. A wrong recovery identity was refused. Recovery and repeated recovery succeeded. Final inspection matched all 11 original lab file hashes and the original APK identity; ownership, active journal and all temporary lab scopes were cleared. The app process was intentionally left stopped.

This exercise ran no inference, APK replacement, uninstallation or recording export. Absent-original recovery, interruption after promotion and interrupted recursive cleanup have host coverage only. Compatible APK rollback, actual instrumentation execution and the next daily dictation session still require live validation; the staging exercise does not establish those outcomes. Local evidence and synthetic fixtures remain under ignored `corpus/lab/wrapper-validation-20261019/`.
