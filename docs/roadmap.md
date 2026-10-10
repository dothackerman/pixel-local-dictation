# Proposed implementation roadmap

All milestones are unimplemented. Oriol requested a project handoff, not a phone deployment. Requirements are authoritative; the engine order is the proposal in [ADR-002](decisions.md#adr-002--parakeet-first-automatic-language-prototype).

## 1. Establish the baseline and prove FUTO integration

Record installed FUTO/GrapheneOS versions, current model/settings, and cold/warm live timings before changing phone settings. Build an independently signed `arm64-v8a` Android app with its own application ID.

Inspect the installed FUTO launch path. Start with a voice-only `InputMethodService`/voice subtype; use `InputConnection` for final text. Implement `RECOGNIZE_SPEECH` activity/results if the actual keyboard path requires it. Locale declarations must not force recognition language. Test the shell in a dedicated test field before real messages.

Provide recording, stop, cancel and return-to-FUTO. Verify browser, messaging and multiline fields; denied permissions, interruption and changed focus must not cause crashes, duplicate insertion or insertion into the wrong field. Document one-time external-provider setup and original settings. For stable FUTO, the documented switch is disabling built-in voice input; verify actual provider discovery rather than assume it works.

**Gate:** existing microphone launches the app without a new per-recording step; correct insertion/cancel/return work on the phone. Deliver APK plus setup/rollback notes. Rollback restores FUTO built-in voice input; retain original keyboard and model.

## 2. Add local Parakeet recognition

Use TDT **0.6B v3**, not English-only v2. Inspect [upstream Android precedent](research.md#implementation-references), licenses and buildability before reusing code. Pin source commit, runtime version, model conversion/precision and asset checksums. Bundle or locally import assets to satisfy R3.

Capture mono audio and convert to the model's required 16 kHz. Begin with an optimized CPU/INT8 implementation; validate against reference outputs. Keep automatic language detection. Reuse the loaded model where Android lifecycle permits; separate cold-load timing from warm recognition. Background listening is unnecessary.

Implement local WAV replay for controlled testing. Start with end-of-recording decoding unless a tested incremental runtime is available: Parakeet v3's throughput claims do not establish native streaming. If repeated partial decoding is added, measure its compute, heat and finalization cost.

**Gate:** live English/German recognition and replay work on the Pro XL; complete microphone flow and manifest/privacy review pass. Deliver APK, reproducible build instructions and engine/model inventory. This gate proves operation, not superiority.

## 3. Measure the personal improvement

Start with 20 English and 20 German clips, 5–30 seconds each, plus several 60-second recordings. Include actual accent, names, technical vocabulary, dates, numbers, quiet/noisy conditions and ambiguous starts. Interleave languages. Oriol reviews reference text and counts corrections; the implementer supplies tooling.

Use identical audio for candidate/model comparisons. A harness with matching FUTO weights and decoding settings is a **model-level** baseline, not proof of installed-app equivalence. FUTO WAV import has not been established: if identical-audio baseline reproduction fails, document that limitation and supplement with matched live trials. Do not call distinct spoken takes an identical-input comparison.

Export per-clip engine/asset versions, language, reference/output and timing only with agreed handling. Report English/German separately: word errors, editing corrections, names/numbers, punctuation/capitalization and wrong-language choices. Record tap-to-ready, model load, first useful partial where available, manual-stop-to-final and last-word-to-final. Include median, slow-tail, cold/warm runs, memory and repeated-session heat/battery observations. Silence, cancellation, focus changes and interruption belong in end-to-end checks.

**Gate:** a phone report supports R4 in both languages without violating other requirements. Expand the corpus if the apparent gain is marginal or inconsistent; retain uncertainty instead of declaring a winner. If unsuccessful, proceed to milestone 4 or report the unmet goal.

## 4. Conditional alternative: Moonshine with invisible routing

First replay the corpus through German Small Streaming and English Small/Medium Streaming with known language in the **test harness**. This screens model quality but is not an acceptable product workflow.

If promising, identify a buildable on-device English/German detector; none is selected or validated. Buffer initial audio, automatically route and replay it so first words survive. Short ambiguous utterances need a measured automatic fallback, potentially Parakeet. Parallel decoding is an alternative, but raw confidence scores across models are not comparable without calibration and it consumes more compute.

**Gate:** evaluate the complete router-plus-recognizer path, including detection delay, routing errors, fallback frequency and language alternation. It must satisfy the same requirements; oracle-language scores alone cannot pass. Whistle or accelerated Whisper remain optional challengers if evidence warrants them, not commitments cancelled by their deferred position.

## 5. Conditional Pixel optimization and final handoff

Once an engine demonstrates better accuracy, tune CPU threads using on-phone measurements. Investigate Vulkan only with an actual supported runtime. For Tensor G5, first validate an official Tensor SDK sample on the installed GrapheneOS build; then assess model conversion and compilation. Verify real accelerator execution, not an availability label or silent CPU fallback. Keep CPU fallback within R3. Requested by Oriol on 2026-10-10: this milestone also compares quantisation schemes (the selective scheme of [ADR-003](decisions.md#adr-003--parakeet-v3-via-sherpa-onnx-with-a-matmul-only-int8-encoder), finer per-layer ones, what accelerators require) and alternative runtimes, each checked against the fp32 export on the personal corpus.

**Gate:** repeat milestone 3 after material changes. Deliver source, pinned dependencies/assets and licenses, build/install instructions, signed APK, per-language results and tested rollback. Store the signing key outside Git. Record remaining limitations and the chosen architecture's ADR status from explicit evidence; prototype selection does not approve permanent deployment.
