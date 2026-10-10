# Architecture decision records

ADR status is independent of implementation. Product commitments live in [requirements](requirements.md); this file records design rationale. Supersede changed decisions without erasing the reason for the earlier choice.

## ADR-001 — Manually routed streaming candidates

**Date:** 2026-10-09. **Status:** superseded research recommendation; never approved or implemented. **Superseded by:** ADR-002.

The original research suggested trying FUTO nightly Nemotron and language-specific Moonshine streaming models to reduce finalization delay. It assumed manual language selection was acceptable. Oriol clarified that the existing setup automatically detects English/German and that he wants no extra steps. Direct manual routing therefore conflicts with R1/R2. These engines are not obsolete: Moonshine remains an alternative with invisible routing; Nemotron would need an automatic path not established by the inspected FUTO build.

## ADR-002 — Parakeet-first automatic-language prototype

**Date:** 2026-10-09. **Status:** proposed; documentation preparation authorized, engine choice not yet validated or explicitly approved. **Implementation:** none. **Supersedes:** ADR-001's implementation priority.

**Context:** R1–R3 need an offline recognizer that detects language without user input. Parakeet TDT v3 documents automatic detection and both languages; Moonshine's published streaming models are language-specific.

**Proposal:** prototype Parakeet v3 on CPU behind a separate FUTO-compatible voice input app. Prove launch/insertion first, then benchmark recognition on the actual phone. Only investigate alternative routing or hardware acceleration when measured results justify it.

**Trade-offs:** one multilingual engine avoids a separate language router, but automatic detection can still fail on short recordings. The roughly 600M-parameter model may have unacceptable load/latency/heat; its accuracy and speed advantage over FUTO are unproven. This is a reversible experiment, not a permanent engine commitment.

**Alternatives:** Moonshine with local language routing may deliver lower delay but adds error and startup paths. Whistle is compact but has no demonstrated personal accuracy win. Accelerated Whisper preserves a familiar model family but compression alone is not new: the current FUTO baseline is already quantized. A laptop/cloud recognizer conflicts with R3 and is outside this project.

**Revisit when:** phone results fail R4, integration cannot meet R1, or a competing full path demonstrates a better result. Document findings before changing the proposed engine; preserve the requirements.

## ADR-003 — Parakeet v3 via sherpa-onnx with a MatMul-only INT8 encoder

**Date:** 2026-10-10. **Status:** approved by Oriol on 2026-10-10 as the working choice for milestone 2 ("for now"), knowing that the English accuracy criterion failed; not a permanent engine decision. **Implementation:** spike only (`ReplayTest`, benchmark build). **Builds on:** ADR-002.

**Evidence.** Pixel 10 Pro XL, app 0.1.5 benchmark build, sherpa-onnx 1.13.8, 4 threads, four takes by Oriol (D6 twice, E6 twice, 28–34 s, 242 reference words). Raw files: `corpus/files/benchmark/spike-*.json` (not in Git). Four takes are anecdotal; FUTO's figures are one different take per script from [baseline](baseline.md).

| Gate from [plan](plan.md) | Published INT8 | MatMul-only INT8 encoder, fp32 decoder and joiner |
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

**Proposal.** Continue milestone 2 with sherpa-onnx and the MatMul-only encoder (built by `tools/export-fp32.sh` and `tools/quant_variant.py` from the official checkpoint; encoder SHA-256 `d5be30ebe93c940eddfeb5c6ce2bf67cdbe5d80b2c5a0256a1bbd6c1f6d27da6`). The published INT8 files are dropped: they fail parity and are worse in both languages.

**Why despite the failed English criterion.** The English shortfall is two words in each of two takes, "Miss Keller" for "Mrs Keller" and "Wintertour" for "Winterthur"; FUTO's take also has "Miss Keller", and FUTO wrote "Wintertour" in its German single sentences. Two takes cannot settle R4 for English; milestone 3 measures it on a real corpus. Speed exceeds the interim target by an order of magnitude, so the remaining question is accuracy on names, not the runtime.

**Trade-offs.** The model is self-built rather than a published artifact, so the build must be reproducible and its checksum pinned; the APK grows to about 990 MB of assets; peak memory sits at the 2 GB gate and loading through `AssetManager` is the suspected cause (the library also offers loading from a file path, untested).

**Alternatives.** Whole-take decoding without VAD would already meet the 10 s target at about 2 s and removes segment joins, at the cost of no in-panel preview and a wait that grows with take length. The plan's fail path (ONNX Runtime directly) addresses a runtime problem that the spike did not find. Roadmap milestone 4 (Moonshine) remains the path if milestone 3 shows English accuracy below FUTO.

**Update 2026-10-10, after the first measurement run ([results](results.md)):** the revisit condition is reached. With this model accuracy is level with FUTO in both languages, not better; full precision does not change that. Decoding during the recording leaves roughly a tenfold speed reserve on the phone (real-time factor about 0.06), which a more accurate, slower model could use. No new engine decision has been taken.

**Revisit when:** milestone 3 results exist, or peak memory or cold load proves a problem in daily use. Oriol wants other quantisations (including selective, per-layer schemes) and other runtimes compared in the final optimisation experiments, see [roadmap](roadmap.md) milestone 5.
