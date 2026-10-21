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

## Rejected

- Graph change to remove shape work: alters the length contract ADR-020 preserves.
- Forked runtime: maintenance burden; forces round trips ORT avoids deliberately.
- Keeping the strict flag: unsatisfiable, so the investigation cannot advance.

## Consequence

Next phone step: one non-strict, verbose-logged runtime session creation to
obtain the 1.31 placement list. It needs separate current-task authorization.
