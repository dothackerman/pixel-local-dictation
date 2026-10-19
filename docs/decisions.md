# Architecture decision records

ADR status is independent of implementation. Product commitments live in [requirements](requirements.md); this file records design rationale. Entries retain their dated rationale. Later updates below identify changed scope; [the current experiment](experiments/current.md) owns execution scope and readiness.

## ADR-001 — Manually routed streaming candidates

**Date:** 2026-10-09. **Status:** superseded research recommendation; never approved or implemented. **Superseded by:** ADR-002.

The original research suggested trying FUTO nightly Nemotron and language-specific Moonshine streaming models to reduce finalization delay. It assumed manual language selection was acceptable. Oriol clarified that the existing setup automatically detects English/German and that he wants no extra steps. Direct manual routing therefore conflicts with R1/R2. These engines are not obsolete: Moonshine remains an alternative with invisible routing; Nemotron would need an automatic path not established by the inspected FUTO build.

## ADR-002 — Parakeet-first automatic-language prototype

**Date:** 2026-10-09. **Status:** originally proposed; the working engine choice was subsequently approved in ADR-003. This does not retroactively approve every part of the proposal. **Implementation:** the Parakeet/FUTO prototype reached milestone 2 (`m2`); [history](archive/milestones-2026-10-10.md) records its limits. **Supersedes:** ADR-001's implementation priority.

**Context:** R1–R3 need an offline recognizer that detects language without user input. Parakeet TDT v3 documents automatic detection and both languages; Moonshine's published streaming models are language-specific.

**Proposal:** prototype Parakeet v3 on CPU behind a separate FUTO-compatible voice input app. Prove launch/insertion first, then benchmark recognition on the actual phone. Only investigate alternative routing or hardware acceleration when measured results justify it.

**Trade-offs:** one multilingual engine avoids a separate language router, but automatic detection can still fail on short recordings. The roughly 600M-parameter model may have unacceptable load/latency/heat; its accuracy and speed advantage over FUTO are unproven. This is a reversible experiment, not a permanent engine commitment.

**Alternatives:** Moonshine with local language routing may deliver lower delay but adds error and startup paths. Whistle is compact but has no demonstrated personal accuracy win. Accelerated Whisper preserves a familiar model family but compression alone is not new: the current FUTO baseline is already quantized. A laptop/cloud recognizer conflicts with R3 and is outside this project.

**Revisit when:** phone results fail R4, integration cannot meet R1, or a competing full path demonstrates a better result. Document findings before changing the proposed engine; preserve the requirements.

## ADR-003 — Parakeet v3 via sherpa-onnx with a MatMul-only INT8 encoder

**Date:** 2026-10-10. **Status:** approved by Oriol on 2026-10-10 as the working choice for milestone 2 ("for now"), knowing that the English accuracy criterion failed; not a permanent engine decision. **Implementation:** integrated in milestone 2 (`m2`) and retained as the app's working default; the table below is the earlier spike evidence. **Builds on:** ADR-002.

**Evidence.** Pixel 10 Pro XL, app 0.1.5 benchmark build, sherpa-onnx 1.13.8, 4 threads, four takes by Oriol (D6 twice, E6 twice, 28–34 s, 242 reference words). Raw files: `corpus/files/benchmark/spike-*.json` (not in Git). Four takes are anecdotal; FUTO's figures are one different take per script from [baseline](evaluation/baseline.md).

| Gate from [plan](archive/milestones-2026-10-10.md) | Published INT8 | MatMul-only INT8 encoder, fp32 decoder and joiner |
|---|---|---|
| Under 10 s after stop, 35 s take, segmented, ten takes at real-time pace | 0.53–1.00 s, median 0.63 s: pass | 0.59–1.04 s, median 0.65 s: pass |
| Whole-take decode of the same takes (not a gate) | 1.6–2.3 s | 1.7–2.2 s |
| Real-time factor per segment, median / maximum | 0.062 / 0.077: pass | 0.065 / 0.083: pass |
| Parity with fp32 within 1 point (laptop) | fail: up to 6 points worse | pass: identical word error rate |
| Peak memory (`VmHWM`) under 2 GB | 1,588,596 kB: pass | 2,051,520 kB: at the limit (1.96 GiB, 2.05 GB) |
| Thermal status never above moderate | none (0) throughout: pass | none (0) throughout: pass |
| At most one join error per long take | none seen | none seen |
| Language flips between segments | none seen | none seen |
| Word error rate no higher than FUTO, German (FUTO 12.1 %) | 7.8 %: pass | 2.6 %: pass |
| Word error rate no higher than FUTO, English (FUTO 1.6 %) | 6.3 %: fail | 3.2 %: fail |

