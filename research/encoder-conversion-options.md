# Encoder conversion alternatives

> Scope: these remain alternatives if conversion is pursued. The proposed first
> investigation now uses the existing ONNX encoder with the downloadable Android
> WebGPU runtime, avoiding conversion. See the [runtime availability check](onnx-webgpu-android-availability.md).

2026-10-20. Primary-source investigation only; no conversion, inference, package installation or phone work.

Scope: existing FP32 Parakeet encoder, dynamic `[batch,128,time]` plus INT64 true lengths. Existing diagnosis: [ADR-019](../adrs/019-encoder-conversion.md). None of the alternatives below is validated on this encoder.

## Finding

Changing converter can avoid a particular writer's 2 GiB ceiling. It cannot alone establish GPU support. The output must preserve the dynamic shape/length contract, pass tensor comparison and contain operations the chosen Android GPU backend supports.

The TensorFlow converter has an experimental `_experimental_use_buffer_offset` switch. Its source describes `use_buffer_offset` as placing constants/custom binaries outside the FlatBuffer structure. TensorFlow's model utility source explicitly handles offsets for models above 2 GB. This establishes an existing large-model storage mechanism, not an accepted conversion of our encoder. Sources: [converter implementation](https://github.com/tensorflow/tensorflow/blob/master/tensorflow/lite/python/convert.py), [Python switch](https://github.com/tensorflow/tensorflow/blob/master/tensorflow/lite/python/lite.py), [large-model model utilities](https://github.com/tensorflow/tensorflow/blob/master/tensorflow/lite/tools/flatbuffer_utils.py).

## Plausible routes, in priority order

1. **Keep current ONNX lowering; add external-buffer serialization.** Existing attempt reached builtins-only lowering. The pinned direct writer puts all tensor bytes in `BufferT.data`; it does not use offset/size storage. A bounded writer change could retain one graph and FP32 weights while placing weight bytes outside the limited FlatBuffer structure. This is an engineering proposal, not an upstream option verified available. Need handle planner's full-model serialization too, and test real runtime file loading. Sources: [pinned writer](https://github.com/PINTO0309/onnx2tf/blob/2.6.9/onnx2tf/tflite_builder/tensor_buffer_builder.py), [pinned planner](https://github.com/PINTO0309/onnx2tf/blob/2.6.9/onnx2tf/tflite_builder/split_planner.py), TF sources above. Local installed source inspected at writer lines 126–136; ADR records other paths.

2. **Repair the existing split route.** Upstream documents automatic partitioning and sequential evaluation against ONNX Runtime. Must remove BOTH whole-model serialization paths found in ADR-019: estimating the full graph before choosing partitions and writing an unsplit graph afterward. Splitting graph at tensor boundaries need not quantize weights or fix the time axis, but this model's boundary tensors/length masks must be checked. It adds partition execution/transfer work to runtime integration; it is therefore not purely a converter substitution. Sources: [onnx2tf README, split options](https://github.com/PINTO0309/onnx2tf), [migration guide](https://github.com/PINTO0309/onnx2tf/blob/main/FLATBUFFER_DIRECT_MIGRATION_GUIDE.md), [pinned exporter](https://github.com/PINTO0309/onnx2tf/blob/2.6.9/onnx2tf/tflite_builder/__init__.py), ADR-019.

3. **ONNX → TensorFlow → LiteRT with external-buffer export.** onnx2tf exposes the legacy `tf_converter` backend; TensorFlow exposes the external-buffer flag. However, pinned onnx2tf 2.6.9 DOES NOT wire that flag through: installed `onnx2tf.py` has no `buffer_offset` references. It saves a SavedModel at line 7035 without special SaveOptions, then creates `TFLiteConverter.from_keras_model` (7154), falls back to `from_concrete_functions` (7156), enables BUILTINS and SELECT_TF_OPS, and converts (7166). A custom export step or patch is needed. Allowing SELECT_TF_OPS is not evidence of GPU compatibility; our acceptance must still require supported builtins. Sources: [pinned implementation](https://github.com/PINTO0309/onnx2tf/blob/2.6.9/onnx2tf/onnx2tf.py), [upstream backend documentation](https://github.com/PINTO0309/onnx2tf), TF sources above.

   This bridge has another size hazard: TensorFlow documents a separate 2 GB protobuf ceiling for SavedModel/GraphDef. The documentation describes proto splitting, while SaveOptions API labels `experimental_image_format` highly experimental and warns about downstream compatibility/OSS availability. Do not present this route as a ready command or assume the LiteRT export flag also fixes intermediate TF serialization. Sources: [SavedModel guide, proto splitting](https://www.tensorflow.org/guide/saved_model), [SaveOptions API](https://www.tensorflow.org/api_docs/python/tf/saved_model/SaveOptions).

## Official PyTorch conversion is a different starting point

LiteRT Torch (formerly AI Edge Torch) converts a `torch.nn.Module` compatible with `torch.export`; it is not a direct ONNX import route. Current API/source supports specifying dynamic shapes and resizing compiled inputs, but this is not proof that this encoder's dynamic masks/INT64 contract lower correctly. Reconstructing or obtaining the exact original PyTorch encoder adds a new equivalence obligation. Prefer the existing ONNX route unless it fails a bounded repair. Sources: [official converter walkthrough](https://github.com/google-ai-edge/litert-torch/blob/main/docs/pytorch_converter/README.md), [runtime model implementation](https://github.com/google-ai-edge/litert-torch/blob/main/litert_torch/model.py).

## Acceptance remains unchanged

No quantization or changed model proposed. Preserve FP32 weights, dynamic time and INT64 true lengths. First serialize/load on host and compare outputs to the original ONNX CPU reference. Only after that test supported GPU execution, memory and speed on the phone. Research establishes plausible routes, not the existence of a working Pixel encoder.
