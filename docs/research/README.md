# Research notes

Source/package inspection unless a note records execution. Decisions live in
`docs/adr/`, specs in GitHub issues, terms in [GLOSSARY.md](../../GLOSSARY.md).
Research-only terms: *exported model* (runtime-ready representation),
*converter* (host tool that creates it), *runtime* (executes it on the phone).

| Note | Answers |
|---|---|
| [ONNX WebGPU on Android](onnx-webgpu-android-availability.md) | Which package has WebGPU, how it was verified, why Sherpa cannot select it, which encoder ops lack kernels |
| [ORT WebGPU placement](ort-webgpu-node-placement.md) | Why strict CPU-fallback rejection always fails; how to obtain a placement list |
| [Short encoder probe](issue4-short-encoder-probe.md) | Protocol and frozen identities of the #4 probe and in-pipeline gates (tools retired; historical) |
| [LiteRT and other alternatives](litert-alternatives.md) | Surveyed non-ONNX routes, ready-made artifacts, LiteRT >2 GiB mechanism |
| [Linux sandboxes vs Ubuntu userns](linux-sandbox-userns.md) | Why sandboxed Claude commands failed on this host, Codex escalation flood, user-run fixes ([ADR-024](../adr/024-phone-access-rewrite.md)) |
| [Spoken numbers to digits](number-itn.md) | sherpa-onnx rule FSTs, NeMo ITN fit, Parakeet number output, Swiss number style, options |
| [Issue 30 panel performance](issue30-panel-performance.md) | Review, startup and component-replay timings of the recording-panel ring; Signal height trade-off |

Evidence levels are distinct: source support, downloadable binary, successful
load, correct GPU output, measured benefit. A note establishes only the level it names.
WebGPU decision trail: [ADR-019](../adr/019-encoder-conversion.md) →
[ADR-020](../adr/020-onnx-webgpu-investigation.md) →
[ADR-022](../adr/022-webgpu-encoder-placement-stop.md) →
[ADR-023](../adr/023-encoder-placement-check.md) →
[ADR-025](../adr/025-issue4-one-case-finding.md) (parked by ADR-026).
