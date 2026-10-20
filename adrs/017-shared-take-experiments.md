# ADR-017 — Share the take; freeze the comparison

2026-10-20 · Implementation authorized by Oriol. No experiment launch or phone change.

## Decision

`DictationSession` owns a take from capture to completion. Production and phone
replay construct it through `Pipeline.runtime`. `DictationRuntime` retains the
recognizer and serial decode queue across takes. A cancelled take cannot commit;
its native work may still be running. Release follows capture and native drain.
Cancellation keeps the take active until capture acknowledges drain. Completion
then permits a new microphone take; it does not wait for cancelled native work.

Microphone and replay are capture adapters. The IME checks its live input session
immediately before committing. A rejected commit is a failure. Stop timing starts
before capture drain. Editor acceptance and a return request are receipts, not
proof that text is visible or the keyboard is ready.

`MeasurementRun` now requires `dictation-session-replay-v1`. Its editor and
keyboard are explicitly simulated. Historical reports, evaluator rules and
timing meanings remain unchanged. Python decoding remains a laptop screen.

## Experiment interface

`python3 tools/lab.py --help` owns command syntax. It never runs inference or
device commands. The existing protected wrapper retains sole phone authority.

1. `prepare` snapshots a tracked specification's host artifacts and source hashes.
   `check` shows missing or changed inputs; it never grants measurement readiness.
2. After preparation and authorization, `freeze` validates one finite comparison
   plan in `corpus/lab/<id>/plan.json`.
3. `reserve` creates one exclusive attempt record before external execution.
   `record` preserves its result once, including failures.
4. `compare` validates the frozen identities, coverage, timing, backend, device
   and repeated controls. It saves a private report with evidence hashes.

A missing receipt means reconcile the actual state. It never means retry.
Reservation is cooperative accounting, not an at-most-once device guarantee.
Interrupted or failed phone operations still use the wrapper's recovery journal.
Do not reset attempts by copying a plan to another identity.

The comparison plan contains `schema: 1`, `id`, `kind` (`conversion-parity` or
`gpu-wait`), `policy_status`, `control`, `candidate`, `allowed_differences`,
`thresholds` and an ordered `runs` list. Each run has `id`, `setup`, `pair` and
`expected`. Expected evidence includes v4 `provenance`, empty `takes`, complete
`endpoints`/`endpoint_references`, host-frozen device artifact/input hashes,
observed `device` and intended `execution`.
Each endpoint reference also freezes `path`, `sha256` and exact `samples`.
Reports must match that case mapping and order, not only the set of WAV hashes.
Capture duration cannot be shorter than the paced input minus one feed block.
The freeze includes evaluator hashes; changed scoring code requires the original
Git revision or an explicit new protocol.

Only the listed build/runtime/model/configuration identities may differ between
setups. Each run must match its exact frozen identity. Conditions and identities
must remain fixed across repeats. The comparison requires both languages,
complete paced phone session evidence and no profiling or backend fallback.
Conversion preserves exact output, including formatting. The GPU comparison also
requires output parity, per-language practical Stop savings, positive paired
savings and a median saving greater than the observed repeat spread. Small
development suites establish no population tail latency or product winner.

## Retirements and preservation

The old `lab.py` execution/ledger operations, `compare.py` and `replay.py` are
retired; their source remains in Git. Keep historical scorers and analysis tools.
Old freeze files describe historical source; they are not current readiness gates.
Remove the ledger-specific test with its retired implementation. Keep scientific
evaluator regressions and add tests at the new take/comparison interfaces.

`quality.py` adds compilation of Android instrumentation sources, without running
them. Synthetic tests cover capture drain, cancellation, editor rejection,
identity drift, incomplete evidence, resource failure and attempt preservation.
Native output parity, microphone behavior and live FUTO return still require
separately authorized phone validation.

Revisit only when a second real protocol requires more orchestration. A general
campaign controller is not part of this change.
