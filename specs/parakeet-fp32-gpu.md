## Problem Statement

Oriol needs a complete dictation workflow that requires less waiting and fewer corrections than the installed FUTO model, in both German and English. Current evidence does not establish a winning setup.

FP32 Parakeet with dictionary support improved development-set recognition, but CPU Stop wait remains a concern. Profiling found substantial encoder work without establishing comparable timings. A synthetic image probe demonstrated FP32 GPU access; it did not demonstrate speech conversion, speech correctness or useful app performance.

The next experiment must resolve one uncertainty: **can an FP32 GPU encoder preserve the FP32 CPU transcript and reduce whole-take Stop wait?** It must leave enough evidence to select the next experiment even if conversion, validation or measurement fails.

## Solution

Prepare and execute a bounded comparison through the shared dictation session:

- **A:** original ONNX FP32 encoder on CPU.
- **B:** converted LiteRT FP32 encoder on CPU.
- **C:** the same converted encoder on LiteRT FP32 OpenGL GPU, without CPU fallback.

First verify synthetic CPU tensor correctness, then whole-take A/B conversion parity in the first phone window and restore the app. Build on that proven CPU workflow to implement C. In the second window, prove physical GPU tensor correctness, compare A/C for practical, repeatable Stop savings, check live C dictation and restore the app. Keep decoder, joiner, search, dictionary, feature extraction, chunking and output cleanup fixed.

The deliverable is a supported advance, rejection or inconclusive decision, with the next question recorded in Git and linked from this issue. An advance qualifies C for later daily-default and FUTO validation; it does not install a new daily winner.

**Publication and the `ready-for-agent` label do not authorize execution.** Oriol will launch work in a new session. Before inference or phone changes, that session must approve the proposed policy, exact allocation and launch window.

## User Stories

1. As Oriol, I want the experiment to measure the wait after Stop through the app's take lifecycle, so that a faster encoder translates into a useful workflow.
2. As Oriol, I want German and English judged separately, so that one language cannot hide a regression in the other.
3. As Oriol, I want meaning, names, numbers and formatting preserved, so that speed does not create more correction work.
4. As Oriol, I want the existing microphone handoff and return to FUTO preserved, so that dictation remains part of my normal typing flow.
5. As an experiment operator, I want an original FP32 CPU control, so that I can distinguish conversion defects from acceleration effects.
6. As an experiment operator, I want the converted model tested on CPU before measured GPU comparison, so that I can isolate conversion correctness.
7. As an experiment operator, I want proof of the backend and precision actually executed, so that a configuration label cannot masquerade as GPU evidence.
8. As an experiment operator, I want CPU fallback rejected, so that mixed execution cannot produce a misleading GPU claim.
9. As an experiment operator, I want decoder, search and dictionary fixed, so that the encoder change remains the tested cause.
10. As an experiment operator, I want the same recordings, exact sample counts, references and case order across setups, so that shortened or reordered inputs cannot manufacture a gain.
11. As an experiment operator, I want repeated pairs in reversed order, so that process warmth and device drift are visible.
12. As Oriol, I want cold load and first-preview timing reported alongside Stop wait, so that improvements do not conceal a worse start to dictation.
13. As Oriol, I want memory and thermal limits checked, so that an impractical setup does not advance.
14. As Oriol, I want cancellation and input changes to suppress late insertion, so that text cannot reach the wrong field.
15. As Oriol, I want repeated Stop to insert at most once, so that a slow completion does not duplicate text.
16. As Oriol, I want the next take to wait for capture drain while cancelled native work finishes safely, so that cancellation does not break recording or release a model still in use.
17. As Oriol, I want the candidate exercised through the live IME, so that simulated replay receipts cannot stand in for visible text and a usable keyboard.
18. As an experiment operator, I want explicit conversion, time and process limits, so that one failed route cannot consume an open-ended session.
19. As an experiment operator, I want each attempt reserved and its result preserved once, so that failures and missing receipts cannot silently become retries.
20. As an experiment operator, I want malformed or mismatched evidence classified as inconclusive, so that incomplete results cannot advance a setup.
21. As Oriol, I want recordings, transcripts and signing material to remain private on this machine, so that publishing the spec does not publish personal evidence.
22. As Oriol, I want phone operations confined to the protected app-scoped wrapper, so that experimentation cannot change GrapheneOS settings or other apps.
23. As a future contributor, I want the hypothesis and decision rules committed before their tests, so that results cannot change the rules after the fact.
24. As a future contributor, I want failures, aggregate results and evidence identities committed after the attempt, so that I can reconstruct why a route was accepted or rejected.
25. As a future contributor, I want each outcome to identify the next question, so that the project accumulates learning instead of disconnected benchmark runs.
26. As Oriol, I want a grounded effort estimate and explicit missing components, so that I can allocate the next session realistically.
27. As Oriol, I want experiment plans and outcomes kept separate from architecture decisions, so that ADRs retain their purpose.

