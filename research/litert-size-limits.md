# LiteRT size limits

2026-10-20. Primary-source inspection only; no conversion, inference, or phone work.

## Conclusion

The observed 2 GiB ceiling is a hard limit of the conventional FlatBuffer builder/container used by the pinned conversion route. It is **not a universal 2 GiB limit on a LiteRT model file or its total weights**. LiteRT has an existing format path for larger models: keep the graph structure in a small FlatBuffer and append constant weights outside that structure. The runtime reads their 64-bit file positions. This avoids splitting the encoder computation or changing FP32 weights. It does not prove this encoder will execute correctly or efficiently on the Pixel GPU.

## Why the limit exists

FlatBuffers uses fixed-width offsets within its conventional buffer. Its C++ maximum is the maximum signed offset; the source explicitly identifies the 32-bit result as 2 GB minus 1. The Python builder independently caps allocation at `2**31` bytes. Simply increasing the Python constant is not a supported compatibility fix: consumers and other builder paths retain format/offset bounds. [C++ offset and maximum definitions](https://github.com/google/flatbuffers/blob/b8431fbcd7a5c71817f314e18b332c0648554efa/include/flatbuffers/base.h#L341), [Python builder](https://github.com/google/flatbuffers/blob/b8431fbcd7a5c71817f314e18b332c0648554efa/python/flatbuffers/builder.py).

## Existing larger-model mechanism

The LiteRT `Buffer` schema explicitly provides `offset: ulong` and `size: ulong` for models larger than 2 GB. These point to weight data outside the FlatBuffer, relative to the beginning of the same file. Thus “outside the FlatBuffer” need not mean a second file. Newer schema also has a separate external-file mechanism, `ExternalBuffer`; these are distinct mechanisms and should not be conflated. [Schema](https://github.com/google-ai-edge/LiteRT/blob/5b1e29a7ac6809bef62491e3b83b29c2c518ac14/tflite/converter/schema/schema.fbs#L1688).

The current official MLIR exporter has `use_buffer_offset`. It enables this near the size limit, and retries with it when the ordinary path reports the limit. Its output code writes the graph structure and appends weight bytes to the export stream, then updates offsets. This is implemented upstream, not merely a proposed workaround. [Automatic selection/retry](https://github.com/google-ai-edge/LiteRT/blob/5b1e29a7ac6809bef62491e3b83b29c2c518ac14/litert/compiler/mlir/flatbuffer_export.cc#L4221), [streaming/appending code](https://github.com/google-ai-edge/LiteRT/blob/5b1e29a7ac6809bef62491e3b83b29c2c518ac14/litert/compiler/mlir/flatbuffer_export.cc#L4470).

The interpreter builder reads an inline weight array when present; otherwise, for a valid offset and file allocation, it checks the range and obtains the weight pointer from allocation base plus offset, with a 64-bit size. Runtime support therefore exists in current source. [Interpreter loading](https://github.com/google-ai-edge/LiteRT/blob/5b1e29a7ac6809bef62491e3b83b29c2c518ac14/tflite/core/interpreter_builder.cc#L810).

TensorFlow exposes `_experimental_use_buffer_offset` on its Python converter. This belongs to that converter; setting it does not automatically repair the direct onnx2tf FlatBuffer backend used here. [TensorFlow Python converter](https://github.com/tensorflow/tensorflow/blob/master/tensorflow/lite/python/lite.py).

## Pinned release source check

The same appended-buffer loading path exists in both exact release tags used by this project: [host v2.1.2 interpreter builder](https://github.com/google-ai-edge/LiteRT/blob/7cd848d3b20e2de517d70f1ac50f8d9a2e2d9dcb/tflite/core/interpreter_builder.cc#L667) and [Android v2.3.0 interpreter builder](https://github.com/google-ai-edge/LiteRT/blob/4352e261d3f1b3cda00345db5dbd75859d3bf0d3/tflite/core/interpreter_builder.cc#L757). This establishes source-level support for loading this representation in those releases. It does not establish execution of the packaged binaries, successful Android loading of a >2 GiB model, or GPU acceptance.

## What remains unproved here

- ADR-019 concerns onnx2tf 2.6.9 direct backend, host LiteRT 2.1.2, Android diagnostic LiteRT 2.3.0. The current exporter evidence above is current upstream source. Both tagged interpreter sources support appended buffers, but exact packaged-binary compatibility has not been established.
- A model converter runs on the development computer. Its output format, operations, shapes and numerical behavior must still match the runtime and GPU backend.
- External/appended weights solve the file-container problem. GPU-supported operations, dynamic lengths, allocation limits, memory use, FP32 behavior and speed remain separate checks.
- Android file-path/native loading should be evaluated explicitly. A Java byte-buffer API is not sufficient evidence that a file above 2 GiB works end to end.
- No claim that all LiteRT GPU paths support every external-weight mechanism follows from interpreter source alone.

## Consequence for this project

A converter/backend that emits supported appended weights is a credible next host-side investigation. Another option is to repair the pinned exporter so it avoids both monolithic serialization paths. Current source evidence supports continuing investigation; it does not guarantee that the existing ONNX encoder can be converted directly by the official MLIR converter. Tensor parity remains the first execution gate. [Existing diagnosis](../adrs/019-encoder-conversion.md).
