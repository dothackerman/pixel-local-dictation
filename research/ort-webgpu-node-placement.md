# ORT WebGPU placement and strict CPU-fallback rejection

2026-10-21. ORT 1.31.0 source (commit `402979ac`), one host runtime session
creation on ORT WebGPU 1.27.0, and one phone placement listing (no Run). Decision:
[ADR-023](../adrs/023-encoder-placement-check.md); phone stop:
[ADR-022](../adrs/022-webgpu-encoder-placement-stop.md); beginner page:
[explainer](../explainers/webgpu-encoder-placement/index.html).

## Finding

`session.disable_cpu_ep_fallback=1` is session-wide and all-or-nothing, and
WebGPU placement always keeps some shape work on CPU. So the unchanged
dynamic-length encoder fails runtime session creation every time. Configuration
mismatch; says nothing about GPU capability or arithmetic.

## Mechanism

[`webgpu_execution_provider.cc`](https://github.com/microsoft/onnxruntime/blob/402979ac1bef89dcd55add41eaa7277c407599eb/onnxruntime/core/providers/webgpu/webgpu_execution_provider.cc)
`GetCapability`, per step in topological order:

1. No kernel for op type + element type → unclaimed (`:685-691`, INFO "webgpu kernel not found in registries").
2. Named in `forceCpuNodeNames` → unclaimed (option only adds CPU steps).
3. Else candidate; then `GetCpuPreferredNodes` runs unconditionally (`:749`) and removes its result.

[`fallback_cpu_capability.cc`](https://github.com/microsoft/onnxruntime/blob/402979ac1bef89dcd55add41eaa7277c407599eb/onnxruntime/core/framework/fallback_cpu_capability.cc):
seeds from CPU-resident outputs (`Shape`) and unclaimed steps, follows consumers,
keeps a candidate on CPU when every input is a CPU value, graph input or constant
≤100 elements. fp16/bf16/fp8 inputs veto; FP32 does not. INFO "Force fallback to
CPU execution for node: … deemed faster". No option disables it (1.31 or `main`).

Strict check: `inference_session.cc:3163-3184`, after placement, no allow-list;
error "assigned to the default CPU EP, but fallback to CPU EP has been explicitly
disabled". The verbose placement dump (`session_state.cc:1575-1590`) runs later,
so a strict failure never prints it; `session_state.cc:1598` calls CPU shape
placement deliberate. Flag added in 1.16 for QNN whole-model offload.

## Int64 kernel coverage (`enableInt64=1`)

- Present: Cast, binary arithmetic/comparison, Range, Unsqueeze, Expand, Reshape,
  Concat, Gather, Where, Tile, ReduceSum, Clip. Shaders use the low 32 bits only.
- No int64 kernel: Squeeze, ReduceMax/Min, Pad, Transpose, Pow, Slice.
- No kernel at all: ConstantOfShape, Or, Xor, Mod, Size, NonZero, Sign, Round.
- Java `addWebGPU` maps `enableInt64` → `ep.webgpuexecutionprovider.enableInt64` (`provider_registration.cc:201`).

## Host reproduction (PyPI `onnxruntime-webgpu` 1.27.0, linux x86_64, Vulkan)

Same encoder, probe options, `ALL_OPT`: reproduces the phone error. Non-strict
verbose rerun: 2,273 steps; 1,640 WebGPU; 633 CPU.

| Cause | Steps | Composition |
|---|---:|---|
| No kernel | 419 | int64 Gather 185, Concat 118, Slice 24, Squeeze 24, Reshape/Equal/Where 18 each, 14 other int64/bool |
| CPU preference | 214 | int64 Unsqueeze 210; And, Not, bool Unsqueeze 4 |
| Tensor math on CPU | 0 | |

About 20 Memcpy steps at boundaries, mostly around the padding mask. Python
quirk: provider option `enableInt64` alone did not apply; session config
`ep.webgpuexecutionprovider.enableInt64=1` was also required.

## Phone placement (Android ORT 1.31.0, Pixel 10 Pro XL)

Run `issue4-placement-webgpu-20261021-01`, protocol `encoder-placement-v1`
(wrapper `1f91df7`): one non-strict runtime session creation, no Run, synthetic
input. Receipt SHA256 `fbd8e93e…22d1` (private `corpus/lab/phone-operations/results/`).
Recovery restored both original APKs and all 11 lab hashes; independent `inspect`
matched the baseline.

| Cause | Steps | Composition |
|---|---:|---|
| No kernel | 54 | Slice 26, Squeeze 24, Shape, ConstantOfShape, Tile, Transpose 1 each |
| CPU preference | 569 | shape work behind those and behind `Shape` |
| CPU total (declared) | 623 | = 54 + 569; 623 unique names |
| WebGPU (declared) | 1,635 | |

All 623 CPU step names exist in the original graph; shape inference finds no
floating-point input or output on any of them: **zero tensor math on CPU**.
1.31's extra int64 kernels shrank the no-kernel group (419 → 54), but the
CPU-preference heuristic absorbed the same shape work (214 → 569).

Limits: logd dropped most verbose per-step rows (323 of 623 CPU, 119 of 1,635
WebGPU received), so the per-provider listing is incomplete and ADR-023's check
fails on its letter. The complete CPU set rests on the cause lines matching the
declared count exactly. Boundary copies were not named. Cold load 6.6 s; HWM
350 MB; thermal 0.

## Obtaining a placement list

Do not set `disable_cpu_ep_fallback`. Log severity verbose (0). Read
`Node(s) placed on [<provider>]` from `VerifyEachNodeIsAssignedToAnEp` plus the
two INFO causes above. A strict run yields only the error. On Android the
verbose burst overruns logd; the two INFO cause lines survive and, when their
sum equals the declared CPU count, name every CPU step.