Also measured: cold load 3.9 s and 4.2 s; total PSS after load 764 MB and 1,045 MB. Battery is not assessable: the phone was charging over USB and the level rose. One whole-take decode with 2, 6 and 8 threads was slower than the 4-thread decodes in both models.

**Approved working choice.** Continue milestone 2 with sherpa-onnx and the MatMul-only encoder (built by `tools/export-fp32.sh` and `tools/quant_variant.py` from the official checkpoint; encoder SHA-256 `d5be30ebe93c940eddfeb5c6ce2bf67cdbe5d80b2c5a0256a1bbd6c1f6d27da6`). The published INT8 files are dropped: they fail parity and are worse in both languages.

**Why despite the failed English criterion.** The English shortfall is two words in each of two takes, "Miss Keller" for "Mrs Keller" and "Wintertour" for "Winterthur"; FUTO's take also has "Miss Keller", and FUTO wrote "Wintertour" in its German single sentences. Two takes cannot settle R4 for English; milestone 3 measures it on a real corpus. Speed exceeds the interim target by an order of magnitude, so the remaining question is accuracy on names, not the runtime.

**Trade-offs.** The model is self-built rather than a published artifact, so the build must be reproducible and its checksum pinned; the APK grows to about 990 MB of assets; peak memory sits at the 2 GB gate and loading through `AssetManager` is the suspected cause (the library also offers loading from a file path, untested).

**Alternatives.** Whole-take decoding without VAD would already meet the 10 s target at about 2 s and removes segment joins, at the cost of no in-panel preview and a wait that grows with take length. The plan's fail path (ONNX Runtime directly) addresses a runtime problem that the spike did not find. Roadmap milestone 4 (Moonshine) remains the path if milestone 3 shows English accuracy below FUTO.

**Update 2026-10-10, after the first measurement run ([results](evaluation/results.md)):** the revisit condition is reached. With this model accuracy is level with FUTO in both languages, not better; full precision does not change that. Decoding during the recording leaves roughly a tenfold speed reserve on the phone (real-time factor about 0.06), which a more accurate, slower model could use. No new engine decision has been taken.

**Revisit when:** milestone 3 results exist, or peak memory or cold load proves a problem in daily use. Oriol wants other quantisations (including selective, per-layer schemes) and other runtimes compared in the final optimisation experiments, see [roadmap](roadmap.md) milestone 5.

## ADR-004 — Experiment loop with a closed variant schema and the phone as second judge

**Date:** 2026-10-10. **Status:** approved by Oriol on 2026-10-10 as the method for the accuracy experiments; no engine decision. **Builds on:** ADR-003's revisit condition. **Campaign record:** [history](archive/campaigns-2026-10-13.md). The decision below describes the October 10 scope.

**Context at approval.** Accuracy was comparable with FUTO and the tested Stop waits were fast, and Oriol wants the experiments to run without him, phone included, with no new recordings until a candidate has earned them. An adversarial review of the first design (2026-10-10) found that a free-form editable pipeline and a 5-word keep rule would overfit ten takes, that a laptop-only pipeline cannot be the same product as the phone's, and that the chunk boundary alone moves the error count by several words.

**Decision.**

- One closed, versioned variant schema (`tools/variant.py`, `Variant.kt`), implemented identically on the laptop (`tools/pipeline.py`) and in the app (`Pipeline`, `VadSegmenter`, `SherpaRecognizer`, `CanaryRecognizer`). The loop edits only `variants/*.json`; new keys are a setup-session change. No lexical post-processing exists.
- A frozen evaluator (`tools/evaluate.py` over `tools/score.py`), checksummed by `tools/lab.py check`, with a paired per-take keep rule, error counts averaged over three recorder feed sizes, a terms column, a punctuation/case column, a silence guard, and a public-clip guard that can only veto.
- The phone is stage 2: the benchmark build's `LabTest` runs any pushed variant from the app's private storage through the app's own classes and reports real-time factor, wait after Stop at real-time pace, peak memory and thermal status. The daily package is reused (Oriol's choice, 2026-10-10): the benchmark build stays installed on loop nights and the release build comes back afterwards. The wrapper gains `push-variant`, `lab` and `pull-results`, all scoped to the package; Oriol applies that change by hand from the proposed file next to the wrapper.
- Memory is a flag, not a gate: the 2 GB figure was a spike heuristic on a 16 GB phone.
- The four unrecorded scripts are a hold-out that lives outside the repository; the loop runner refuses to start while they are in `corpus/`. Recording happens once, when the pre-registered trigger fires.
- Out of scope for the loop: hotwords (a list from the scripts would leak the corpus), Tensor G5 acceleration (changes speed, not accuracy; the CPU reserve is tenfold), model downloads (assets are pinned in `experiments.lock` by the setup session).

