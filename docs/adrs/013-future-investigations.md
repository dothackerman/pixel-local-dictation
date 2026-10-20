# ADR-013 — Preserve the quality gain at lower cost

2026-10-19–20 · Updated proposals; no successor commissioned.

Prioritize a result that changes engineering choice, not another allocation campaign.
Moonshine Small screens failed advancement (ADR-014); Medium/router remain untested. Whistle still merits bounded reference-quality screening.
CPU profiles identify substantial encoder work but failed timing transfer (ADR-015).
GPU FP32 access/numerical smoke passed; TPU access passed but one numerical check failed (ADR-016).
Next custom encoder proposal: Parakeet original CPU/converted CPU/GPU, then a separately justified Cohere comparison.
Preserve beam/dictionary quality; selective/per-channel precision and supported CPU providers remain alternatives.
Earlier automatic LID with complete opening replay addresses observed chunk delay separately from arithmetic speed.
Same-build memory confirmation is useful only if its outcome changes a deployment decision; not a universal prerequisite.
Qwen 1.7B reference/native plus 0.6B reference separates capacity from conversion loss; reviewed Unsloth/ggml-org hashes matched.
Conditional Canary 1B/full Whisper large-v3 screens remain; smaller/turbo failures do not reject those checkpoints.
Overlap repeats computation; test queue feasibility and missing/duplicate tails before promising short finalization.
Deferred: reference-TDT/greedy fallback, per-name boosts, phonetic matching with abstention, loading/warm-up.
Dictionary UI remains undecided; custom calibration uses public speech separate from evaluation.
Require untuned spontaneous EN/DE validation and actual FUTO path before claiming R4; no recordings authorized here.
Evidence: source 925ef8c87e054855246a6105a95778c73961ecef; ADR-014–016 retain newer outcomes and private evidence locations.
Recheck/pin upstream during preparation; stop on failed quality/correctness or unbounded integration.