## Implementation Decisions

- **Existing architecture:** use `DictationSession` through the shared `Pipeline.runtime` factory. `DictationRuntime` owns recognizer reuse and serial native work. Keep microphone, replay and editor behavior in their existing adapters. Do not create another session runner or a campaign scheduler.
- **Encoder integration:** add the smallest encoder adapter inside the pinned Sherpa runtime. Keep feature extraction, true sequence lengths and masks, decoder/joiner bytes, search, hotwords, VAD, chunking, automatic language handling and Swiss spelling fixed. Preserve variable-length semantics; do not silently replace them with truncation or different padding.
- **Conversion:** inspect operator and shape support before selecting a route. Pin converter versions, dependencies, input model bytes, options and generated artifacts. Use a thin synthetic fixture harness around the actual encoder adapter; share adapter source and converted model bytes between host and Android, and pin their distinct binary/runtime identities. Do not duplicate a recognizer, audio pipeline or session runner. The speech converter, converted encoder and encoder bridge do not yet exist. Existing image-probe code is prior art, not proof of speech support.
- **Build identity:** use one unprofiled benchmark build within each comparison, with separate frozen CPU-window and GPU-window identities. Complete, test and commit each window's implementation before that window. Enumerate and hash every build, runtime, model or configuration difference, preserving the validated conversion, CPU semantics and other fixed inputs. Each run must match its own frozen identities; unrelated inputs and conditions must match across setups within its comparison. Never pool timings across windows or differing builds. A changed converted model or invalidated CPU evidence requires an approved amendment before reuse of that proof.
- **Actual execution evidence:** collect backend, precision, fallback status and GPU-only execution proof from the running implementation. Do not copy a requested backend into the report and call it observed evidence. Preserve tensor transfers and shape preparation within the timed encoder path.
- **Candidate live path:** the benchmark IME must construct the frozen C setup through the same runtime factory used by replay. A smoke test of the unchanged daily CPU setup does not validate C. Any selection mechanism is benchmark-only and app-scoped, and is removed or restored during recovery.
- **Development suite:** use the ten already prepared full recordings and their private references. Freeze audio hashes, endpoint-to-input mapping, exact sample counts, references, terms and execution order. Do not expose or tune on the reserved final validation set. Historical pruned prefix manifests are unavailable; do not reconstruct references from ASR output.
- **Replay protocol:** use the existing shared-session replay protocol with 640-sample feeds at 16 kHz, one second of silence warmup, a fresh process per measured run and a warm recognizer across that run's cases. Editor and keyboard receipts are simulated and must remain labelled as such.
- **Frozen numerical gate:** proposed tensor tolerances are `atol=1e-4` and `rtol=1e-3`; every output value must satisfy the frozen absolute-plus-relative tolerance against the matched original ONNX CPU output. Require exact output lengths and no non-finite values. Cover synthetic silence and representative shortest, median and longest chunk shapes. Freeze concrete shapes, fixture values/seeds and true lengths/masks before running them.
- **Gate order:** synthetic converted-CPU correctness first, then measured A/B conversion parity in phone window 1. Restore and independently verify the app before GPU engineering. Only a passing A/B gate permits the GPU workflow. In window 2, first use one smoke for physical GPU execution and GPU tensor correctness; only on success run A1, C1, C2, A2. A passing A/C result requires the supervised candidate live check in the second smoke before it can advance. Stop on failed/incomplete execution or a terminal gate; remaining allocated processes need not be consumed.
- **Phone accounting:** at most six measured processes and two smoke processes across both windows: A/B uses two measured processes; the GPU window uses four measured processes and both smokes. One smoke establishes GPU execution/tensor correctness; the other covers the candidate live take lifecycle. Freeze exact executable procedures and process boundaries, including recovery verification, before each launch. Keep smoke reservations/receipts in the explicit operator record; the comparison reservation interface is for measured runs. If the procedures cannot fit the allocation, declare the mismatch before execution and seek a revised allocation; do not silently create extra processes or pilots.
- **Proposed bounds:** one conversion attempt plus one diagnosed conversion repair; at most twelve host synthetic smoke invocations and sixty minutes of host smoke computation in total. Freeze the invocation counting unit before inference, including original/converted evaluations; failed and partial invocations consume the allocation. Stop engineering after twelve cumulative active hours if readiness for both windows is still absent. Reserve two separately dated phone windows with at most four hours of combined allocation, including staging, restoration and verification twice. Engineering may continue between the restored windows within the shared cap. Completing the CPU phase does not reset or retire these bounds; neither a new ticket, context nor experiment identity renews them.
- **Transcript gate:** A/B and A/C require exact final text parity on every matched case, including punctuation. Report normalized word errors separately for German and English. A changed transcript, even an apparent improvement, fails this mechanism-isolation gate and needs a separate quality experiment.
- **Proposed Stop gate:** C must reduce the pooled median warm Stop wait by at least 20% **and** 500 ms in each language. Each repeated pair's language median must improve; the pooled saving must exceed the largest observed repeat-median spread. These are development-suite gates, not population tail-latency claims.
- **Proposed resource gate:** peak process high-water memory at or below 3,500,000 KB and sampled thermal status NONE (0). A control A breach makes the comparison inconclusive with a control blocker, regardless of candidate observations; it cannot by itself reject B or C. If every required A control is valid, a candidate-only breach rejects the corresponding B or C setup. Retain all observed breaches. Extend the current comparator to encode this distinction before freezing measurements; its current generic rejection of any resource breach is insufficient. Report all per-case waits, maximum waits, cold load, first preview and resource observations regardless of the primary result. Process memory and sampled thermal state do not establish total GPU memory use or battery cost.
- **Frozen comparison contract:** retain the current separation between preparation, freezing, reservation, recording and comparison. Freeze evaluator identities as well as evidence identities. Validate ordered cases, exact sample counts, input hashes, paced duration, full coverage, actual backend, fresh process identities and absence of profiling. Missing, failed, duplicate, changed or incompatible evidence cannot advance.
- **Attempt preservation:** reserve before external execution; preserve successful, failed and partial results without overwriting them. A reservation without a receipt requires reconciliation, not an automatic retry. This is cooperative accounting, not a guarantee of at-most-once physical device execution.
- **Authorization and recovery:** obtain task-specific launch authorization, approved policy and exact allocation before inference or phone changes, with an applicable dated window in Europe/Zurich. Reach the phone only through `/home/oriol/Projects/private/pixel-local-dictation/tools/phone`, with one subcommand per shell call and writes confined to `ch.og.dictation`. Verify compatible artifacts and recovery copies before changes; restore and independently verify the app after each window or abort. An interruption handoff records consumed allocation, pending reservations, current device/app state and recovery owner/deadline. Unverified recovery blocks further experimentation. Oriol operates FUTO. Agents do not change the wrapper, phone settings or other apps.
- **Git learning record:** commit the approved engineering allocation before its synthetic inference. Before each window's measured replay, commit its tested implementation and approved measurement protocol and freeze its comparison against the tested evaluator. Record implementation and protocol commit IDs, exact artifact identities, suite/evaluator hashes, decision rules and cumulative allocations. Protocol changes require an explicit amendment commit before new runs.
- **Outcome record:** each slice preserves its evidence and decision; any terminal failure or exhausted cap immediately produces a reviewed experiment outcome and next question, without waiting for downstream tickets. Commit the numerical decision, A/B decision and final outcome as applicable, with consumed/attempted/completed counts, available aggregate DE/EN errors, timings, resources, gate result and private receipt/report digests. Mark unavailable results and downstream work not attempted; a closed or skipped ticket is not a passing gate. Link result commits from this issue using result comments. Keep raw recordings, transcripts, references, logs and credentials outside Git and GitHub. Preserve private evidence locally; a hash cannot reconstruct a deleted file. Use ADRs only when an outcome produces an architectural decision.
- **Completion:** this spec is complete when the bounded attempt has a reviewed outcome and next decision recorded in Git and the issue. A supported conversion failure or rejection can complete the experiment. It must not be relabelled as a product success.

