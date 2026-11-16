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
Matched laptop primary-feed DE/EN errors: FP32 greedy 45/24, beam8 without dictionary 42/26, dictionary twin 35/11.
The demonstrated gain is supplied-vocabulary biasing; precision/search alone did not improve English.
All 43 dictionary entries occur in the scripts: this establishes mechanism, not unfamiliar-name recognition.
One instance does not halve total memory or inference latency.
Revisit precision/runtime cost; generic fallback or search patches remain unvalidated.

Ablation provenance: `925ef8c:docs/research/remaining-improvement-levers-2026-10-19.md`; original run identities `fp32-fec1163`, `beam8-fp32-gate-2d11d13`, `dict-beam8-fp32-s20-2d11d13`.
