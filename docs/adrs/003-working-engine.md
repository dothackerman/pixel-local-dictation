# ADR-003 — Selective INT8 working engine

2026-10-10 · Approved “for now”; retained default.

Use Parakeet v3/sherpa-onnx 1.13.8: selective MatMul INT8 encoder, FP32 decoder/joiner, four CPU threads.
Published INT8 lost up to six WER points; selective INT8 matched FP32 greedy laptop WER.
Phone segmented Stop median 0.65 s; cold load about 4 s; peak 2,051,520 kB (1.96 GiB / 2.05 GB).
English spike WER 3.2% versus FUTO 1.6%, from different takes; provisional approval acknowledged failure.
Later comparisons reached the revisit condition: accuracy superiority remains unproven.
Greedy parity does not imply beam/dictionary parity; ADR-007.
Self-built encoder; pinned assets, clean full-export reproducibility still unverified.
Evidence: `corpus/files/benchmark/spike-*.json`, `corpus/laptop/`, tags m1/m2.
Revisit accuracy, load/memory and alternative runtimes before permanent deployment.