## Testing Decisions

- Prefer the **shared `DictationSession` boundary** for behavioral tests: feed controlled audio through capture, request Stop/cancellation, and observe output and lifecycle receipts. Assert external behavior, not private method calls or implementation layout.
- Reuse existing session tests for capture drain, repeated Stop, rejected commit, input invalidation, late callbacks, subsequent takes and runtime release after queued native work. Add only missing behavior needed by the encoder integration.
- Use the existing comparison interface as the evidence seam. Retain synthetic regressions for shortened inputs, case reordering, wrong hashes, invalid pacing, profiling, incomplete coverage and duplicate or missing receipts. Add cases where only A breaches a resource limit, only the candidate breaches, and both breach: the decisions must respectively be inconclusive, candidate rejection and inconclusive, with all breaches reported.
- The one additional low-level acceptance seam is **encoder tensor parity**, because transcript equality alone cannot locate conversion, length/mask or precision defects. Its ticket produces an actual complete numerical verdict or bounded diagnosis through the shared encoder adapter. It is explicitly distinct from app-flow validation and supplements the shared-session tests.
- Reuse the physical accelerator probe as prior art for backend verification, and the shared replay adapter as prior art for whole-take measurement. A successful synthetic image probe is never a speech acceptance test.
- Run host unit tests, Android test-source compilation and lint before device validation. Host speech inference on personal recordings is not part of this experiment.
- Separately verify the live candidate through supervised capture, Stop, cancellation with native work outstanding, input change, subsequent take, exactly one insertion, visible text and return to a usable FUTO keyboard. Distinguish OS acceptance receipts from observed UI behavior.
- Check the decision branches below with the frozen policy. Never relax thresholds after viewing the results.

