# Non-ONNX-WebGPU alternatives (surveyed, not chosen)

2026-10-20. Public source/metadata only; no downloads, conversion, inference or
phone work. [ADR-020](../adrs/020-onnx-webgpu-investigation.md) chose unchanged
ONNX on WebGPU; these remain fallbacks if that route fails. Conversion diagnosis
and the pinned converter's failure: [ADR-019](../adrs/019-encoder-conversion.md).
Established phone GPU access (LiteRT 2.3/OpenGL, FP32 only):
[ADR-016](../adrs/016-accelerator-access.md). None below is validated on this encoder.

## LiteRT models above 2 GiB

The 2 GiB ceiling is the conventional FlatBuffer builder's, not LiteRT's.
C++ max offset is 2 GB−1 ([`base.h#L341`](https://github.com/google/flatbuffers/blob/b8431fbcd7a5c71817f314e18b332c0648554efa/include/flatbuffers/base.h#L341));
the [Python builder](https://github.com/google/flatbuffers/blob/b8431fbcd7a5c71817f314e18b332c0648554efa/python/flatbuffers/builder.py)
caps at `2**31`; raising that constant is not a fix. LiteRT's `Buffer` has
`offset`/`size: ulong` pointing to weight bytes appended in the same file
([schema.fbs#L1688](https://github.com/google-ai-edge/LiteRT/blob/5b1e29a7ac6809bef62491e3b83b29c2c518ac14/tflite/converter/schema/schema.fbs#L1688));
`ExternalBuffer` (separate file) is a distinct mechanism. The upstream MLIR
exporter sets `use_buffer_offset` near the limit and retries with it
([#L4221](https://github.com/google-ai-edge/LiteRT/blob/5b1e29a7ac6809bef62491e3b83b29c2c518ac14/litert/compiler/mlir/flatbuffer_export.cc#L4221),
[#L4470](https://github.com/google-ai-edge/LiteRT/blob/5b1e29a7ac6809bef62491e3b83b29c2c518ac14/litert/compiler/mlir/flatbuffer_export.cc#L4470)).
Interpreter loading reads inline data or base+offset with 64-bit size
([current](https://github.com/google-ai-edge/LiteRT/blob/5b1e29a7ac6809bef62491e3b83b29c2c518ac14/tflite/core/interpreter_builder.cc#L810),
[host v2.1.2#L667](https://github.com/google-ai-edge/LiteRT/blob/7cd848d3b20e2de517d70f1ac50f8d9a2e2d9dcb/tflite/core/interpreter_builder.cc#L667),
[Android v2.3.0#L757](https://github.com/google-ai-edge/LiteRT/blob/4352e261d3f1b3cda00345db5dbd75859d3bf0d3/tflite/core/interpreter_builder.cc#L757)).
Source-level support only: packaged binaries, Android >2 GiB file-path loading
and GPU (`CompiledModel`) acceptance are unproved. TensorFlow's
[`_experimental_use_buffer_offset`](https://github.com/tensorflow/tensorflow/blob/master/tensorflow/lite/python/lite.py)
belongs to the TF converter, which the pinned direct onnx2tf backend does not call.

## Conversion routes (if conversion is resumed)

1. **Pinned ONNX lowering + appended-weight writer.** onnx2tf 2.6.9's
   [writer](https://github.com/PINTO0309/onnx2tf/blob/2.6.9/onnx2tf/tflite_builder/tensor_buffer_builder.py)
   (lines 126–136) puts all bytes in `BufferT.data`; the
   [planner](https://github.com/PINTO0309/onnx2tf/blob/2.6.9/onnx2tf/tflite_builder/split_planner.py)
   serializes the full model too. Engineering proposal, not an upstream option.
   Prefer one graph with appended weights over splitting (splitting adds partition
   orchestration at runtime). Regression fixture: offsets, alignment, range
   checks, loading with the exact Android loader, synthetic file crossing 2 GiB.
   Seek upstream acceptance (posting needs separate authorization). 2.6.9 was
   latest at inspection; "update onnx2tf" is not an established fix.
2. **Repair the split route.** Must remove both whole-model serializations in
   ADR-019 ([migration guide](https://github.com/PINTO0309/onnx2tf/blob/main/FLATBUFFER_DIRECT_MIGRATION_GUIDE.md),
   [exporter](https://github.com/PINTO0309/onnx2tf/blob/2.6.9/onnx2tf/tflite_builder/__init__.py)).
3. **ONNX → TF → LiteRT.** Pinned [`onnx2tf.py`](https://github.com/PINTO0309/onnx2tf/blob/2.6.9/onnx2tf/onnx2tf.py)
   has no `buffer_offset` reference: SavedModel at 7035 without SaveOptions,
   `from_keras_model` 7154 / `from_concrete_functions` 7156, BUILTINS +
   SELECT_TF_OPS, convert 7166. Needs a patch; SELECT_TF_OPS is not GPU
   compatibility. Separate 2 GB protobuf ceiling for SavedModel/GraphDef
   ([guide](https://www.tensorflow.org/guide/saved_model),
   [SaveOptions](https://www.tensorflow.org/api_docs/python/tf/saved_model/SaveOptions) `experimental_image_format` highly experimental).
4. **LiteRT Torch from the NeMo checkpoint** ([walkthrough](https://github.com/google-ai-edge/litert-torch/blob/main/docs/pytorch_converter/README.md),
   `find_culprits`): `torch.export` module, not ONNX import; adds an equivalence
   obligation to the frozen ONNX reference. Google's
   [GPU model table](https://developers.google.com/edge/litert/next/gpu) reports a
   torchaudio Conformer only partly delegated; "all builtins" ≠ GPU-runnable.

## Ready-made Parakeet v3 artifacts

[litert-community/parakeet-tdt-0.6b-v3](https://huggingface.co/litert-community/parakeet-tdt-0.6b-v3),
revision `3ce205aa928f443f423214195cfe83ebe4802826` (publisher LFS hashes, not
locally verified; NeMo → PyTorch → litert-torch; CC-BY-4.0 weights, Apache-2.0 code):

| Artifact | Bytes | SHA256 |
|---|---:|---|
| `parakeet_tdt_0.6b_v3_5s_f32.tflite` | 2,422,415,168 | `ffa0d4cbb94589b78507bcac9d8b5e5b78d84f8f9d86dc8c397da1f433777a85` |
| `parakeet_tdt_0.6b_v3_5s_f32_stateful.tflite` | 2,421,888,688 | `8c1f06a9e1682ab2f4d95f65d413055c8f83d64a7b3daab3185b5e0e91e9f4ab` |
| `parakeet_tdt_0.6b_v3_5s_i8_stateful.litertlm` | 614,588,752 | `66c5a74cf6c2c1d2b990901f6cece5bb53e3214dbceafa0989900a6f0eeb3504` |
| `parakeet_tdt_0.6b_v3_5s_f32_Google_Tensor_G5.tflite` | 1,263,633,824 | `a2b49b05cad12ae1e2bf3efacc2befd6005d2b3dc59b4bd42b6de4c24e7f480b` |

Not a drop-in: the [conversion wrapper](https://github.com/google-ai-edge/litert-samples/blob/f1e415713f0354920c263c66e65ebd4b138c7e67/samples/litert/speech_recognition/convert/parakeet_tdt.py)
calls the encoder with `length=None`, returns hidden states only, replaces
`MaskedConvSequential`/normalization; the [driver](https://github.com/google-ai-edge/litert-samples/blob/f1e415713f0354920c263c66e65ebd4b138c7e67/samples/litert/speech_recognition/convert/convert_to_tflite.py)
passes no `dynamic_shapes` (`input_sec`, `quant=none|drq` configurable);
[metadata](https://github.com/google-ai-edge/litert-samples/blob/f1e415713f0354920c263c66e65ebd4b138c7e67/samples/litert/speech_recognition/AndroidApp/app/src/main/assets/model_metadata.json)
fixes 500 mel frames / 5 s → 63×1,024. The [Android runner](https://github.com/google-ai-edge/litert-samples/blob/f1e415713f0354920c263c66e65ebd4b138c7e67/samples/litert/speech_recognition/AndroidApp/app/src/main/java/com/google/ai/edge/examples/asr/LiteRtRunner.kt)
pads/truncates, runs both signatures under one accelerator with CPU fallback.
Publisher Galaxy S24 Ultra numbers (FP32 GPU slower than INT8 CPU) use its
overlapping-window pipeline; no Pixel/GrapheneOS evidence. Sharing a checkpoint
does not establish identical outputs.

Others, all changing scope: [LiteRT-LM Omni](https://github.com/google-ai-edge/LiteRT-LM/blob/2ef6baebc2890e4f38bf2c69787ebc86882b2c5a/omni/README.md)
(experimental; [parakeet_tdt.cc](https://github.com/google-ai-edge/LiteRT-LM/blob/2ef6baebc2890e4f38bf2c69787ebc86882b2c5a/omni/speech2text/parakeet_tdt.cc)
one compiled model for encode+decode, auto-downloads must be disabled); Tensor G5
file is NPU, `f32` name ≠ FP32 arithmetic (ADR-016 NPU mismatch);
[Arm INT8](https://huggingface.co/Arm/parakeet-tdt-0-6b-v3-int8-litert) (CPU
benchmarks); [spybyscript](https://huggingface.co/spybyscript/parakeet-tdt-litert)
15 s INT8; [supyoshi](https://huggingface.co/supyoshi/parakeet-tdt-0.6b-v3-litert) split INT8.

## Other runtimes

- **ncnn/Vulkan via pnnx** ([ONNX conversion](https://github.com/Tencent/ncnn/wiki/use-ncnn-with-pytorch-or-onnx),
  [pnnx README](https://github.com/Tencent/ncnn/blob/master/tools/pnnx/README.md)):
  separate graph/weight files avoid the FlatBuffer limit; export FP16 by default
  (`fp16=0`); Shape/Range/Expand/Where absent from its ONNX table; Vulkan
  [LayerNorm](https://github.com/Tencent/ncnn/blob/master/src/layer/vulkan/layernorm_vulkan.cpp)
  exists; [per-layer Vulkan support](https://github.com/Tencent/ncnn/wiki/layer-support-behavior),
  [lower-precision options](https://github.com/Tencent/ncnn/wiki/vulkan-notes) must be disabled; no PowerVR Pixel in tested devices.
- **ORT NNAPI/XNNPACK/QNN**: NNAPI deprecated in Android 15; XNNPACK is CPU; QNN needs Snapdragon.
