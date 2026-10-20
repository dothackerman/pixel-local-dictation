# User-applied recovery proposal

The protected wrapper remains untouched. This candidate was reviewed independently
and tested only against fabricated `DirectoryPhone` devices. It has never contacted
the phone. Actual result/profile bytes remain on the phone; no CPU numerical pass
or successful phone restoration is established by these tests.

Original source: commit `278bd94`, Git blob
`2a4420b87912123364d83921b63f599f55675c90`, SHA256
`ca3c367a662f3810bc6c1e45b341c0d7e2f12f08fd7204ee76707a0305457ed6`.
Candidate `phone-encoder-probe-recovery.py` SHA256:
`0b6bc00d55dbfeda09882fa75cc492acd78310c4652be750572e7c68cfe25cb7`.
The adjacent `.diff` compares these exact sources.

1. Review the candidate/diff and fixed incident identities. If the current wrapper
   differs from the original source pin, stop and review the difference.
2. The user manually replaces the contents of the checkout's protected
   `tools/phone` with this exact candidate, preserving its executable permission.
   The agent must not apply the replacement or execute the proposal path.
3. Confirm manual application. Recovery and independent inspection then use only
   the existing wrapper and exact run `issue4-short-cpu-20261020-03`.

The exception requires the exact app/test pair, owner/device, preserved baseline,
rollback APKs, frozen configuration and complete pinned file inventory. Quiescence
is mandatory. It preserves bounded exact result JSON and the one pinned ORT trace
under the existing gitignored results directory before normal restoration.
Embedded tensor hashes are preserved, never accepted as numerical parity.
The attempt stays incomplete: no role-profile completion, inference retry, or new
run is authorized. Unknown drift still refuses recovery.

Tests translate only fixed policy digests in the test process to deterministic
fabricated payloads. No production argument or environment variable changes the
incident policy.
