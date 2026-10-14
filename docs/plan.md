# Milestone implementation history

This records the October 9–10 implementation and its remaining evidence limits. It is not an execution plan. Current work starts at [release 1a](batch-1a-v1.md); unfinished product validation and delivery belong to [the roadmap](roadmap.md).

## Completed checkpoints

| Checkpoint | Evidence and limits |
|---|---|
| FUTO integration and release signing, milestone 1 (`m1`) | Oriol verified microphone launch, insertion and return. Release and benchmark builds share the release certificate; benchmark is debuggable. On October 10 Oriol closed the recording step with D6/E6 twice each, omitting the proposed single-sentence takes. |
| Recognition, milestone 2 (`m2`, app 0.2.1) | Live German/English dictation, preview, Cancel, browser focus change, denied permission and screen-lock discard were checked. A ringing, declined call did not interrupt; an answered call was not tested. Merged manifest/APK requested only RECORD_AUDIO; Oriol completed an airplane-mode take. |
| Warm model lifetime | One 11-minute USB-powered gap retained the process with no reload. This satisfied the proposed 10-minute check, not longer or battery-only survival. Warm short takes still waited about 1 s; the proposed sub-second target was narrowly missed. |
| First comparison and app 0.2.2 | [Results](results.md#run-of-2026-10-10) own the protocol, numbers and accuracy limitation. Contiguous pause-based chunks replaced speech-only cuts. Phone `PipelineTest` checked chunk counts/text lengths; that checkpoint did not verify 0.2.2 live dictation. |

The early laptop spike used four takes (242 reference words). Published INT8 lost parity with FP32; selective MatMul quantization preserved greedy scores. The source reports remain at `corpus/laptop/{int8,fp32,var-*}.json`. [ADR-003](decisions.md#adr-003--parakeet-v3-via-sherpa-onnx-with-a-matmul-only-int8-encoder) owns the resulting phone measurements and working engine choice. Later beam parity differs: see [the precision diagnosis](beam-investigation-2026-10-12.md).

## Original spike gate

These were design thresholds for the October 10 spike, not product requirements or current loop gates: segmented Stop-to-final under 10 s for a 35 s take; per-segment RTF median under 0.5; INT8 WER at most 1 percentage point worse than FP32; peak `VmHWM` under 2 GB; thermal status at most moderate; at most one join error per long take; German and English D6/E6 WER no higher than FUTO's different takes. Language flips and charging-confounded battery readings were reported separately. ADR-003 records the English failure and the decimal-GB/GiB memory ambiguity alongside Oriol's provisional approval.

The proposed fail path was direct ONNX Runtime with the same model, then Moonshine. The spike found no speed problem requiring that runtime fallback; runtime comparisons and Moonshine remain in the current plan. A failed 10-minute survival check would have required revisiting the sub-second warm design, without relaxing the interim 10 s target.

## Integration rationale still needed by maintainers

- A locale-less voice subtype needed `overridesImplicitlyEnabledSubtype="true"` for FUTO 0.1.30 to discover it. The declaration is in [method.xml](../app/src/main/res/xml/method.xml); the source interpretation was FUTO's use of `getShortcutInputMethodsAndSubtypes()`.
- Browser field changes reported `onStartInput(restarting=true)` with identical attributes. The original instruction to retain that session was replaced: [DictationImeService](../app/src/main/kotlin/ch/og/dictation/DictationImeService.kt) discards an active take on every input start (commits `1853670`, `cd5a9be`). The cost is losing a take if an app restarts input mid-recording.
- Preview stays inside the panel; Stop commits once. Composing text was rejected because the framework can finalize it during a focus change before cancellation. [TakeTranscriber](../app/src/main/kotlin/ch/og/dictation/TakeTranscriber.kt) and [Engine](../app/src/main/kotlin/ch/og/dictation/Engine.kt) own queueing and cutting; [Variant](../app/src/main/kotlin/ch/og/dictation/Variant.kt) owns defaults.
- Oriol approved automatic recording on panel open, opt-in benchmark storage with deletion, per-run agreement for copying recordings, and the signing key at `~/.keys/pixel-dictation`, backed up by him (D3–D5). Current user instructions are in [setup](setup.md); safeguards own access procedures.

`ReplayTest` is the original spike harness, not the current pipeline benchmark. Its October 10 0.2.2 run still used speech-only segmentation and a second recognizer; its 4.5 GB peak and Stop timings cannot describe the daily app. Use the frozen batch's named harness for new measurements.
