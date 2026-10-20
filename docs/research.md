# Research index

Research notes are dated evidence and proposed tests, not execution instructions. [The current experiment](experiments/current.md) owns scope and readiness; [the roadmap](roadmap.md#future-experiments) owns future sequencing. Consult and pin current upstream before implementing a later proposal.

| Question | Evidence owner |
|---|---|
| Can this GrapheneOS Pixel actually execute on the GPU or TPU? | [Device check, October 20](research/accelerator-access-device-check-2026-10-20.md) |
| What do the completed phone profiles justify for GPU and TPU acceleration? | [Accelerator routes after profiling, October 20](research/accelerator-routes-after-profiling-2026-10-20.md) |
| What improvement lever remains after the completed allocation loop? | [Remaining levers, October 19](research/remaining-improvement-levers-2026-10-19.md) |
| What improved on the development set, and what can it establish? | [Evaluation audit, October 14](research/evaluation-audit-2026-10-14.md) |
| Why did Qwen truncate and beam search delete speech? | [Adapter diagnosis](research/loop-investigation-2026-10-12.md), [precision diagnosis](research/beam-investigation-2026-10-12.md) |
| Can allocation, precision, scheduling or another backend make a winner deployable? | [Runtime research, October 14](research/runtime-options-2026-10-14.md) |
| Which untested checkpoints and reference runtimes deserve screening? | [Model research, October 14](research/model-options-2026-10-14.md) |
| What selected the existing prototype? | [ADR-002/003](decisions.md), [milestone history](archive/milestones-2026-10-10.md) |
| What was measured against installed FUTO? | [Baseline](evaluation/baseline.md), [results](evaluation/results.md) |

## Reading the earlier Claude research correctly (2026-10-14)

The setup/review transcript `c8cb31bb-cad2-4a11-aa4c-1a030eed2ba5.jsonl` and latest loop transcript `4616df77-4a59-477a-8e2d-0b5447ba1588.jsonl`, under the project's directory in `~/.claude/projects/`, were reviewed against saved reports. The October 13 handback is `corpus/lab/handback-2026-10-13.md`. The earlier prediction that one Cohere instance would fit around 2.5 GB was not borne out by the phone run; use the measured runtime evidence.

Apple's phonetic-retrieval paper reports entity error falling from 37.61% to 12.60% in its simple-replacement ablation. It uses named-entity spans from a trained, tagged LLM-ASR system on English assistant queries. It does not validate nearest-name replacement over arbitrary windows of this app's greedy German/English output. Any such experiment needs entity detection, abstention and false-replacement evaluation. [Primary paper, sections II-B/III, Table II](https://arxiv.org/html/2409.15353v1).

The [runtime note](research/runtime-options-2026-10-14.md#overlap-alone-does-not-make-the-final-decode-cheap) explains why overlap alone does not make final inference proportional to newly received audio. Earlier one-second-tail promises and universal CPU-speedup ceilings were not established.

## Implementation references

These retain the October 9 source review behind the prototype; later model/runtime notes own follow-up findings. Publisher benchmarks do not establish personal accuracy or Pixel performance.

- **FUTO integration:** [external API](https://github.com/futo-org/voice-input#api-support) describes voice IME and `RECOGNIZE_SPEECH`; it does not establish `SpeechRecognizer` service support. The inspected [nightly documentation](https://docs.keyboard.futo.tech/improvements/voice-input-nightly-september-2026) described manual multilingual selection for non-Whisper modes. That conflicts with R1/R2 unless an automatic path is built. A failed anonymous submodule fetch did not establish universal unbuildability.
- **Installed-baseline provenance:** the [pinned keyboard registry](https://github.com/futo-org/android-keyboard/blob/70a5d390c505a6bbcc4e14966e5628e43ca3f1fc/voiceinput-shared/src/main/java/org/futo/voiceinput/shared/Models.kt) maps Multilingual-244 to `small_acft_q8_0.bin`, already quantized Whisper small with [short-context ACFT tuning](https://github.com/futo-org/whisper-acft). A source registry does not prove installed decoding settings.
- **Parakeet:** [v3 model card](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3) documents automatic language detection, mono 16 kHz input and CC BY 4.0 weights. [Parakeeb](https://github.com/surma/parakeeb) is Android precedent, not verified FUTO integration. Check asset and code licenses independently; offline throughput does not establish native streaming.
- **sherpa-onnx:** [v1.13.8 release](https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.13.8), [model documentation](https://k2-fsa.github.io/sherpa/onnx/pretrained_models/offline-transducer/nemo-transducer-models.html) and [export source](https://github.com/k2-fsa/sherpa-onnx/tree/v1.13.8/scripts/nemo/parakeet-tdt-0.6b-v3) establish the Android AAR/Kotlin path and dynamic-INT8 export. The project uses the release AAR rather than an unconfirmed Maven artifact. [assets.lock](../assets.lock), [the build](../app/build.gradle.kts) and the batch's environment lock own exact versions/checksums. Published INT8 parity failed; ADR-003 explains the selective encoder.
- **Later models/runtimes:** Moonshine, Whistle, Qwen, Canary and full Whisper source links and input contracts are consolidated in [model research](research/model-options-2026-10-14.md). Moonshine's earlier Pixel 10a timing covered English and excluded endpoint silence; it was not German Pro XL end-to-end evidence. [faster-whisper](https://github.com/SYSTRAN/faster-whisper)'s CUDA path is not Tensor acceleration.

## Pixel constraints on research

[Pixel specifications](https://store.google.com/product/pixel_10_pro_specs?hl=en-US) list Tensor G5 and 16 GB RAM; capacity alone proves neither low latency nor accelerator compatibility. [Runtime research](research/runtime-options-2026-10-14.md#proposed-acceleration-experiment) owns the updated Tensor SDK feasibility proposal, including actual execution/fallback checks on GrapheneOS.

[Deprecated NNAPI](https://developer.android.com/ndk/guides/neuralnetworks) is not a basis for a new strategy, and Qualcomm-specific backends do not target Tensor. GrapheneOS's [ordinary TPU/API discussion](https://grapheneos.org/usage#pixel-camera) does not validate every SDK dispatcher; [secure app spawning](https://grapheneos.org/usage#exec-spawning) concerns cold startup, not a demonstrated sustained transcription penalty.
