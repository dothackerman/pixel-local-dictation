# ADR-016 — Prefer FP32 GPU for custom encoder correctness

2026-10-20 · Access diagnostics completed; next speech experiment proposed only.

Two app-scoped processes proved physical GPU and Tensor G5 TPU execution on GrapheneOS using public segmentation models/synthetic inputs.
GPU: LiteRT 2.3.0, OpenGL, PowerVR hardware renderer; GPU-only runtime rejects undelegated operators.
Explicit FP32 passed both frozen numerical checks; default precision failed both. Precision choice matters.
TPU: one executable DISPATCH_OP without original CPU graph, loaded Tensor dispatch libraries and successful distinct finite outputs.
TPU gradient passed; checkerboard failed (mean 0.108865, max 2.250799, class agreement 94.9997%). Access is not numerical acceptance.
No thresholds relaxed. Neither image-model route proves speech compatibility, accuracy, latency or energy benefit.
GPU creation ~2.3 s; two invocations 298/46 ms versus CPU 249/209 ms: exploratory observations, not a speech benchmark.
Keep daily recognizer CPU. Next proposed comparison: original ONNX CPU → converted LiteRT CPU → same converted encoder GPU.
Start with Parakeet FP32; Cohere needs its own suitable reference because installed export is INT8.
Hold decoder/search/dictionary fixed; verify representative lengths, masks, precision, transfers and real Stop wait.
Proposed gate: ≥20% lower warm Stop wait beyond paired variation, with per-language statistic/absolute saving frozen before launch and no semantic regression.
Unintrusive timings, cold setup, first usable text, memory/thermal and complete-path quality remain required; no daily adoption follows.
TPU access needs no beta signup. Custom speech compilation still needs compiler acquisition and mismatch diagnosis; earlier public engine URL returned 404.
The user manually applied the constrained diagnostic compatibility patch; no OS settings changed.
Both JUnit runs passed but lab rejected non-ASR completion format; genuine synthetic JSON was pulled before recovery, without fake ASR records.
Both original APKs/all 11 lab hashes restored and independently verified; no owner/recovery journal remained.
Two of two separately frozen access slots consumed; no further inference authorized.
Evidence: private `corpus/retained/evidence.json` indexes retained reports and original run identities.
Revisit numerical correctness first, then transfer-inclusive encoder/app value; custom GPU/TPU speech remains unimplemented.
