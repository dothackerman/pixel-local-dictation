# Ready-made Parakeet GPU options

> Priority follow-up: investigate unchanged ONNX on the official Android WebGPU
> runtime first. The ready LiteRT model and recipe remain useful alternatives,
> with different input/pipeline contracts. This supersedes the LiteRT-first
> recommendation below; see the [focused runtime check](onnx-webgpu-android-availability.md).

> Follow-up: an official Android WebGPU runtime is available in ONNX Runtime
> 1.31.0. Earlier package-availability uncertainty below is superseded by
> [the package and model compatibility check](onnx-webgpu-android-availability.md).
> Availability does not establish encoder compatibility or Pixel execution.


2026-10-20. Public publisher metadata/docs/source only. No model binaries downloaded, conversion, inference, installation or phone work. This note concerns issue #1: FP32 Parakeet v3 encoder only on GPU, existing decoder/joiner/search held fixed.

## Finding

**A ready-converted FP32 Parakeet v3 LiteRT model exists. Investigate it before repairing our converter.** It exceeds 2 GiB on disk and has an encoder signature that can be called separately. It is not a verified drop-in replacement: the published pipeline uses fixed five-second windows, omits our true-length encoder input/output and rewrites some operations. Exact tensor equivalence and our Pixel GPU execution remain unproved.

| Rank | Available option | What can be reused | Remaining work / experiment fit |
|---|---|---|---|
| 1 | LiteRT Community generic FP32 Parakeet v3 `.tflite` | Already converted model, separate `encode` signature; Android example and PyTorch conversion source | Best first inspection. Fixed 500-frame input and no explicit true length differ from our dynamic contract. Connect to existing CPU decoder only after tensor/shape/length validation; not an exact drop-in. |
| 2 | Google LiteRT-LM Omni Parakeet implementation + downloadable INT8 `.litertlm` | Audio preprocessing, model loading, GPU options, decoding, streaming and text merging | Closest ready pipeline, but experimental, native build/integration required. INT8 plus changed chunking/decoder means a separate product comparison, not issue #1. |
| 3 | ONNX Runtime native WebGPU execution provider | Potentially use existing ONNX model without LiteRT conversion | Official Android/Vulkan support documented, but no compatible Android WebGPU binary verified here. Need runtime build/integration/operator coverage and numerical checks. Not plug-and-play. |
| 4 | Tensor G5 precompiled Parakeet v3 `.tflite` | Existing downloadable Pixel-family accelerator artifact | NPU, not GPU; separate experiment. `f32` filename does not prove FP32 internal accelerator arithmetic. GrapheneOS and exact Pixel execution untested here. |

Sources and limitations follow. No exact drop-in meeting every issue #1 condition was established; this is not a claim that none exists.

## Verified downloadable files

The [live repository metadata](https://huggingface.co/api/models/litert-community/parakeet-tdt-0.6b-v3) and [file listing](https://huggingface.co/api/models/litert-community/parakeet-tdt-0.6b-v3/tree/main?recursive=true) were read directly, not inferred from a model name or search snippet. Revision inspected: `3ce205aa928f443f423214195cfe83ebe4802826`, last modified October 17. The current [model card at that revision](https://huggingface.co/litert-community/parakeet-tdt-0.6b-v3/blob/3ce205aa928f443f423214195cfe83ebe4802826/README.md) describes these:

| Artifact | Bytes | Publisher LFS SHA256 |
|---|---:|---|
| `parakeet_tdt_0.6b_v3_5s_f32.tflite` | 2,422,415,168 | `ffa0d4cbb94589b78507bcac9d8b5e5b78d84f8f9d86dc8c397da1f433777a85` |
| `parakeet_tdt_0.6b_v3_5s_f32_stateful.tflite` | 2,421,888,688 | `8c1f06a9e1682ab2f4d95f65d413055c8f83d64a7b3daab3185b5e0e91e9f4ab` |
| `parakeet_tdt_0.6b_v3_5s_i8_stateful.litertlm` | 614,588,752 | `66c5a74cf6c2c1d2b990901f6cece5bb53e3214dbceafa0989900a6f0eeb3504` |
| `parakeet_tdt_0.6b_v3_5s_f32_Google_Tensor_G5.tflite` | 1,263,633,824 | `a2b49b05cad12ae1e2bf3efacc2befd6005d2b3dc59b4bd42b6de4c24e7f480b` |

These are publisher metadata hashes, not locally verified downloaded bytes. Generic FP32 files are larger than 2 GiB: useful evidence of an existing published large-model route. Their external-buffer layout was **not** inspected; file size alone does not identify the exact storage mechanism.

Publisher provenance is NVIDIA's `.nemo` checkpoint → PyTorch → litert-torch; no additional training claimed. Model supports 25 European languages including English/German. Current card specifies CC-BY-4.0 for weights/tokenizer and Apache-2.0 for conversion/runtime; [NVIDIA's base model](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3) also specifies CC-BY-4.0. Stale search results showed an empty card and Apache metadata; do not use those in place of current publisher documentation.

## Why it is useful, but not a drop-in encoder

