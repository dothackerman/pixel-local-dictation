# ADR-022 — Strict WebGPU encoder placement stops at session creation

2026-10-21 · Physical diagnosed stop; both recoveries independently verified. Specification incomplete.

## Experiment

Continued [#4](https://github.com/dothackerman/pixel-local-dictation/issues/4),
the early safety gate of [#1](https://github.com/dothackerman/pixel-local-dictation/issues/1).
The user applied the independently reviewed `short-encoder-v1` wrapper proposal.
Implementation/protocol commit `4bd63fb` preceded inference. All 227 host tests
and Android unit tests, test-source compilation and lint passed; the official-AAR
packaging regression also passed separately. Independent review covered 48 public
phone-command tests, interrupted execution and exact restoration.

Two new physical encoder invocations ran sequentially in separate processes:
`issue4-short-cpu-20261021-04` and `issue4-short-webgpu-20261021-04`.
Including [ADR-021](021-encoder-probe-recovery-blocker.md), three physical probe
invocations exist: two CPU, one WebGPU. No inference was retried. The redundant
CPU `pull-results` operation exceeded its 30-second inventory budget after `lab`
had already preserved the complete receipt. Recovery retained that same evidence;
this operation failure is not another inference attempt.

The original FP32 encoder graph/weights and synthetic zero input `[1,128,17]`,
INT64 true length `[17]`, were frozen before execution. Both new setups used
the same final APKs, runtime, source identities, input bytes and session settings;
only provider differs. Official Android ORT 1.31.0 identities and packaging are
documented in the [probe protocol](../research/issue4-short-encoder-probe.md).
The app contains one pinned ORT core; the test APK contains matching 4j JNI only.
Actual binary manifests match the previously audited offline SDK36 pair.

| Frozen artifact | SHA256 |
|---|---|
| App APK | `253982573618d6c4231259f80402e84698387c84db8735103fb7de30b15533d4` |
| Test APK | `19523bbc140b090875b1ad73170f398120a8f104e47bb8f056671c7594184d5c` |
| CPU manifest | `e18323d0fd5f2de3d5a9ac59341464fe8168cb31840e7294a8639fd2f7278291` |
| WebGPU manifest | `9bf333bbb1c291b2ca84d6abc881961ae52bd6b6622750264563414a2557e719` |
| CPU receipt | `20ce267b1916fa93008e232efcb2e28771206c80e6e9a81336a2813837781979` |
| WebGPU receipt | `02ed09511c2f2a0bdb9e1d2714a54c830c3df6d1050939aeeec0014fae10ac78` |
| Private aggregate manifest | `ad6882c24846ed4500b7d6d4713f47d8c728fd03edd5ea8a159ba3ed3f72db7e` |

Private originals, frozen inputs, runtime/header/native inventory, source/evaluator
hashes, comparison and independent recovery inspections remain under
`corpus/lab/issue4-artifacts-04/`, `corpus/lab/issue4-short-{cpu,webgpu}-04/`
and the existing transaction journals/results. No recordings or transcripts were used.

## Finding

CPU completed with finite FLOAT `[1,1024,3]` and exact INT64 output length `[3]`.
The directed comparison with the pre-frozen **supplementary host ORT 1.26**
reference passed `atol=1e-4`, `rtol=1e-3`; maximum absolute error was
`1.341104507446289e-7`. This is not Android legacy 1.28/new CPU parity.

WebGPU provider registration completed, but session creation returned:

> This session contains graph nodes that are assigned to the default CPU EP, but fallback to CPU EP has been explicitly disabled by the user.

The strict fallback guard worked. Run never started and no GPU tensors exist.
This proves rejection of residual CPU assignment in this tested configuration.
The receipt does not identify the nodes or distinguish missing kernels from
ORT's CPU-placement preference. It does not establish hardware incompatibility,
selected physical adapter/driver, complete GPU placement or FP32 shader arithmetic.

| Observation | CPU | WebGPU |
|---|---:|---:|
| Initialization elapsed | 5,211.09 ms, completed | 1,214.73 ms, failed |
| Input preparation through host outputs | 438.36 ms | Not run |
| Maximum sampled process HWM | 2,676,828 KB | 347,880 KB |
| Sampled thermal status | All four 0 | Both 0 |

Observed resources meet the 3,500,000 KB/thermal-0 limits. The GPU rejection is
not classified as a resource breach. These are single synthetic diagnostics,
not matched speed measurements, whole-take Stop latency or total GPU memory.

## Recovery and decision

After each invocation the protected wrapper restored the exact original APK pair
and lab. Separate inspections each matched the **entire** frozen baseline:
all 11 lab hashes, app/test identities, no owner/journal and no preserved staging,
discard or disposal directories. Recovery is independently verified twice.

#4 has a diagnosed early-stop finding, not a full-take pass. Android legacy/new
CPU parity, full development replay/text parity, complete GPU tensor/placement/
precision coverage, repeated timing and supervised live validation were not run.
#5 and #8 remain unstarted; #1's intended performance/accuracy outcome is unmet.
No default changes or PR. The user requires one PR only after the whole spec.

Next question: can the residual assignment be identified and eliminated while
retaining the unchanged graph, full FP32 and no original CPU operations? No
supported CPU-preference override was found in the pinned
[provider source](https://github.com/microsoft/onnxruntime/blob/402979ac1bef89dcd55add41eaa7277c407599eb/onnxruntime/core/providers/webgpu/webgpu_execution_provider.cc):
`GetCapability` invokes the CPU-preference heuristic unconditionally. This source
observation does not establish why these particular nodes were assigned CPU.
A custom runtime build, graph rewrite or relaxed acceptance needs explicit
replanning; none was attempted. The current route cannot advance past this gate.
