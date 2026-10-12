# Model experiments missing from the closed loop

Research date: 2026-10-12. Source/code investigation and proposed tests, not a new model selection or phone benchmark. The [requirements](requirements.md) and [experiment protocol](autoresearch.md) remain authoritative. This is research outside the unattended loop; no frozen pipeline, evaluator, model assets or phone settings were changed.

The highest-value model experiment is to validate the existing Qwen3 adapter before rejecting its checkpoint. After that, screen Moonshine's language-specific streaming models and Canary 1B v2. Full Whisper large-v3 is a lower-priority accuracy ceiling test. Before further Parakeet decoding sweeps, investigate the catastrophic beam deletion identified in [the loop investigation](loop-investigation-2026-10-12.md); the earlier aggregate regression does not establish that all beam-based dictionary approaches must fail.

## 1. Qwen3: a concrete context-budget failure path

**Observed configuration:** `Qwen3.__init__` in [the laptop pipeline](../tools/pipeline.py) supplies model paths, threads and hotwords, but no token budgets. The installed sherpa Python wrapper defaults to `max_total_len=512` and `max_new_tokens=128`. [The variant](../variants/qwen3.json) does not cap chunk duration. These are adapter defaults, not evidence of the original checkpoint's maximum usable recording length.

**Verified upstream mechanism:** sherpa-onnx v1.13.8 uses the smaller of the requested total length and the model object's maximum length. Prompt tokens and audio placeholders consume that same budget. If they exceed it, the runtime truncates both placeholders and audio features; after generating its first token, its generation loop stops when the occupied context reaches the budget. Therefore an overlong chunk can produce almost no text rather than an ordinary recognition error. Increasing `max_new_tokens` alone cannot fix an exhausted total context. See [pinned implementation, lines 840–936 and 1025–1030](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/sherpa-onnx/csrc/offline-recognizer-qwen3-asr-impl.cc).

