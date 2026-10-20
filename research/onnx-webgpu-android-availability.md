# Native Android ONNX Runtime WebGPU availability

2026-10-20. Focused primary-source research; model graph metadata inspected locally with `onnx.load(..., load_external_data=False)`. No model download, inference, build, installation or phone access. Official runtime packages were downloaded into memory for ZIP inspection; one Android runtime library was retained in `/tmp` for static ELF inspection. No runtime library executed.

## Answer

**Yes: a downloadable official Android ARM64 runtime with native WebGPU exists.** The standard `com.microsoft.onnxruntime:onnxruntime-android:1.31.0` AAR contains it. A source build of ONNX Runtime is therefore not the necessary first step. This corrects the earlier bounded search in `ready-made-encoder-gpu-options.md`, which inspected GitHub release assets and separate plugin packages but missed the built-in provider in the Android AAR.

This establishes runtime availability, **not that either exact encoder runs fully/correctly/quickly on the Pixel GPU**. Parakeet FP32 is the better first candidate. Our quantized Parakeet and Cohere graphs use integer matrix operations for which the inspected native WebGPU kernel registries provide no execution route.

## Correct model names and local variants

| Base model | Exact local encoder | GPU assessment |
|---|---|---|
| [NVIDIA Parakeet-TDT-0.6B-v3](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3) | `models/parakeet-v3-fp32/encoder.onnx`, FP32, opset17; 612 external FP32 initializers | Plausible conversion-free test. Dynamic lengths, INT64 mask/shape operations, actual node assignment, numerical correctness and memory remain unresolved. |
| Same Parakeet model, provisional product variant | `models/parakeet-v3-matmul8/encoder.int8.onnx`, selective MatMul INT8, opset17 | 217 `MatMulInteger` and 146 `DynamicQuantizeLinear` nodes. No registered native WebGPU kernels for these or their usual fused replacements found in ORT1.31.0. GPU acceleration of all encoder calculations is not established. |
| [Cohere Transcribe, `CohereLabs/cohere-transcribe-03-2026`](https://huggingface.co/CohereLabs/cohere-transcribe-03-2026) | `models/sherpa-onnx-cohere-transcribe-14-lang-int8-2026-04-01/encoder.int8.onnx`, INT8, opset17 | Same substantive quantized-operation gap: 450 `MatMulInteger`, 292 `DynamicQuantizeLinear`; cannot assume that selecting WebGPU moves its main work to GPU. |

Cohere's publisher identifies the model as a 2B-parameter Conformer encoder plus Transformer decoder, supporting 14 languages including English/German. The dated Sherpa package name identifies our ONNX export, not a separate Cohere base model. The GitHub-issue GPU experiment concerns unchanged FP32 Parakeet; it is distinct from the product's selective-INT8 variant. See local README and ADR-019.

## Published Android package: verified beyond provider-name strings

[Official Maven metadata](https://repo.maven.apache.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/maven-metadata.xml) lists1.31.0 as latest/release. The [actual AAR](https://repo.maven.apache.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/1.31.0/onnxruntime-android-1.31.0.aar) was read and its ZIP entries inspected.

| Item | Verified identity / contents |
|---|---|
| Coordinates | `com.microsoft.onnxruntime:onnxruntime-android:1.31.0` |
| AAR bytes / SHA256 | 55,138,854 / `72683787a80d0608a3121b967d8e1239aef48286826661c953211626690540a7` |
| Native ABI directories | `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64` |
| Each ABI's native libraries | `libonnxruntime.so`, `libonnxruntime4j_jni.so` |
| ARM64 core bytes / SHA256 | 34,208,680 / `8005a741e57117b4d7b476417706c0e9af13cf1b89db47e60f0cbc56e1af91e0` |
| ARM64 ELF `NEEDED` dependencies | Android system `libdl.so`, `liblog.so`, `libandroid.so`, `libm.so`, `libc.so` |
| POM dependency entries | None in the inspected [POM](https://repo.maven.apache.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/1.31.0/onnxruntime-android-1.31.0.pom) |

Three independent checks establish the compiled provider:

1. The [release-tag AAR build settings](https://github.com/microsoft/onnxruntime/blob/v1.31.0/tools/ci_build/github/android/default_full_aar_build_settings.json) explicitly include `--use_webgpu`, all four ABIs, minimum SDK24 and target SDK34.
2. The actual ARM64 library contains implementation-specific native WebGPU source/error strings, `WebGpuExecutionProvider::OnRunStart`, buffer-management implementation and Dawn Vulkan Android hardware-buffer implementation. This is substantially stronger evidence than a generic `WebGpuExecutionProvider` name, which can appear even in builds that lack that provider.
3. The [release provider registration](https://github.com/microsoft/onnxruntime/blob/v1.31.0/onnxruntime/core/session/provider_registration.cc#L270) connects the generic `WebGPU` session API to its built-in factory when `USE_WEBGPU` is enabled. The release [Java API](https://github.com/microsoft/onnxruntime/blob/v1.31.0/java/src/main/java/ai/onnxruntime/OrtSession.java#L1633) exposes `addWebGPU(Map<String,String>)`; the [C++ API](https://github.com/microsoft/onnxruntime/blob/v1.31.0/include/onnxruntime/core/session/onnxruntime_cxx_inline.h#L1796) exposes `SessionOptions::AppendExecutionProvider("WebGPU", ...)`.

Android native WebGPU uses Dawn over Vulkan. It does not require running the app inside a browser. [Official native EP documentation](https://onnxruntime.ai/docs/execution-providers/WebGPU-ExecutionProvider.html).

Separate plugin packages are a different distribution route. Inspected NuGet `Microsoft.ML.OnnxRuntime.EP.WebGpu`0.4.0 has Windows x64/ARM64, Linux x64/ARM64 and macOS ARM64 binaries, no Android directory. PyPI0.4.0 likewise has no Android wheel. Linux ARM64 is not the Android ABI. These omissions **do not mean Android lacks WebGPU**, because the AAR includes the built-in provider. [NuGet package](https://api.nuget.org/v3-flatcontainer/microsoft.ml.onnxruntime.ep.webgpu/0.4.0/microsoft.ml.onnxruntime.ep.webgpu.0.4.0.nupkg), [PyPI metadata](https://pypi.org/pypi/onnxruntime-ep-webgpu/json).

## Why our app cannot just switch a setting today

Our Sherpa1.13.8 AAR bundles a different ONNX Runtime1.28.2 binary, verified against the pinned publisher binary by `tools/native-role-profiler/build.py`. Its ARM64 library is22,249,560 bytes and lacks the WebGPU/Dawn implementation markers above. The publisher's current [Android build workflow](https://github.com/csukuangfj/onnxruntime-libs/blob/master/.github/workflows/android-shared.yaml) enables NNAPI but does not enable WebGPU. That mutable workflow corroborates static inspection; it is not immutable provenance for the earlier release. No actual provider-enumeration call was executed here.

More decisively, our exact [Sherpa provider parser](https://github.com/k2-fsa/sherpa-onnx/blob/11afbd009a7f8c08f4bcf2fc1b265d0df4670fbf/sherpa-onnx/csrc/provider.cc) has no `webgpu` case and falls back to CPU for unknown strings. Its [Parakeet transducer](https://github.com/k2-fsa/sherpa-onnx/blob/11afbd009a7f8c08f4bcf2fc1b265d0df4670fbf/sherpa-onnx/csrc/offline-transducer-model.cc#L95) and [Cohere model](https://github.com/k2-fsa/sherpa-onnx/blob/11afbd009a7f8c08f4bcf2fc1b265d0df4670fbf/sherpa-onnx/csrc/offline-cohere-transcribe-model.cc#L43) share session options across their model parts. Changing a global provider setting would not implement the required encoder-only GPU split.

Minimum work: integrate the available GPU-enabled runtime with our native wrapper, construct separate encoder session options selecting WebGPU, retain CPU options for decoder/joiner, preserve file-path loading for external ONNX weights, and report actual provider assignment/failures. A separate encoder-only adapter is another possible seam. Rebuilding the small wrapper may be necessary; rebuilding ONNX Runtime itself is not the starting requirement. Runtime/Sherpa ABI and API compatibility must be checked; blindly packaging two `libonnxruntime.so` files is not a solution. No wrapper/runtime replacement or build was performed.

## Exact encoder compatibility limits

The [ORT1.31.0 native core registry](https://github.com/microsoft/onnxruntime/blob/v1.31.0/onnxruntime/core/providers/webgpu/webgpu_execution_provider.cc) includes FP32 matrix multiplication, convolution, LayerNormalization, masking/data movement and other operation families used by Parakeet. That is encouraging but not exhaustive model validation: per-node types/shapes/attributes can still prevent GPU assignment. INT64 kernels are conditional on `enableInt64`; dynamic shapes prevent use of the static-shape graph-capture optimization. Do not assume the current docs' descriptions of native INT64/device features apply identically to every released version; the exact Pixel/1.31.0 behavior remains to be checked.

The same core registry and [contributed kernel registry](https://github.com/microsoft/onnxruntime/blob/v1.31.0/onnxruntime/contrib_ops/webgpu/webgpu_contrib_kernels.cc) contain no `MatMulInteger`, `DynamicQuantizeLinear`, `MatMulIntegerToFloat` or `DynamicQuantizeMatMul` registrations. We checked common optimizer alternatives: [DynamicQuantizeMatMulFusion](https://github.com/microsoft/onnxruntime/blob/v1.31.0/onnxruntime/core/optimizer/dynamic_quantize_matmul_fusion.cc) can produce `DynamicQuantizeMatMul`, but that operator also has no native WebGPU registration in the inspected tables. WebGPU's supported `MatMulNBits` is a different representation and does not establish support for our INT8 exports.

Inference: a mixed CPU/GPU session might load the quantized models and accelerate some supported operations, while important integer matrix work remains on CPU. This is not evidence of an efficient encoder GPU move. It also is not proof that Cohere's architecture can never run on GPU: another precision/export/kernel route could work, but changes the current plug-in premise. GPU assignment, tensor parity and transfer-inclusive speed were not measured for either model.

## Recommended bounded next test

Use the **existing FP32 Parakeet ONNX encoder plus the downloadable Android WebGPU runtime** as the first conversion-free experiment. Prepare the wrapper/adapter and synthetic checks on the host; actual Android GPU checks wait for a connected phone and task authorization. Keep decoder/joiner on CPU. Verify actual node assignment and numerical results before interpreting speed. For Cohere, first resolve the current INT8 kernel mismatch; downloading the same runtime alone does not resolve it.
