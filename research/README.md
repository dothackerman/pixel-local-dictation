# Encoder acceleration research

2026-10-20. Source and package inspection unless a note explicitly records execution.
Specifications and implementation tickets live in GitHub Issues, not this directory.

## Current findings

- The official Android package `com.microsoft.onnxruntime:onnxruntime-android:1.31.0`
  contains native WebGPU support for ARM64. It is a candidate for the unchanged
  FP32 Parakeet encoder; no Pixel encoder execution or speed result is established.
- Our Sherpa integration uses a different runtime and cannot select WebGPU today.
  Runtime integration and separate encoder/decoder session options are required.
- Current INT8 Parakeet/Cohere exports have integer-operation gaps in the inspected
  native WebGPU registries. Package availability does not establish model support.
- The conventional FlatBuffer container has a 2 GiB ceiling. LiteRT supports weights
  outside that container, including appended data in the same larger model file.
  Our pinned converter does not use that route successfully. This is separate from
  phone/GPU memory. [Executed conversion diagnosis](../adrs/019-encoder-conversion.md).
- A ready FP32 Parakeet v3 LiteRT model exists. Its fixed five-second input and
  overlapping-window pipeline differ from our dynamic, pause-based pipeline.
  Sharing a base checkpoint does not establish identical recognition behavior.

The selected next investigation is unchanged ONNX FP32 Parakeet on WebGPU.
First isolate the CPU runtime upgrade from moving the encoder to GPU. This is a
published spec decision, not authorization to build or run experiments.

## Evidence and options

| Note | Purpose |
|---|---|
| [Android ONNX WebGPU availability](onnx-webgpu-android-availability.md) | Strongest current package/model assessment, actual package hashes, native integration gaps |
| [GPU preparation best practices](encoder-gpu-best-practices.md) | Correctness gates and reuse-before-custom-conversion advice; later ONNX package finding changes route priority |
| [Ready-made options](ready-made-encoder-gpu-options.md) | Verified published artifacts, interfaces and pipeline differences |
| [LiteRT size limits](litert-size-limits.md) | Container limit versus large-weight model representation |
| [Conversion alternatives](encoder-conversion-options.md) | Conditional writer, split and TensorFlow routes; no accepted conversion |
| [Android GPU runtime options](pixel-gpu-runtime-options.md) | Earlier runtime survey; package-availability uncertainty superseded by the focused ONNX check |

Some recommendations reflect the evidence available when written. In particular,
the first search missed WebGPU inside the standard Android AAR. The focused ONNX
availability note supersedes that uncertainty. Source support, a downloadable
binary, successful loading, correct GPU output and measured benefit are distinct
levels of evidence. Research notes do not establish the later levels by implication.

## Terms

| Term | Meaning here |
|---|---|
| Trained model/checkpoint | Calculations and learned numbers from training |
| Exported model | Representation prepared for a runtime; may constrain lengths or precision |
| Converter | Development-computer tool that creates an exported model |
| Runtime | Software that executes the model on the phone |
| Execution provider | Runtime component that assigns calculations to CPU or GPU |
| Recognition pipeline | Sound preparation, chunking, word search and combining text |
| Take/session | One dictation recording through Stop or cancellation |

Full issue requirements, dependency changes and acceptance remain in
[spec #1](https://github.com/dothackerman/pixel-local-dictation/issues/1).
Private measurements, recordings, transcripts and credentials stay out of these notes.
