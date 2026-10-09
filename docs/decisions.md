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
