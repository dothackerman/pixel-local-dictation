# ADR-021 — Encoder checkpoint recovery incident

2026-10-20 · Recovery independently verified. Implementation partial; no GPU finding or advancement.

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

## Initial recovery blocker

The helper's redundant tensor sidecars, marker and timestamped ORT profile violate
the wrapper's accepted generated-file contract. The direct helper also does not
produce the numeric role-profile/timeline binding required by diagnostic `lab`.
These integration requirements were missed in host review. A successful test
receipt does not make this an accepted encoder result.

At the blocked checkpoint, diagnostic app/test remained installed and restoration
was unverified. The original APK pair and original 11-file lab inventory remained
retained. Transaction: `issue4-short-cpu-20261020-03`; its journal is preserved.

The on-device result has SHA256
`c24b366ec782470d8c7e8ca903acce4eb65530515d571ecc2c4bbfd91317bc5c`;
the ORT profile has SHA256
`50582fbf1576b67f54780e2b345b53ffae95c42d3ce7b604f4ed5afbb34bcb70`.
These were initially unavailable through the wrapper and are now preserved by the
reviewed recovery below. Instrumentation elapsed time is not encoder performance evidence. No full take, Android legacy/new CPU parity,
GPU tensor/placement/precision check, repeated speed series or live validation ran.

Recovery required an independently reviewed exact-run, exact-inventory proposal
outside the protected wrapper, tested with fabricated device evidence. The user applied protected-wrapper changes
manually under the operation rule of the time (superseded by [ADR-024](024-phone-access-rewrite.md): `snapshot`/`restore`, no journal, no proposal flow).
Do not edit the active journal directly, broaden unknown-file tolerance, bypass the
wrapper, fabricate a role-profile pass, or discard the private evidence.

The independently reviewed recovery candidate `tools/proposals/phone-encoder-probe-recovery.py`
and its `.diff` were committed outside the protected path (deleted in `74519c3`, ADR-024; readable at `74519c3^`). Candidate SHA256 is
`0b6bc00d55dbfeda09882fa75cc492acd78310c4652be750572e7c68cfe25cb7`.
Six fabricated-device tests cover successful preservation/restoration, refused
drift, interrupted restoration and partial exports. All 179 host tests and Android
checks passed. These fabricated tests alone did not certify actual recovery or unseen payloads.

## Verified resolution and bounded CPU finding

The user manually applied the exact reviewed candidate. Its Git object matched
`a5a9bebd190dc039e04a05a320c37006aef40e75`; executable mode was unchanged.
The protected wrapper then preserved the exact result JSON and original ORT trace
before restoring the original app/test APKs and lab. A separate inspection matched
the entire pre-run baseline: both APK identities, all 11 lab hashes, no owner,
no recovery journal and no staging/disposal paths. Recovery is verified.

The recovered Android ORT1.31 CPU tensors are finite FP32 `[1,1024,3]` plus exact
INT64 length `[3]`. Against the pre-frozen **supplementary host ORT1.26 CPU**
reference, the directed tolerance check passed; maximum absolute float error was
`1.341104507446289e-7`. This is not Android legacy/new runtime parity.
Maximum sampled process high-water memory was `2,680,528 KB`; all four thermal
samples were `0`. Cold initialization was `5,541.36 ms` and the profiled diagnostic
Run/preparation/host-output/persistence boundary was `363.33 ms`. These single
synthetic diagnostics are not an unprofiled speed comparison or a full-take result.
Private verification and supplementary comparison remain under
`corpus/lab/issue4-artifacts-03/`; rescued originals are in the existing
`corpus/lab/phone-operations/results/` transaction evidence. No GPU run occurred.

## Decision and next question

#4 stays incomplete; #5 and #8 stay unstarted.
[PR #9](https://github.com/dothackerman/pixel-local-dictation/pull/9) is closed.
Create one pull request only after the whole specification is complete.
No backend compatibility, speed improvement, daily default or FUTO comparison is
established. No recordings were used or exported by this synthetic checkpoint.

Recovery is complete. Next decide how the bounded encoder checkpoint can meet the protected
wrapper's actual receipt and generated-file contract without inventing profiling
evidence or weakening recovery checks.
