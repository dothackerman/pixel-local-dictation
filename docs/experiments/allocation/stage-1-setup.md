# Completed preparation — stage 1

**Completed 2026-10-14; experiment launch remains separate.** [The frozen brief](stage-1-runbook.md) and private `corpus/lab/release-1a/manifest.json` own execution commands, artifact identities and remaining launch prerequisites. Completed setup is not a checklist for the next Sol session.

Oriol authorized measurement/evaluator setup, later benchmark provisioning and one public/synthetic phone smoke, then one additional smoke of revised instrumentation. Scope excluded personal-corpus ranking, model downloads/exports and experiment execution. Original limits were eight active setup hours, 10 GB additional storage, two dependency repair attempts, twelve smoke processes and sixty inference minutes; the final authorization extended only the process allowance to thirteen for that additional smoke.

## Delivered evidence

| Deliverable | Evidence / limit |
|---|---|
| Matched-platform evaluator, allocation controls, three diagnostic process modes, annotated endpoints and frozen commands | [Brief](stage-1-runbook.md); private environment, manifest and smoke records under `corpus/lab/release-1a/` |
| Host validation | Review checkpoint: 42 Python tests passed; benchmark unit tests and instrumentation built offline. Later execution-helper checks are recorded in commit `0cc6dc8`. No batch inference validated that helper. |
| Original device smoke | `device-setup.json`: one persistent process, two passes over German, English and silence; six results, coverage, counters and APK hashes checked. |
| Revised instrumentation smoke | `device-setup-reviewed.json`: fresh process/run identities, six valid results, matching revised APK hashes, empty silence, no missing speech output or prefix-tail mismatch, valid memory counters, thermal maximum 0. **Historical-full mode was not exercised.** |

Thirteen setup measurement processes were consumed. Both smoke reports and their distinct build identities are retained. The last recorded phone state is the benchmark build installed; no settings, safeguards or other apps changed. The subsequent recorder lint annotation did not rebuild the saved release APK or alter these smoke identities. Fresh-session verification must establish the actual state before execution.

The rejected first handoff save and intermediate untested-instrumentation checkpoints were resolved by the later authorized work. Their chronology remains in Git (`64e11b4` through `6cb5294`); none is outstanding setup work. The manifest's `launch_requirements`, rather than that history, determines what is missing for launch.