Current official [conversion wrapper](https://github.com/google-ai-edge/litert-samples/blob/f1e415713f0354920c263c66e65ebd4b138c7e67/samples/litert/speech_recognition/convert/parakeet_tdt.py):

- Exposes `get_encoder()` and an `encode` signature; encoder output is hidden states, so CPU decoding need not conceptually be replaced.
- Calls NeMo encoder with `length=None`, returns hidden states only. Our encoder accepts a separate true length and returns the encoded length. This difference matters for short/padded inputs.
- Replaces `MaskedConvSequential` and normalization implementations. Shared model name/checkpoint does not establish identical output values.

The [conversion entry point](https://github.com/google-ai-edge/litert-samples/blob/f1e415713f0354920c263c66e65ebd4b138c7e67/samples/litert/speech_recognition/convert/convert_to_tflite.py) exports signatures from example inputs and passes no `dynamic_shapes`. It has `quant=none` versus `drq`, and an `input_sec` setting; different fixed sizes therefore have a ready source recipe. It does not establish our existing dynamic-length contract. Android [model metadata](https://github.com/google-ai-edge/litert-samples/blob/f1e415713f0354920c263c66e65ebd4b138c7e67/samples/litert/speech_recognition/AndroidApp/app/src/main/assets/model_metadata.json) fixes 500 mel frames / five seconds. The card describes 63 output frames of 1,024 values. Existing ONNX reference is `[batch,128,time]` plus length → `[batch,1024,encoded_time]` plus length; the dimensional family matches, but interface/semantic equivalence remains unverified.

The [Android runner](https://github.com/google-ai-edge/litert-samples/blob/f1e415713f0354920c263c66e65ebd4b138c7e67/samples/litert/speech_recognition/AndroidApp/app/src/main/java/com/google/ai/edge/examples/asr/LiteRtRunner.kt) pads/truncates features to its expected tensor size. One `CompiledModel` runs both signatures under one accelerator selection, and CPU fallback is always included. It does **not** demonstrate our explicit GPU-encoder/CPU-decoder split or GPU-only coverage. Its FP32 option is configurable; mere successful execution does not prove FP32 GPU arithmetic or full delegation.

The publisher reports actual generic FP32 GPU runs on Galaxy S24 Ultra, plus slower full-pipeline GPU execution than INT8 CPU. Those measurements use its five-second overlapping stream pipeline. They establish neither Pixel/PowerVR/GrapheneOS compatibility nor the benefit of our encoder-only split. Fixed windows, padded short tails and text merging must not quietly replace issue #1's controlled pipeline.

## Closest ready runtime integration

[LiteRT-LM Omni documentation](https://github.com/google-ai-edge/LiteRT-LM/blob/2ef6baebc2890e4f38bf2c69787ebc86882b2c5a/omni/README.md) explicitly labels Omni experimental, provides an Android ARM64 build target and a selective Parakeet link target. Actual [Parakeet registration/implementation](https://github.com/google-ai-edge/LiteRT-LM/blob/2ef6baebc2890e4f38bf2c69787ebc86882b2c5a/omni/speech2text/parakeet_tdt.cc) exists; it references the downloadable INT8 package and configures GPU buffers. It creates one compiled model/runner used for encoding and decoding. No separate encoder/decoder backend switch was found in that path. This avoids self-conversion for its existing pipeline, but still needs native integration and cannot be assumed to exist in our pinned runtime version. Its documented automatic downloads must be disabled/replaced with local provisioning to retain our no-network inference requirement.

The current working Android demo/converter location is [litert-samples/samples/litert/speech_recognition](https://github.com/google-ai-edge/litert-samples/tree/f1e415713f0354920c263c66e65ebd4b138c7e67/samples/litert/speech_recognition). Older `speech_recognition` or `LiteRT/litert/samples/asr/AndroidApp` links are stale; the latter location currently has a README only.

## Alternatives that change scope

- Tensor G5 NPU files really exist, as listed above. Their smaller size and filename do not establish unchanged weights or FP32 internal computation. NPU compatibility/numerical correctness is separate from GPU; ADR-016 already found an unrelated NPU smoke mismatch. Treat as new investigation, not shortcut approval.
- [Arm's Parakeet v3 INT8 LiteRT package](https://huggingface.co/Arm/parakeet-tdt-0-6b-v3-int8-litert/tree/main) lists a 649 MB cached encoder and separate decoder/joint/featurizer. Its [card](https://huggingface.co/Arm/parakeet-tdt-0-6b-v3-int8-litert) describes CPU/XNNPACK/KleidiAI benchmarks, not Pixel GPU proof. Different precision/cache contract: useful separate product candidate, not unchanged FP32 encoder.
- [spybyscript's release](https://huggingface.co/spybyscript/parakeet-tdt-litert) contains a fixed 15-second INT8 encoder with FP16 decoder/joint; [supyoshi's release](https://huggingface.co/supyoshi/parakeet-tdt-0.6b-v3-litert) uses split INT8-weight CPU models. Neither is a strict FP32 GPU shortcut. Their publisher-stated languages derive from the same base model; our German/English quality remains untested.

## Conversion-free runtime possibility

[ONNX Runtime's native WebGPU docs](https://onnxruntime.ai/docs/execution-providers/WebGPU-ExecutionProvider.html) list Android via Vulkan and plugin/source-build routes. This could preserve existing ONNX model bytes and avoid LiteRT conversion. The [current official GitHub release listing](https://api.github.com/repos/microsoft/onnxruntime/releases/latest), v1.31.0 at inspection, supplies desktop assets; it does not supply an Android WebGPU AAR. General NuGet/Python package availability is not evidence of an Android ARM64 compatible plugin. No verified plug-and-play Android WebGPU build was found in this bounded search. Existing Sherpa integration, unsupported operations/fallback and precision remain work.

## Recommendation

Next bounded step: inspect the ready generic FP32 model's `encode` signature and its source recipe against our exact FP32 encoder. Determine whether true lengths and variable chunk lengths can be preserved without changing behavior. If yes, validate output tensors before building the small encoder adapter. If no, decide explicitly between adapting the supported PyTorch conversion recipe to our contract or commissioning a separate comparison of the ready five-second pipeline. Do not start by repairing onnx2tf until this reusable route is assessed. Do not promise phone speed from another device's measurements.
