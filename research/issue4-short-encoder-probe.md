# Issue #4: short Android encoder checkpoint

Host preparation only. No physical finding, full-take parity, or #5 eligibility.
The production runtime default is unchanged. This explicit benchmark build calls
ORT directly without loading Sherpa or the speech pipeline. Repacking its core
is not a claim that the old Sherpa JNI is compatible with ORT 1.31.

## Frozen contract

`tools/encoder_probe.py freeze` writes immutable little-endian synthetic silence
FP32 `[1,128,17]` and INT64 `[17]`, model/weights, source, app/test APK and official
runtime identities before phone inference. No recordings or transcripts. One
provider per frozen manifest; separate fresh processes and preserved directories
for CPU and WebGPU. Exact raw SHA256 identities:
`audio_signal.f32` (8,704 zero bytes)
`e8b31e302d11fbf7da124b537ba2d44f88e165da03c6557e2b0f6dc486e025bb`;
`length.i64` (`11 00 00 00 00 00 00 00`)
`35e3a6176b50da27fcb868bf840f0e76290bd9bf55c40545e67bac66353d0674`.
The phone creates an exclusive attempt marker before loading;
process death leaves an unresolved attempt and cannot silently retry.

The fixed `LabTest.variantOnSavedTakes` first checks `lab/encoder-probe.json`.
It runs neither the saved recordings nor the pipeline in this mode. Stage the
manifest, its two raw input files, and the unchanged encoder/weights in `lab/model`.
A `variant.json` is not consumed by this early dispatch. Staging, execution and
recovery must use the protected checkout wrapper and current-task authorization.

## Runtime preparation and build

Official Maven Central artifact:
`com.microsoft.onnxruntime:onnxruntime-android:1.31.0`.
AAR SHA256 `72683787a80d0608a3121b967d8e1239aef48286826661c953211626690540a7`.
Source tag commit `402979ac1bef89dcd55add41eaa7277c407599eb`:
<https://github.com/microsoft/onnxruntime/tree/402979ac1bef89dcd55add41eaa7277c407599eb>.

```
python3 tools/encoder_probe.py package --ort-aar /tmp/pixel-ort131.aar \
  --sherpa-aar app/libs/sherpa-onnx-1.13.8.aar --out /tmp/pixel-issue4-runtime
ANDROID_HOME=/home/oriol/Android/Sdk ./gradlew \
  -PnativeRoleProfilerAar=/tmp/pixel-issue4-runtime/sherpa-ort131.aar \
  -PencoderProbeDir=/tmp/pixel-issue4-runtime \
  :app:assembleBenchmark :app:assembleBenchmarkAndroidTest :app:lintBenchmark
```

The packaging command retains matching Java/JNI and every official AAR header with
individual digests in `runtime.json`; only the app APK owns the ORT core. The test
APK contains matching 4j JNI, no second core. No `pickFirst` selection. Verify final
ZIP entries/digests and AArch64 ELF dependencies before installation. Header API31,
core export `OrtGetApiBase@@VERS_1.31.0`, JNI reference
`OrtGetApiBase@VERS_1.31.0` must agree. Published 4j JNI additionally needs only
Android `libm`, `libdl`, `libc` and the single core. Actual native loading/version
and installed app/test/core/JNI identities are checked again on device.

```
python3 tools/encoder_probe.py freeze --model models/parakeet-v3-fp32 \
  --app-apk app/build/outputs/apk/benchmark/app-benchmark.apk \
  --test-apk app/build/outputs/apk/androidTest/benchmark/app-benchmark-androidTest.apk \
  --provider cpu --out corpus/lab/issue4-short-cpu
```

Repeat freeze with provider `webgpu` and another new private output directory.
Do not write into or reuse a reserved phone attempt; preserve receipt and marker.
No inference command is included here: root owns wrapper authorization/recovery.

## Strict provider behavior and receipts

CPU and GPU both use sequential execution, four intra-op/one inter-op threads,
normal `ALL_OPT` graph optimizations, unchanged source graph and true length.
WebGPU explicitly selects Vulkan, NCHW, Int64 support, graph capture off, and
`session.disable_cpu_ep_fallback=1`; no CPU EP is appended on this path. Unknown
providers fail. Residual shape/length/mask assignment to CPU must fail session
initialization. Provider creation, native loading, session creation and execution
failures retain their checkpoint/stack and every available resource observation.
Profiling/logging are diagnostic. Cold load measures the complete monotonic interval
from native initialization through provider/Dawn and session creation. Separate
native/provider/session times are retained; failed initialization retains elapsed
time through the failure in the final receipt. Inference timing includes input reads/preparation/copies,
synchronous Run, host output materialization and diagnostic persistence; it cannot
enter a practical speed comparison.

`result.json` contains output shape/dtype, raw little-endian Base64 and digest so
the protected JSON-only result pull preserves bounded synthetic tensors. It also
retains app/test/input/runtime identities, cold load, HWM/PSS/thermal, profile path
and failures. Raw tensor sidecars are redundant and need not be pulled.

```
python3 tools/encoder_probe.py compare --reference corpus/lab/issue4-short-cpu \
  --candidate corpus/lab/issue4-short-webgpu
```

Directories contain pulled `result.json` receipts. Direct comparison requires
Android 1.31 CPU/WebGPU labels. Provider is the sole allowed frozen difference:
model/inputs, builds, runtime, sources and every other frozen setting must match.
Actual installed app/test/core/JNI identities must match those frozen bindings;
normal optimization, sequential execution, thread counts and profiling are checked.
It validates raw
hashes, FP32/int64, finite values, shapes, exact lengths and reference-scaled
`atol=1e-4, rtol=1e-3` using the existing tensor seam. Historical host reference
outputs are supplementary only. A numerical pass alone remains `eligible:false`:
physical adapter/driver, residual assignment and FP32 shader proof remain
unproven. There is no general force-FP32 provider switch; the release's
`enableMatmulFp32Accumulation` applies only to MatMulNBits. Any diagnosed stop must
mark legacy/full-take/session/recovery checks separately as not run or verified;
host preparation or a successful probe alone cannot complete #4. A physical
diagnosed stop, independently verified recovery and committed aggregate outcome
can complete #4 with later checks explicitly not run; it cannot permit #5.
