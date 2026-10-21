# ADR-025 — Issue #4 one-case run: CPU upgrade parity passes; WebGPU gates interrupted

2026-10-21 · Partial #4 finding. Phone disconnected mid-session; **recovery pending** (see Handoff).

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
| chunk-encoder-v1 tensor gate | CPU run done and pulled; WebGPU run passed on phone, outputs **not pulled** |
| pipeline-placement-v1 / ADR-023 | not run |
| adapter/driver evidence | in chunk receipts; WebGPU side not pulled |
| webgpu-parity timed replay | not run |

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

## Handoff (phone state at disconnect, ~16:20)

Installed: ort131 pair (`d0e168d2…` / `a42c613a…`). `files/lab` = chunk-encoder-v1 WebGPU lab
with `result.json` and `outputs/` from attempt `ba74114a…` (inventory in `corpus/lab/last.json`).
Snapshot `issue4-baseline-20261021` (data `c8304efc…`) is the recovery point; restore is fixed
(`fb881a5`) and was verified byte-identical once today.

Next session, in order:
1. `phone status`, `phone app`: lab inventory must still match `last.json`.
2. `phone pull files/lab/result.json files/lab/outputs --to corpus/lab/issue4-chunk-webgpu-pull`
   (retrieval, not an inference retry), then `encoder_probe.py compare` CPU vs WebGPU.
3. pipeline-placement-v1 + `placement-check`; then the timed `webgpu-parity` (plan prepared in
   `corpus/lab/issue4-webgpu-parity-02`, check its variant has `runtime.profile: false`).
4. `phone restore issue4-baseline-20261021`, `phone app` vs `corpus/lab/issue4-run/baseline-app.json`,
   then a fresh snapshot compared per file to the baseline archive.
If the lab no longer matches, record attempt `ba74114a…` as unresolved, do not rerun it under the
same id, and restore before anything else.

## Limits

One take per language, one run per setup. No WebGPU tensor verdict, placement, adapter proof or
speed result yet; #5/#8 remain blocked on #4.
