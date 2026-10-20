# ADR-021 — Encoder checkpoint blocked on recovery

2026-10-20 · Implementation partial. No GPU finding or advancement; recovery unresolved.

## Observed

Continued [#1](https://github.com/dothackerman/pixel-local-dictation/issues/1) on
`codex/issue-1-fp32-encoder`. The host-only control correction in #2 passed its
public CLI regressions. The next slice, #4, added an explicitly selected diagnostic
build using the official Android ORT 1.31.0 Java/JNI/core package. Source graph and
weights remained unchanged; ordinary product defaults were not selected differently.

The short fixture is frozen FP32 zeros `[1,128,17]` with INT64 length `[17]`.
This is a synthetic checkpoint, not a development-length quantile or full take.
The installed diagnostic source/protocol was committed at `278bd94` before execution.
The AAR, core, JNI, headers, source commit, APKs and fixture have retained identities.

Preparation histories remain private under `corpus/lab/issue4-*`:

- Preparation 01: superseded after review; no phone execution.
- Preparation 02: installation refused because the official library manifest added
  network permissions and a telemetry initializer. Inspection verified no change.
  Packaging now removes that initializer/permissions and declares SDK36 to avoid
  implicit legacy phone/storage permissions. Actual final manifests were compared
  with the retained installed originals before the next installation.
- Preparation 03: diagnostic app/test installed; initial staging refused a missing
  `variant.json`. Supplied the existing FP32 staging declaration, which the early
  encoder dispatcher does not consume. The unchanged encoder and frozen inputs staged.
- Exactly one CPU instrumentation invocation completed: `OK (1 test)`. Inspection
  found its output tensors, result JSON, exclusive marker and ORT profile.
- The wrapper then failed while expecting its numeric role-profile receipt.
  Retrieval and recovery refused the generated files as lab inventory drift.
  No inference retry and no GPU invocation followed.

## Limits and recovery

The helper's redundant tensor sidecars, marker and timestamped ORT profile violate
the wrapper's accepted generated-file contract. The direct helper also does not
produce the numeric role-profile/timeline binding required by diagnostic `lab`.
These integration requirements were missed in host review. A successful test
receipt does not make this an accepted encoder result.

The diagnostic app/test remain installed. The original APK pair and original
11-file lab inventory remain retained; restoration is **not verified**. The active
transaction is `issue4-short-cpu-20261020-03`. Preserve its journal.

The on-device result has SHA256
`c24b366ec782470d8c7e8ca903acce4eb65530515d571ecc2c4bbfd91317bc5c`;
the ORT profile has SHA256
`50582fbf1576b67f54780e2b345b53ffae95c42d3ce7b604f4ed5afbb34bcb70`.
Neither has been exported through the current wrapper. Tensor accuracy, resources,
native cold load and encoder timing remain unproven. Instrumentation elapsed time
is not encoder performance evidence. No full take, Android legacy/new CPU parity,
GPU tensor/placement/precision check, repeated speed series or live validation ran.

Phone work is limited to resolving recovery. Prepare and independently review an
exact-run, exact-inventory recovery proposal outside the protected wrapper; test
with fabricated device evidence. The user applies protected-wrapper changes
manually under [the existing operation rule](../README.md#phone-lab-operations).
Do not edit the active journal directly, broaden unknown-file tolerance, bypass the
wrapper, fabricate a role-profile pass, or discard the private evidence.

The independently reviewed [recovery candidate](../tools/proposals/phone-encoder-probe-recovery.py),
[diff](../tools/proposals/phone-encoder-probe-recovery.diff) and
[manual instructions](../tools/proposals/README-encoder-probe-recovery.md) are
committed outside the protected path. Candidate SHA256 is
`0b6bc00d55dbfeda09882fa75cc492acd78310c4652be750572e7c68cfe25cb7`.
Six fabricated-device tests cover successful preservation/restoration, refused
drift, interrupted restoration and partial exports. All 179 host tests and Android
checks passed. These tests do not certify actual recovery or unseen payloads.

## Decision and next question

#4 stays incomplete; #5 and #8 stay unstarted. Keep
[PR #9](https://github.com/dothackerman/pixel-local-dictation/pull/9) in draft.
No backend compatibility, speed improvement, daily default or FUTO comparison is
established. No recordings were used or exported by this synthetic checkpoint.

First restore and independently verify both original APKs and all original lab
hashes. Then decide how the bounded encoder checkpoint can meet the protected
wrapper's actual receipt and generated-file contract without inventing profiling
evidence or weakening recovery checks.