**Evidence from the setup run (laptop, 2026-10-10).** Baseline variant on the ten takes: German 46.0, English 25.3 errors averaged over three feed sizes (total 71.3 of 1,216 words); the sum of per-take spreads between feed sizes is 19 words, which is why single-feed counts are not compared. Guard: German 4.8 %, English 2.0 %. Prepared families on the same ten takes, as a first accuracy-ranking entry and not a verdict: Whisper large-v3-turbo German 41.0, English 67.0 (one English take at 25 %, the failure mode is to be inspected by the loop), laptop real-time factor 0.52, writes "Thank you." on silence; Canary 180M flash German 58.7, English 36.7, real-time factor 0.11, language identification correct on all ten takes. Canary 1B v2 is not runnable with sherpa-onnx 1.13.8.

**Trade-offs.** The loop searches a fixed knob space instead of editing code; ideas outside the schema wait for a setup round. Phone and laptop texts differ by a few words for the same variant because of boundary sensitivity, so parity is checked with a tolerance and the phone text counts when they diverge. The benchmark build, debuggable, stays on the phone overnight.

**Revisit when:** a kept variant reaches the recording trigger, the control row shows laptop and phone accuracy diverging, or the schema blocks an idea with evidence behind it.

**Scope supersession:** ADR-005 lifted the hotword exclusion and introduced the 3.5 GB rejection policy; ADR-006 corrected enforcement. Hardware/runtime proposals are now sequenced in the roadmap. The closed-loop/setup separation remains; these earlier exclusions are not current blanket bans.

**Update 2026-10-11, after the first night.** 40 experiments, none kept (`corpus/lab/handback-2026-10-10.md`). The review found the cause: nobody had checked whether any schema-1 knob could move the 8-word margin. It could not; see ADR-005.

## ADR-005 — Schema 2: range-finding, a dictionary as mechanism test, and two new model families

**Date:** 2026-10-11. **Status:** approved by Oriol on 2026-10-11, with the note that the dictionary's user experience is not designed and not decided. **Builds on:** ADR-004.

**Context.** The baseline's 67 word errors on the ten takes are 38 names and terms, 10 numbers and formats, 16 ordinary words and 3 insertions. The same dozen names are missed by all 36 Parakeet variants of the first night; cutting and decoding knobs reach only the ordinary words, and their whole span (68.7 to 86.0 total errors) lies inside the margin. Whisper turbo misses the same names and runs at half real time. Beam search gains about 3 words but writes on silence.

**Decision.**