There is an important distinction between fixed and dynamic exports: if the decoder's cache dimension is dynamic, the runtime sets its model object's ceiling from the **constructor's** `max_total_len`; otherwise it uses the fixed export dimension. Raising only the per-stream option cannot exceed the already-created object's ceiling. A dynamic export may need only reconstruction with a larger budget, not re-export. [Pinned model-loading code, lines 311–323](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/sherpa-onnx/csrc/offline-qwen3-asr-model.cc#L311).

The setup report's severe English score consequently cannot establish a generally poor English model until truncation is ruled out. The [loop investigation](loop-investigation-2026-10-12.md#1-confirmed-qwens-english-catastrophe-was-context-exhaustion) records the executed diagnostic and its limits; this note owns the source evidence and model alternatives.

**Proposed discriminating test:** replay the identical failing chunk with debug diagnostics, record audio/prompt/context token counts and the initialized model ceiling, then compare: original settings; reconstruction with adequate context if supported; a duration cap that reserves space for generated text. Keep identical weights, language behavior and scoring. Add hotwords only after this passes, because their prompt tokens reduce the available budget further. A setup change should expose and validate these budgets or reject unsafe chunks instead of treating truncation as a model-quality verdict.

If errors remain on safe-length chunks, compare the original floating-point checkpoint locally against the INT8 export on exactly those chunks before blaming quantization. Qwen's first-party implementation exposes automatic or forced language and a generation budget; it now also documents native Transformers support. These are reference-inference options, not Android deployment evidence. See [Qwen's official inference documentation](https://github.com/QwenLM/Qwen3-ASR#quick-inference).

Language is testable without a second model instance: pinned sherpa v1.13.8 reads the per-stream `language` option and inserts it into the decoder prompt, even though this repository's constructor/schema does not expose it. Use automatically selected `English`/`German` only after comparing with automatic recognition; oracle language is a diagnostic, not an R2-compliant product. [Pinned prompt and stream-option code](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/sherpa-onnx/csrc/offline-recognizer-qwen3-asr-impl.cc#L459).

A generic runtime upgrade is not yet a justified cure. The centered-STFT alignment and silent-input fixes listed in sherpa 1.13.7 predate the project's pinned 1.13.8. Recommending those fixes as missing would be incorrect. [Release history](https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.13.7).

## 2. Moonshine: the most practical untested model family

The publisher currently lists German Small Streaming at 123M parameters and English Medium Streaming at 245M, with English Small Streaming also available. These are separate models, so they need invisible routing. The published German and English WERs use different evaluation panels and cannot rank this choice against the personal Parakeet results. [Available models and evaluation caveats](https://moonshine-voice.readthedocs.io/en/latest/models/available-models/).

Screen German Small plus English Medium using oracle language in the test harness first. If either language clearly loses, stop before Android integration. If promising, reuse the existing language-identification concept, buffer and replay the initial speech, and test the entire router on short and alternating-language recordings. The existing ten-take routing result does not establish short-utterance reliability. The official runtime provides an Android Maven package and samples, which makes this a more concrete deployment prospect than a server-only checkpoint. Pin a verified release containing the chosen models rather than copying a documentation example version. [Android integration](https://moonshine-voice.readthedocs.io/en/latest/using/adding-the-library/).

A newly relevant reason to prioritize this test is runtime key-term biasing for streaming models, exposed in Java as well as Python. Its implementation biases subword decoding; it does not simply replace output words. This addresses the project's documented names problem directly. Test unbiased recognition first, then the dictionary mechanism against its unbiased twin and false-insertion guard. The publisher explicitly reports worsening unrelated-word errors and false alarms at excessive boosts, so its general improvement claim is not a personal-corpus guarantee. [Key terms, implementation and trade-offs](https://moonshine-voice.readthedocs.io/en/latest/models/domain-customization/).

## 3. Canary: 180M did not test the 1B accuracy ceiling

Canary 1B v2 is a distinct, larger checkpoint supporting English/German among 25 languages, with different training from the tested 180M Flash model. Its official path includes NeMo and Transformers; the documented benchmark hardware is NVIDIA GPUs. Its long-form recipe uses one-second overlaps beyond 40 seconds. None of those results establishes Pixel speed or the correctness of a custom export. [Official model card](https://huggingface.co/nvidia/canary-1b-v2).

The pinned sherpa export directory contains only the 180M Flash export/test, hard-coded to that checkpoint. This confirms a missing integration path, not that the larger model cannot run on the phone. [Pinned export directory](https://github.com/k2-fsa/sherpa-onnx/tree/v1.13.8/scripts/nemo/canary). Run a local reference-quality screen of 1B v2 before investing in conversion. Only a substantial per-language accuracy gain justifies building and validating an Android-size export. Canary 1B Flash is another English/German-capable checkpoint, but it is not necessary to sweep both initially; prefer v2 as the first ceiling test, without claiming that its extra languages inherently improve German. [1B Flash model card](https://huggingface.co/nvidia/canary-1b-flash).

## 4. Whisper: separate checkpoint quality from runtime cost

The tested large-v3-turbo is a pruned/fine-tuned large-v3 with four decoder layers instead of 32. A turbo failure does not prove full large-v3 has identical accuracy. A single reference run of full large-v3 can establish whether extra decoder capacity buys enough German/English improvement to pursue. [Official turbo model card](https://huggingface.co/openai/whisper-large-v3-turbo).

Changing to whisper.cpp with the same weights primarily opens different execution and decoding options; it is not, by itself, evidence of more accurate recognition. Upstream supports Android, quantization and Vulkan, but its support matrix is not a Tensor G5/GrapheneOS benchmark. Investigate that runtime only after an accuracy advantage survives controlled decoding and export comparisons. [whisper.cpp](https://github.com/ggml-org/whisper.cpp).

## Proposed order

1. Resolve Qwen's context contract and rerun its quality screen; this needs no new model download.
2. In parallel with the separate Cohere single-instance investigation, screen Moonshine German Small/English Medium locally.
3. If needed, screen Canary 1B v2 and full Whisper large-v3 for their accuracy ceiling before implementing new phone runtimes.
4. Spend conversion, acceleration and phone-measurement effort only on candidates with a repeatable per-language gain. Apply the existing silence, insertion, names, memory and finalization checks to the complete path.

No recommendation above establishes R4, and no publisher benchmark substitutes for the reserved hold-out and phone measurements.
