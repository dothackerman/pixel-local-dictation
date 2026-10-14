# Runtime and deployment options, 2026-10-14

Research and local public-audio diagnostics; no phone access or production changes. This note
provides evidence for [the current experiment](next-experiment-plan.md) and [future work](roadmap.md#future-experiments). Allocation adapters and measurement modes proposed here were subsequently prepared for [release 1a](batch-1a-v1.md); their comparison loop has not run. The latest phone
evidence is the private `corpus/lab/handback-2026-10-13.md` and its linked raw reports; earlier
precision evidence remains authoritative in [beam investigation](beam-investigation-2026-10-12.md).

## What is actually missing

The loop has demonstrated useful accuracy gains, but has not made those candidates fit its
deployment gates. Continuing the same beam-width/chunk-length search does not address that
problem. The highest-value missing work is **runtime allocation measurement and selective
precision under the actual beam decoder**, followed by a measured finalization design.

The exact 2 s Stop limit, 0.3 real-time factor and 3.5-million-kB peak are experimental acceptance
rules in [the historical evaluator](../tools/evaluate.py), not numerical
product commitments in [requirements](requirements.md). Keep reporting failures honestly. If
Oriol later chooses a different trade-off, change the design rule explicitly; do not reinterpret
past failures as passes. Passing these rules also would not prove improvement over FUTO in
both languages.

## A concrete runtime experiment is already available

Pinned sherpa-onnx accepts `cpu:/absolute/path/session.config` as its provider string. Its
session setup supports `EnableCpuMemArena=0`, `EnableMemPattern=0`, `ProfilingFilePrefix`, and
`SessionConfig.*` entries forwarded to ONNX Runtime. At the research checkpoint the app and Python adapters did not expose these controls. They now do through [Kotlin RuntimeConfig](../app/src/main/kotlin/ch/og/dictation/RuntimeConfig.kt) and [Python runtime_config](../tools/runtime_config.py); this is setup completion, not measured allocation savings. The actual local Android AAR's JNI library contains the arena
and session-config keys, so this is stronger evidence than finding an API only on upstream
main. Binary strings establish presence, not successful Android operation.
[Pinned session implementation](https://github.com/k2-fsa/sherpa-onnx/blob/11afbd009a7f8c08f4bcf2fc1b265d0df4670fbf/sherpa-onnx/csrc/session.cc),
[Kotlin adapter](../app/src/main/kotlin/ch/og/dictation/Engine.kt),
[Python adapter](../tools/pipeline.py).

An executed laptop smoke test used the existing selective-INT8 Parakeet model on the public
German collapse clip, with arena and memory pattern both disabled and profiling enabled.
Output matched stock CPU exactly; three ORT profile files were produced. This confirms the
configuration path works in the installed Python runtime. It does not isolate either setting's
performance effect or establish phone memory savings.

A second executed diagnostic isolated those settings in three fresh processes, using the
existing FP32 encoder, beam 8, no dictionary, four threads, and three repetitions of public
clip `models/guard/de/140_82_000065.wav` per process:

| Setting | Decode seconds, three runs | Process peak RSS, kB | Current RSS after third run, kB |
|---|---|---:|---:|
| Stock CPU | 4.92 / 4.75 / 4.84 | 2,981,892 | 2,981,892 |
| CPU arena disabled only | 4.60 / 4.89 / 4.64 | 2,812,732 | 2,572,212 |
| Memory pattern disabled only | 4.52 / 4.65 / 4.64 | 2,779,208 | 2,779,208 |

All nine outputs had the same SHA-256 and 63 whitespace-separated words. Peak reduction in
these individual processes was about 5.7% and 6.8%, respectively. This is useful feasibility
evidence for both controls. It is one public clip without dictionary, on a laptop, in fixed
test order; it does not establish a general speed improvement, a phone saving, or full-corpus
parity. Stock's second inference grew its retained memory substantially; pattern-off did not
show that same growth here. Do not attribute the phone's paced increase to this without the
controlled phone comparison. Load times were 5.50 / 3.50 / 3.55 s, confounded by file-cache order.
The diagnostic and raw aggregate outputs remain at `/tmp/runtime-ab-20261014.py` and
`/tmp/runtime-ab-{stock,arena-off,pattern-off}.json`; they contain no personal audio or text.

**Prepared controls; comparison pending:** independent recognizer/LID settings now exist. Compare stock,
arena-off only, and memory-pattern-off only with unchanged weights/search/chunks. Collect
load, encoder/decoder time, memory after load, per-chunk peak, and repeated warm runs. Disabling
an arena can reduce retention while increasing allocation cost; it is an experiment, not a
recommended production default. ORT documents session/shared arena allocation as a memory
tuning mechanism. [ORT memory guidance](https://onnxruntime.ai/docs/performance/tune-performance/memory.html).

## Cohere single-instance evidence, 2026-10-12

The pre-change Kotlin and Python adapters allocated one full model per language. This section retains the API, smoke-test and loading evidence that justified replacing that duplication; later phone outcomes are in [the audit](evaluation-audit-2026-10-14.md).

The **already pinned sherpa-onnx v1.13.8** supports per-stream language selection:

- Cohere's `DecodeStream` reads `s->GetOption("language")` before falling back to the model configuration, then builds the language tokens for that decode. It also accepts per-stream `use_itn` and `use_punct`. [Pinned implementation](https://github.com/k2-fsa/sherpa-onnx/blob/11afbd009a7f8c08f4bcf2fc1b265d0df4670fbf/sherpa-onnx/csrc/offline-recognizer-cohere-transcribe-impl.h).
- Kotlin exposes `OfflineStream.setOption(key, value)`; Python exposes `set_option`. The locally installed AAR contains both the Kotlin method name and the native `OfflineStream_setOption` symbol. This verifies API presence, **not successful inference**. [Kotlin API](https://github.com/k2-fsa/sherpa-onnx/blob/11afbd009a7f8c08f4bcf2fc1b265d0df4670fbf/sherpa-onnx/kotlin-api/OfflineStream.kt), [Python binding](https://github.com/k2-fsa/sherpa-onnx/blob/11afbd009a7f8c08f4bcf2fc1b265d0df4670fbf/sherpa-onnx/python/csrc/offline-stream.cc).

**Original proposal, implemented by [ADR-007](decisions.md#adr-007--night-three-the-full-precision-dictionary-family-and-single-instance-cohere-go-to-the-phone):** retain the existing local language identifier and one Cohere recognizer; set `language` on every new stream before decoding. Keep language fixed within a take, reset it between takes, and preserve the current serial decode queue. No new weights or runtime upgrade should be necessary. Removing a duplicate model should materially reduce memory, but it does not imply exactly half the measured process peak: buffers, the identifier, session arenas, and temporary allocations remain. It does not inherently halve decode latency, since the existing code decodes only one selected instance.

**Executed laptop smoke test:** using installed sherpa-onnx 1.13.8 and the existing INT8 assets, constructed one recognizer with default language `en`, then set explicit per-stream languages `de/en/de/en`. Public guard clips were `de/7406_6765_000098.wav` (10.09 s) and `en/7021-85628-0005.wav` (5.01 s). All four outputs matched the saved two-instance Cohere guard outputs exactly after the existing Swiss-spelling normalization. Only parity booleans, timings and memory were emitted; no text was copied into this report. This validates actual Python inference and language alternation, not just API presence. The source comparison is `corpus/lab/runs/cohere-1f31656.json`'s public `guard` entries, not its personal takes.

The smoke process peaked at 3,465.4 MiB RSS, **without** a loaded language identifier. Load was 10.072 s; decode times were 6.017/2.376/4.165/1.800 s in that order. These are unisolated laptop observations during concurrent investigation, not comparable phone performance results. The memory result specifically prevents promising that one instance will necessarily meet the 3.5 GB phone criterion: it removes known duplication but leaves little apparent headroom, and platform-specific measurement remains necessary. No frozen pipeline/evaluator files or ledger rows were changed.

**Production-port trap:** Cohere's pinned model constructor explicitly rejects `AssetManager` loading. It loads encoder and decoder from filesystem paths. LabTest already uses private filesystem paths; the daily app's default asset path cannot be assumed to work. This calls for private local provisioning, not a storage permission or literal external SD card requirement. [Model implementation](https://github.com/k2-fsa/sherpa-onnx/blob/11afbd009a7f8c08f4bcf2fc1b265d0df4670fbf/sherpa-onnx/csrc/offline-cohere-transcribe-model.cc), [LabTest](../app/src/androidTest/kotlin/ch/og/dictation/LabTest.kt).

## The benchmark's extra memory is not yet explained

[LabTest](../app/src/androidTest/kotlin/ch/og/dictation/LabTest.kt) creates one recognizer and
reuses it throughout. It creates a VAD per take but calls `segmenter.reset()` after each, which
releases the detector in [VadSegmenter](../app/src/main/kotlin/ch/og/dictation/Engine.kt).
There is no obvious extra recognizer or unreleased VAD explaining the paced-phase increase.

The harness does preload every recording into FloatArrays, retain them through both phases,
and report process high-water RSS rather than attributing allocations by owner. High-water
memory after an entire run is not interchangeable with current PSS after the unpaced phase.
The similar paced increase in baseline and FP32 is a useful diagnostic clue, not proof that
the increase is artificial or safely subtractable. Cohere already exceeds its budget before
the paced phase, so a paced-only explanation cannot rescue that result.

**Prepared measurement modes:** fresh process for paced-only; fresh process for unpaced-only; and repeated
production-like takes with one audio buffer and one persistent decode executor. Record current
RSS, PSS, Java/native allocations and high-water memory around load, VAD, each inference and
release. Preserve the existing full-run result alongside these diagnostics. Only a controlled
comparison can justify a benchmark correction or a memory optimization.

## Precision should be searched by sensitivity, not only operator type

The current [quantization tool](../tools/quant_variant.py) exposes only operator-type selection
and fixes unsigned dynamic INT8. It does not expose per-channel weights or node exclusions.
ORT's quantizer supports both; its guidance recommends locating damaging weight/activation
differences and leaving those tensors unquantized. This opens a concrete route between the
fast INT8 encoder and the larger FP32 encoder. [ORT quantization guidance](https://onnxruntime.ai/docs/performance/model-optimizations/quantization.html),
[quantizer source](https://github.com/microsoft/onnxruntime/blob/main/onnxruntime/python/tools/quantization/quantize.py).

**Proposed bounded search:** first per-channel MatMul quantization; then grouped encoder-layer
exclusions located with public-audio activation comparisons and the known public collapse
regression. Hold decoder, joiner, beam and dictionary constant. Test greedy, beam without
dictionary, and beam with dictionary on the full guard before considering phone deployment.
Do not pick exclusions solely to repair one clip or tune against the future hold-out. Record
size and speed as well as recognition: retaining a few sensitive layers may be enough, but
that is unverified.

FP16 is another candidate, not a drop-in promise. ORT's conversion guide warns that its CPU
path does not universally support FP16 operations; conversion alone cannot establish native
half-precision execution, lower resident memory, or faster Android inference. Check the exact
AAR/kernel support on a small exported encoder first, including fallback/casts and output
parity. Mixed FP16/FP32 is worth screening if the runtime really executes it efficiently.
[ORT float16 guidance](https://onnxruntime.ai/docs/performance/model-optimizations/float16.html).

The upstream TDT deletion issue remains relevant, but PR 3657 was still open and unmerged
when checked on 2026-10-14, with the same head examined in the earlier investigation.
A speculative search patch should not displace the already reproduced precision diagnosis.
A reference-NeMo versus sherpa beam comparison is a separate experiment requiring the same
weights and comparable search settings. [Issue 3267](https://github.com/k2-fsa/sherpa-onnx/issues/3267),
[PR 3657](https://github.com/k2-fsa/sherpa-onnx/pull/3657),
[existing algorithm analysis](beam-investigation-2026-10-12.md#what-the-source-does-and-does-not-explain).

## Overlap alone does not make the final decode cheap

Current [TakeTranscriber](../app/src/main/kotlin/ch/og/dictation/TakeTranscriber.kt) serially
decodes independent closed chunks; Stop queues the remainder. The interface returns text,
with no encoder-cache reuse or ownership/timestamp information for overlap merging.

Let `D(W)` be decode time for a window of `W` audio seconds and `H` the interval between new
windows. Repeated offline windows need `D(W) < H` with headroom to avoid a growing queue.
For illustration only, a 20 s window at real-time factor 0.3 takes about 6 s: rerunning it every
2 s consumes three times real time. Even with a feasible hop, the final window still costs
`D(W)`, plus any in-flight work, if it must be decoded again after Stop. Overlap preserves
context by repeating computation; it does not expose an incremental encoder cache.
NVIDIA explicitly distinguishes buffered overlap from cache-aware streaming models trained
and exported for state reuse. [NeMo streaming documentation](https://docs.nvidia.com/nemo-framework/user-guide/latest/nemotoolkit/asr/models.html#cache-aware-streaming-conformer).

**Proposed scheduling experiment:** first simulate queues with measured per-window times and
Stop at multiple points, including immediately before a normal chunk boundary. Reject
unschedulable policies before implementation. Then compare a stable committed prefix plus
a genuinely short tail (possibly modest left overlap) with the existing long-context result.
That trades tail context for latency and needs explicit boundary omission/duplication scores.
Equivalent context and computation cannot be assumed: the current adapters do not reuse
encoder state. A short tail could empirically retain comparable accuracy, but that must be
measured rather than inferred from overlap.
Agreement-based buffered transcription is research precedent, not proof for Cohere on Pixel.
[Whisper streaming paper](https://arxiv.org/abs/2307.14743).

## When a different runtime earns work

ONNX is a model representation; ONNX Runtime executes the network, while sherpa-onnx supplies
model adapters and recognition/search logic. These are separate choices. NVIDIA's official
[NeMo-Speech.cpp](https://github.com/NVIDIA/NeMo-Speech.cpp) now lists Parakeet TDT 0.6B v3
with native ggml inference, providing a concrete alternative for the same model family.
Its published streaming performance table concerns Nemotron on other hardware, not our
Parakeet dictionary configuration on Pixel. Android buildability, equivalent beam/hotword
behavior and recognition parity must be checked before adopting it. This is a candidate
for a bounded same-model comparison, not an established replacement or acceleration result.

XNNPACK is a legitimate Android CPU-provider experiment, but unsupported operators remain
on ORT CPU and its separate thread pool requires tuning. Profile operator assignment rather
than accepting the provider name as acceleration. There is no substantiated model/chip-specific
ceiling of “only 1.3–1.5×” speedup, and no basis to promise a larger one.
[ORT XNNPACK guidance](https://onnxruntime.ai/docs/execution-providers/Xnnpack-ExecutionProvider.html).

Google now documents Tensor G5 support, AOT execution and packaged Tensor runtime libraries;
the optional cloud-project prerequisite does not apply to Pixel 10. Thus Tensor is a real
feasibility track once a worthwhile accuracy candidate exists. It is still a conversion and
integration project: validate a locally provisioned official sample on this GrapheneOS build,
then one fixed-shape encoder subgraph, checking delegated operations, numerical parity and
latency. Dynamic decoder conversion and fallback remain separate problems. No source proves
this ASR workload will run on this phone's NPU. [Tensor setup](https://developers.google.com/edge/litert/next/tensor-sdk),
[NPU deployment](https://developers.google.com/edge/litert/next/npu).

Choose provider/runtime work from the measured bottleneck; batch ordering belongs to the experiment plan.

## Unsloth quantization applicability (2026-10-14)

Follow-up checked 2026-10-14 after Oriol suggested Unsloth. Quantization can make a stronger
checkpoint fit the phone while preserving its recognition quality; it is not, by itself,
evidence of better transcription. Keep checkpoint quality and compression loss as separate
measurements.

Unsloth has two relevant, distinct mechanisms. Its original Dynamic 4-bit method builds on
bitsandbytes and leaves selected sensitive parameters unquantized. Dynamic 2.0 GGUF instead
assigns different precisions across tensors for deployment in compatible engines such as
llama.cpp. The published calibration recipe targets conversational text, with MMLU and
distributional comparisons; those results do not establish German/English speech parity or
an audio-encoder calibration recipe. An ordinary GGUF export is not automatically a Dynamic
mixed-precision export. [Dynamic 4-bit](https://unsloth.ai/blog/dynamic-4bit),
[Dynamic GGUF methodology](https://unsloth.ai/blog/dynamic-v2),
[GGUF export interface](https://unsloth.ai/docs/basics/inference-and-deployment/saving-to-gguf).

There **are** released Qwen3-ASR artifacts from the publisher's `unslothai` backup account,
so applicability need not be inferred from Qwen text-model support. Live Hugging Face model
metadata lists BF16 and Q8_0 decoder/audio-projector pairs for both 0.6B and 1.7B, but no
Dynamic 4-bit or other mixed-bit ASR pair in those repositories. Q8_0 combined disk sizes are
1.019 GB and 2.521 GB respectively; neither number is peak runtime memory.
[0.6B files, revision a663ee7](https://huggingface.co/unslothai/Qwen3-ASR-0.6B-GGUF/tree/a663ee7cae110eead2f1236fbceb48960abf880f),
[1.7B files, revision cad2b84](https://huggingface.co/unslothai/Qwen3-ASR-1.7B-GGUF/tree/cad2b8497d1541a8b6f9f7ad32e8c6f9c9908da3).

More decisively, the publisher APIs report **identical LFS SHA-256 values for all four 1.7B
files** in `unslothai` and the already recommended `ggml-org` repository. The Q8 decoder hash
is `58e22d0532d4eacaf034cfac17a6fed159f37c41390c710186783be439d1fc57`; its Q8 audio-projector
hash is `46c1d533af3f354ceb37ce855dbceff7da7fa7cf1e6a523df3b13440bd164c0d`.
This is metadata evidence, obtained without downloading weights. Testing both distributions
would duplicate the same weights rather than compare quantization methods.
[Unsloth metadata](https://huggingface.co/api/models/unslothai/Qwen3-ASR-1.7B-GGUF?blobs=true),
[ggml-org metadata](https://huggingface.co/api/models/ggml-org/Qwen3-ASR-1.7B-GGUF?blobs=true).

No published Parakeet or Cohere model appeared in either publisher account's model API search,
and the reviewed documentation did not establish an Unsloth conversion path for our ONNX
TDT or Cohere models. That is a bounded search result, not proof of impossibility. Bitsandbytes
weights and GGUF weights are not interchangeable with this app's current ONNX artifacts.
For Parakeet, the supported equivalent experiment remains the node-exclusion/per-channel
ORT quantization described above. [Publisher inventory](https://huggingface.co/unsloth/models),
[backup inventory](https://huggingface.co/unslothai/models).

**Recommendation:** include Unsloth through the existing
[Qwen reference/native experiment](model-options-2026-10-14.md#2-qwen3-asr-17b-with-a-reference-to-runtime-parity-check),
with one pinned provenance source for the identical Q8 files. If reference quality earns more
compression work, compare a genuinely different lower-bit decoder with the audio projector
held fixed first, then vary audio precision separately. Require full German/English and guard
parity plus actual runtime memory/latency before accepting either. Any custom calibration must
use representative public speech and remain separate from evaluation data. Unsloth branding
alone does not justify another experiment or a switch away from ONNX.

## Proposed acceleration experiment

Observed accuracy gains justify a hardware feasibility track. The roadmap schedules it after allocation/profiling review; this research note does not start parallel work. ONNX Runtime is the current
tensor execution engine; sherpa adds speech preprocessing, model adapters and search. Replacing
sherpa while retaining ORT differs from replacing ORT with LiteRT, and either differs from
upgrading their versions. A hybrid could run the encoder in LiteRT while retaining the proven
CPU decoder/search, but that requires an explicit integration seam.
[sherpa documentation](https://k2-fsa.github.io/sherpa/onnx/index.html).

1. **One official sample.** Adapt Google's AOT segmentation example into this repository's
   permitted benchmark package, using bundled test input, compiled model and vendor libraries.
   Pin versions; remove any network-dependent delivery path. Google's documented asset-loading
   API and Tensor runtime package support this packaging proposal, but its operation on this
   GrapheneOS build remains unverified. First prove actual Tensor execution and correct sample
   output. Failure requiring OS/security changes ends this path under the existing requirements.
   [Local asset inference and runtime packaging](https://developers.google.com/edge/litert/next/npu).
2. **One encoder.** Convert one fixed-shape Parakeet encoder window, retaining the existing
   FP32 beam/dictionary pipeline as the quality control. Verify padding/masks and conversion
   outputs against the reference, then feed those outputs through unchanged decoder/search.
   Record compiler partitions and executed backend assignment: a successful invocation or
   “NPU available” flag can conceal partial or complete fallback. The official deployment guide
   explicitly permits fallback. [Delegation behavior](https://developers.google.com/edge/litert/next/npu#step_3_inference_on_npu_using_litert_runtime).
3. **Go/no-go.** Require numerical checks plus transcript parity/accuracy across both languages
   and public guards. Measure input/output transfers, encoder and decoder time, cold load,
   repeated warm Stop-to-final, peak memory and heat against the same CPU control. Hold chunks
   and search fixed first. Advance only if the complete path improves the limiting budget
   without losing the demonstrated quality. No kernel-speed claim alone selects a runtime.

This is a proposed sample-and-encoder spike, not an established speedup or a commitment to
rewrite every model backend. Existing phone-access and package boundaries still apply.