## Out of Scope

- Selecting or deploying a daily winner; claiming superiority to FUTO from replay.
- Replacing the ASR family, decoder, joiner, search, dictionary, chunking policy or language-routing policy.
- Quantization, reduced GPU precision, CPU fallback, TPU compilation or a parallel Cohere experiment.
- A general experiment scheduler, automatic retries or an expanding model/parameter search.
- Recreating pruned prefix references, consuming the reserved final validation set, or making population tail-latency and battery claims from this small suite.
- Changing GrapheneOS security/settings, FUTO or other apps; copying private evidence to GitHub.
- Running any part of the experiment merely because this issue was published.

## Further Notes

ADRs 012, 015 and 016 explain the motivating evidence and its limits. ADR-017 defines the implemented shared-session and comparison boundaries. The former ADR-018 was a plan and is removed; this issue is the experiment spec.

Oriol approved four outcome-based subissues and the two-window amendment after fresh isolated adversarial Astra review: an auditable invalid-control decision; the complete CPU tensor gate; CPU integration through physical whole-take parity and recovery; GPU integration through measured/live acceptance and recovery. Only the latter two require phone access. The numerical gate is the explicit low-level acceptance seam. CPU and GPU tickets own their implementation and physical outcome, rather than deferring execution to a separate campaign ticket. Conversion and GPU work retain feasibility risk; the effort estimate does not establish guaranteed single-context completion.

