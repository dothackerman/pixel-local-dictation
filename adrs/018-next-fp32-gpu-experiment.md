# ADR-018 — Next experiment: FP32 encoder parity, then whole-take wait

2026-10-20 · Prepared only. No conversion, inference, phone operation or new recording
is authorized by this document. Oriol will launch work in a new session.

## Question and reason

Can a GPU encoder preserve Parakeet FP32 dictionary output and reduce the wait
after Stop? This tests a possible route to a better app flow. It does not select
an ASR model in isolation.

ADR-012 found better development-set output with FP32 and dictionary support.
ADR-015 found substantial encoder work, but its profiled timings were not
comparable. ADR-016 proved GPU FP32 access for an image model only. A speech
conversion and encoder adapter are still missing. These facts justify a bounded
test, not a prediction that GPU will win.

## Prepared handoff

The tracked specification is `experiments/parakeet-fp32-gpu.json`. The private
suite and preparation snapshot live in `corpus/lab/parakeet-fp32-gpu/`.
Preparation hashes host files without loading a model or touching the phone.
Use `python3 tools/lab.py prepare experiments/parakeet-fp32-gpu.json`, then
`python3 tools/lab.py check <returned-path>` to refresh the inventory.
Missing converted model, bridge and APKs are expected.

The suite uses all ten existing development recordings. It freezes file hashes,
sample counts, references and terms
privately. Do not tune on or expose the reserved final validation set.
The same cases and case order apply to every setup. Replay feeds 640 samples
at 16 kHz through `DictationSession`, with the existing one-second silence
warmup. Each run uses a fresh process; the model remains warm across its cases.
The reports include cold load, first preview, Stop wait, coverage, memory and
thermal state. Replay editor and keyboard acknowledgements remain simulated.
Prepared suite SHA-256:
`76d156d93662f06dbbf91c2e85dea5e0b5eac78a6f5e5203490081c62666b901`.
Historical prefix manifests were pruned; their hashes cannot reconstruct their
references. Do not invent them from ASR output. Short interrupted speech needs
the supervised smoke and a separately prepared endpoint suite in later validation.

## Execution after launch authorization

1. Freeze a launch date/window, policy and budgets. Check current phone state,
   exact artifact compatibility and recovery through the protected wrapper.
   No current device state is assumed. If wrapper changes are necessary, prepare
   a patch for Oriol; agents do not change that protected file.
2. Pin a conversion environment and recipe. Inspect supported operators and
   shapes before choosing the conversion route. Convert only the existing FP32
   encoder. Keep feature extraction, lengths/masks, decoder, joiner, search,
   dictionary, chunking and output cleanup fixed. Do not silently substitute
   fixed padding or truncation for variable-length semantics.
3. Implement the encoder seam in the pinned Sherpa native path, keeping the
   decoder/search path intact. Use one unprofiled build with A/B/C selection
   where possible. Every intended build or runtime difference must be listed
   and hashed. Record the actual backend, precision and absence of CPU fallback
   from the executed implementation, not from a requested configuration label.
4. Before private replay, test synthetic silence and representative shortest,
   median and longest chunk shapes. Compare original ONNX CPU output, converted
   CPU output and converted GPU output: finite tensors, exact output lengths,
   every value within `atol + rtol * abs(reference)`. Proposed tolerances:
   `atol=1e-4`, `rtol=1e-3`. Include transfers and shape preparation in the
   encoder path. Freeze checks before results; do not loosen them after failure.
5. Use up to two phone smoke processes, including a supervised live take check.
   Check capture drain, repeated Stop, cancellation with native work outstanding,
   input change, next take, exact single insertion and actual return to FUTO.
   These are host-test gaps. Oriol operates FUTO; do not automate other apps.
6. Freeze two private comparison plans. A/B is conversion parity: original
   FP32 CPU versus converted FP32 CPU. A/C is wait improvement: original FP32
   CPU versus the same converted encoder on GPU. Run A/B first. Only a pass
   opens A/C. A/C order is A1, C1, C2, A2 to reverse pair order.
7. Before each external execution, `reserve PLAN RUN`. Preserve successful,
   failed and partial evidence through `record PLAN RUN RESULT`. Then `compare
   PLAN` saves the decision. A reserved run without a receipt requires
   reconciliation; it does not grant a retry. Do not renew budgets by changing
   experiment IDs. Recovery and its verification remain inside the phone window.

There are six measured processes and at most two smoke processes. A conversion
attempt plus one diagnosed repair is the conversion limit. Host smoke inference
is capped at twelve invocations and sixty minutes total. Stop engineering after
twelve active hours if measurement readiness is still absent; record the blocker.
These are proposed allocations, not grants. A missed gate ends this attempt.

## Decision before results