- **Range-finding is a rule.** The extremes of a knob family are run first; a family whose span stays inside the keep margin is not an experiment (`tools/lab.py range`).
- **Dictionary via hotwords, judged as a mechanism.** Schema 2 adds `hotwords` (a file of names, read from the laboratory directory on both sides) for Parakeet with modified beam search and for Qwen3 as a prompt. The list is the 43 names of the ten scripts, so the test is "names in the user's list are recognised", with pre-registered metrics: terms recovered, errors outside dictionary words not worse, false insertions in the 210 public clips (where none of the names occur), silence clean, speed on the phone. Oriol lifted the earlier "no hotwords" on this basis. The smoke test shows the mechanism works ("Mrs Keller" recovered at score 3) and that score 6 floods the text with insertions.
- **No-speech gate** (`post.skip_silent_chunks`): a chunk in which the voice detector found no speech is not decoded. Removes the silence failure of beam search and Whisper on both sides.
- **Two new families** from the pinned runtime, assets pinned in `experiments.lock`: Qwen3-ASR 0.6B INT8 (laptop real-time factor about 0.2, detects the language itself) and Cohere Transcribe 14 languages INT8 (2.9 GB, laptop real-time factor about 0.25, needs the language, so it shares Canary's language-identification path). Canary 1B stays unavailable in this runtime.
- Nights only: Oriol needs the phone during the day; the brief tells the loop to skip stage 2 after 06:30.

**Current scope:** dictionary mechanism approval remains; UI design remains undecided. Current execution needs each batch's dated authorized window; Oriol's daytime phone needs still apply unless he explicitly authorizes a different arrangement. [The current recording policy](experiments/protocol.md#recording-policy) preserves the stricter trigger.

**Trade-offs.** A dictionary that works on the scripts' names proves the mechanism, not Oriol's real vocabulary; the recording session, if triggered, tests names outside the list. Hotword scores above 3 are destructive, so the useful range is narrow. Cohere is near the phone's speed budget and 2.9 GB. The dictionary's user experience (how names get into the list, how they are edited) is deliberately undecided.

**Update 2026-10-11, after the first dictionary run and a second adversarial review.** The comparison point for the dictionary rule's other-word errors and guard was changed from experiment 0 to the no-dictionary twin (the same pipeline without hotwords), because modified beam search by itself raises the guard's German rate from 4.8 % to 6.6 % and the rule is meant to isolate the dictionary's effect. The terms metric still compares with experiment 0. Consequence: the beam-search regression rides along into any dictionary keep and is not judged by the rule; the recording trigger therefore additionally requires the guard not to be worse than experiment 0, and Oriol weighs the regression before any port. Also from the review: Cohere's first phone run is a measurement (two model instances, about 6 GB; wait after Stop about 4 s by construction), a memory flag above 3.5 GB is not a keep, and a cohere variant sets either a fixed language or the identifier, never both (the two sides would disagree; validator gap to close in a setup round). Laptop falsification of Cohere's gain before the phone: language identification right on all 210 public clips, insertions in the ten texts 1 against the baseline's 5; the run without number formatting is in the ledger.

**Revisit when:** Cohere's phone measurement is in the hand-back, or a dictionary variant reaches the trigger.

## ADR-006 — Validate Qwen input budgets and enforce complete phone evidence

**Date:** 2026-10-12. **Status:** implementation authorized by Oriol on the existing feature branch;
no engine selection or daily deployment. **Implementation:** schema/evaluator revision 3 in `c63cb0d`, laptop validation in [results](evaluation/results.md#laptop-setup-validation-2026-10-12), followed by the night-three campaign. **Builds on:** ADR-004/005.

**Context.** The [loop investigation](research/loop-investigation-2026-10-12.md) reproduced a Qwen chunk
whose audio consumed its 512-token context, leaving almost no transcription. Raising the budget
only on an audio stream could not enlarge the already-created model's cache. The evaluator also
ignored a later memory peak and did not enforce its own stated requirement to judge divergent
phone text. A separate beam investigation found that greedy precision parity did not establish
beam-search precision parity.

**Decision.** Schema 3 exposes Qwen's context and generation budgets identically in Python and
Kotlin, initializes the recognizer with them, bounds chunk duration, and reserves space for the
actual hotword prompt and generated output. Legacy Qwen variants migrate to bounded settings;
the explicitly named corrected variant distinguishes new results from historical reports. The
guard rejects near-generation-limit output rather than accepting possibly truncated text.

Evaluator revision 3 uses the complete recorded memory peak and enforces the already documented
3.5 GB rejection limit while preserving the 2 GB warning. It requires matching complete laptop
evidence before phone acceptance and applies the unchanged accuracy rules to phone output, with
paced replacements checked as well. The public guard and silence evidence remain laptop-only.
Dictionary comparisons must identify their genuine no-dictionary reference and include the full
expected take/guard sets. Existing reports and ledger rows are not retroactively rewritten.

**Trade-offs.** Qwen budget bounds are conservative for the pinned dynamic-cache export; a
near-limit rejection may also reject valid output and requires a larger budget or shorter chunk.
These checks do not certify every export or detect every model hallucination. Correctly completing
a transcript costs more inference than the broken early cutoff. Stricter evidence checks can reject
historical incomplete reports that previously passed. No word-error thresholds or normalization
rules change, and no recording trigger is relaxed.

**Revisit when:** full laptop comparisons exist, an export changes its input contract, or the
later authorized phone validation exposes a mismatch. Beam precision and Cohere allocation remain
separate experiments; this decision does not change the daily app's Parakeet default.

## ADR-007 — Night three: the full-precision dictionary family and single-instance Cohere go to the phone

**Date:** 2026-10-12. **Status:** approved by Oriol on 2026-10-12 as the next step; no engine decision. **Builds on:** ADR-005, ADR-006, the October 12 [failure diagnosis](research/loop-investigation-2026-10-12.md), [beam investigation](research/beam-investigation-2026-10-12.md) and [Cohere source evidence](research/runtime-options-2026-10-14.md#cohere-single-instance-evidence-2026-10-12).

**Context.** With the INT8-encoder beam collapse isolated (ADR-006), the best laptop candidate is the full-precision encoder with beam 8 and the dictionary at score 2.0: 47.0 errors against 71.3, 13 names recovered, public guard 4.62 % / 1.75 % against 4.81 % / 2.01 %. Night two had measured the INT8 dictionary's wait after Stop at 1.4 to 2.4 s depending on beam width and phone temperature. Cohere Transcribe, the most accurate model (45.7), had been run as two instances (5.4 GB); the research round verified from the pinned runtime's source and from the Kotlin and Python bindings that the language is read per stream, so one instance suffices.

**Decision.**
- Cohere runs as one instance with the language set on each stream (`LanguageSwitched` in the pipeline, `LanguageSwitchedRecognizer` in the app). Laptop texts of the single instance are identical to the saved two-instance texts on the public clips tested. Canary keeps two instances.
- Night three is a measurement night with no new variants: the control row, the full-precision dictionary family at beam 4, 6 and 8 (each width run twice on the phone so the wait reading repeats), the score neighbours at the widest width that held the wait, and one Cohere run. The decision table for Oriol is the product.
- Not done tonight, because they are design changes outside the schema: phonetic post-matching of names on the greedy output, overlapping windows so that Cohere's tail at Stop is short, per-name normalised hotword boost, the evaluator's paired bootstrap. Each has a laptop experiment described in the research summaries; they are the candidates for the next setup round.

**Trade-offs at approval.** The full-precision encoder is 2.4 GB on disk and its phone memory was then unmeasured; the evaluator's 3.5 GB limit decides. The one false dictionary insertion in the public clips keeps the recording trigger shut regardless of the phone result; that is left as is rather than relaxed.

**Outcome:** night three completed; `corpus/lab/handback-2026-10-13.md` and [the audit](research/evaluation-audit-2026-10-14.md) record development accuracy gains with resource failures. The one-instance change is implemented. The dated sweep is complete; [release 1a](experiments/allocation/stage-1-runbook.md) supersedes its execution schedule. Deferred design ideas remain proposals in the roadmap, not automatic follow-on work.

## ADR-008 — One allocation experiment with three stages

**Date:** 2026-10-14. **Status:** approved by Oriol in the documentation restructuring request; no inference launch or engine approval. **Implementation at approval:** only the legacy stage-1 run manifest was prepared; combined transitions and continuation accounting were outstanding. **Subsequent implementation:** [the versioned workflow](experiments/allocation/workflow.md) owns prepared host contracts, tested transitions/accounting and remaining device/launch prerequisites.

**Context and decision:** the earlier plan split measurement characterization, FP32 allocation comparison and Cohere allocation comparison into releases 1a/1b/1c to bound phone work and require interpretation between them. The dependency is real, but three independent loops are unnecessary when the transition is specified. [One current experiment](experiments/current.md) now contains all three stages, preserves their individual caps and adds cumulative accounting. A valid result advances to the next prepared stage; ambiguity returns for interpretation.

**Consequences:** the experiment may span authorized phone windows without renewing budgets. Preparation must freeze later-stage contracts and support checked continuation before claiming full-loop readiness. The existing stage-1 runbook remains valid under its original narrower scope; finishing it alone is partial execution of the combined experiment. This supersedes the administrative three-release split, not historical evidence, acceptance rules, deferred work or phone authorization.

**Execution follow-up, 2026-10-19:** the three-stage loop is complete; [the allocation archive](archive/allocation-2026-10-19.md) owns its results, explicit continuation amendments and verified final restoration. The approval-time rationale above is retained. Completion does not select a permanent engine, establish R4/deployment acceptance or commission another experiment; [current status](experiments/current.md) and [the roadmap](roadmap.md#future-experiments) own subsequent work.