| Observation | Decision and next question |
|---|---|
| Conversion or tensor parity fails | Stop; preserve the operator, shape or precision diagnosis. At most one diagnosed repair within the frozen allocation; another route requires a new plan. |
| A/B final text differs | Stop before A/C; diagnose conversion, sequence lengths or the bridge. |
| Any required A control breaches a resource limit | Inconclusive comparison/control blocker; preserve all observed breaches and establish a valid control protocol before drawing a candidate conclusion. |
| A controls are valid, but B or C alone breaches a resource limit | Reject that candidate setup; preserve resource observations and scores. |
| A/C text differs | Reject C; retain the scores and failure evidence. |
| A/C lacks practical repeatable Stop savings | Reject this acceleration route; use the unprofiled trace to choose a fresh chunk-scheduling or decoder/search hypothesis. |
| A/C and candidate live checks pass | Advance to a separate matched daily-default comparison, then reserved, user-operated FUTO workflow validation. |
| Evidence is missing, mismatched or incomplete | Inconclusive; reconcile actual state. No winner or automatic extra run. |
| Recovery cannot be verified | Stop further experimentation, retain the recovery state and resolve restoration before any next experiment. |

A product winner still needs better correction effort and complete workflow timing than the current default and installed FUTO in both languages, with accepted cold-use, lifecycle and resource behavior on reserved evidence.

Preparation already includes ten private full takes, with suite SHA-256 `76d156d93662f06dbbf91c2e85dea5e0b5eac78a6f5e5203490081c62666b901`. Tooling was committed as `794f396`; the earlier preparation as `73262c7`. Refresh host preparation after changes and before freezing a run.

The following estimate is engineering judgment grounded in the inspected assets, not a measured conversion benchmark:

| Work | Active effort | Basis |
|---|---:|---|
| Pin conversion environment, convert and verify tensors | 3–6 h | Converter absent in both inspected model environments; encoder has a 41.8 MB graph plus 2.44 GB external weights, 4,955 ONNX nodes, opset 17 and dynamic batch/time. Compatibility is unproven. |
| Encoder-only adapter, actual-backend evidence and correctness checks | 4–8 h | Existing Sherpa encoder entry point and LiteRT probe are reusable; speech adapter is absent. Previous cached native compilation took about 36 s, so implementation/debugging dominates. |
| Freeze, supervised smoke, analysis and outcome commit | 2–4 h | The shared session has host tests; candidate live behavior and exact wrapper/device compatibility still need validation. |
| **Total if feasible** | **9–18 h** | The first attempt stops at twelve cumulative active engineering hours if readiness for both windows is absent; continuation then needs an explicit new allocation. |

Allow roughly **1.5–3 working days elapsed**. The host has 16 logical CPUs and about 33 GB RAM, which does not prove conversion will fit or finish quickly. A hard compatibility failure may end the attempt earlier.

The ten takes total 726.12 seconds. Six paced measured runs therefore require **72.61 minutes of audio alone**: approximately 24.20 minutes for A/B and 48.41 minutes for repeated A/C. The existing estimate is **2–4 total hours of phone wall time**. The approved decomposition adds a second staging/restoration sequence within that same four-hour cap; its fit is not yet demonstrated. Budget both sequences, model loading, finalization, smokes, cooling and recovery verification in the exact procedures before launch; request an explicit amendment if they cannot fit. That partly overlaps active effort. Oriol's supervised portion remains approximately **20–40 minutes**, with the phone available for both agreed windows.
