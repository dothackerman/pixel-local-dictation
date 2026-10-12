# What the autoresearch loop missed

This records the initial research pass. Subsequent authorized implementation is recorded in
[ADR-006](decisions.md#adr-006--validate-qwen-input-budgets-and-enforce-complete-phone-evidence),
with laptop follow-up measurements in [results](results.md).

Investigation date: 2026-10-12. This is an evidence review and bounded laptop diagnosis, not a new engine decision or a successful product benchmark. The phone, frozen loop, ledger and production implementation were not changed. Personal audio and diagnostic transcripts remained under `corpus/`; this note contains aggregate findings only.

**Conclusion:** the evidence supports investigating adapter contracts and decoder failures before running another parameter sweep. The loop excluded implementation changes, and several observed failures were interpreted too broadly. Three concrete leads use the already installed runtime and assets. Model and accelerator alternatives remain available, but none is yet an established winner under [the requirements](requirements.md).

## Claude history was available

Relevant sessions are in `~/.claude/projects/-home-oriol-Projects-private-pixel-local-dictation/`: setup/review `c8cb31bb-cad2-4a11-aa4c-1a030eed2ba5.jsonl`, night-two loop `3161711b-114d-416a-87f7-8ab87eef87ea.jsonl`. These were read alongside both handbacks and saved reports under `corpus/lab/`. Two Claude research worktrees already existed; they were left untouched. Their inspected transcripts had no completed findings at the time of this review.

The [loop brief](autoresearch.md) intentionally disallowed changing adapters, adding runtimes, downloading models, or changing the schema. That containment was useful for comparable experiments, but a negative result only covers the permitted search space. It cannot rule out an implementation fix or an untested model family.

## 1. Confirmed: Qwen's English catastrophe was context exhaustion

The worst English take contained a 39.568-second chunk whose saved output was one word. The same chunk reproduced the failure today. Runtime diagnostics reported 529 context tokens against a 512-token limit, then truncated audio placeholders from 514 to 497. The decoder had no remaining space to generate a transcription.

| Isolated replay of identical audio and weights | Output words | Result |
|---|---:|---|
| Constructor budget 512 | 1 | Original failure reproduced |
| Constructor 512, per-stream override 1024 | 1 | Already-created model object still capped at 512 |
| Constructor budget 1024 | 73 | Output recovered |

Replacing only that chunk's text in the original primary-feed take reduced its normalized errors **78 → 6**. This was reproduced twice with the larger constructor budget. No forced language, dictionary, model change, or lexical correction was used. The successful decode took roughly 14–16 seconds on the laptop; unlike the truncated run, it actually generated the transcript. This is not a phone-speed result.

Reproduce from the repository root: `models/venv/bin/python corpus/lab/probe-qwen-context.py`. Diagnostic text and results stay in `corpus/lab/qwen-context-diagnostic-2026-10-12.json`. The source report is `corpus/lab/runs/qwen3-1f31656.json`. The probe does not write ledger rows or alter the evaluator.

**Implication:** the previous severe English score is not a valid rejection of Qwen's intrinsic English recognition. It is evidence of an invalid adapter/chunk contract. However, recovering one chunk does not establish superiority: German errors remain, all feeds and the guard need reevaluation, and correctly completed inference is slower. See [the model investigation](model-investigation-2026-10-12.md#1-qwen3-a-concrete-context-budget-failure-path) for pinned runtime code and the dynamic-cache constructor distinction.

**Proposed setup change:** explicitly configure total context and generation budgets, constrain chunks to leave output headroom, and detect truncation. Tokenized hotwords consume the same budget. Rerun the entire unchanged quality evaluation after this contract is fixed; do not splice this diagnostic into the official score.

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

**Proposed next experiment:** compare this case with a reference TDT beam implementation, or test a generic VAD/token-coverage sanity check followed by greedy retry. Timestamps and token durations are already exposed. A tail-only check would miss the width-2 middle deletion; any fallback criterion needs full-guard evaluation, false-trigger rates and runtime accounting. Preserve this guard clip and its acceptance rule. Do not special-case its identity or patch words from the reference.

## 3. Cohere has an avoidable allocation and a separate scheduling problem

The existing runtime already permits one Cohere model with per-stream language selection. A one-instance laptop test alternating German/English matched all four existing public-guard outputs. Memory nevertheless remained substantial, so compliance with the phone budget is unproven. Implementation evidence, measured smoke-test limits, private-filesystem loading requirements and accelerator research are in [the runtime investigation](runtime-investigation-2026-10-12.md).

The saved phone result (`corpus/lab/phone/20261011-200157.json`) also corrects two interpretations:

- The 9,966 ms worst Stop wait occurs on the **shortest** take, 28.2 seconds, not the longest. Its two decode times are 9,686 and 5,585 ms. About 4.38 seconds of the wait therefore precedes the final chunk's inference; queueing/LID/scheduling need separate instrumentation. The longest take waits 6,435 ms. The app already decodes while recording.
- On identical saved phone audio, baseline German/English errors are **46/22**, versus Cohere **24/23**. That is a strong German gain, not demonstrated improvement in both languages. The best dictionary variant's corresponding counts are **37/13**, with the guard failure above still unresolved.

**Proposed next experiment:** one Cohere instance, language fixed automatically per take, then measure chunk emission, queue start, LID, inference and Stop separately. Preserve accuracy when testing shorter residual chunks or overlap/context reuse. Removing the second instance does not inherently speed up the selected decoder.

## Evaluation gaps to close in the next setup round

These findings do not authorize silently changing the current acceptance rules. Preserve old reports and separately record any revised evaluator version.

- **Peak memory is sampled too early for the reported value.** `LabTest` records both `vmHwmKb` and `vmHwmAfterPacedKb`, but `phone_check` uses only the former. Baseline reports 1,846,396 kB although the later high-water mark is 2,131,232 kB. Use the full-run maximum. The same issue affects dictionary results; Cohere's large peak was already present earlier. [Test](../app/src/androidTest/kotlin/ch/og/dictation/LabTest.kt), [evaluator](../tools/evaluate.py).
- **Phone text differences do not change acceptance automatically.** The evaluator prints parity differences but does not rerun the accuracy keep rule on phone output, despite the brief saying phone accuracy takes precedence. Existing paced/unpaced texts match, so this is a latent validation gap rather than an observed source of the current gains.
- **Three feed sizes do not give independent evidence of generalization.** Their spread measures deterministic boundary sensitivity. They are useful robustness checks, not a confidence interval on ten repeatedly tuned recordings.
- **Thermal attribution needs repeated matched runs.** Slower late runs with different variants do not isolate temperature as the cause. Repeat the same configurations in controlled/counterbalanced order before reclassifying a failed phone result.
- **Current personal coverage is narrow.** The ten takes span 28.2–109.68 seconds. Very short, spontaneous, noisy and ambiguous-language dictation remain untested. No new recording request is needed to diagnose the existing defects; eventual product acceptance still requires the reserved hold-out and appropriate usage coverage. Installed FUTO comparisons remain distinct spoken takes, as documented in [results](results.md).

## Recommended order

1. Correct Qwen's adapter contract and rerun its complete quality screen; diagnose or contain the beam deletion failure against the full guard. These are evidenced setup/code changes using existing assets.
2. Validate one-instance Cohere on the phone during an authorized benchmark window, with corrected memory accounting and decomposed latency. Keep its German gain and English uncertainty explicit.
3. Screen Moonshine German Small/English Medium streaming next. Its Android runtime and key-term biasing directly address this workflow. If needed, test Canary 1B v2 and full Whisper large-v3 as accuracy ceilings before investing in their deployment. Sources and discriminating tests are in [model research](model-investigation-2026-10-12.md).
4. Pursue CPU/runtime profiling, Vulkan or Tensor G5 conversion when they enable a verified accuracy advantage to meet the device budget. Existing source support is not evidence that a particular ASR graph runs efficiently on this GrapheneOS phone; see [runtime research](runtime-investigation-2026-10-12.md#runtime-paths-and-their-actual-relevance).

The next loop should begin with explicit model-input contracts and a per-clip failure review, then search a justified parameter family. More unattended runs in the same closed space would leave the identified implementation failures intact.
