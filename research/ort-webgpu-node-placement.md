# ORT WebGPU placement and strict CPU-fallback rejection

2026-10-21. Source inspection of ORT 1.31.0 plus one host session-creation
reproduction on ORT WebGPU 1.27.0. No phone execution; no recordings.
Independent of whether GPU execution accelerates dictation.
Terms: [GLOSSARY.md](../GLOSSARY.md). Beginner explainer:
[explainers/webgpu-encoder-placement](../explainers/webgpu-encoder-placement/index.html).

## Finding

`session.disable_cpu_ep_fallback=1` cannot pass on the WebGPU provider for a
dynamic-length ONNX graph with shape work. The flag is session-wide and
all-or-nothing; WebGPU placement always sends some shape work to CPU. The
unchanged Parakeet FP32 encoder therefore fails runtime session creation every
time ([ADR-022](../adrs/022-webgpu-encoder-placement-stop.md)). This is a
configuration mismatch, not evidence about GPU capability or arithmetic.

## Mechanism (source, commit `402979ac`, release 1.31.0)

[`webgpu_execution_provider.cc`](https://github.com/microsoft/onnxruntime/blob/402979ac1bef89dcd55add41eaa7277c407599eb/onnxruntime/core/providers/webgpu/webgpu_execution_provider.cc)
`GetCapability`, per step in topological order:

1. No WebGPU kernel for op type + element type: not claimed (`:685-691`, INFO
   "webgpu kernel not found in registries"). It falls to CPU.
2. Named in `forceCpuNodeNames`: not claimed. This option only adds CPU steps.
3. Otherwise a candidate. Then `GetCpuPreferredNodes` runs unconditionally
   (`:749`) and removes its result from the claim.

[`fallback_cpu_capability.cc`](https://github.com/microsoft/onnxruntime/blob/402979ac1bef89dcd55add41eaa7277c407599eb/onnxruntime/core/framework/fallback_cpu_capability.cc):
starts from outputs a kernel declares CPU-resident (`Shape`) and from unclaimed
steps, follows consumers, and keeps a candidate on CPU when every input is a CPU
value, a graph input or a constant of ≤100 elements. fp16/bf16/fp8 inputs veto it;
FP32 does not. INFO log: "Force fallback to CPU execution for node: … deemed
faster". No session or provider option disables it, in 1.31 or on `main` at
inspection time.

Strict check: `inference_session.cc:3163-3184`, after placement. Any step on CPU
fails with "assigned to the default CPU EP, but fallback to CPU EP has been
explicitly disabled". No allow-list. The verbose per-step placement dump
(`session_state.cc:1575-1590`) runs later, so a strict failure never prints it.
ORT's own warning (`session_state.cc:1598`) calls CPU shape placement deliberate.

The flag arrived in 1.16 for QNN whole-model offload. It does not coordinate
with per-provider CPU-preference logic.

## Kernel coverage, `enableInt64=1` (source)

- int64 kernels exist for Cast, binary arithmetic/comparison, Range, Unsqueeze,
  Expand, Reshape, Concat, Gather, Where, Tile, ReduceSum, Clip. Shaders compute
  int64 on the low 32 bits only.
- No int64 kernel: Squeeze, ReduceMax/Min, Pad, Transpose, Pow, Slice.
- No kernel at all: ConstantOfShape, Or, Xor, Mod, Size, NonZero, Sign, Round.
- Kotlin/Java `addWebGPU` maps `enableInt64` to
  `ep.webgpuexecutionprovider.enableInt64` (`provider_registration.cc:201`).

## Host reproduction (ORT WebGPU 1.27, Vulkan)

PyPI `onnxruntime-webgpu` 1.27.0, linux x86_64. Same encoder, probe options and
`ALL_OPT` reproduce the phone error. Non-strict, verbose-logged rerun:
2,273 steps; 1,640 WebGPU; 633 CPU.

| Cause | Steps | Composition |
|---|---:|---|
| No kernel | 419 | int64 Gather 185, Concat 118, Slice 24, Squeeze 24, Reshape/Equal/Where 18 each, 14 other int64/bool |
| CPU preference | 214 | int64 Unsqueeze 210; And, Not, bool Unsqueeze 4 |
| Tensor math on CPU | 0 | |

About 20 Memcpy steps sit at the boundaries, mostly around the padding mask.
Python quirk: the provider option `enableInt64` alone did not apply; the session
config entry `ep.webgpuexecutionprovider.enableInt64=1` was also required.

1.31 adds int64 Gather/Concat/Reshape, so the phone's no-kernel group is likely
smaller. The CPU-preference group remains. The phone placement list is unmeasured.

## Obtaining a placement list

Do not set `disable_cpu_ep_fallback`. Set log severity verbose (0). Read
`Node(s) placed on [<provider>]` from `VerifyEachNodeIsAssignedToAnEp`, plus the
two INFO causes above. A strict run yields only the error.

## Routes without a CPU shape island

- Graph change: fixed input length, so shape work folds to constants; or a
  host-computed mask as input. Changes the length contract.
- Runtime fork: drop the heuristic and add kernels. Forces CPU↔GPU round trips
  ORT avoids deliberately; int64 still 32-bit.

[ADR-023](../adrs/023-encoder-placement-check.md) chose a placement check instead.
