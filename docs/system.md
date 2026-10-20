# Local dictation

Be extremely succinct; sacrifice perfect grammar for conciseness.

## Requirements

Confirmed 2026-10-09; R7 added October 10. Engine-independent.

| ID | Requirement |
|---|---|
| R1 | Keep FUTO’s microphone workflow; no per-take picker or confirmation. |
| R2 | Automatic English/German, one language per take; ordinary names/loanwords allowed. |
| R3 | Phone-only speech processing; no cloud fallback, Internet permission or telemetry. |
| R4 | Faster **and** more accurate than installed FUTO Multilingual-244, in each language. |
| R5 | Preserve meaning; transcribe, never translate/rewrite. Check names, numbers, omissions, inventions and formatting. |
| R6 | Normal GrapheneOS security; reversible return to FUTO with its baseline model retained. |
| R7 | Swiss spelling: ß → ss; scorer treats both equally. |

Language-conditioned models are acceptable behind automatic routing. Mixed-language conversation and diarization are outside scope. Dialect support, stop preferences and battery/heat limits remain unresolved. Roughly 3× less waiting, sub-second warm finalization and 10–20% fewer corrections are aspirations, not approved gates.

## Architecture

FUTO microphone → voice IME → capture → contiguous pause-based chunks → serial local decoding → panel preview → Stop commits once, returns to FUTO.

- [DictationImeService](../app/src/main/kotlin/ch/og/dictation/DictationImeService.kt): record on panel open; Cancel, Keyboard, input restart, hide or lock discard. Preview stays outside the editor until Stop.
- [TakeTranscriber](../app/src/main/kotlin/ch/og/dictation/TakeTranscriber.kt) / [Engine](../app/src/main/kotlin/ch/og/dictation/Engine.kt): decode closed chunks while recording; Stop queues the remainder. Offline chunks reuse no encoder state.
- [Variant](../app/src/main/kotlin/ch/og/dictation/Variant.kt): default Parakeet v3, selective MatMul INT8 encoder, FP32 decoder/joiner, sherpa-onnx CPU. Provisional choice: [ADR-003](adrs/003-working-engine.md).
- [method.xml](../app/src/main/res/xml/method.xml): locale-less voice subtype overrides implicit subtype selection for FUTO discovery. Input restarts discard even with identical attributes: [ADR-010](adrs/010-ime-lifecycle.md).
- Lab alternatives share Python/Kotlin schema semantics. Cohere uses one recognizer with per-stream language and filesystem models; Canary retains two instances. [ADR-007](adrs/007-fp32-cohere.md).
- [RuntimeConfig](../app/src/main/kotlin/ch/og/dictation/RuntimeConfig.kt) / [runtime_config.py](../tools/runtime_config.py): independent recognizer/LID allocation and profiling. Stock remains the product default. Bounded native-role counters and offline profile analysis are diagnostic; [ADR-015](adrs/015-cpu-profiling.md).

## Privacy and operation

Capture explicit takes; release microphone afterwards. Production audio stays in memory, then is discarded. Benchmark saving is opt-in, visibly marked and deletable; each export needs agreement. Recordings/transcripts stay in gitignored `corpus/`; signing credentials stay outside Git. Provision models locally; downloads are separate from inference.

Oriol enables the IME, grants microphone permission and enables FUTO’s “Disable built-in voice input” toggle. For rollback, disable that toggle **before** removing the app. First take loads the model; warm retention beyond an 11-minute USB-powered gap is unverified. Release/benchmark builds share signing; benchmark is debuggable. README owns build commands; locks/build files own versions/hashes.

[AGENTS.md](../AGENTS.md) owns phone access. Fresh sessions verify trusted hooks and filesystem protections; approvals are checkout-specific. Hooks are guardrails, not isolation: terminal input/specialized tools can bypass them; escalations bypass sandboxing. Claude live filesystem enforcement remains unverified. Synthetic checks: `python3 tools/test_agent_guard.py`.

## Phone lab boundary

Use only the canonical main-checkout wrapper, never historical worktree copies; `help` owns grammar. One authorized owner/run follows inspect → stage → installed-only lab → pull results → recover; inaccessible baseline means stop. Retain matching originals first. Stopping needs separate current-task authorization; closing the IME is insufficient. Interrupted/failed recovery retains its journal: inspect/recover, never erase or blindly restage.

