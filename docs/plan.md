# Implementation plan (revision 5, 2026-10-09)

Status: decisions D1–D5 approved by Oriol on 2026-10-09. Revision 5 incorporates two independent reviews; the second verified revision 4 and its remaining findings are fixed here. Requirements in [requirements](requirements.md) stay authoritative; this file says how the [roadmap](roadmap.md) milestones are executed. The implementing session reads, in order: AGENTS.md, this file, [setup](setup.md), [baseline](baseline.md), [research](research.md), [decisions](decisions.md). It touches the phone only through `tools/phone`, never edits the gate files, and asks Oriol for every wrapper change by writing a proposed replacement file with the Write tool and naming its path. The hook refuses any shell command whose text mentions the wrapper path, the Android debug bridge, fastboot or platform-tools, including heredocs and commit messages: read the wrapper with the Read tool and keep those words out of shell commands.

## Where we stand (AS-IS, 2026-10-09)

| Fact | Evidence |
|---|---|
| Pixel 10 Pro XL, Tensor G5, GrapheneOS 2026100601, Android 17 (API 37) | `tools/phone versions` |
| FUTO Keyboard Play Store 0.1.30; voice model Multilingual-244; keyboard languages Deutsch (Schweiz), English (UK); Voice Input settings offer one toggle, no provider dropdown | phone, Oriol |
| `ch.og.dictation` 0.1.1 (debug-signed `app-debug.apk`) installed, enabled, microphone granted, FUTO toggle on. FUTO's microphone key opens our panel, Stop inserts text, FUTO returns | Oriol, 2026-10-09 |
| FUTO baseline: about 1× real-time decode after stop (35.85 s wait for a 35 s German take, 34.75 s English); single sentences nearly error-free; long German take lost two names and one common word | [baseline](baseline.md) |
| Oriol's normal dictation is a long take like D6/E6, 30–40 s | Oriol |
| Toolchain: JDK 21, Gradle 9.8.1, AGP 9.4.1, platform 36.1, build-tools 37.0.0, NDK 30.0.16248370, CMake 4.1.2, all in `~/Android/Sdk` | laptop |
| Gate: deny rules, hook, `tools/phone` with status/versions/install/uninstall/logs/crash/test/subtypes | commits b3378aa, a02d007 |

Handoff: observed, FUTO's key reaches our panel only when our locale-less voice subtype carries `overridesImplicitlyEnabledSubtype="true"`. Inferred from FUTO 0.1.30 and framework source: FUTO asks `getShortcutInputMethodsAndSubtypes()`, which returns the first enabled input method with an implicitly enabled voice subtype. Rollback: FUTO toggle off first, then uninstall.

## Target, derived from the baseline

R4 in both languages on Oriol's real usage: a 35 s take. FUTO waits about 35 s after stop. Interim target for the engine decision: under 10 s after stop for a 35 s take. For whole-take decoding that means a real-time factor of 0.28 or lower; with decoding during the recording a per-segment factor below 0.5 suffices because only the last segment remains at Stop. Accuracy on the long takes: word error rate after normalisation no higher than FUTO's on the same scripts, names and numbers listed separately. The sub-second warm target from requirements is reachable only if decoding runs during the recording and the model stays loaded between takes; both are measured, not assumed.

## Milestone 1 remainder (shell), in this order