The specification marks thresholds as proposed. Launch must approve them before
`freeze`, which requires `policy_status: approved`.

Both A/B and A/C require exact final text parity on every matched case, including
punctuation. Report normalized word errors separately by language. This strict
gate isolates the encoder change; an apparent quality improvement still needs a
separate declared quality experiment.

A/C additionally needs at least 20% **and** 500 ms lower median warm Stop wait in
each language. Each repeated pair must improve; the pooled saving must exceed
the observed spread of repeat medians. Peak process high-water memory must stay
at or below 3,500,000 KB; sampled thermal status must stay at NONE (0).
These small development samples support no population tail-latency claim.
Report all per-case waits, maximum waits, cold load and first-preview values,
even when the primary gate passes. Visible editor text and usable keyboard
timing require the supervised check and later product validation.

| Observation | Decision and next experiment |
|---|---|
| Conversion or tensor parity fails | Stop. Retain the operator/shape/precision diagnosis. One diagnosed repair is allowed; a further route needs a new plan. |
| A/B final text differs | Stop before A/C. Diagnose conversion, lengths or the bridge; do not interpret GPU speed. |
| A/C final text differs or resource gate fails | Reject this setup. Preserve scores and failure evidence. |
| A/C has no practical repeatable Stop gain | Reject this acceleration route. Next: test chunk scheduling or decoder/search work from the unprofiled trace, with a fresh hypothesis. |
| A/C passes all gates and live smoke passes | Advance to a separate matched daily-default comparison and reserved, user-operated FUTO workflow validation. |
| Missing, mismatched, profiled or incomplete evidence | Inconclusive. Reconcile; no winner and no automatic extra run. |

No result in this experiment promotes a daily setup. A product winner must also
beat the current default and installed FUTO on correction effort and complete
workflow timing in both languages, with accepted lifecycle, cold-use and
resource behavior on reserved evidence.

## Git record

Keep the tooling implementation and this preparation as separate commits.
The preparation commit defines the question, policy proposal and next decisions.
Commit the approved engineering allocation before its synthetic smoke inference.
After future conversion/bridge work, commit that implementation and the approved
measurement plan **before measured replay**. Record its Git SHA in private provenance. Append the
approved numeric policy, suite digest, plan digest, exact build identities and
allocation to this ADR in that plan commit; keep personal content private.

After the run, append a dated outcome here and commit it, including failures:
implementation/plan commit IDs, preparation/plan/report digests, attempted and
completed counts, aggregate DE/EN scores, timings, resource results, gate decision
and the specific next question. Hash the private receipt set so the conclusion
can be traced back to immutable evidence. `lab compare` pins evaluator sources
and refuses changed scoring code.

A protocol change needs an explicit amendment commit before its new runs. Never
rewrite a failed attempt into a success or silently replace its receipts.
Raw audio, transcripts, case references, logs and credentials remain outside Git.
These commits preserve intent → implementation/approved test → outcome.
Digests prove identity, not availability; the private evidence must remain on
this machine for later re-analysis.

## Effort estimate

This is an engineering estimate, not a measured conversion benchmark.

| Work | Active effort | Basis and uncertainty |
|---|---:|---|
| Pin environment; convert and verify encoder tensors | 3–6 h | No converter is installed in either inspected model environment. Encoder graph is 41,838,345 bytes plus 2,435,420,160 bytes of external weights, 4,955 ONNX nodes, opset 17, dynamic time/batch. Operator and shape support are unproven. |
| Encoder-only bridge, backend evidence and correctness checks | 4–8 h | Existing Sherpa `RunEncoder` is the seam; decoder/joiner stay unchanged. LiteRT probe exists, speech bridge does not. Prior cached native build took 36 s: implementation/debugging dominates compilation. |
| Freeze artifacts, supervised smoke, analysis and outcome commit | 2–4 h | New shared-session path has host coverage but still needs live validation. Exact wrapper compatibility and phone state must be checked at launch. |
| **Total active effort if feasible** | **9–18 h** | A bounded first attempt stops at 12 active engineering hours if readiness is absent. Finishing after that requires an explicit new allocation. |

The host has 16 logical CPUs and about 33 GB RAM. This does not prove the
conversion will fit memory or finish quickly. Allow **1.5–3 working days elapsed**
if the route works, including conversion/build waits and phone access. A hard
unsupported-operator or shape failure may close the attempt earlier.

Ten complete takes total 726.12 s. One paced suite therefore has an audio lower
bound of 12.10 min. Six measured processes require **72.61 min of audio alone**.
Allow **2–4 h of phone wall
time**, including model staging/loading, finalization, smoke tests, cooling and
restoration. This phone time partly overlaps the active effort above; do not add
all table values as elapsed time. Oriol's supervised portion is about 20–40 min,
plus device availability for the full window.
