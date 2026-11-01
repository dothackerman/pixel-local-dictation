# ADR-028 — Code map: deterministic, LLM-free dev tool

2026-11-01 · Proposed; built on `ccr-57fffcc6-p6wdoz`, awaiting owner review.

Add `tools/codemap.py`, a stdlib-only dev tool that builds an explorable map of this repository. It is independent of the released app: no change to `app/`, Gradle or the phone wrapper, and no network. Same tree, same output.

Four parts:

- **Code graph.** Kotlin (`app/src/**/*.kt`) and Python (`tools/`) extraction yields one graph of files, types, functions, imports, calls, supertypes and test references, written to gitignored `explainers/codemap/graph.json`.
- **Policy.** [`tools/codemap_policy.json`](../tools/codemap_policy.json) declares components by path, their layers, and rules (no network, no test libraries in app code, layer order). `--check` lists violations and exits 1; it is informational and not part of the gate.
- **Trace overlay.** Optional `--trace FILE` adds observed calls from an explicit JSON file, shown as a sequence. Nothing is read from `corpus/` automatically.
- **Viewer.** [`explainers/codemap/index.html`](../explainers/codemap/index.html) drills down from system to components, flow, functions and source.

Principle borrowed from [unclebob/uml-viewer](https://github.com/unclebob/uml-viewer): code supplies facts, the policy supplies interpretation, traces supply observed behaviour, and the viewer keeps them apart. A missing metric is shown as unknown, never as bad.

Limits. Extraction is regex- and tokenizer-based; calls resolve by name, so dynamic dispatch and overloads are approximate and unresolved calls are only counted. `tests_referencing` counts test files naming a symbol; it is not coverage. The static sequence shows possible calls, not executed ones. Components are our declaration, not inferred.

`tools/test_codemap.py` runs in the existing gate through `python3 tools/quality.py` ([DEVELOPMENT.md](../DEVELOPMENT.md)); the viewer is served by the existing launch configuration.

Origin: the owner's ChatGPT conversation "Deterministic Diagram Generation" (2026-11-01). unclebob/uml-viewer was inspiration only; no code was ported.
