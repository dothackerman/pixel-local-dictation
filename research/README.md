# Research notes

Source/package inspection unless a note records execution. Decisions live in
`adrs/`, specs in GitHub issues, terms in [GLOSSARY.md](../GLOSSARY.md).
Research-only terms: *exported model* (runtime-ready representation),
*converter* (host tool that creates it), *runtime* (executes it on the phone).

| Note | Answers |
|---|---|
| [ONNX WebGPU on Android](onnx-webgpu-android-availability.md) | Which package has WebGPU, how it was verified, why Sherpa cannot select it, which encoder ops lack kernels |
| [ORT WebGPU placement](ort-webgpu-node-placement.md) | Why strict CPU-fallback rejection always fails; how to obtain a placement list |
| [Short encoder probe](issue4-short-encoder-probe.md) | How to package, freeze, run and compare the #4 probe and in-pipeline gates; frozen identities |
| [LiteRT and other alternatives](litert-alternatives.md) | Surveyed non-ONNX routes, ready-made artifacts, LiteRT >2 GiB mechanism |
| [Linux sandboxes vs Ubuntu userns](linux-sandbox-userns.md) | Why sandboxed Claude commands failed on this host, Codex escalation flood, user-run fixes ([ADR-024](../adrs/024-phone-access-rewrite.md)) |

Evidence levels are distinct: source support, downloadable binary, successful
load, correct GPU output, measured benefit. A note establishes only the level it names.
Decision trail: [ADR-019](../adrs/019-encoder-conversion.md) →
[ADR-020](../adrs/020-onnx-webgpu-investigation.md) →
[ADR-022](../adrs/022-webgpu-encoder-placement-stop.md) →
[ADR-023](../adrs/023-encoder-placement-check.md).
