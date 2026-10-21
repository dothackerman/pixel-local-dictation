# ADR-006 — Validate adapters and complete evidence

2026-10-12 · Authorized; schema/evaluator revision 3 implemented in c63cb0d.

Qwen’s 39.568 s chunk exhausted 512 context tokens; stream overrides could not enlarge the model cache.
Constructor budget 1024 restored output; full corrected replay still lost: 107 errors versus baseline 71.33.
Expose constructor/generation budgets on both platforms; reserve prompt/output space, bound chunks, reject near-limit output.
Reject this export/configuration, not the whole Qwen family.
Count complete memory peak, including paced phase; preserve historical rules and reports.
Exact historical resource gates: 3,500,000 kB peak, Stop 2 s, chunk RTF 0.3; 2 GB remains a warning.
Apply accuracy rules to actual phone text; require complete matching laptop/twin/guard evidence.
No normalization, quality threshold or recording-policy relaxation.
Evaluator 4 later adds matched-platform provenance; ADR-011.
Revisit on changed export contracts, truncation or platform mismatch.
