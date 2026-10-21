# ADR-025 — Issue #4 one-case run: WebGPU keeps exact text but is ~5× slower at Stop

2026-10-21 · #4 one-case finding, completed in the evening session; phone recovered and verified.

## Scope

[#4](https://github.com/dothackerman/pixel-local-dictation/issues/4) (parent
[#1](https://github.com/dothackerman/pixel-local-dictation/issues/1)). Cases take-05 (de) and
take-08 (en), both in every run (user decision: satisfies the bilingual freeze rule without an
evaluator change). Variant `variants/beam4-fp32-gate.json` (FP32) plus explicit
`runtime.profile: false`; WebGPU variant adds `runtime.encoder_provider: "webgpu"`.

Source `f188200` (test manifest drops androidx `<queries>`, ADR-024). Host checks passed before
phone work (171 Python tests, Kotlin unit tests, androidTest compile, lint).

| Artifact | SHA256 |
|---|---|
| legacy app / test APK | `39cdfc9f…db60` / `03bc83f4…73d1` |
| legacy ORT core (1.28.2) | `33847ad4…2da1` |
| ort131 app / test APK | `d0e168d2…c1ce` / `a42c613a…5f12` |
| ort131 ORT core (1.31.0) | `8005a741…91e0` |
| FP32 encoder / weights | `2144d185…a725` / `9a22d372…4f36` |
| gate variant as run | `390754af…3571` |

## Results

| Gate | Result |
|---|---|
| runtime-parity legacy-cpu vs ort131-cpu | **advance** (attempt 2) |
| chunk-encoder-v1 tensor gate | **pass**: WebGPU vs CPU max abs 7.4e-6, all 5 fixtures (atol 1e-4 / rtol 1e-3) |
| pipeline-placement-v1 / ADR-023 | 623 CPU steps declared; listing incomplete (logd drops); **accepted by consistency**, ADR-023 amendment 3 |
| adapter/driver evidence | observed: `vulkan.powervr.so` (`7a00aa08…59c0`) mapped, `/dev/dri/renderD128` held |
| webgpu-parity timed replay | **advance** (exact text, no breach); **WebGPU 5.3× / 3.3× slower Stop wait** |

Runtime parity (plan `issue4-parity-02`), exact final text on both cases, no resource breaches:

| | legacy-cpu | ort131-cpu |
|---|---:|---:|
| de normalized errors / words | 23 / 156 | 23 / 156 |
| en normalized errors / words | 6 / 168 | 6 / 168 |
| Stop wait take-05 / take-08 (ms) | 1,974 / 2,368 | 2,302 / 2,463 |
| first preview take-05 / take-08 (ms) | 29,188 / 25,541 | 29,465 / 26,058 |
| cold load (ms) | 4,382 | 4,650 |
| peak high-water (KB) | 3,143,692 | 3,145,704 |
| thermal | 0 | 0 |

One run per setup: the Stop differences are not a speed result (runtime upgrade, not GPU).
Encoder-only time is not in replay rows.

Attempts, all retained: parity-01 (both runs recorded) was **inconclusive**, because the variant omitted
`runtime.profile` and the evaluator requires an explicit `false`; texts and scores matched there too.
parity-02 reran both setups with the explicit key. Chunk attempts `25b71010…` (CPU) and
`ba74114a…` (WebGPU), one each.

Private digests: parity-02 report `666914df…2004`, plan `05c72dd2…3e1f`, receipts legacy
`56820afe…0048`, ort131 `83e4e27b…37cf3`; parity-01 report `dd4bb426…0d15`.

## WebGPU continuation (evening)

Lab matched the handoff inventory; WebGPU chunk outputs pulled from attempt `ba74114a…` (retrieval,
not a rerun). `phone pull` silently skipped single files; fixed in `e176bec`.

WebGPU parity (plan `issue4-webgpu-parity-03`: parity-02 re-frozen unchanged except id, because
`experiment.py` changed in `f97a287` after its freeze; no parity-02 attempt was reserved).
Same ort131 pair, variant differs only by `encoder_provider` (`2e4266d1…f9e9`).

| | ort131-cpu | ort131-webgpu |
|---|---:|---:|
| de normalized errors / words | 23 / 156 | 23 / 156 |
| en normalized errors / words | 6 / 168 | 6 / 168 |
| changed final text | | 0 / 2 |
| Stop wait take-05 / take-08 (ms) | 2,054 / 2,545 | **12,970 / 11,028** |
| first preview take-05 / take-08 (ms) | 29,435 / 25,369 | 40,947 / 36,165 |
| cold load (ms) | 4,542 | 4,924 |
| peak high-water (KB) | 3,140,820 | 414,224 |
| thermal | 0 | 0 |

One run per setup, but the gap (+8.5 to +10.9 s at Stop) is far beyond run-to-run spread seen so
far (≤ 330 ms). Peak RSS drops because encoder weights leave the CPU heap. Hypothesis, untested:
each chunk has a new input length, so WebGPU compiles shaders per shape (the cold first Run in the
short probe took 6.8 s vs 0.29 s CPU).

Placement attempts `75ec3aac…` (receipt `1f1d6482…318b`) and `6e1f0b2c…` (`77d40447…e5f4`);
`bd878c14…` refused before work (result already present). Private digests: parity-03 report
`bdee63ae…a874`, plan `575b744d…19ae`, receipts cpu `dc5c5ad8…343a`, webgpu `874bfa67…58fa`;
chunk compare `fea8fae7…cff0`, WebGPU chunk receipt `b88d916a…59b9`.

Recovery: `phone restore issue4-baseline-20261021`; `phone app` equals `issue4-run/baseline-app.json`
(app, test, FUTO, IME, lab); fresh snapshot `issue4-postrestore-20261021b` equals the baseline per
file (35/35 data files, both APKs; data `c8304efc…`).

## Limits

One take per language, one run per setup; diagnostic only, not complete-suite coverage. Encoder-only
time is not separated from whole-take Stop wait. WebGPU in its current form gives no Stop-wait gain;
#5's question needs a re-plan (e.g. shape-stable chunks or warmed shader cache) before running.
