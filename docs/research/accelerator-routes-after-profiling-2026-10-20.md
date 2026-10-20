# GPU and Tensor G5 TPU after phone profiling — 20 October 2026

**Device update: GPU and TPU hardware execution are now proven on the GrapheneOS Pixel. GPU FP32 passed numerical checks; TPU failed one.** See the [actual device check](accelerator-access-device-check-2026-10-20.md).

**A bounded accelerator experiment is justified; a full port is not.** The encoder—the stage turning audio into numerical features—accounts for roughly half the profiled recognition worker's service time. Accelerating it could help; substantial decoding remains. Prioritize the public GPU route for custom speech computation, alongside a small precompiled G5 TPU access test **before beta signup**. This assessment originally preceded device execution; the device update above supersedes its access status.

NPU is the generic name for a neural-network accelerator; TPU is Google's implementation inside Tensor G5. GPU is a separate processor. Both still need the surrounding app, audio preparation and text search.

## Completed profiling: useful opportunity, uncertain user benefit

Source: [completed CPU campaign](../experiments/campaign-2026-10-19.md#cpu-profiling-continuation--20-october-2026), originating [chief-of-staff chat](codex://threads/01a15509-56fd-7490-b52c-f7d8cd54b4c7). Corrected private aggregates remain in gitignored `corpus/lab/campaign-20261019/profile-execution-20261020/`: `corrected-combined-analysis.json` and `corrected-combined-summary.json`. No recordings or transcripts are reproduced here.

Each setup ran unprofiled/profiled/profiled/unprofiled, reversing case order in the second pair: eight successful phone processes, ten cases/382.065 seconds audio each. Both used four threads. Percentages below are P1 → P2.

| Setup/language | Encoder | Decoder | Joiner | Errors/words, identical across four runs |
|---|---:|---:|---:|---:|
| A: FP32 Parakeet, beam 8, dictionary 2.0; DE |44.41 → 43.08%|22.99 → 23.32%|20.33 → 19.70%|41/374|
| A: EN |46.60 → 44.42%|21.96 → 22.75%|19.97 → 20.60%|14/367|
| B: INT8 Cohere, automatic language routing; DE |45.17 → 44.04%|41.13 → 42.03%|—|26/374|
| B: EN |51.24 → 51.68%|31.56 → 31.61%|—|115/367|

These divide native role **wall spans by worker service wall**, excluding capture idle. They are neither processor utilization nor fractions of the user's Stop wait. Cross-role overlap is rejected; the remainder includes search and surrounding worker work, whose individual costs are not isolated.

Both setups passed output/routing/coverage/thermal/memory consistency checks, but **both failed timing comparability** against unprofiled execution: A failed 6/10 and 7/10 cases; B failed 3/10 and 1/10. Failure means recognition-wall change exceeded 10% or Stop-wait change exceeded 100ms. The traces identify substantial work; they cannot predict ordinary execution speed.

Illustrative arithmetic only: if encoder share were 44–52% and it alone became twice as fast, sequential service would shrink about 22–26%, before transfers. This is not a GPU/TPU forecast. Unprofiled Stop waits ranged 0.543–7.069s for A and 0.013–6.035s for B. Work finished while speaking may yield little Stop improvement. Existing FIFO replay is conditional on traced arrivals and unchanged other work, not a validated unprofiled forecast.

For B, the first short chunk closed roughly 21.7s after capture; language identification then computed for 0.84–0.97s. Faster arithmetic cannot eliminate waiting for that chunk. Earlier routing with complete opening replay deserves separate investigation; neither speedup nor opening retention is proven. Known-vocabulary development scores establish no daily-default change or FUTO superiority. B's English quality and coarse peak memory of 4,421,752–4,450,200 kB remain concerns.

Provenance: analysis SHA-256 `71b8ac693f469c14db67824d2126f267e07438ee3c5f2cb37d5d3991e8f0420c`; summary `d2db22aef58bad0039d58d91b8ebe80ee43dfc5775a91bdc51621cddeb2a0087`. The corrected analyzer and regression tests are versioned with these findings. Reproducing the private aggregates also requires the ignored input evidence.

## Available routes and obstacles

**GPU: first custom-model candidate.** Google documents LiteRT 2.3.0 with the public `litert-gpu` package and Android OpenCL/OpenGL backends. Its Maven package was reachable on 20 October. No Tensor beta approval is needed. This establishes a software route, not compatibility or speed on this GrapheneOS build. [Official GPU guide](https://developers.google.com/edge/litert/next/gpu).

**TPU: access before compiler acquisition.** Google's sample supplies a [G5-compiled segmentation model](https://github.com/google-ai-edge/litert-samples/blob/main/samples/litert/image_segmentation/c%2B%2B_segmentation/build_from_source/models/selfie_multiclass_256x256_Tensor_G5.tflite) and [uncompiled CPU/GPU twin](https://github.com/google-ai-edge/litert-samples/blob/main/samples/litert/image_segmentation/c%2B%2B_segmentation/build_from_source/models/selfie_multiclass_256x256.tflite). Both returned HTTP 200 on 20 October, sized 15,979,712 and 16,371,837 bytes. They permit an access test with synthetic input, without compiling speech or using recordings. Verify model/runtime generation pairing first; a G5 filename is insufficient. Image-model speed does not predict speech speed.

Google Tensor supports ahead-of-time compilation; on-device compilation remains unsupported. The public 2.3.0 vendor-runtime package is reachable. Google's matrix pairs SDK 2.1 with G5, LiteRT 2.3.0 and Android 26Q3; device/library compatibility remains unverified. Successful invocation or an accelerator-selection flag does not establish delegated computation: require backend and partition evidence. [NPU guide](https://developers.google.com/edge/litert/next/npu), [compatibility matrix](https://developers.google.com/edge/tensor-sdk/release-notes).

Custom TPU graphs still need the compiler engine. The official installer references engine 6.1.0; its archive again returned HTTP 404 on 20 October. Google's alternative is signup followed by an emailed archive, supplied locally through `GOOGLE_TENSOR_SDK_BETA`. Stable LiteRT does not establish an openly downloadable stable Tensor compiler. This narrows the [earlier campaign stop](tpu-campaign-2026-10-19.md): custom conversion is blocked by acquisition; **access proof need not be**. [Installer source](https://github.com/google-ai-edge/LiteRT/blob/main/ci/tools/python/vendor_sdk/google_tensor/setup.py), [G5 codelab](https://codelabs.developers.google.com/codelabs/google-tensor-ml-sdk).

**Integration and correctness are shared costs.** The [current engine](../../app/src/main/kotlin/ch/og/dictation/Engine.kt) owns a whole sherpa-onnx recognizer; [runtime selection](../../app/src/main/kotlin/ch/og/dictation/RuntimeConfig.kt) is CPU. There is no encoder-only LiteRT switch. Preserve decoder, joiner, beam and dictionary behavior while introducing an encoder seam. Small repeated transfers could erase savings; prefer one substantial contiguous region.

The [graph audit](tpu-campaign-2026-10-19.md#local-graph-audit-no-execution) identifies dynamic-shape, masking/length and conversion risks. TPU compilation requires static shapes. Start with Parakeet FP32 and one padded shape, preserving valid lengths. Prefer export of the original NeMo/PyTorch encoder through the [official PyTorch-to-LiteRT converter](https://github.com/google-ai-edge/litert-torch); existing ONNX files cannot simply be loaded by LiteRT. Installed Cohere is INT8; obtain a genuine FP32 reference before any full-precision claim. TPU flags include `auto`, `half`, `bfloat16` and `no_truncation`; requesting the last does not prove internal FP32 execution. Record precision and compare outputs and recognition. [Tensor FAQ](https://developers.google.com/edge/tensor-sdk/tensor-faqs), [compiler flags](https://developers.google.com/edge/tensor-sdk/compilation-flags).

Access-sample identities were verified in memory on October 20 at official sample [revision `32a331c`](https://github.com/google-ai-edge/litert-samples/commit/32a331c33a7115a3b86ef554fb58a9c11d03b123), without saving assets:

| Artifact | SHA-256 |
|---|---|
| `selfie_multiclass_256x256_Tensor_G5.tflite` | `4cc1848c7a3a30183a11dc8735b444ba2f7d5afa279cf10de6e3eadd6a3d9cde` |
| `selfie_multiclass_256x256.tflite` | `c6748b1253a99067ef71f7e26ca71096cd449baefa8f101900ea23016507e0e0` |

The hashes pin the proposed access inputs; runtime compatibility still needs validation.

## Finite next experiment, proposed only

1. **Host preparation:** pin sample assets and compatible runtime versions, deterministic inputs, numerical tolerances and the finite plan. Inspect the Parakeet conversion seam in parallel; defer substantial integration until the relevant device-access test passes.
2. **One access process:** within `ch.og.dictation`, run the twin on CPU/GPU and the compiled model on TPU using identical synthetic inputs. Record initialization, outputs, actual backend, partitions/fallback, transfers and synchronization. Stop a route on uncertain attribution, incorrect output or unavailable libraries/drivers. No settings changes. Failed sample/version pairing does not prove G5 speech acceleration impossible.
3. **GPU speech comparison:** after GPU access passes, allow one Parakeet encoder-region conversion attempt and one diagnosed repair. Compare deterministic outputs against original ONNX across representative lengths/padding boundaries; stop on incorrect lengths, unexplained mismatch, excessive memory or an unbounded integration requirement. Then use six fresh processes: original ONNX CPU → converted LiteRT CPU → converted GPU → converted GPU → converted CPU → original CPU; reverse case order in the second half. Reuse ten fixed cases with unchanged arrivals/chunks/decoder/dictionary/threads. Disable intrusive profiling for decision timings; retain lightweight timestamps. Measure cold setup separately, warm Stop-to-final-text, first usable text, transfer-inclusive encoder latency, recognition, memory and thermal state. Original/converted CPU isolates conversion; converted CPU/GPU isolates acceleration; original/hybrid measures app value.
4. **Custom TPU only after access passes:** acquire the compiler if opportunity warrants; one compilation plus one diagnosed repair maximum, then the same six-process comparison with TPU replacing GPU. No model/shape/precision sweep.

**Proposed continuation gate:** correct accelerator attribution and preserved dictionary/recognition behavior; at least 20% lower warm Stop-to-final-text on a predeclared nontrivial baseline, exceeding paired run variation, without material language-specific regression. Before launch, fix the statistic separately for each language and an absolute saving target; exclude near-zero waits only by a prospective rule. Reject semantic regressions in names/numbers/negation despite aggregate improvements. Keep first-usable delay, cold load and sustained memory/thermal costs visible. Faster kernels alone establish no app benefit. Unstable results mean inconclusive: stop rather than automatically expanding. This development-suite gate admits further engineering, not a new daily default; spontaneous untuned speech and a matched FUTO comparison remain separate product gates. Energy savings require direct measurement.

At the end of the initial research-only assessment, the prior campaign had closed at 18/18 phone slots; APP+TEST and 11 lab files were restored/verified at 06:18:41 UTC. A read-only inspection during this assessment observed the same app/test APK identities as the retained restoration receipt, 11 accessible lab files and no active owner, staging area or recovery journal. This report allocates no new measurement budget or launches. Follow current [phone lab constraints](../phone-lab.md), scoped authorization and restoration guarantees for future execution. That initial assessment performed no accelerator inference, installation or app change and saved no model assets or personal data. The subsequent [device access check](accelerator-access-device-check-2026-10-20.md) records actual execution and supersedes this paragraph’s historical status.
