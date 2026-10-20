# Encoder GPU preparation: best practices

> Follow-up: the proposed first route is now unchanged ONNX on the downloadable
> Android ONNX Runtime 1.31.0 WebGPU backend. See the [availability check](onnx-webgpu-android-availability.md).
> The LiteRT-first priority and OpenGL-specific steps below describe the earlier
> LiteRT alternative. They are not the backend or execution order for the proposed
> WebGPU/Dawn/Vulkan investigation. General correctness/evidence advice still applies.

2026-10-20. Primary-source research; no model download, conversion, inference,
installation or phone access. Recommendations below are engineering judgments,
not results from a working Parakeet GPU encoder.

## Recommendation

**Inspect the published Parakeet v3 LiteRT model and its export recipe before
writing a converter patch.** A ready-made FP32 model exists above 2 GiB. It may
provide reusable conversion code, but its fixed five-second encoder interface
does not satisfy our current dynamic-length contract without further work.
If it cannot preserve that contract, a bounded appended-weight writer repair
remains credible. Do not start full app integration before numerical correctness
and GPU compatibility are established.

## What changes the priority

The [LiteRT Community Parakeet v3 model card](https://huggingface.co/litert-community/parakeet-tdt-0.6b-v3)
publishes standalone FP32 `.tflite` files (~2.42 GB) with `encode` and `decode`
signatures, reports mobile CPU/GPU use, and describes export from the original
NeMo checkpoint through LiteRT Torch. It uses fixed five-second windows and an
overlap/merging pipeline. This is evidence of a published larger model and useful
prior work, not evidence that its encoder matches our ONNX export for arbitrary
lengths. Its Samsung results establish no Pixel FP32 correctness or speed.
The ready-made-options investigation records exact artifact identities separately.

The current Google [Parakeet export wrapper](https://github.com/google-ai-edge/litert-samples/blob/main/samples/litert/speech_recognition/convert/parakeet_tdt.py)
is materially different: lines 62–65 call the encoder with `length=None` and return
hidden states alone. It replaces masked convolution and normalization modules
for export. The [conversion driver](https://github.com/google-ai-edge/litert-samples/blob/main/samples/litert/speech_recognition/convert/convert_to_tflite.py)
builds signatures from sample inputs without `dynamic_shapes`. Therefore this
published recipe is useful prior work, but it does not implement our unchanged
true-length/mask interface. Audit or adapt the recipe before considering it a
replacement; merely extracting its `encode` signature does not solve that gap.

Google's [conversion overview](https://developers.google.com/edge/litert/conversion/overview)
documents PyTorch, TensorFlow and JAX routes. ONNX is not listed there as a direct
official conversion input. [LiteRT Torch's walkthrough](https://github.com/google-ai-edge/litert-torch/blob/main/docs/pytorch_converter/README.md)
accepts a `torch.nn.Module` compatible with `torch.export`, recommends output
comparison before deployment, and provides `find_culprits` to reduce export
failures to small reproducible cases. Thus a native-checkpoint recipe is a more
upstream-aligned option to inspect than immediately maintaining an ONNX writer
fork. Recovering the exact model/weights and preserving our semantics are still
obligations. This is an alternative starting point, not an ONNX converter switch.

## Ordered proof gates

1. **Compare interfaces and provenance on paper first.** Inspect the published
   FP32 model's exporter and signature metadata: checkpoint revision, learned
   weights, attention context, normalization, layout, true-length input, output
   lengths and precision. Determine whether `encode` can be used separately with
   our CPU decoder/joiner. Fixed windows, padding or a changed overlap algorithm
   are material behavior changes; do not silently replace our pause chunks with
   this sample's end-to-end pipeline. Our required contract and existing probes
   are in [ADR-019](../adrs/019-encoder-conversion.md) and GitHub issue #1.

2. **Prove the file representation with the exact loader before the full model.**
   If a writer repair is still needed, first test a small synthetic model whose
   weights use appended offsets; then exercise a synthetic file crossing 2 GiB.
   Use a filesystem path and the same native loading path planned for Android.
   Google's [GPU example](https://developers.google.com/edge/litert/next/gpu)
   constructs `CompiledModel` from a file path, and its current recommended API is
   [CompiledModel](https://developers.google.com/edge/litert/inference).
   A successful Python Interpreter test alone does not prove the Android
   CompiledModel loader or GPU compiler accepts the same representation.

3. **Audit GPU feasibility alongside file packaging.** List the *lowered LiteRT*
   operations, versions, tensor types, ranks and dynamic dimensions. Check shape
   and mask computations as well as expensive floating-point operations. Our
   encoder has INT64 true lengths, dynamic Shape/Range/Expand/Where masks and 120
   LayerNormalization nodes. “All builtins” means translation succeeded; it does
   not mean all operations can run on the GPU. Google's current GPU model table
   even reports a torchaudio Conformer as only partly delegated. That is a warning
   about this model family, not proof Parakeet fails. Preserve the requirement of
   GPU-only execution; do not quietly accept host mask computation or fallback.
   [GPU documentation and model table](https://developers.google.com/edge/litert/next/gpu).

4. **Run original CPU → converted CPU tensor checks before integration.** Keep
   the frozen FP32 ONNX model as the reference; compare every output and exact
   lengths. Include short tails, masked padded inputs and changing lengths in
   one retained runtime. Extend the current synthetic probes to the real chunk
   envelope: [ADR-019](../adrs/019-encoder-conversion.md) explicitly says current
   probes are not established development-length quantiles and production has
   no hard maximum. Passing one fixed five-second input cannot validate that
   envelope. Preserve the issue's tolerances, finite-value checks and failure
   records; a useful diagnosis is a valid stopping outcome.

5. **Stop at the phone boundary until connected and authorized.** Later require
   observed OpenGL GPU placement, explicit FP32, no fallback, tensor checks,
   memory and cold setup measurements. Then measure transfer-inclusive encoder
   time and whole-take Stop wait with unchanged decoder/joiner/search/dictionary.
   Public speed numbers and successful compilation cannot replace those gates.
   These are the already-frozen requirements of issue #1 and
   [ADR-016](../adrs/016-accelerator-access.md), not new acceptance thresholds.

## If a writer repair is necessary

Use the existing large-model representation rather than enlarge the FlatBuffer
builder's limit or invent a new file format. Keep graph metadata small and append
unchanged weights; `Buffer.offset` and `Buffer.size` are 64-bit file positions and
lengths. This differs from a separate external-file mechanism. The upstream MLIR
exporter already implements offset-based export; the exact-runtime source
references and boundaries are in [LiteRT size limits](litert-size-limits.md),
backed by the [schema](https://github.com/google-ai-edge/LiteRT/blob/5b1e29a7ac6809bef62491e3b83b29c2c518ac14/tflite/converter/schema/schema.fbs)
and [MLIR exporter](https://github.com/google-ai-edge/LiteRT/blob/5b1e29a7ac6809bef62491e3b83b29c2c518ac14/litert/compiler/mlir/flatbuffer_export.cc).
Those earlier source checks establish Interpreter support, not a completed
CompiledModel/GPU test. A regression fixture should cover offsets, alignment,
range checks and loading, not just a successful file write.

The current [onnx2tf release page](https://github.com/PINTO0309/onnx2tf/releases)
still identifies 2.6.9 as latest. Updating to an unspecified “new version” is not
an established fix. Its [direct-backend migration guide](https://github.com/PINTO0309/onnx2tf/blob/main/FLATBUFFER_DIRECT_MIGRATION_GUIDE.md)
documents splitting and sequential correctness checks; our pinned implementation
has two whole-model serialization paths that defeat this case. Prefer one graph
with appended weights if feasible: graph splitting adds partition orchestration
and boundary overhead, so it is not merely a file-writer change. This preference
is our engineering judgment.

Prepare a small nonpersonal reproduction of the serialization defect and a
minimal upstream-oriented patch. Seek upstream acceptance rather than begin an
open-ended private converter fork. Posting requires separate user authorization;
this research posts nothing. Pin the patch commit, converter/dependency versions,
input and output hashes, options and runtime binaries. Changing export route or
model bytes invalidates prior parity evidence.

TensorFlow's [`_experimental_use_buffer_offset`](https://github.com/tensorflow/tensorflow/blob/master/tensorflow/lite/python/lite.py)
is an existing exporter mechanism, but our direct ONNX backend does not invoke
that converter. Switching through TensorFlow adds a bridge and possible
intermediate serialization problems; it is not a proven one-flag fix. See
[conversion alternatives](encoder-conversion-options.md).

## Stop criteria

Stop and record the precise blocker if the published recipe cannot preserve our
dynamic/length contract, an exact loader rejects appended weights, CPU parity
fails, GPU execution requires fallback/reduced precision, or memory is unsuitable.
Do not repair unrelated modules to keep this route alive. A new runtime or changed
windowing/model is a separate decision. The largest remaining uncertainty is
**unchanged encoder GPU compatibility**, not merely writing a file above 2 GiB.
