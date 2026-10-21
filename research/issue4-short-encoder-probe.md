# Issue #4 short encoder probe: protocol `short-encoder-v1`

Preparation and comparison only; not permission to run. Outcome and receipts:
[ADR-022](../adrs/022-webgpu-encoder-placement-stop.md); recovery incident:
[ADR-021](../adrs/021-encoder-probe-recovery-blocker.md). Staging, execution and
recovery go through the protected wrapper under current-task authorization.
Production runtime default unchanged. The benchmark build calls ORT directly
without Sherpa or the speech pipeline; repacking its core does not claim the old
Sherpa JNI is compatible with ORT 1.31.

## Identities

- Runtime: `com.microsoft.onnxruntime:onnxruntime-android:1.31.0`, AAR SHA256
  `72683787a80d0608a3121b967d8e1239aef48286826661c953211626690540a7`, source tag
  commit [`402979ac1bef89dcd55add41eaa7277c407599eb`](https://github.com/microsoft/onnxruntime/tree/402979ac1bef89dcd55add41eaa7277c407599eb).
  Core/JNI digests pinned in `tools/encoder_probe.py`.
- Input: synthetic silence FP32 `[1,128,17]`, INT64 `[17]`, little-endian.
  `audio_signal.f32` (8,704 zero bytes) `e8b31e302d11fbf7da124b537ba2d44f88e165da03c6557e2b0f6dc486e025bb`;
  `length.i64` (`11 00 00 00 00 00 00 00`) `35e3a6176b50da27fcb868bf840f0e76290bd9bf55c40545e67bac66353d0674`.
- Model: unchanged `models/parakeet-v3-fp32` (hashes in [ADR-019](../adrs/019-encoder-conversion.md)).

## Package and build

```
python3 tools/encoder_probe.py package --ort-aar /tmp/pixel-ort131.aar \
  --sherpa-aar app/libs/sherpa-onnx-1.13.8.aar --out /tmp/pixel-issue4-runtime
ANDROID_HOME=/home/oriol/Android/Sdk ./gradlew \
  -PnativeRoleProfilerAar=/tmp/pixel-issue4-runtime/sherpa-ort131.aar \
  -PencoderProbeDir=/tmp/pixel-issue4-runtime \
  :app:assembleBenchmark :app:assembleBenchmarkAndroidTest :app:lintBenchmark
```

`package` keeps matching Java/JNI and every official AAR header with digests in
`runtime.json`; only the app APK owns the ORT core, the test APK only matching 4j
JNI, no `pickFirst`. The Java/JNI AAR gets an SDK36-only manifest: the official
telemetry initializer and INTERNET/ACCESS_NETWORK_STATE permissions are removed
(pinned Java init loads core/JNI and API31 directly; the initializer only serves
the optional telemetry HttpClient). Before installation: compare merged APK
manifests against the offline originals; verify ZIP digests and AArch64 ELF
dependencies; header API31, core export `OrtGetApiBase@@VERS_1.31.0` and JNI
reference `OrtGetApiBase@VERS_1.31.0` must agree; 4j JNI needs only `libm`,
`libdl`, `libc` and the single core. The packaging regression runs against the
official AAR when `ORT131_AAR=/path/to/onnxruntime-android-1.31.0.aar` is set.

## Freeze

```
python3 tools/encoder_probe.py freeze --model models/parakeet-v3-fp32 \
  --app-apk app/build/outputs/apk/benchmark/app-benchmark.apk \
  --test-apk app/build/outputs/apk/androidTest/benchmark/app-benchmark-androidTest.apk \
  --provider cpu --out corpus/lab/issue4-short-cpu
```

Repeat with `--provider webgpu` into a new private directory. One provider per
manifest; fresh process per provider. Freeze records inputs, model/weights,
source, APK and runtime identities, copies `variants/beam4-fp32-gate.json` (digest
as `staging_envelope`; the helper does not consume it) and hardlinks the model
directory. Stage the whole directory (manifest, two raw inputs, `variant.json`,
`model/`). `LabTest.variantOnSavedTakes` dispatches on `lab/encoder-probe.json`
and runs no recordings or pipeline. The wrapper journals an exclusive attempt
UUID before loading; process death leaves an unresolved attempt, never a retry.

## Runtime session options (both providers)

Sequential execution, 4 intra-op / 1 inter-op threads, `ALL_OPT`, unchanged
graph and true length, file profiling off. WebGPU adds Vulkan, NCHW,
`enableInt64`, graph capture off, `session.disable_cpu_ep_fallback=1`, no CPU
provider appended (unsatisfiable per [placement note](ort-webgpu-node-placement.md);
[ADR-023](../adrs/023-encoder-placement-check.md) replaces it with a placement
check). Unknown providers fail. Cold load = monotonic interval from native init
through provider/Dawn and runtime session creation, with separate native,
provider and runtime-session times; inference time includes input reads, copies, synchronous
Run and host output materialization; failures retain elapsed time, checkpoint,
stack and resource observations.

`result.json`: output shape/dtype, raw little-endian Base64 + digest, attempt id,
app/test/input/runtime identities, cold load, HWM/PSS/thermal, failures. No tensor
sidecars, marker or ORT profile. The typed wrapper validates staged inputs and
output inventory, keeps partial/malformed results as unresolved evidence,
requires quiescence before retrieval and never synthesizes a role profile.

## Compare

```
python3 tools/encoder_probe.py compare --reference corpus/lab/issue4-short-cpu \
  --candidate corpus/lab/issue4-short-webgpu
```

Requires Android 1.31 CPU/WebGPU receipts; provider is the only allowed frozen
difference (model, inputs, builds, runtime, sources, installed identities,
optimization, threads, profiling all checked). Validates raw hashes, FP32/int64,
finite values, shapes, exact lengths, reference-scaled `atol=1e-4, rtol=1e-3`.
Host references are supplementary only. A numerical pass is still
`eligible:false`: adapter/driver, placement and FP32 shader proof remain. No
general force-FP32 switch exists; `enableMatmulFp32Accumulation` applies only to
MatMulNBits. A diagnosed stop with verified recovery can complete #4 with later
checks marked not run; it cannot permit #5.
