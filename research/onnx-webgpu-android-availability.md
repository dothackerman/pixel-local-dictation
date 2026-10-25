# Native Android ONNX Runtime WebGPU availability

2026-10-20. Package ZIP/ELF inspection and source reading; model graphs inspected
with `onnx.load(..., load_external_data=False)`. Nothing executed, installed or
run on the phone. Decision: [ADR-020](../adrs/020-onnx-webgpu-investigation.md).

## Package

The standard `com.microsoft.onnxruntime:onnxruntime-android:1.31.0` AAR
([Maven](https://repo.maven.apache.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/1.31.0/onnxruntime-android-1.31.0.aar),
[POM](https://repo.maven.apache.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/1.31.0/onnxruntime-android-1.31.0.pom), no dependencies)
contains the native WebGPU execution provider (Dawn over Vulkan; no browser).
No source build needed. Separate plugin packages (NuGet/PyPI
`Microsoft.ML.OnnxRuntime.EP.WebGpu` / `onnxruntime-ep-webgpu` 0.4.0) ship no
Android binary; irrelevant because the AAR has the built-in provider.

| Item | Verified |
|---|---|
| AAR bytes / SHA256 | 55,138,854 / `72683787a80d0608a3121b967d8e1239aef48286826661c953211626690540a7` |
| ABIs | `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`; each `libonnxruntime.so` + `libonnxruntime4j_jni.so` |
| ARM64 core bytes / SHA256 | 34,208,680 / `8005a741e57117b4d7b476417706c0e9af13cf1b89db47e60f0cbc56e1af91e0` |
| ARM64 `NEEDED` | `libdl.so`, `liblog.so`, `libandroid.so`, `libm.so`, `libc.so` |

Three independent checks of the compiled provider (a bare `WebGpuExecutionProvider`
string is not enough; it appears in builds without the provider):

1. [Release AAR build settings](https://github.com/microsoft/onnxruntime/blob/v1.31.0/tools/ci_build/github/android/default_full_aar_build_settings.json): `--use_webgpu`, four ABIs, minSdk 24, targetSdk 34.
2. ARM64 library contains `WebGpuExecutionProvider::OnRunStart`, WebGPU buffer-management and Dawn Vulkan Android hardware-buffer implementation strings.
3. [`provider_registration.cc#L270`](https://github.com/microsoft/onnxruntime/blob/v1.31.0/onnxruntime/core/session/provider_registration.cc#L270) wires `"WebGPU"` to the built-in factory under `USE_WEBGPU`; Java [`addWebGPU(Map)`](https://github.com/microsoft/onnxruntime/blob/v1.31.0/java/src/main/java/ai/onnxruntime/OrtSession.java#L1633), C++ [`AppendExecutionProvider("WebGPU", ...)`](https://github.com/microsoft/onnxruntime/blob/v1.31.0/include/onnxruntime/core/session/onnxruntime_cxx_inline.h#L1796).

## Why the app cannot switch a setting

- Sherpa 1.13.8 AAR bundles ORT 1.28.2 (verified against the publisher binary by
  `tools/native-role-profiler/build.py`); ARM64 core 22,249,560 bytes, no WebGPU/Dawn
  markers. Publisher's [Android workflow](https://github.com/csukuangfj/onnxruntime-libs/blob/master/.github/workflows/android-shared.yaml)
  enables NNAPI, not WebGPU (mutable; corroboration only).
- Sherpa's [provider parser](https://github.com/k2-fsa/sherpa-onnx/blob/11afbd009a7f8c08f4bcf2fc1b265d0df4670fbf/sherpa-onnx/csrc/provider.cc)
  has no `webgpu` case (unknown → CPU). Its [Parakeet transducer](https://github.com/k2-fsa/sherpa-onnx/blob/11afbd009a7f8c08f4bcf2fc1b265d0df4670fbf/sherpa-onnx/csrc/offline-transducer-model.cc#L95)
  and [Cohere model](https://github.com/k2-fsa/sherpa-onnx/blob/11afbd009a7f8c08f4bcf2fc1b265d0df4670fbf/sherpa-onnx/csrc/offline-cohere-transcribe-model.cc#L43)
  share runtime session options across model parts, so an encoder-only GPU split
  needs separate encoder options; packaging two `libonnxruntime.so` is not a solution.

## Encoder kernel coverage (ORT 1.31.0 source)

[Core registry](https://github.com/microsoft/onnxruntime/blob/v1.31.0/onnxruntime/core/providers/webgpu/webgpu_execution_provider.cc)
and [contrib registry](https://github.com/microsoft/onnxruntime/blob/v1.31.0/onnxruntime/contrib_ops/webgpu/webgpu_contrib_kernels.cc):

| Local encoder | Nodes without any WebGPU kernel |
|---|---|
| `models/parakeet-v3-fp32/encoder.onnx` (FP32, opset 17, 612 external initializers) | None found by op family; per-node placement unresolved until a [placement list](ort-webgpu-node-placement.md) exists |
| `models/parakeet-v3-matmul8/encoder.int8.onnx` | 217 `MatMulInteger`, 146 `DynamicQuantizeLinear` |
| `models/sherpa-onnx-cohere-transcribe-14-lang-int8-2026-04-01/encoder.int8.onnx` ([CohereLabs/cohere-transcribe-03-2026](https://huggingface.co/CohereLabs/cohere-transcribe-03-2026)) | 450 `MatMulInteger`, 292 `DynamicQuantizeLinear` |

`MatMulIntegerToFloat` and `DynamicQuantizeMatMul` (the
[fusion](https://github.com/microsoft/onnxruntime/blob/v1.31.0/onnxruntime/core/optimizer/dynamic_quantize_matmul_fusion.cc)
output) are also unregistered; `MatMulNBits` is a different representation.
So the INT8 exports would leave their integer tensor math on CPU. Int64 kernels
depend on `enableInt64`; dynamic shapes disable graph capture.
