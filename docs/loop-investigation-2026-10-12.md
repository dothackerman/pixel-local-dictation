# Adapter and evaluation failure diagnosis, 2026-10-12

This records the initial research pass. Subsequent authorized implementation is recorded in
[ADR-006](decisions.md#adr-006--validate-qwen-input-budgets-and-enforce-complete-phone-evidence),
with laptop follow-up measurements in [results](results.md).

Investigation date: 2026-10-12. This is an evidence review and bounded laptop diagnosis, not a new engine decision or a successful product benchmark. The phone, frozen loop, ledger and production implementation were not changed. Personal audio and diagnostic transcripts remained under `corpus/`; this note contains aggregate findings only.

**Conclusion:** the evidence supports investigating adapter contracts and decoder failures before running another parameter sweep. The loop excluded implementation changes, and several observed failures were interpreted too broadly. Three concrete leads use the already installed runtime and assets. Model and accelerator alternatives remain available, but none is yet an established winner under [the requirements](requirements.md).

## Evidence provenance

Relevant sessions are in `~/.claude/projects/-home-oriol-Projects-private-pixel-local-dictation/`: setup/review `c8cb31bb-cad2-4a11-aa4c-1a030eed2ba5.jsonl`, night-two loop `3161711b-114d-416a-87f7-8ab87eef87ea.jsonl`. These were read alongside both handbacks and saved reports under `corpus/lab/`.

The [loop brief](archive/campaigns-2026-10-13.md) intentionally disallowed changing adapters, adding runtimes, downloading models, or changing the schema. That containment was useful for comparable experiments, but a negative result only covers the permitted search space. It cannot rule out an implementation fix or an untested model family.

## 1. Confirmed: Qwen's English catastrophe was context exhaustion

The worst English take contained a 39.568-second chunk whose saved output was one word. The same chunk reproduced the failure today. Runtime diagnostics reported 529 context tokens against a 512-token limit, then truncated audio placeholders from 514 to 497. The decoder had no remaining space to generate a transcription.

| Isolated replay of identical audio and weights | Output words | Result |
|---|---:|---|
| Constructor budget 512 | 1 | Original failure reproduced |
| Constructor 512, per-stream override 1024 | 1 | Already-created model object still capped at 512 |
| Constructor budget 1024 | 73 | Output recovered |

Replacing only that chunk's text in the original primary-feed take reduced its normalized errors **78 → 6**. This was reproduced twice with the larger constructor budget. No forced language, dictionary, model change, or lexical correction was used. The successful decode took roughly 14–16 seconds on the laptop; unlike the truncated run, it actually generated the transcript. This is not a phone-speed result.

Reproduce from the repository root: `models/venv/bin/python corpus/lab/probe-qwen-context.py`. Diagnostic text and results stay in `corpus/lab/qwen-context-diagnostic-2026-10-12.json`. The source report is `corpus/lab/runs/qwen3-1f31656.json`. The probe does not write ledger rows or alter the evaluator.

**Implication:** the previous severe English score is not a valid rejection of Qwen's intrinsic English recognition. It is evidence of an invalid adapter/chunk contract. However, recovering one chunk does not establish superiority: German errors remain, all feeds and the guard need reevaluation, and correctly completed inference is slower. The pinned mechanism is below.

**Verified upstream mechanism:** sherpa-onnx v1.13.8 uses the smaller of the requested total length and the model object's maximum length. Prompt tokens and audio placeholders consume that same budget. If they exceed it, the runtime truncates both placeholders and audio features; after generating its first token, its generation loop stops when the occupied context reaches the budget. Therefore an overlong chunk can produce almost no text rather than an ordinary recognition error. Increasing `max_new_tokens` alone cannot fix an exhausted total context. See [pinned implementation, lines 840–936 and 1025–1030](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/sherpa-onnx/csrc/offline-recognizer-qwen3-asr-impl.cc).

There is an important distinction between fixed and dynamic exports: if the decoder's cache dimension is dynamic, the runtime sets its model object's ceiling from the **constructor's** `max_total_len`; otherwise it uses the fixed export dimension. Raising only the per-stream option cannot exceed the already-created object's ceiling. A dynamic export may need only reconstruction with a larger budget, not re-export. [Pinned model-loading code, lines 311–323](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/sherpa-onnx/csrc/offline-qwen3-asr-model.cc#L311).

**Resolved setup work:** [ADR-006](decisions.md#adr-006--validate-qwen-input-budgets-and-enforce-complete-phone-evidence) added constructor/generation budgets, bounded chunks and truncation rejection. [Full replays](results.md#laptop-setup-validation-2026-10-12) still rejected this export/configuration; the diagnostic was not spliced into a score. Reference-checkpoint parity remains a separate [model experiment](model-options-2026-10-14.md#2-qwen3-asr-17b-with-a-reference-to-runtime-parity-check).

Pinned sherpa also reads a per-stream Qwen language option ([prompt implementation](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/sherpa-onnx/csrc/offline-recognizer-qwen3-asr-impl.cc#L459)); that was not exposed by the inspected adapter. Oracle language remains diagnostic. The centered-STFT and silent-input fixes in [v1.13.7](https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.13.7) already preceded the pinned v1.13.8, so they were not missing fixes.

## 2. Reproduced: the beam-search German regression is concentrated in one collapse

The public guard recording `models/guard/de/140_82_000065.wav` is 20 seconds long, with 65 normalized reference words. Fresh decodes with the pinned assets/runtime gave:

| Decoder | Normalized output words | Errors |
|---|---:|---:|
| Greedy | 64 | 5 |
| Modified beam, width 1 | 64 | 5 |
| Modified beam, width 2 | 24 | 44 |
| Modified beam, width 4 | 4 | 62 |
| Modified beam, width 8 | 4 | 62 |

Across the saved German guard, beam 8 adds 57 errors over baseline, and this clip alone adds all 57; changes on other clips cancel. Beam 4 adds 60, of which 57 come from this clip. The best dictionary variant adds 39, all 39 from this clip. Source reports: `corpus/lab/baseline.json`, `runs/beam8-gate-0cebdf1.json`, `runs/beam4-gate-1f31656.json`, `runs/dict-beam8-s20-5d69a8d.json`, with `runs/` relative to `corpus/lab/`.

Beam 4's last token ends around 1.04 seconds; greedy continues through 19.68 seconds. Beam 2 can retain a later suffix despite deleting the middle. This is catastrophic deletion in the beam path, not proof of a uniform German accuracy cost, and not necessarily an early-return bug. Positive blank penalties 0.5/1 and 0.5 seconds of tail padding did not repair it; splitting the clip reduced the damage but did not match greedy.

Reproduce the central comparison: `models/venv/bin/python corpus/lab/probe-beam-collapse.py`. The [pinned decoder](https://github.com/k2-fsa/sherpa-onnx/blob/11afbd009a7f8c08f4bcf2fc1b265d0df4670fbf/sherpa-onnx/csrc/offline-transducer-modified-beam-search-nemo-decoder.cc) runs hypotheses through the final frame. The exact scoring/duration-search cause remains unresolved.

**Unresolved alternative hypothesis:** compare this case with a reference TDT beam implementation, or test a generic VAD/token-coverage sanity check followed by greedy retry. Timestamps and token durations are already exposed. A tail-only check would miss the width-2 middle deletion; any fallback criterion needs full-guard evaluation, false-trigger rates and runtime accounting. The subsequent [precision investigation](beam-investigation-2026-10-12.md) recovered the clip with FP32; it did not establish a general fallback or search repair.

## 3. Cohere has an avoidable allocation and a separate scheduling problem

The existing runtime already permits one Cohere model with per-stream language selection. A one-instance laptop test alternating German/English matched all four existing public-guard outputs. The original per-stream API evidence, alternating-language smoke and filesystem-loading constraint are retained in [the runtime note](runtime-options-2026-10-14.md#cohere-single-instance-evidence-2026-10-12). ADR-007 implemented the one-instance path; the later audit records its phone results.

The saved phone result (`corpus/lab/phone/20261011-200157.json`) also corrects two interpretations:

- The 9,966 ms worst Stop wait occurs on the **shortest** take, 28.2 seconds, not the longest. Its two decode times are 9,686 and 5,585 ms. About 4.38 seconds of the wait therefore precedes the final chunk's inference; queueing/LID/scheduling need separate instrumentation. The longest take waits 6,435 ms. The app already decodes while recording.
- On identical saved phone audio, baseline German/English errors are **46/22**, versus Cohere **24/23**. That is a strong German gain, not demonstrated improvement in both languages. The best dictionary variant's corresponding counts are **37/13**, with the guard failure above still unresolved.

Removing the second instance did not inherently speed up the selected decoder. Queue/LID/inference decomposition and tail scheduling remain [runtime hypotheses](runtime-options-2026-10-14.md#overlap-alone-does-not-make-the-final-decode-cheap).

## Evaluation findings and disposition

Revision 3 addressed two concrete gaps: `phone_check` had ignored the later `vmHwmAfterPacedKb` peak (baseline 1,846,396 → 2,131,232 kB), and merely printed phone/laptop text differences without applying accuracy rules to phone output. Existing paced/unpaced texts matched, so the latter was a latent validation gap. [ADR-006](decisions.md#adr-006--validate-qwen-input-budgets-and-enforce-complete-phone-evidence) owns the repair and [results](results.md#laptop-setup-validation-2026-10-12) its validation.

The remaining limitations—dependent feed schedules, uncontrolled thermal attribution and missing short/spontaneous/noisy coverage—are consolidated in [the evaluation audit](evaluation-audit-2026-10-14.md). They remain limitations, not explanations invented for every disappointing score.
