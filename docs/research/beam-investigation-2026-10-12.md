# Beam deletion investigation, 2026-10-12

Status: reproduced and isolated to an encoder-precision/search interaction on one public clip. A general repair is not established. No phone access, production decoder change, fallback implementation, evaluator change, model download or commit was performed by this investigation. See [earlier audit](loop-investigation-2026-10-12.md#2-reproduced-the-beam-search-german-regression-is-concentrated-in-one-collapse) for the full-guard concentration evidence.

Follow-up: the complete paired laptop replays are now recorded in
[measurement results](../evaluation/results.md#laptop-setup-validation-2026-10-12). The sections below preserve the isolated diagnosis. Later phone results are in [the October 14 audit](evaluation-audit-2026-10-14.md); neither full-suite replay nor first phone measurement remains pending.

## Discriminating result

The German public guard clip `models/guard/de/140_82_000065.wav` is approximately 20 seconds with 65 normalized reference words. Changing **only encoder precision** removes its catastrophic deletion under beam search:

| Encoder | Decoder search | Output words | Word errors | Last token start |
|---|---|---:|---:|---:|
| MatMul INT8 | Greedy | 64 | 5 | 19.60 s |
| MatMul INT8 | Beam 4 or 8, no dictionary | 4 | 62 | 0.96 s |
| MatMul INT8 | Beam 8, dictionary score 2.0 | 24 | 44 | See prior report |
| FP32 | Greedy | 64 | 5 | 19.84 s |
| FP32 | Beam 4, no dictionary | 63 | 5 | 19.84 s |
| FP32 | Beam 8, no dictionary | 63 | 5 | 19.84 s |
| FP32 | Beam 8, dictionary score 2.0 | 63 | 6 | 19.84 s |

FP32 rows were executed in this investigation with the installed sherpa-onnx 1.13.8, four threads, existing local model files, identical public audio and the unchanged scorer. INT8 rows are the independently reproduced/saved results linked by the earlier audit. No transcript text is reproduced here. FP32 beam 8 and dictionary smoke runs took about 10.35 seconds each **including model loading**; this is not a paced latency or phone result.

The decoder and joiner ONNX files are byte-identical between `parakeet-v3-fp32` and `parakeet-v3-matmul8`:

- Decoder SHA-256: `0ddb1f1bc4ea627a362a9bbe94340ea11538b6aafa9050de42738afe407a8124`.
- Joiner SHA-256: `7ec8e5f5e3d45f05e828c14567ffec252f50b659537d976d72a6318e047b9fb1`.

The encoder is therefore the controlled model change. The selective INT8 exporter is [quant_variant.py](../../tools/quant_variant.py); it applies ONNX Runtime dynamic unsigned INT8 quantization to selected operator types. Existing greedy parity does **not** establish beam/hotword parity. The single-clip result did not prove generally better accuracy; the linked complete evaluation supplies the broader comparison.

## What the source does and does not explain

The pinned beam decoder continues until all surviving hypotheses reach the encoder's final frame. It can therefore return a four-word prefix while still processing the entire recording; an early last token is not evidence of an early-return bug. Each hypothesis independently advances by the argmax duration, adds token and duration log probabilities, and is globally pruned by accumulated score. These discontinuous search decisions are a plausible mechanism by which encoder perturbations select a deletion-heavy path. This is an explanation consistent with the observations, **not a traced numerical root cause**. [Pinned sherpa implementation](https://github.com/k2-fsa/sherpa-onnx/blob/11afbd009a7f8c08f4bcf2fc1b265d0df4670fbf/sherpa-onnx/csrc/offline-transducer-modified-beam-search-nemo-decoder.cc).

NVIDIA's reference TDT beam implementation explores token/duration pairs, separately expands legal nonzero-duration blanks, merges duplicate hypotheses, and offers normalized final scores. The pinned sherpa decoder is not that same search algorithm. This identifies useful comparison points but does not justify copying one isolated scoring rule without validation. The reference was inspected in the existing export environment (`nemo-toolkit==2.5.0`); no independent NeMo inference was needed for the precision isolation. [NVIDIA reference](https://github.com/NVIDIA-NeMo/Speech/blob/v2.5.0/nemo/collections/asr/parts/submodules/tdt_beam_decoding.py).

There is an upstream report of NeMo TDT beam search returning tiny/empty transcriptions when greedy works, and an **open, unmerged** proposed fix. Its rationale is not sufficient evidence for this case: it claims a missing blank/zero-duration guard, but the pinned decoder already advances blanks by at least one frame. Its proposal to omit duration probability on blanks also differs from NVIDIA's reference, which includes that probability. Do not treat that PR as a verified repair. [Issue 3267](https://github.com/k2-fsa/sherpa-onnx/issues/3267), [PR 3657 at inspected head](https://github.com/k2-fsa/sherpa-onnx/pull/3657), head `867762892495a6bf1a2b031699906aaa73217e90`. Earlier [PR 3589](https://github.com/k2-fsa/sherpa-onnx/pull/3589) is merged and its changes are already present in the pinned source.

## Reproduction and remaining hypotheses

The isolated harness is gitignored under `corpus/lab/`; it reads the public clip and emits only aggregate error counts/timing. Its default comparison includes both old and FP32 variants:

```sh
models/venv/bin/python corpus/lab/probe-beam-precision.py
```

To repeat just the newly tested beam/dictionary pair:

```sh
models/venv/bin/python corpus/lab/probe-beam-precision.py beam8-fp32-gate dict-beam8-fp32-s20
```

The diagnostic variants [beam8-fp32-gate](../../variants/beam8-fp32-gate.json) and [dict-beam8-fp32-s20](../../variants/dict-beam8-fp32-s20.json) changed only model directory and identity from the INT8 pair. Their completed full-suite comparison is in [results](../evaluation/results.md#laptop-setup-validation-2026-10-12). Use those paired controls when interpreting the single-clip result.

Selective precision must now pass both greedy and beam/dictionary checks; [runtime research](runtime-options-2026-10-14.md#precision-should-be-searched-by-sensitivity-not-only-operator-type) owns that proposal. Generic greedy fallback and a reference-search comparison remain untested alternatives, outside the current allocation experiment.
