# Model options after night three

Research checked 2026-10-14 against publisher documentation and runtime source. These are proposed experiments, not measured accuracy or Android performance. No model downloads, inference, phone access or corpus export occurred in this research. [Requirements](requirements.md) remain authoritative; [the adapter diagnosis](loop-investigation-2026-10-12.md) retains the earlier Qwen failure evidence. [The roadmap](roadmap.md#future-experiments) owns future order; these proposals are outside the current allocation experiment.

The pinned sherpa model menu does not bound checkpoint quality. Screen reference quality before deployment engineering; Whistle's proposal below follows Oriol's October 14 suggestion.

## What the latest evidence changes

The corrected Qwen 0.6B run has already happened and still loses; rerunning the same context fix is stale advice. Its aggregate results and precision failure findings are authoritative in [results](results.md#laptop-setup-validation-2026-10-12). They reject that export/configuration, not every Qwen checkpoint or implementation.

Night three also already measured one-instance Cohere and FP32 dictionary decoding. The local handback, `corpus/lab/handback-2026-10-13.md`, records better phone transcripts than the control, but failed resource/finalization gates. Thus “no accuracy gain exists” and “no candidate passes the complete product gates” are different claims. Rescue of those candidates belongs to the runtime investigation; another model search is an independent track.

## Bounded screening experiments

### 1. Moonshine German Small Streaming + English Medium Streaming

This is the lowest integration-risk untested family. The publisher currently lists German Small Streaming at 123M parameters and English Medium Streaming at 245M, with MIT licensing. Its English and German published error rates use different panels, so they cannot rank the languages or predict personal-corpus gains. [Model inventory](https://moonshine-voice.readthedocs.io/en/latest/models/available-models/).

Use the publisher's own runtime first: it provides Android integration through `ai.moonshine:moonshine-voice`. Streaming models support key-term biasing through Java and Python. Bias occurs during decoding; stronger boosts can increase unrelated errors and false insertions. This directly tests whether names can improve without the existing TDT beam/precision failure. [Android integration](https://moonshine-voice.readthedocs.io/en/latest/using/adding-the-library/), [key-term mechanism and tradeoffs](https://moonshine-voice.readthedocs.io/en/latest/models/domain-customization/).

**Test:** run unbiased oracle-language recognition on the existing development audio and public guard, then add the current dictionary as a separately labelled mechanism test against its unbiased twin. Feed the streaming API at recorded cadence and retain its final output. Do not force this model through Parakeet's offline chunking assumptions. Report terms, ordinary words, false insertions, silence and finalization separately.

**Falsification:** if oracle routing cannot deliver a useful per-language accuracy advantage, stop before implementing a router. If it can, evaluate automatic routing and replay of buffered initial audio, including short ambiguous utterances; oracle language cannot satisfy R2. Pin a released runtime and exact model artifacts before any Android port. Both accuracy and complete-path speed remain unknown.

### 1b. Cactus Whistle

Cactus's new speech model is **Whistle**, released October 2, 2026. It is distinct from Needle's text/tool-calling model. The publisher describes a 16.9 MB CPU model supporting English and German, automatic language detection and keyword biasing. This combination earns a cheap quality screen; size alone establishes neither resident RAM nor recognition quality. [Release](https://www.cactuscompute.com/blog/whistle), [model card](https://huggingface.co/Cactus-Compute/whistle).

The platform inventory contains `android-arm64`; Whistle uses the Needle engine and its `.cact` format. This is an Android integration lead, not a successful APK build or Pixel measurement. It is a separate adapter from our ONNX path. Use the local transcription API with locally provisioned assets; Needle's tool execution is unnecessary for dictation. [Published platform artifacts](https://huggingface.co/Cactus-Compute/needle3/tree/main), [upstream repository](https://github.com/cactus-compute/needle).

The headline speed was measured on an Apple M4 Pro. Published quality comparisons primarily involve Whisper base and Moonshine tiny v2, rather than our Parakeet/FUTO controls or the proposed Moonshine streaming models. Those comparisons do not predict a personal accuracy win. [Benchmark methodology](https://www.cactuscompute.com/blog/whistle).

**Input-contract checks before scoring:** the October 3 guide says individual calls accept at most 30 seconds; the current model card advertises automatic windowing of longer files. Pin the package/engine/model together and verify the actual behavior, complete audio coverage and generation limits. The release describes a 320-token transcript cap. Existing long takes must not silently become truncated model-quality results. Whistle's streaming API repeatedly transcribes buffered audio and commits agreement between passes; it is not evidence of encoder-cache reuse or a fixed finalization time. [Usage guide](https://www.cactuscompute.com/blog/whistle-best-practices), [current model card](https://huggingface.co/Cactus-Compute/whistle).

**Test:** use full decoder depth with automatic language detection; score final text first without keywords, then with the existing dictionary as a separately labelled mechanism test against its unbiased twin. Test both independent bounded clips and the complete streaming path, checking wrong-language output, missing/duplicated boundary words, false keyword insertions, silence, and Stop latency. Cactus's keyword benchmark is English LibriSpeech evidence, not proof for German names. [Keyword evaluation](https://www.cactuscompute.com/blog/whistle-best-practices).

**Falsification:** if neither unbiased nor biased recognition yields a useful per-language quality gain, stop before Android integration. If promising, run the full existing quality guard and subsequent phone evaluation. No Whistle inference or download occurred during this source review.

### 2. Qwen3-ASR 1.7B, with a reference-to-runtime parity check

Oriol's Unsloth suggestion is assessed separately in [the runtime note](runtime-options-2026-10-14.md#unsloth-quantization-applicability-2026-10-14), including whether it supplies a distinct quantization to compare.

The larger checkpoint is a real untested hypothesis. Qwen's matched multilingual table reports the 1.7B variant ahead of 0.6B on MLS, Common Voice, MLC-SLM and FLEURS; these multilingual averages include German but do not establish a German-specific or personal win. Both support English/German and automatic language identification. The official implementation provides local reference inference. [Qwen source and evaluation](https://github.com/QwenLM/Qwen3-ASR).

There is now a concrete native alternative to the tested ONNX export: upstream llama.cpp lists both ASR sizes, and its maintainers publish converted 1.7B GGUF files. The Q8 decoder and Q8 audio projector together occupy **2.52 GB on disk**; this is not peak RAM. Android cross-compilation is documented. None of those facts proves that Qwen audio preprocessing, accelerator execution or sustained dictation meets the Pixel budget. [Pinned supported models](https://github.com/ggml-org/llama.cpp/blob/091b6a7fc561b78da90a0ed2d524ec995458c657/docs/multimodal.md), [GGUF assets](https://huggingface.co/ggml-org/Qwen3-ASR-1.7B-GGUF/tree/36a678687ba7d07a74ca70ccb0e36902e005fb80), [Android build](https://github.com/ggml-org/llama.cpp/blob/091b6a7fc561b78da90a0ed2d524ec995458c657/docs/android.md).

**Test:** on a preselected development subset containing German, English, names and former truncation cases, compare official floating-point 0.6B, official 1.7B, and 1.7B GGUF. Keep audio, automatic language policy and transcription task fixed; verify sufficient context/output budgets and complete decoding. First use no dictionary. Store all personal outputs under `corpus/`. Run the full unchanged development/guard evaluation only if the pilot warrants it.

**Falsification:** if reference 0.6B substantially beats its corrected ONNX counterpart, investigate conversion/preprocessing before attributing losses to model capacity. If 1.7B reference does not improve quality, stop its deployment work. If reference improves but GGUF loses it, reject that conversion/precision configuration. Only a surviving quality gain earns phone profiling. Llama.cpp support is a testable path, not a guarantee of speed.

### 3. Establish the untested accuracy ceiling: Canary 1B v2 and full Whisper large-v3

Canary 1B v2 is a 978M-parameter model with English/German support, released August 14, 2025. The publisher provides NeMo and Transformers paths and uses source/target language task tokens; its long-form recipe uses overlapping chunks. Its benchmark hardware is NVIDIA GPUs. Current sherpa's Canary export directory still contains only the 180M Flash recipe: missing integration does not reject the 1B checkpoint. [Canary model card](https://huggingface.co/nvidia/canary-1b-v2), [sherpa export directory](https://github.com/k2-fsa/sherpa-onnx/tree/master/scripts/nemo/canary).

Whisper turbo prunes the full large-v3 decoder from 32 layers to four, trading some quality for speed. A weak turbo run therefore does not settle full large-v3. Whisper.cpp provides an Android/native, quantized and Vulkan-capable deployment path, with no verified Pixel timing here. [Publisher's turbo description](https://huggingface.co/openai/whisper-large-v3-turbo), [whisper.cpp](https://github.com/ggml-org/whisper.cpp).

**Test:** run both floating-point reference checkpoints on the same small, predeclared development panel using their documented inference recipes and sufficient generation budgets. Canary oracle language is diagnostic; later test the existing automatic identifier. For Whisper, compare full large-v3 with turbo in the same reference implementation, first holding language policy and decoding settings fixed. Native chunking differences should be recorded, then separated by a matched-chunk follow-up if material.

**Falsification:** if neither reference improves both languages enough to matter, stop their conversion work. If only German improves, record that as a language-specific result; a routed product could still be investigated, but is not yet an R4 win. A quality winner justifies conversion and phone optimization, not automatic deployment.

## Candidates to keep behind those tests

Cohere remains worth optimizing because it already has personal evidence; downloading another checkpoint is not required to investigate its measured runtime costs. Its publisher now documents native Transformers inference, language conditioning and long-form chunk assembly, which provide a reference for future export parity checks. [Cohere model card](https://huggingface.co/CohereLabs/cohere-transcribe-03-2026).

Voxtral Realtime is open-weight, multilingual and natively streaming, including German/English. However, its published checkpoint is 4B, the model card's reference serving recipe needs a GPU with at least 16 GB, and its ExecuTorch route is explicitly labelled untested. Its 480 ms algorithmic delay is not a Pixel compute benchmark. Keep it as a later reference-quality challenger rather than the next Android integration. Do not confuse this checkpoint with the separate Mini Transcribe V2 service. [Mistral model card](https://huggingface.co/mistralai/Voxtral-Mini-4B-Realtime-2602), [publisher release](https://mistral.ai/news/voxtral-transcribe-2/).

## Interpretation rule

The ten repeatedly examined takes are development data. A better development score nominates a candidate; it cannot establish generalization. Use the unchanged guard and reserved hold-out process, report each language independently, and keep dictionary-mechanism gains distinct from recognition of previously unseen names. Do not lower acceptance thresholds after seeing results or treat three feed schedules as three independent corpora. The aim of this round is to identify a new mechanism or checkpoint worth validating, not to generate more rows inside an exhausted parameter range.
