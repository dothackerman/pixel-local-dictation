# Experiment status

Completed work through 20 October 2026:

- [Allocation and measurement](../archive/allocation-2026-10-19.md): benchmark app retained and original setup lab baseline restored; archive owns results, evidence and amendments.
- [Bounded Moonshine/TPU campaign](campaign-2026-10-19.md#measurement-outcome-and-handoff): Small screens did not advance; no phone measurements or mutations. Its wrapper-readiness blockers describe the pre-`d86f527` checkout, not current capabilities.
- [CPU profiling continuation, October 20](campaign-2026-10-19.md#cpu-profiling-continuation--20-october-2026): eight Parakeet/Cohere phone runs completed; original app/test packages and lab restored and verified. Encoder work is substantial, but both setups failed profiling timing comparability. Role shares do not predict everyday Stop wait.

The recorded campaigns are closed. The retained earlier profiling proposal is historical; the October 20 continuation owns completed profiling and its limitations. [The roadmap](../roadmap.md#future-experiments) retains future directions and unfinished product gates. Neither unused campaign capacity nor the later [wrapper implementation](../decisions.md#adr-009--bounded-recovery-through-the-protected-phone-wrapper) authorizes new runs.

The [TPU/GPU assessment after profiling](../research/accelerator-routes-after-profiling-2026-10-20.md) proposes a public GPU route and a precompiled G5 access test before beta enrollment. The later [device access check](../research/accelerator-access-device-check-2026-10-20.md) proved physical GPU and Tensor G5 TPU execution on this GrapheneOS Pixel using public models. GPU FP32 passed both frozen numerical checks; TPU failed one. This establishes access, not speech compatibility or speedup. Oriol manually applied the reviewed wrapper compatibility patch; no OS settings changed. Both separately frozen access-test processes were consumed; old campaign budgets are unchanged. Final restoration is recorded in the device-check report.

Start with [the remaining improvement levers](../research/remaining-improvement-levers-2026-10-19.md) and a decision the next result could change. Retired setup/execution code is [recoverable from its pinned source](../archive/allocation-2026-10-19.md#source-recovery-and-offline-analysis); retained report tools support offline reanalysis. Prepare no successor machinery before a bounded work order is selected.
