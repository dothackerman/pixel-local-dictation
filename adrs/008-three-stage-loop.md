# ADR-008 — One allocation loop, three stages

2026-10-14 · Organization approved; completed October 19.

Combine measurement characterization, FP32 allocation and Cohere allocation in one bounded loop.
The completed campaign retained its stage gates and cumulative attempt counts.
Freeze transitions/continuation before readiness; ambiguity returns for interpretation.
The retired `allocation_execution.py` owned append-only reservations/barriers; returns commands without executing them.
Ownership was cooperative; records alone did not prove device-process termination.
Amendments retain actual consumption/history; no retroactive refreeze.
All stages completed; ADR-012. No new launch, engine or acceptance approval follows.
Revisit for a separately commissioned experiment.

October 19 retirement: setup/execution/continuation controllers removed. Retained `allocation_report.py` supports offline scientific validation; recover old source at d0e984641d9d247bd6dd4f500abfbba1091b7b43 without running historical launch/device commands.
