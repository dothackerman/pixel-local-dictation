# Issue #4 short encoder probe: protocol `short-encoder-v1`

Preparation and comparison only; not permission to run. Outcome and receipts:
[ADR-022](../adrs/022-webgpu-encoder-placement-stop.md); recovery incident:
[ADR-021](../adrs/021-encoder-probe-recovery-blocker.md). Staging, execution and
recovery went through the former protected wrapper (now [ADR-024](../adrs/024-phone-access-rewrite.md)).
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
and runs no recordings or pipeline. Historical: the former wrapper journalled an
exclusive attempt UUID; since ADR-024 the attempt id is a `run` argument and
process death leaves an unresolved attempt, never a retry.

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
sidecars, marker or ORT profile. Validation of staged inputs and results is in
`tools/encoder_probe.py`, not the phone wrapper (ADR-024).

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

## In-pipeline gates: `chunk-encoder-v1` and `pipeline-placement-v1`

Both ran on 2026-10-21; results: [ADR-025](../adrs/025-issue4-one-case-finding.md).
Both need the ort131 build with the probe
classes: app from `-PnativeRoleProfilerAar=build/ort131-sherpa/sherpa-ort131.aar`
(patched Sherpa JNI), test APK with `-PencoderProbeDir=<package --out>` (only its
`ort131-java-jni.aar` is used; its `sherpa-ort131.aar` has the unpatched JNI).

**`chunk-encoder-v1`** (tensor gate). `freeze --protocol chunk-encoder-v1 --provider cpu|webgpu`
derives five FP32 `[1,128,T]` fixtures from `variants/beam4-fp32-gate.json` cutting
and Sherpa fbank frames `(samples+80)//160`: `typical` 2040 (min chunk + pad),
`tiny-tail` 4 (one 640-sample feed), `upper` 4040 (min + VAD max speech + pad, or
`max_chunk_s`), `silence` 2000 zeros, `masked-padding` 2040 with true length 2000
(zero padding like Sherpa's `PadSequence`). Noise: SHAKE-256 unit-variance uniform;
no recordings, no host inference. One retained session runs them in that order
(changing lengths). Session = Sherpa encoder: intra = inter = `threads` (4),
`ALL_OPT`, sequential, Env ERROR, CPU fallback allowed; WebGPU options as the
Sherpa patch. Outputs: `outputs/<name>-{0.f32,1.i64}` (digests in the receipt).
`compare` checks identities, options, fixture bytes (recomputed), shapes, encoded
length (3× `(n-1)//2+1` of the true length), then `tensor_verdict` per fixture on
valid frames (`masked-padding` padded frames need only be finite).
`gpu_evidence` (both runs): GPU driver libraries mapped in the process (path +
digest), open GPU device nodes, Android Vulkan features, ORT `getEpDevices()`.
`adapter_driver.status` = `observed` when the WebGPU run mapped a
`/vendor/lib64/hw/vulkan.*.so` HAL and holds a GPU device node.

**`pipeline-placement-v1`** (ADR-023 on the Sherpa session; untimed).
`freeze --protocol pipeline-placement-v1 --provider webgpu` stages the gate variant
plus `runtime: {encoder_provider: webgpu, placement_log: true}` and the FP32 model
(encoder, weights, decoder, joiner, tokens). `placement_log` adds
`LogSeverityLevel=0` to the Sherpa session config (session logger VERBOSE over the
Env's ERROR); `MeasurementRun` refuses it, so timed runs stay unlogged. The test
builds the real recognizer via `Pipeline.recognizer`, keeps this process's ORT
placement/cause lines (`LogTail`), records `gpu_evidence`, releases; no audio, no
Run. `placement-check` attributes lines to the encoder session (the only WebGPU
one; CPU-only "All nodes" sessions ignored), then applies `placement_check`.
Observed: logd drops cause lines here too (438 and 466 of 623 named), so the check
fails closed on the count; [ADR-023](../adrs/023-encoder-placement-check.md) amendment 3
accepts by consistency and records the file-logger alternative.

Run (`P=.claude/hooks/phone`, alone on its line; fresh 32-hex attempt id per run;
a lab already holding `result.json` refuses the run, so push the lab again first):
```
python3 tools/encoder_probe.py freeze --protocol chunk-encoder-v1 --provider cpu \
  --model models/parakeet-v3-fp32 --app-apk APP --test-apk TEST --out corpus/lab/issue4-chunk-cpu
$P push corpus/lab/issue4-chunk-cpu
$P run ch.og.dictation.LabTest#variantOnSavedTakes shortEncoderAttempt=<id>
$P pull files/lab/result.json files/lab/outputs --to corpus/lab/issue4-chunk-cpu-pull
# repeat with --provider webgpu -> issue4-chunk-webgpu(-pull)
python3 tools/encoder_probe.py compare --reference corpus/lab/issue4-chunk-cpu-pull/files/lab \
  --candidate corpus/lab/issue4-chunk-webgpu-pull/files/lab
python3 tools/encoder_probe.py freeze --protocol pipeline-placement-v1 --provider webgpu \
  --model models/parakeet-v3-fp32 --app-apk APP --test-apk TEST --out corpus/lab/issue4-pipeline-placement
$P push corpus/lab/issue4-pipeline-placement
$P run ch.og.dictation.LabTest#variantOnSavedTakes shortEncoderAttempt=<id>
$P pull files/lab/result.json --to corpus/lab/issue4-pipeline-placement-pull
models/encoder-conversion-venv/bin/python tools/encoder_probe.py placement-check \
  --receipt corpus/lab/issue4-pipeline-placement-pull/files/lab/result.json \
  --model models/parakeet-v3-fp32/encoder.onnx
```
