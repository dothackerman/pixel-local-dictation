# Pixel GPU and TPU access — 20 October 2026

**Both execute on Oriol’s GrapheneOS Pixel.** GPU OpenGL FP32 passed both frozen numerical checks. Tensor G5 TPU dispatch succeeded, but its public sample failed one numerical check. Access is established; speech compatibility and app speedup remain unmeasured. The daily recognizer remains CPU.

## Actual device proof

Pixel 10 Pro XL / Tensor G5, GrapheneOS `2026101601`, Android 17/API 37. All device contact used the canonical main-checkout wrapper. Two instrumentation processes inside `ch.og.dictation`; public segmentation models and two deterministic synthetic RGB inputs each. No recordings, photos, camera or microphone.

**GPU:** LiteRT 2.3.0 requested only GPU, explicitly OpenGL FP32. Before compilation no EGL context existed. Compilation created a context identifying **Imagination Technologies / PowerVR D-Series DXT-48-1536**, OpenGL ES 3.2. The same context and renderer remained current after both invocations. The pinned runtime rejects undelegated operations when CPU is absent. Successful finite outputs therefore establish actual hardware GPU inference, not enumeration or software rendering. [Exact runtime guard](https://github.com/google-ai-edge/LiteRT/blob/4352e261d3f1b3cda00345db5dbd75859d3bf0d3/litert/runtime/compiled_model.cc#L1112).

**TPU:** the pinned G5 model has one executable `DISPATCH_OP`, no original CPU graph. Two invocations succeeded with finite, distinct outputs. Loaded runtime includes `libLiteRtDispatch_GoogleTensor.so`, `libedgetpu_litert.so`, `libedgetpu_client.google.so` and Edge TPU service libraries. The pinned dispatch implementation maps this bytecode to an NPU node and waits for invocation completion. LiteRT adds CPU to NPU options; that does not supply a CPU implementation of this compiled graph. This establishes TPU execution. [CPU injection](https://github.com/google-ai-edge/LiteRT/blob/4352e261d3f1b3cda00345db5dbd75859d3bf0d3/litert/kotlin/src/main/kotlin/com/google/ai/edge/litert/Model.kt#L514), [NPU mapping/invocation](https://github.com/google-ai-edge/LiteRT/blob/4352e261d3f1b3cda00345db5dbd75859d3bf0d3/litert/vendors/google_tensor/dispatch/litert_dispatch_invocation_context.cc#L64).

GPU is the graphics processor; NPU is the generic neural-accelerator category, and TPU is Google’s implementation. Neither currently runs the app’s speech encoder: these were access diagnostics.

## Numerical results: access and correctness differ

All outputs contained 393,216 finite floats; different inputs produced different outputs. CPU reference hashes matched across processes. Thresholds were frozen before execution and never relaxed.

| Route / synthetic input | Mean absolute difference | Maximum difference | Same pixel class | Smoke pass |
|---|---:|---:|---:|---|
| GPU default / gradient | 0.014316 | 0.219815 | 100% | No |
| GPU default / checkerboard | 0.041890 | 0.920112 | 98.0576% | No |
| **GPU FP32 / gradient** | **0.000003480** | **0.00006330** | **100%** | **Yes** |
| **GPU FP32 / checkerboard** | **0.000003990** | **0.00012076** | **100%** | **Yes** |
| TPU / gradient | 0.029110 | 0.562989 | 100% | Yes |
| **TPU / checkerboard** | **0.108865** | **2.250799** | **94.9997%** | **No** |

GPU limits: mean ≤0.001, maximum ≤0.05, class agreement ≥99%. TPU limits: mean ≤0.05, maximum ≤1.0, agreement ≥99%. These are synthetic smoke criteria, not speech accuracy criteria. TPU failed overall numerical acceptance; the mismatch needs model/compilation/precision diagnosis before custom speech acceptance.

The targeted GPU repair requested `GpuOptions.Precision.FP32`. Default OpenGL precision can select F16; explicit FP32 selects F32 calculation. This fixed these two numerical checks, without changing inputs or thresholds. [Pinned precision implementation](https://github.com/google-ai-edge/LiteRT/blob/4352e261d3f1b3cda00345db5dbd75859d3bf0d3/ml_drift_delegate/delegate/delegate_opengl.cc#L449).

Exploratory timings for the two image inputs: CPU 249/209ms, GPU FP32 298/46ms. The second GPU call was approximately 4.5× faster; the first was slower. GPU model creation took about 2.3s versus 47ms on CPU. This was an access diagnostic with two inputs, not a speed benchmark or a speech forecast.

“GPU setup” here is runtime creation of the GPU-ready model (`CompiledModel.create`), including model preparation and GPU program/resource setup; individual costs were not separately measured. A future integration could prepare the encoder when the keyboard opens and retain it between recordings. It need not begin only after Record is pressed. Process restart or model recreation can incur setup again. This lifecycle is not implemented, and Parakeet/Cohere setup costs remain unknown.

The next speech comparison should first establish numerical correctness, then compare each model against its own CPU baseline: original ONNX CPU, converted LiteRT CPU, and the same converted encoder on GPU. Keep decoder, search and dictionary settings unchanged; include transfers and actual Stop wait. Start with Parakeet FP32, then repeat for **Cohere Transcribe**, whose current INT8 artifact needs an appropriate reference. Compare models only after their individual CPU/GPU effects are established.

No performance conclusion: isolated image-model timings do not predict encoder latency, Stop wait, energy or speech quality. The [completed CPU profiling](accelerator-routes-after-profiling-2026-10-20.md#completed-profiling-useful-opportunity-uncertain-user-benefit) motivates an encoder experiment, whose integration/correctness/measurement gates remain in that report. Prioritize FP32 GPU for the next custom encoder correctness test. TPU access needs no beta signup; compiling a custom speech graph still requires compiler acquisition and its separate validation.

## Tooling obstacle resolved without OS changes

The original wrapper refused full and test-only packages before installation because manifests differed. A public-OpenGL-only test with the exact original manifest enabled the first run. A narrowly reviewed compatibility patch then allowed four optional native-driver declarations and instrumentation native-library extraction for diagnostic provisioning. **Oriol manually applied it**, confirming SHA-256 `ca3c367a662f3810bc6c1e45b341c0d7e2f12f08fd7204ee76707a0305457ed6`, as required by the [safeguard instructions](../agent-safeguards.md#verification-and-limits). Seven host tests passed. [Reviewed patch](../proposals/accelerator-diagnostic-compatibility-2026-10-20.patch).

No GrapheneOS settings or protections changed. Ordinary installation keeps its original constraints. Final standalone dependencies add no unrelated permissions, components or services. The dispatch library loads from installed native-code storage. The initially prepared full TEST had unrelated transitive additions and was rejected; it was never installed. The cleaned final pair passed constrained manifest, package, version and signer checks.

`lab` still expects ASR/native-profiler result structure: both JUnit processes passed, then its completion-format check refused. Supported matching-run `pull-results` retrieved the real synthetic JSON before recovery. No fake ASR takes, transcripts or profiler bindings were supplied. Native log corroboration was unavailable because the fixed log filter timed out; final renderer, dispatch graph and output evidence supply attribution.

## Provenance and restoration

Two of two frozen instrumentation slots consumed; JUnit durations 7.927s and 9.204s. No further phone inference authorized by this plan. Public assets are pinned at official sample revision `32a331c33a7115a3b86ef554fb58a9c11d03b123`; [model hashes and source links](accelerator-routes-after-profiling-2026-10-20.md#available-routes-and-obstacles).

| Final artifact | SHA-256 |
|---|---|
| Diagnostic APP | `bd43700417cd11fbe855ffd434cc92033f5c23326d3437adb2000c21dda1471b` |
| Diagnostic TEST | `358f4873bbe23bf558ed7a1f471510362ea022365498d7eeab78eaa52781301c` |
| Final synthetic result JSON | `a62b646e5c662c2d3815badc530841b1046541af9f00d7faa9621177d27d94c6` |

Raw results, receipts, plans, reviews and artifact/source hashes remain under ignored `corpus/lab/accelerator-access-20261020/`. The exact final build snapshot, manifests and reproducible isolated build script remain in its `prepared-tpu/` directory. The exact tested [Java probe](../../app/src/androidTest/java/ch/og/dictation/AcceleratorAccessProbe.java) and manifests are now persisted in the main checkout. Its final host rebuild and 31 unit tests passed; that rebuilt TEST differs from the actual device-tested APK identified above and is not substituted for its evidence. For a fresh build, place the two pinned public models under `models/accelerator-access-20261020/accelerator/`; the build uses them only in instrumentation assets. Before the wrapper patch, the Python suite ran 139 tests with 14 errors and two failures from stale wrapper fakes. During integration these fakes were updated to the current transfer/instrumentation contract and meaningful diagnostic/restoration regressions were added: all 40 wrapper tests pass. The normal commit gate runs the complete Python suite, Kotlin unit tests and Android lint without device contact.

Both runs were recovered. Final independent inspection exactly matched the starting APP, original TEST and all 11 lab file hashes; no active owner or recovery journal remained. Original APP SHA-256 `da17de22e58bae85a9b2ca9a9e1c4202a199c98bfdfcb11e98c864125f3ce106`; original TEST `ee9df0f9e81b03eaf975436af2fbee362cc971ca881f52aa15ac71a3a01cf1a3`.

## Standards

Independent reviews: no blocking findings. Synthetic inputs preserve privacy; only optional reviewed driver access and installed native libraries. No additional permissions, services or security-setting changes.

## Spec

Independent reviews: no actionable implementation findings. Final evidence proves physical GPU and TPU execution. GPU FP32 passed numerical smoke checks; TPU failed one. Neither route establishes speech speedup or an accepted daily engine.

## Save state

The earlier task-only commit was refused because related profiling and manually applied wrapper changes were still outside the index. For the requested integration, the completed profiling, analyzer corrections, wrapper update, diagnostic source and result reports were reviewed together and included in the same normal commit. Host wrapper tests were repaired, and generated Python bytecode was removed from tracking while its ignored local copy was retained. No hook bypass or stash was used. Git history records the integration into `main`; raw personal evidence remains ignored.
