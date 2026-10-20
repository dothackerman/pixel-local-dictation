# ADR-007 — FP32 beam control; one Cohere instance

2026-10-12 · Approved experiment/implementation; no new product engine.

Public 65-word clip: selective INT8 beam had 62 errors; changing only encoder to FP32 yielded five.
Decoder/joiner were identical: precision/search interaction isolated, numerical root cause unresolved.
Require greedy, beam and dictionary precision checks; plain FP32 beam still missed the keep margin.
Use one Cohere recognizer, language set on every stream/reset per take; retain serial decoding.
Python alternating-language smoke matched two-instance outputs; Canary retains two instances.
Cohere requires filesystem paths; pinned AssetManager loading unsupported.
Night three measured FP32 widths/scores and single-instance Cohere with repeated phone waits.
Phone DE/EN errors: baseline 46/22; FP32 dictionary 35/11; Cohere 24/23, over 584/632 words.
FP32 improves this development set; Cohere improves German only. Both failed historical resource gates.
One instance does not halve total memory or inference latency.
Evidence: `corpus/lab/handback-2026-10-13.md`, `corpus/lab/setup3-comparisons-20261012.json`.
Revisit precision/runtime cost; generic fallback or search patches remain unvalidated.
