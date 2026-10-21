# ADR-023 — Replace strict CPU-fallback rejection with an encoder placement check

2026-10-21 · Accepted by the user. Revises the strict-fallback clause of
[ADR-020](020-onnx-webgpu-investigation.md). No phone run authorized by this ADR.

## Context

ADR-020 required strict no CPU fallback, including residual shape/mask
calculations. [ADR-022](022-webgpu-encoder-placement-stop.md) stopped there.
The [placement research](../research/ort-webgpu-node-placement.md) shows the
rule cannot pass with the unchanged graph on stock ORT: WebGPU placement always
keeps shape work on CPU, and the flag is all-or-nothing. Host reproduction: 633
CPU steps, all shape work, zero tensor math.

## Decision

Do not set `session.disable_cpu_ep_fallback`. Accept a WebGPU encoder runtime
session only after a placement check from a verbose placement list:

- Every tensor-math step is on WebGPU. No exception list.
- Every CPU step is shape work: int64/int32/bool inputs and outputs only,
  no floating-point tensor entering or leaving it.
- Every CPU step and boundary copy is named in the retained receipt.
- An unreadable or incomplete placement list fails the check.

The intent of ADR-020 stays: no hidden encoder tensor math on CPU. Timing
includes shape work and boundary copies; they are not subtracted.

## Amendment 2026-10-21: completeness from cause lines

Accepted by the user. A CPU placement is complete when ORT's INFO cause lines
("webgpu kernel not found", "Force fallback to CPU") name unique steps whose
count equals ORT's declared CPU count. Verbose per-step rows are optional.
Reason: the phone's logd drops verbose rows, but the cause lines named all 623
CPU steps and matched the declared total, so a repeat run would only reconfirm
a known fact while the speed question remains unanswered. Boundary copies stay
unnamed until a later run needs them.

First phone listing (ORT 1.31, run `issue4-placement-webgpu-20261021-01`):
623 CPU steps, all shape work, zero tensor math →
[placement research](../research/ort-webgpu-node-placement.md). Passes this check.

## Amendment 2 2026-10-21: no WebGPU-count condition

Accepted by the user. The check does not require ORT's WebGPU summary line.
Reason: only CPU and WebGPU are registered and ORT assigns every step to one of
them, so a matched, fully named CPU cause set already implies all other steps
are on WebGPU; a failed WebGPU registration would produce no "webgpu kernel not
found" lines and no match. logd dropped that summary line in the first
WebGPU Run (`issue4-v2-webgpu-20261021-01`).

## Amendment 3 2026-10-21: in-pipeline placement by consistency

Accepted by the user. The in-pipeline Sherpa encoder session (`pipeline-placement-v1`)
passes on consistency with the complete first listing, not on its own cause count.
Reason: logd drops lines at write time during session creation (the wrapper's own
logcat holds the same gaps), so a complete in-pipeline listing would need a JNI file
logger, rebuild and re-freeze, for a "why" detail that does not change the speed or
accuracy verdict. The speed/accuracy question is answered by the timed replay.

Evidence, same model (`2144d185…a725`), ORT 1.31 core and app build (`d0e168d2…c1ce`):
- ORT declared 623 CPU steps in both attempts, equal to the complete first listing.
- Cause lines named 438 (`75ec3aac…`, receipt `1f1d6482…318b`) and 466
  (`6e1f0b2c…`, receipt `77d40447…e5f4`); union 495, all in the original graph,
  all shape work, zero tensor math. 54 "kernel not found" in each, as before.
- Vulkan HAL `vulkan.powervr.so` mapped and GPU device node held.
- `placement-check` output stays `passed: false` (count mismatch); it is not relaxed.

Limit: 128 in-pipeline CPU steps are unnamed; their shape-only nature is inferred from
the identical declared count and the first listing, not observed.

Not pursued (revive if a complete in-pipeline listing is ever needed): bypass logd.
Extend `tools/ort131-sherpa/patch-webgpu.py` so that, when `placement_log` is set, the
Sherpa JNI creates its `Ort::Env` with a custom logger (`CreateEnvWithCustomLogger`)
that appends every ORT line to a file under `files/lab`. The test pulls that file in
place of `LogTail`'s logcat stream; `pipeline_listing` parses it unchanged. Cost:
AAR rebuild, new app/test APK identities, re-freeze of the placement lab, and a check
that timed runs (no `placement_log`) keep the default logger. Retrying the logcat
route does not help: drops happen at write time (438, then 466 of 623 named).

## Rejected

- Graph change to remove shape work: alters the length contract ADR-020 preserves.
- Forked runtime: maintenance burden; forces round trips ORT avoids deliberately.
- Keeping the strict flag: unsatisfiable, so the investigation cannot advance.

## Consequence

Done: see amendment. Next: a short non-strict WebGPU Run compared directly with
Android 1.31 CPU on the same build (#4 tensor gate).
