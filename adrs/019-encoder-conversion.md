# ADR-019 — Bounded FP32 encoder conversion diagnosis

Subsequent route proposal: [ADR-020](020-onnx-webgpu-investigation.md). The conversion
outcome and evidence recorded below remain unchanged.

2026-10-20 · Issue #3; synthetic host work only. No phone execution.

Use `tools/encoder_conversion.py` with the exact environment in
`tools/encoder-conversion-requirements.txt` (Python 3.12). The commands are:

```
python tools/encoder_conversion.py freeze --model models/parakeet-v3-fp32 --out corpus/lab/parakeet-fp32-gpu/conversion-NN --infer-shapes --timeout 900
python tools/encoder_conversion.py attempt --out corpus/lab/parakeet-fp32-gpu/conversion-NN
```

Run from the primary checkout with the conversion virtualenv's Python. Each output
directory is new; a started attempt cannot be overwritten. Freeze precedes execution
and records original/transformed graph/weights, exact harness snapshot, complete dependency versions, converter options,
fixture bytes/seeds, true lengths and internally derived mask semantics. The matched
reference uses ONNX Runtime CPU with four intra-op and one inter-op thread. Compare
every float output at atol 1e-4 / rtol 1e-3; reject nonfinite outputs, shape changes,
non-FP32 candidate values, and any integer length/dtype difference. No quantization,
custom-operator acceptance, static input overwrite or GPU fallback is configured.
The harness rejects any observed implicit custom lowering. New attempts bound
converter RSS plus swap to 24 GiB; the timeout is frozen explicitly. The final tool
also rejects interpreter/dependency/requirements drift before starting a converter.

The installed converter is onnx2tf 2.6.9, direct FlatBuffer backend; host LiteRT is
2.1.2 and ONNX Runtime is 1.26.0. Existing Android diagnostic LiteRT is 2.3.0;
these are distinct runtimes, and no Android encoder runtime/binary is validated here.
Original model identities:

- encoder.onnx: `2144d185b85e7c75077a19afbd8b77f0cb8daf814844c79f213e68e8fbf5a725`
- encoder.weights: `9a22d372c51455c34f13405da2520baefb7125bd16981397561423ed32d24f36`

Input is dynamic `[batch,128,time]` FP32 plus INT64 true length. Original graph has
120 LayerNormalization nodes and dynamic Shape/Range/Expand/Where mask machinery.
External weights are 2,435,420,160 bytes, beyond a conventional single 2 GiB buffer;
the converter automatically requests splitting. This is an observed size constraint,
not proof that splitting preserves runtime behavior.

Frozen probes: zero features at T=17; PCG64 seeds 731/732/733 at T=17/2000/6000;
true lengths 17/1961/6000 in attempt 03. Attempts 01/02 used 1993 at T=2000;
that weaker probe did not change the subsampled output length and is preserved separately. The short nonzero and silence references have output
shape `[1,1024,3]`, length 3. The masked 2000-frame reference has shape
`[1,1024,250]`, true encoded length 246 in attempt 03 (250 in 01/02); the 6000-frame reference `[1,1024,750]`, length 750.
All four original ONNX CPU executions were finite. Synthetic zero features test
encoder silence-shaped input, not the feature extractor's acoustic silence.

The production chunker has no hard maximum and flush permits short tails. These
are bounded synthetic stress probes, **not established development shortest/median/
longest chunk quantiles**. No complete-envelope or transcript-parity claim follows.
No recording was processed on the host and reserved validation remained untouched.

Attempt 01 was terminated after 52.49 s (observed converter RSS 18,845,992 KB)
while inspecting upstream source: default simplification/name generation can replace
the input model in place. Both model hashes were unchanged when independently
frozen again. Attempt 02 disables those transformations and preserves dynamic axes.
The first coverage report identifies missing intermediate type/shape inference:
`wa/pre_encode/conv/Shape` is inferred FLOAT32 though Shape requires INT32/INT64;
562/4,955 nodes are rejected, including 120 LayerNormalization shape failures
(input inferred `[1]`, scale `[1024]`). The converter retries lowering internally with custom operators enabled even
though the caller did not request them. Its final 100% coverage report contains
562 custom nodes (ONNX_SHAPE and five other operator families), and therefore
does not establish builtin runtime support. That route timed out after 301.64 s
during split planning; it produced no converted encoder.