1. **Release signing (D5).** One-time, by Oriol with a command the implementer provides: `keytool -genkeypair -keystore ~/.keys/pixel-dictation/release.jks -alias dictation -keyalg RSA -keysize 4096 -validity 36500`. The key exists at `~/.keys/pixel-dictation/release.jks` (created 2026-10-09; one password for store and key, kept in Oriol's password manager). The build reads the password from `local.properties` (gitignored; set it to mode 600 after writing; Oriol pastes the value himself, it never goes through chat) or, if Oriol prefers, from the environment variable `DICTATION_KEYSTORE_PASSWORD`; `app/build.gradle.kts` checks the variable first, then the file, and creates `signingConfigs.create("release")` from whichever is set. Ask Oriol which he wants before the first release build. Add `buildTypes.create("benchmark")`: `initWith(getByName("release")); isDebuggable = true; signingConfig = signingConfigs.getByName("release"); matchingFallbacks += "release"`, and `testBuildType = "benchmark"`. Daily use runs the release build, which is not debuggable (R6). The benchmark build is debuggable, which `run-as` needs for `pull-report`; it is installed only for recording sessions and replaced by the release build afterwards. Both share the key, so either updates the other in place. Also add `testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"` and the `androidTestImplementation` dependencies `androidx.test:runner`, `androidx.test:rules`, `androidx.test.ext:junit` at their current stable versions, recorded in `docs/dependencies.txt`. The first release-signed install replaces the debug-signed 0.1.1 and requires one uninstall (toggle off first, then setup.md steps 1–4 again); say so to Oriol before doing it.
2. **Session-safe insertion.** Every text operation is tied to the input session that started the recording: capture a token in `onStartInput` when `restarting` is false and keep it when `restarting` is true (same session, text changed by the app); commit only on the main thread if the token still matches. `onFinishInputView` and `onFinishInput` invalidate it. Required before any background decoding exists.
3. **Benchmark switch (D4).** A settings activity for the app (declared via `android:settingsActivity` in method.xml so it is reachable from Android's keyboard list), with one switch "Benchmark mode: save recordings", off by default, and a button "Delete benchmark data". When on, the panel shows a red banner "benchmark: saving audio", and each take is written to `files/benchmark/<timestamp>.wav` (16 kHz mono PCM) plus `<timestamp>.json` with recognizer output and timings. Off by default means production audio stays in memory as today.
4. **Wrapper changes for Oriol to apply:** `test` runs `connectedBenchmarkAndroidTest`; new `pull-report` runs `exec-out run-as ch.og.dictation tar c files/benchmark | tar x -C corpus/`, read-only, used only after Oriol says yes for that run. Write the proposed file to the scratchpad and name the path.
5. **Edge cases with the FUTO key, by Oriol:** Cancel inserts nothing; tapping another field during recording inserts nothing; a messaging app restarting input mid-recording keeps recording; call or screen lock leaves no stuck microphone; denied permission shows the message once.
6. **Oriol records** D1–D5 and E1–E5 as single takes and D6 and E6 twice each, through the benchmark switch on the benchmark build, and says yes to one `pull-report`. These are the spike clips; the implementer cannot record on the phone. Tag `m1`.

## Milestone 2: Parakeet TDT 0.6B v3 via sherpa-onnx

### Design (proposal, confirmed or changed by the spike)

- Audio is segmented live with sherpa-onnx's silero VAD. Parameters start at: minimum silence 0.8 s, minimum speech 0.25 s, maximum segment 20 s; segments under 2 s are merged into the next. A cut at the maximum length falls inside continuous speech by construction; such cuts are counted as join errors, not avoided. Each completed segment is decoded by the offline Parakeet recognizer on one serial background thread while recording continues.
- Progress is shown only inside our own panel (a scrolling preview of the joined segments, single space between them). Nothing is written to the field before Stop, not even composing text, because the framework finalises composing text on a focus change before the input method can clear it. On Stop the final partial segment is decoded, the queue is drained with the panel showing "finishing", and the whole text is committed once. Cancel or focus loss discards the queue and the preview, so the field is untouched. This resolves O1 from revision 3.
- The language is detected by Parakeet per segment; one language per take is Oriol's usage, so a flip between segments is measured as an error.
- Why not alternatives: Moonshine's streaming models are per language and would need a language router of our own (roadmap milestone 4); FUTO nightly's Nemotron path requires manual multilingual selection in FUTO's UI (research); repeated whole-buffer decoding grows quadratically with take length.

### Step 0: assets and tooling, pinned before the spike

`assets.lock` lists exact URLs and SHA-256 of: `sherpa-onnx-1.13.8.aar` (GitHub release v1.13.8), `sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8.tar.bz2` (release tag `asr-models`), `silero_vad.onnx` (release tag `asr-models`, asset dated 2025-07-11, 643,854 bytes; record its SHA-256 at fetch time). `tools/fetch-assets.sh` downloads into gitignored `models/` and verifies checksums. The AAR goes into `app/libs/` (gitignored) and is consumed with `implementation(files("libs/sherpa-onnx-1.13.8.aar"))`. Before the first install, check the AAR's `arm64-v8a/*.so` for 16 KB page alignment (`readelf -lW ... | grep LOAD`, alignment 0x4000); unaligned libraries may fail or run in a compatibility mode on recent Android, so this is a check, not an assumption about the phone. Model files are bundled uncompressed via `androidResources { noCompress += listOf("onnx", "txt") }` under `app/src/main/assets/parakeet-v3/`; sherpa-onnx's Kotlin API reads them through `AssetManager`, so peak load memory is about twice the file size and is measured, not assumed. The phone never downloads anything. `tools/score.py` on the laptop: normalise digits and punctuation, word error rate per language, names and numbers listed separately; needed for parity and for milestone 3.

### Step 1: spike (ADR-003 gate)

A throwaway instrumentation test in `app/src/androidTest/`, no UI, reading Oriol's recordings from `files/benchmark/` on the phone (single sentences and long takes from milestone 1 step 6). Logged under tag `ReplayTest` and written to `files/benchmark/spike.json`:

- cold load time; memory as `Debug.MemoryInfo.totalPss` after load and after the longest take, plus `VmHWM` from `/proc/self/status` as the peak;
- real-time factor, defined as decode time divided by audio duration, warm, median and maximum, per segment of 3–10 s, German and English;
- for each D6/E6 recording: stop-to-final for whole-take decode, and for VAD-segmented decode with the segments fed at real-time pace;
- INT8 parity, on the laptop: a Python virtual environment with the `sherpa-onnx` package at 1.13.8 and the upstream export script `scripts/nemo/parakeet-tdt-0.6b-v3/export_onnx.py` (needs NeMo and PyTorch, CPU is enough) produces the fp32 ONNX files; both fp32 and the published INT8 files decode the pulled clips on the laptop; `tools/score.py` compares each against the reference scripts. Pass: INT8 word error rate at most 1 point worse than fp32;
- join quality: capitalisation and punctuation errors at segment boundaries, counted separately;
- language flips between segments of one take;
- `PowerManager.currentThermalStatus` and battery level before and after ten consecutive long takes.

Pass: under 10 s after stop for a 35 s take on the segmented path, per-segment RTF median under 0.5, parity within 1 point, peak memory (`VmHWM`) under 2 GB, thermal status never above "moderate", at most one join error per long take, and word error rate on D6/E6 after normalisation no higher than FUTO's from [baseline](baseline.md) (two takes, labelled anecdotal). Record ADR-003 with numbers either way. Fail paths, in order: ONNX Runtime directly with the same model (Parakeeb precedent); then roadmap milestone 4.

### Steps after a passed spike, each its own commit

2. `Recognizer` interface (`transcribe(samples: FloatArray): String`, 16 kHz mono in [-1, 1]), `FakeRecognizer`, `ParakeetRecognizer` wrapping `OfflineRecognizer` with `modelType = "nemo_transducer"`, created once per process on a background thread at service creation; cold and warm timings and the process id logged under tag `Recognizer`. Gate before step 3: Oriol does a take, returns to FUTO, waits 10 minutes, does another take; the log shows whether the process survived and whether the load repeated. If it did not survive, the sub-second warm target needs a design change (recorded as a decision); the under-10 s target still stands.
3. `Segmenter` wrapping sherpa-onnx `Vad` on the live stream; serial decode queue; panel preview and single commit on Stop, as designed above.
4. Privacy review: merged manifest has no INTERNET; `./gradlew :app:dependencies` output committed under `docs/dependencies.txt`; airplane-mode test by Oriol. Gate: roadmap milestone 2 gate; tag `m2`.

## Milestone 3: measurement

Corpus: D1–D5 and E1–E5 single sentences, D6 and E6, plus at least 15 further long takes per language written by Oriol about his real topics with names and numbers, all recorded through the benchmark switch on the benchmark build, copied with `pull-report` after a per-run yes, then the release build is reinstalled. FUTO comparison: first attempt identical-audio replay through FUTO; the roadmap says file import is not established, so if it cannot be done the same scripts are dictated live through FUTO on the same day (toggle off, dictate, toggle on) and timed by screen recording, labelled "matched live takes, not identical audio". `tools/score.py` produces word error rate per language, names and numbers separately, stop-to-final median and worst case, cold versus warm, memory, thermal, battery. Gate: R4 in both languages, else milestone 4.

## Approved decisions

D1 wrapper changes applied. D2 sherpa-onnx first. D3 recording starts when the panel opens; only Stop is tapped. D4 benchmark switch in the app; clips copied only with a per-run yes; deletion button in the app. D5 signing key at `~/.keys/pixel-dictation`, backed up by Oriol.
