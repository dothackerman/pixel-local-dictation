# Milestones and remaining work

[Requirements](requirements.md) define product commitments. This roadmap retains the original milestone gates and unfinished obligations; **The only current execution plan is [Allocation and measurement](next-experiment-plan.md).** It owns the next implementer's task and readiness. This roadmap owns unfinished product gates and future work; it supplies no additional runs.

## 1–2. Integration and local recognition — completed prototype gates

Tags `m1` and `m2` record FUTO launch/insertion and local English/German recognition, including replay, microphone flow and privacy checks. [Archived milestone history](archive/milestones-2026-10-10.md) owns the evidence and integration rationale; [ADR-003](decisions.md#adr-003--parakeet-v3-via-sherpa-onnx-with-a-matmul-only-int8-encoder) records the provisional engine approval. These gates prove operation, not superiority or permanent deployment.

## 3. Personal improvement — open

R4 still needs speed **and** accuracy improvement over installed FUTO in each language. [Results](results.md) own the approved reduced-corpus protocol and first comparison; [the audit](evaluation-audit-2026-10-14.md) explains why later development-set wins do not close this gate.

The original coverage proposal was 20 English and 20 German clips of 5–30 s, plus several 60 s recordings, with actual accent, names, technical vocabulary, dates/numbers, quiet/noisy conditions and ambiguous starts. The reduced October 10 corpus did not cover all of that. Preserve the coverage need; any new collection follows [the recording policy](experiment-protocol.md#recording-policy), not this old sample-count proposal.

Compare identical audio where feasible. Matching FUTO weights/settings in a harness is only a model baseline; FUTO WAV import remains unestablished. Otherwise use matched live trials and label the different-take limitation. Report each language's word errors, correction effort, names/numbers, meaning errors, punctuation/capitalization and wrong-language choices. Measure tap-to-ready, cold load, first useful partial when available, Stop-to-final and last-word-to-final, with median/slow tail, cold/warm conditions, memory and repeated-session heat/battery. Include silence, cancel, focus changes and interruption.

**Gate:** the complete automatic phone path satisfies R4 without violating other requirements. Once a candidate is technically plausible, freeze its settings and vocabulary before a small reserved validation session covering short/spontaneous use. Oriol supplies speech, operates FUTO and reviews references; the implementer designs and interprets the comparison. Report script/session-level sensitivity proportionally to the limited evidence. A German-only gain or a win against the app baseline cannot establish R4; marginal results remain unresolved.

## 4. Alternative recognition paths — pending experiments

Moonshine German Small Streaming plus English Small/Medium remains the conditional alternative from the original roadmap. Oracle-language quality screening is diagnostic; a product path needs automatic local routing, buffered/replayed first words, and measured short-utterance fallback. No router is selected. If parallel decoding is considered, model confidence scores need calibration and compute must be measured.

**Gate:** evaluate the complete router/recognizer, including detection delay, wrong routes, fallback frequency and language alternation. Apply the same requirements. Whistle, larger models and accelerated Whisper remain challengers; [model research](model-options-2026-10-14.md) supplies tests and the future-work sequence below supplies order.

## 5. Runtime optimization and handoff — pending

Oriol requested comparisons of quantization schemes (selective MatMul, finer per-layer choices, accelerator-required precision) and alternative runtimes on 2026-10-10. Compare against FP32 on the personal corpus, including beam/dictionary parity; greedy parity alone is insufficient. Tune CPU threads on the phone. Vulkan needs a supported runtime; Tensor G5 needs a verified official sample on this GrapheneOS build before model conversion. Confirm actual backend execution and numerical correctness, including any CPU fallback. [Runtime research](runtime-options-2026-10-14.md) owns feasibility evidence; future work follows the sequence below.

**Gate:** repeat milestone 3 after material changes. Deliver reproducible source/build instructions, pinned dependencies/assets and licenses, signed APK, per-language results, setup instructions and tested rollback to FUTO with its baseline model retained. Keep the key outside Git. Record explicit engine approval separately from implementation.

## Validation gaps and unscheduled request

These are retained obligations or uncertainties, outside the current allocation experiment:

- Answered-call interruption, longer and battery-only model survival, repeated-session heat/battery, and short/spontaneous/noisy or ambiguous-language use remain unverified by the milestone evidence. The one-hour locked-screen connection test had no completion evidence in the old plan; fresh-session connection checks do not establish unattended survival.
- The assembled `tools/export-fp32.sh` was not rerun end to end in the recorded milestone work. The quantization step reproduced its encoder checksum; that does not prove a clean full export.
- **Personal dictionary:** requested by Oriol on 2026-10-10, unscheduled. Mechanism experiments are approved ([ADR-005](decisions.md#adr-005--schema-2-range-finding-a-dictionary-as-mechanism-test-and-two-new-model-families)); user editing/entry UX is undecided. Measure the mechanism before building a settings screen.

## Future experiments

These are planned directions from the earlier experiment strategy, **not prepared execution plans**. Review the current experiment's handback before freezing the next one; deferral does not cancel a commitment.

| Order | Work to prepare | Decision sought |
|---|---|---|
| After allocation evidence | Whistle and Moonshine, unbiased/dictionary twins; distinguish Moonshine oracle screening from complete automatic routing | A simpler model/runtime with useful quality and phone headroom |
| Precision/execution alternatives | Finite per-channel/selective-layer Parakeet exports, CPU-provider/version comparison, public collapse regression and full quality guards | Retain FP32 beam quality at lower cost |
| Larger-model screen | Qwen 1.7B reference/native pair and 0.6B diagnostic; conditional Canary 1B v2 and full Whisper large-v3 | Separate checkpoint capacity from conversion/runtime loss before phone engineering |

Hardware feasibility may proceed beside the small-model work **after allocation/profiling evidence is reviewed**. The current stage-1 comparisons have profiling off; if their evidence cannot identify the bottleneck, prepare a bounded profiling follow-up. First test an official Tensor G5 sample in the permitted benchmark package; only verified execution/output earns a fixed-shape encoder with CPU search. GPU/CPU paths likewise need a named supported backend and actual assignment evidence.

Retained ceilings for each later bounded experiment are 12 laptop configurations, 40 laptop processes/eight inference hours, and eight phone processes total, including controls/retries. Phone work is limited to the smaller of four hours or its authorized window minus restoration margin. Setup freezes smaller concrete budgets where possible under [the protocol](experiment-protocol.md). Group layer sweeps in advance; a new hypothesis after the cap needs a new plan, not a reset.

Unscheduled hypotheses remain: idle-pause warm-up, asset versus file loading, generic greedy fallback/reference-TDT search, per-name normalized hotword boosts, phonetic entity matching with abstention, and overlap/short-tail scheduling with omission/duplication checks. [Research](research.md) owns their evidence and limitations; the dictionary UI request above is distinct from mechanism tests.
