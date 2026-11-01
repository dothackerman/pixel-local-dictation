# Explainers

Standalone beginner pages, one folder each, `index.html` opens in a browser.
Terms follow [GLOSSARY.md](../GLOSSARY.md). Findings live in `adrs/` and `research/`;
an explainer only teaches them.

| Explainer | Teaches |
|---|---|
| [WebGPU encoder placement](webgpu-encoder-placement/index.html) | Why strict WebGPU runtime session creation stopped ([ADR-022](../adrs/022-webgpu-encoder-placement-stop.md), [ADR-023](../adrs/023-encoder-placement-check.md)) |
| [Teach the engine your words](next-direction/index.html) | What a take, a chunk and the model parts are; why the dictionary, not FP32 or beam search, carries the accuracy gain; the dictionary-boosting direction and the laptop-first test loop ([ADR-026](../adrs/026-dictionary-first.md), spec #11) |
| [Code map](codemap/index.html) | Code map: files, types, calls and policy components, from system down to source ([ADR-028](../adrs/028-codemap.md)). Run `python3 tools/codemap.py` first; served by the existing launch config at http://127.0.0.1:8765/codemap/ |