Ordinary APK replacement requires matching payload/version/signer; diagnostic APP/TEST replacement is a separately authorized transaction. Recovery covers original lab and supported APKs, not preferences/permissions/recordings/caches. New provisioning or broader preservation must be resolved before launch. Recording export needs separate consent; lab/profile JSON stays under `corpus/`. Protected-wrapper changes require an outside-path proposal, review, Oriol’s manual application and verification. [ADR-009](adrs/009-phone-recovery.md) preserves guarantees and unresolved release-return commitment.

## Evaluation architecture

Closed variants → frozen laptop controls → matched phone measurements → separate quality/resource/completeness verdicts. [ADR-004](adrs/004-closed-loop.md), [ADR-005](adrs/005-dictionary.md), [ADR-006](adrs/006-adapter-evaluator.md), [ADR-011](adrs/011-measurement-contract.md).

Ten development takes represent eight scripts, four per language; three feeds measure boundary sensitivity, not independent speech. Repeatedly inspected public guards are development evidence. Phone accuracy needs phone controls; dictionary benefit needs otherwise identical no-dictionary twins. Laptop guards/silence are not phone hallucination measurements. WER cannot replace correction-effort/meaning/formatting checks.

Each future experiment gets one private plan/manifest in `corpus/lab/`: source/runtime/APK/model/input identities, exact commands, controls, repeats/endpoints, thresholds, budgets, failure rules, dated Europe/Zurich window and restoration. Setup freezes contracts; fresh GPT 6.1 Sol executes only after explicit launch. Preserve budgets, evidence and exclusive namespace ownership across continuation. Deployment is separate; no active experiment is commissioned.

## Status and acceptance gaps

Tags `m1`/`m2` prove integration/local recognition. October 10’s six matched-live scripts: FUTO Stop 79.9–99.7 s versus app 0.01–1.04 s; different takes and laptop replays leave accuracy superiority unresolved. Allocation completed October 19: 66 measurements, no production change; [ADR-012](adrs/012-allocation-outcome.md). Moonshine Small did not advance; [ADR-014](adrs/014-moonshine-screen.md). October 20’s eight CPU profile runs found substantial encoder work but failed timing comparability; [ADR-015](adrs/015-cpu-profiling.md). Physical GPU/TPU access is proven: GPU FP32 passed numerical smoke, TPU failed one check; speech acceleration remains unimplemented ([ADR-016](adrs/016-accelerator-access.md)).

R4 requires the complete automatic phone path against installed FUTO, each language separately. Freeze settings/vocabulary before reserved validation; cover short/spontaneous/noisy/ambiguous starts, correction effort, names/numbers/meaning, formatting, wrong language, cold/warm latency, memory and repeated-session heat/battery. Prefer identical audio; disclose different-take comparisons. Answered calls, longer battery-only survival and a clean full FP32 export remain unverified. Dictionary entry/editing UX remains undecided.

Next decisions: one Parakeet FP32 conversion/GPU optimization track, bounded diagnosis of Cohere’s English-prefix failures, then independent FUTO validation; [ADR-013](adrs/013-future-investigations.md) owns order and stop conditions. Allocation/sweep campaigns are closed. No new runs or recordings authorized.

## Documentation boundary

This file owns requirements/current architecture; `adrs/` owns dated decisions, including experiment outcomes. Preserve approval status, context, decision, evidence/limits and revisit condition. Link private evidence; do not copy it.

Private evidence lives under `corpus/retained/`; `evidence.json` indexes selected reports, original identities and pruning limits. Retain decision-changing evidence, calibration/evaluation inputs and operational recovery state; closed campaign directory trees are not documentation. Future work creates a fresh bounded plan rather than continuing old ledgers.

Scoring uses private `corpus/references/baseline-v1.json`, with pinned Git-source provenance. Its file hash is the new reference identity; historical identities/verdicts stay unchanged. Personal references/transcripts remain outside Git.

Pre-compression documentation remains at `925ef8c87e054855246a6105a95778c73961ecef`; retired controllers at `d0e984641d9d247bd6dd4f500abfbba1091b7b43` are for offline reading only. Git preserves source/documentation, not deleted private runs. HTML is a generated review artifact, not another authority.
