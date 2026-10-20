# Pixel GPU runtime options

> Follow-up: an official Android WebGPU runtime is available in ONNX Runtime
> 1.31.0. Earlier package-availability uncertainty below is superseded by
> [the package and model compatibility check](onnx-webgpu-android-availability.md).
> Availability does not establish encoder compatibility or Pixel execution.


2026-10-20. Primary-source desk research only; no phone, inference, downloads or installs.

## Established project facts

[ADR-016](../adrs/016-accelerator-access.md) records actual LiteRT 2.3/OpenGL GPU execution on the Pixel/GrapheneOS with public segmentation models. FP32 passed the frozen checks; default precision failed. This proves access for those probes, not this encoder.

[ADR-019](../adrs/019-encoder-conversion.md) records Parakeet's 2,435,420,160-byte FP32 weights, dynamic time input, INT64 length input, 120 LayerNormalization nodes and dynamic Shape/Range/Expand/Where masks. onnx2tf's builtin lowering report is not proof that LiteRT's GPU backend accepts every lowered operation. No converted numerical result exists.

## Practical candidates

| Route | Established | Still requires proof |
|---|---|---|
| ONNX → compatible LiteRT conversion → LiteRT/OpenGL | Existing phone GPU access and FP32 probe acceptance; least hardware uncertainty (ADR-016). | Large-weight packaging, dynamic lengths/masks, CPU parity, actual speech GPU operator coverage, memory and speed. |
| Original ONNX → ONNX Runtime native WebGPU EP → Dawn/Vulkan | Current official docs explicitly list Android/Vulkan, native C++ integration and source-build flags. This can avoid the LiteRT file conversion entirely. | Matching Android runtime/build integration, installed-version compatibility, Pixel driver acceptance, all relevant operators/shapes/INT64 and FP32 behavior; no project proof. |
| ONNX → pnnx → ncnn/Vulkan | Official ONNX conversion route, Android Vulkan support and explicit FP32 controls. Separate graph/weight files avoid this particular monolithic FlatBuffer format. | Full conversion and GPU coverage for this encoder, >2 GiB loading/allocation, dynamic masks/lengths, Pixel driver and correctness. |

Sources: [LiteRT Android GPU](https://developers.google.com/edge/litert/android/gpu), [native ORT WebGPU EP](https://onnxruntime.ai/docs/execution-providers/WebGPU-ExecutionProvider.html), [pnnx ONNX conversion](https://github.com/Tencent/ncnn/wiki/use-ncnn-with-pytorch-or-onnx), [ncnn Vulkan/FP32](https://github.com/Tencent/ncnn/wiki/vulkan-notes), [ncnn format](https://github.com/Tencent/ncnn/wiki/param-and-model-file-structure).

### ONNX Runtime: avoid an outdated dismissal

ORT's current native WebGPU documentation describes Dawn dispatch to Android Vulkan. It offers native plugin/library registration and `--use_webgpu` source builds. It also documents INT64 device-feature dependence and that CPU fallback/shape changes prevent graph capture. Thus “ORT has no Android GPU path except NNAPI” is incorrect against current docs. This is a candidate, not proof of an Android package or compatibility with this checkout's pinned ORT. No source build was performed. [Official WebGPU EP docs](https://onnxruntime.ai/docs/execution-providers/WebGPU-ExecutionProvider.html).

The older NNAPI EP exists, but Android deprecated NNAPI in Android 15 and warns future devices may primarily use CPU. NNAPI presence does not establish a Pixel GPU driver. ORT's CPU-disabled NNAPI flag prevents NNAPI CPU devices but can still leave operations on ORT CPU kernels. XNNPACK is CPU acceleration. QNN Android instructions require supported Qualcomm Snapdragon hardware; that route is not established for this Tensor phone. [Android NNAPI](https://developer.android.com/ndk/guides/neuralnetworks), [ORT NNAPI flags](https://onnxruntime.ai/docs/execution-providers/NNAPI-ExecutionProvider.html), [ORT XNNPACK](https://onnxruntime.ai/docs/execution-providers/Xnnpack-ExecutionProvider.html), [ORT Android build](https://onnxruntime.ai/docs/build/android.html).

### ncnn: plausible alternative, not a drop-in promise

pnnx documents ONNX conversion, alternative input shapes for dynamic dimensions, and LayerNormalization/MatMul/Softmax export. Its current ONNX table does not list Shape/Range/Expand/Where; this is an unresolved coverage question, not definitive proof they cannot be rewritten. Default weight export is FP16; use `fp16=0` for the FP32 experiment. [pnnx README](https://github.com/Tencent/ncnn/blob/master/tools/pnnx/README.md).

ncnn has an actual Vulkan LayerNorm implementation. Disable its lower-precision options for FP32. Layers advertise Vulkan support individually; GPU mode alone does not demonstrate the entire encoder used GPU. Its published tested-device table does not establish PowerVR Pixel compatibility. [LayerNorm source](https://github.com/Tencent/ncnn/blob/master/src/layer/vulkan/layernorm_vulkan.cpp), [Vulkan options/platform table](https://github.com/Tencent/ncnn/wiki/vulkan-notes), [layer capability behavior](https://github.com/Tencent/ncnn/wiki/layer-support-behavior).

## Interpretation

The converter is a preparation-time tool. The runtime executes the prepared model on the phone. They must agree on file representation, operations, precision and input/output contracts. A successful converter cannot supply missing GPU kernels or remove driver/memory limits. Runtime GPU compilation can still occur during setup.

Recommended bounded next decision: first examine a compatible LiteRT large-weight export because its phone access is established; separately audit native ORT WebGPU against the exact ONNX operation/shape inventory because it may avoid conversion. Keep ncnn as a conversion/runtime fallback. Before choosing any route, require synthetic CPU parity; later, with a connected phone and authorization, require observed GPU assignment, no unintended CPU fallback, correctness and transfer-inclusive latency. Preserve the original decoder/joiner/search on CPU. These are proposed checks, not executed results.
