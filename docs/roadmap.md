# Milestones and remaining work

[Requirements](requirements.md) define product commitments. This roadmap retains the original milestone gates and unfinished obligations; [the experiment plan](next-experiment-plan.md) owns the current batch sequence. **Run [release 1a](batch-1a-v1.md) next when its launch prerequisites are met.** Later work below is not part of that frozen batch.

## 1–2. Integration and local recognition — completed prototype gates

Tags `m1` and `m2` record FUTO launch/insertion and local English/German recognition, including replay, microphone flow and privacy checks. [Milestone history](plan.md) owns the evidence and integration rationale; [ADR-003](decisions.md#adr-003--parakeet-v3-via-sherpa-onnx-with-a-matmul-only-int8-encoder) records the provisional engine approval. These gates prove operation, not superiority or permanent deployment.

## 3. Personal improvement — open

R4 still needs speed **and** accuracy improvement over installed FUTO in each language. [Results](results.md) own the approved reduced-corpus protocol and first comparison; [the audit](evaluation-audit-2026-10-14.md) explains why later development-set wins do not close this gate.

The original coverage proposal was 20 English and 20 German clips of 5–30 s, plus several 60 s recordings, with actual accent, names, technical vocabulary, dates/numbers, quiet/noisy conditions and ambiguous starts. The reduced October 10 corpus did not cover all of that. Preserve the coverage need; any new collection follows [the recording policy](next-experiment-plan.md#recording-policy), not this old sample-count proposal.

Compare identical audio where feasible. Matching FUTO weights/settings in a harness is only a model baseline; FUTO WAV import remains unestablished. Otherwise use matched live trials and label the different-take limitation. Report each language's word errors, correction effort, names/numbers, meaning errors, punctuation/capitalization and wrong-language choices. Measure tap-to-ready, cold load, first useful partial when available, Stop-to-final and last-word-to-final, with median/slow tail, cold/warm conditions, memory and repeated-session heat/battery. Include silence, cancel, focus changes and interruption.

**Gate:** the complete automatic phone path satisfies R4 without violating other requirements. Marginal or inconsistent evidence remains unresolved; it does not justify a winner. [The experiment plan's final validation](next-experiment-plan.md#primary-assistants-review-and-final-validation) applies this gate after a technically plausible candidate exists.

## 4. Alternative recognition paths — pending experiments

Moonshine German Small Streaming plus English Small/Medium remains the conditional alternative from the original roadmap. Oracle-language quality screening is diagnostic; a product path needs automatic local routing, buffered/replayed first words, and measured short-utterance fallback. No router is selected. If parallel decoding is considered, model confidence scores need calibration and compute must be measured.

**Gate:** evaluate the complete router/recognizer, including detection delay, wrong routes, fallback frequency and language alternation. Apply the same requirements. Whistle, larger models and accelerated Whisper remain challengers; [model research](model-options-2026-10-14.md) supplies tests and [the batch sequence](next-experiment-plan.md#batch-sequence-and-purpose) supplies order.

## 5. Runtime optimization and handoff — pending

Oriol requested comparisons of quantization schemes (selective MatMul, finer per-layer choices, accelerator-required precision) and alternative runtimes on 2026-10-10. Compare against FP32 on the personal corpus, including beam/dictionary parity; greedy parity alone is insufficient. Tune CPU threads on the phone. Vulkan needs a supported runtime; Tensor G5 needs a verified official sample on this GrapheneOS build before model conversion. Confirm actual backend execution and numerical correctness, including any CPU fallback. [Runtime research](runtime-options-2026-10-14.md) owns feasibility evidence; the current plan schedules bounded releases.

**Gate:** repeat milestone 3 after material changes. Deliver reproducible source/build instructions, pinned dependencies/assets and licenses, signed APK, per-language results, setup instructions and tested rollback to FUTO with its baseline model retained. Keep the key outside Git. Record explicit engine approval separately from implementation.

## Validation gaps and unscheduled request

These are retained obligations or uncertainties, not instructions to expand release 1a:

- Answered-call interruption, longer and battery-only model survival, repeated-session heat/battery, and short/spontaneous/noisy or ambiguous-language use remain unverified by the milestone evidence. The one-hour locked-screen connection test had no completion evidence in the old plan; fresh-session connection checks do not establish unattended survival.
- The assembled `tools/export-fp32.sh` was not rerun end to end in the recorded milestone work. The quantization step reproduced its encoder checksum; that does not prove a clean full export.
- **Personal dictionary:** requested by Oriol on 2026-10-10, unscheduled. Mechanism experiments are approved ([ADR-005](decisions.md#adr-005--schema-2-range-finding-a-dictionary-as-mechanism-test-and-two-new-model-families)); user editing/entry UX is undecided. Measure the mechanism before building a settings screen.