Attempt 03 adds metadata only: ONNX shape inference supplies 4,977 intermediate
value-info entries; graph-node bytes remain identical. All 4,955 lowered nodes
then use builtins, with zero custom operators. This repairs the first shape/type
blocker but does not produce a numerical verdict.

**Outcome: this pinned converter route is unsupported for this encoder's size.**
The installed split planner computes its size estimate with
`len(serialize_model(full_model))` *before* choosing partitions
(`split_planner.py:96`, `:2185`). Each constant is embedded separately, without
deduplication/external data (`tensor_buffer_builder.py:126`). The 2,435,420,160-byte
FP32 weights alone exceed the installed builder's `2**31` ceiling. An actual
`flatbuffers.Builder(2**31 + 1)` probe raises `BuilderSizeError`. Independently,
the exporter unconditionally writes an unsplit FP32 model after writing partitions
(`tflite_builder/__init__.py:754`), hitting the same ceiling even if planning were repaired.
This conclusion combines actual conversion progress, installed primary-source
inspection and the runtime limit probe; no full exporter exception is claimed.

The operator terminated attempt 03 with SIGTERM during split-planning serialization
after 663.34 s, once both mandatory impossible paths were verified. Peak observed
converter RSS plus swap was 19,327,844,352 bytes. The process was reaped; reference
execution completed afterward. Three attempts are retained: one interrupted,
one timed out with implicit custom lowering, one stopped with the verified size
diagnosis. Twelve original CPU fixture executions were finite; zero converted CPU
fixtures executed. No TFLite model or shared encoder adapter is eligible for #4/#5.

Private evidence stays under `corpus/lab/parakeet-fp32-gpu/conversion-01` through
`conversion-03`. SHA256 identities:

| Evidence | SHA256 |
|---|---|
| 01 freeze | `9041b8013363ead266d231452bca1daa490a49232ce6ef9a4122342475ab7052` |
| 01 result | `162846fd5b7a149f9ea5237c53f33e72106db2e29790e84f755e4752c3ebebbc` |
| 02 freeze | `0d99f74f022cd5d97b536cb347e46cf7de7d28cfc6ceda1e60d14793433502c6` |
| 02 result | `1166ddcd8dc74d16cf269cccee3bb093a435d76babbd3ac476a84e6b135d8276` |
| 03 freeze | `29bd414bac3e7b50dc4f340e9849fed73c42310bf10a3ecf112faaaeac97676a` |
| 03 result | `4bd2c9a7ceb6c5b42ad438a6c8e18a84fb72deb924561b192adf1cb16d53f8a6` |
| 03 diagnosis/source identities | `b848497e9e6c5246d1a96f6a0a6ed0af7a54bdbaeaad6dac2df3aeadec477871` |
| Host runtime/converter binary identities | `d7e8c0351ad479b01cfe1e9bbaa1ae3f5f77c95e45b64cdd7fc4386da28e53ac` |

Each freeze identifies its exact executed source snapshot. Post-attempt guard and
tensor-verdict hardening in the committed tool is covered by seven synthetic
regressions; it is not represented as previously executed numerical proof.

The next question is whether repairing both monolithic serialization paths, or
a converter with supported external buffers, can retain the dynamic length contract. Any changed graph/options need a new freeze and real tensor comparison.
Only after conversion succeeds should the smallest Sherpa encoder adapter be built
and compiled from the same source for host and Android, using identical converted
bytes and separately recorded runtime/binary identities. This slice supplies no
accepted adapter or model for #4/#5; tensor correctness, whole-take text, GPU
execution and speed remain unproven.
