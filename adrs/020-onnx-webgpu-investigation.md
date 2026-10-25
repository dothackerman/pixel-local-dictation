# ADR-020 — ONNX-first GPU investigation

2026-10-20 · Revised spec and ticket mapping accepted by the user and published. No runtime implementation or phone run authorized by this ADR.
Strict-fallback clause revised by [ADR-023](023-encoder-placement-check.md).

## Context

[ADR-019](019-encoder-conversion.md) records a failed pinned LiteRT conversion route,
not a general LiteRT model-size limit or a GPU failure. LiteRT can keep weight data
outside the conventional limited FlatBuffer container. A published FP32 Parakeet
LiteRT model also exists, but its fixed windows, length handling and surrounding
recognition pipeline differ from our current dynamic encoder.

Focused research verified that the official Android ONNX Runtime 1.31.0 AAR
includes native WebGPU/Dawn/Vulkan support for ARM64. Our current Sherpa wrapper
and bundled ORT1.28.2 cannot select that route today. Package availability is not
physical Pixel or model compatibility evidence. Source/package identities and
limitations: [runtime research](../research/onnx-webgpu-android-availability.md).

## Decision

Investigate the unchanged FP32 Parakeet ONNX encoder through the published runtime
before commissioning more conversion work. Preserve source model bytes, true
lengths/masks, feature extraction, decoder/joiner, search, dictionary and chunking.
This minimizes model/pipeline changes; native integration effort remains uncertain.
The ready five-second LiteRT pipeline, LiteRT writer repair, NPU and INT8 Cohere
remain separate alternatives, not silently substituted configurations.

Use three identified setups: legacy CPU, ORT1.31 CPU, ORT1.31 WebGPU encoder with
other recognition roles CPU. Check runtime-upgrade parity separately; measure GPU
speed only against CPU on the same final1.31 build. Do not attribute a combined
runtime-upgrade/GPU difference solely to the GPU.

Start with a minimal benchmark-only encoder harness and short physical synthetic
GPU feasibility before full Sherpa/session integration. Then establish legacy/new
CPU whole-take parity and complete GPU tensor/placement coverage. Both gate matched
speed and live validation. Keep strict no encoder CPU fallback, including residual
original shape/mask calculations. Ordinary scheduling/allocation is distinct and
timed. Use sequential processes; do not load multiple full encoders concurrently.

Preserve failure outcomes. Synthetic host evidence is not Android proof. A finite
shape suite does not establish the entire domain of an unbounded chunker. Full
coverage and same-build identity requirements stay explicit in the GitHub spec.

## Status and revisit condition

The user accepted ONNX-first and the corrected ticket mapping after a fresh
adversarial Astra verticality review. Delivery follows complete operator outcomes:
one full-take finding, the complete repeated bilingual decision, then actual live
dictation. Packaging, CPU parity and tensor correctness are mandatory internal
steps, not separate layer-delivery tickets. Existing consecutive-take support is
reused rather than manufactured into a new feature.

The published [spec #1](https://github.com/dothackerman/pixel-local-dictation/issues/1)
links the native dependency graph: host-only #2, then phone-required #4, #5 and #8.
#3 stays closed with its historical conversion diagnosis. Passing scientific
outcomes remain additional start conditions; a failed investigation's closure is
not permission to advance. Keep acceptance criteria in GitHub. No backend switch,
new measurements or daily-default change follows from publishing these notes.

If the exact model requires unsupported GPU calculations, reduced precision,
CPU fallback, changed lengths or unacceptable resources, retain the diagnosis and
explicitly reconsider the route. Do not weaken acceptance to keep it alive.
