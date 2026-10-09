# Implementation plan (for review, 2026-10-09, revision 2)

Status: proposal awaiting Oriol's review. Revision 2 incorporates an independent review; its accepted findings are marked (rev). Nothing beyond the milestone 1 shell is built. Requirements in [requirements](requirements.md) stay authoritative; this file says how the [roadmap](roadmap.md) milestones will be executed and what was learned on the phone.

## Where we stand (AS-IS, 2026-10-09)

| Fact | Evidence |
|---|---|
| Pixel 10 Pro XL, Tensor G5, GrapheneOS build 2026100601, Android 17 (API 37) | `tools/phone versions` |
| FUTO Keyboard Play Store build 0.1.30, the only enabled keyboard before this project | same |
| FUTO voice model Multilingual-244; keyboard languages Deutsch (Schweiz), English (UK) | Oriol |
| FUTO 0.1.30 Voice Input settings offer one toggle, "Disable built-in voice input / use voice input provided by external app", and no provider dropdown | Oriol |
| `ch.og.dictation` 0.1.0 installed and enabled as an input method; only permission RECORD_AUDIO, no INTERNET | `aapt2 dump permissions`, install log |
| Laptop toolchain: JDK 21, Gradle 9.8.1, AGP 9.4.1, platform 36.1, build-tools 37.0.0, NDK 30.0.16248370, CMake 4.1.2 | installed from Google's live listing |
| Phone access gate active: deny rules, hook, `tools/phone` wrapper; audio and keys refused by pre-commit | commit b3378aa |

Observed 2026-10-09, shell 0.1.0 reached via the system keyboard switcher: panel opens recording, Stop inserted `[recorded 3.0 s]` and returned to FUTO. FUTO's microphone key with the toggle on did nothing, also after a language switch and a force-stop.

Diagnosed from framework source (`SubtypeUtils.getImplicitlyApplicableSubtypesImpl`): voice subtypes without a locale are dropped from the implicitly enabled list, so FUTO's `getShortcutInputMethodsAndSubtypes()` lookup found no voice subtype. Fix in 0.1.1: `overridesImplicitlyEnabledSubtype="true"` on our subtype, which Android honours regardless of locale. Installed; Android now lists the subtype with the flag. The microphone-key path itself is not yet re-tested. Still unobserved: FUTO baseline timings.

## How FUTO hands off to us (inferred, to be verified first)

The Play Store build has only the toggle. Inferred from FUTO's LatinIME ancestry and AOSP, not verified in 0.1.30 source: with the toggle on, the microphone key triggers Android's shortcut-IME switch. Android, not FUTO, then chooses among enabled input methods that declare a subtype of mode `voice`, preferring one whose locale matches the system locale, else the first found. Our subtype declares no locale on purpose (R2). Risks (rev): another enabled voice input method could win; FUTO's own subtypes might qualify and loop back; the auxiliary subtype's implicit enablement is an AOSP rule not yet observed on Android 17/GrapheneOS.

Verification before anything else: Oriol's six-step live test, plus a read-only `ime list -a` through the wrapper to list every enabled input method's subtypes and modes. If the wrong target is chosen, the fallbacks are, in order: declare locales on our subtype, a `RECOGNIZE_SPEECH` activity, or a newer FUTO build with the explicit provider dropdown. Each is a design change recorded in decisions.md.

Rollback (rev, corrects revision 1): turn the FUTO toggle off first, then optionally uninstall our app. Uninstalling with the toggle still on leaves FUTO's microphone key dead.

## Milestone 1 completion (shell, no recognizer)

1. Baseline before any change (rev): Oriol dictates five short sentences per language with FUTO as installed and notes stop-to-text wait, cold and warm, in `docs/baseline.md`. No instrumentation; this is the reference the roadmap requires before settings change.
2. Live test by Oriol: toggle on; microphone key; Record/Stop inserts `[recorded N s]`; Cancel inserts nothing; focus change during recording inserts nothing; messaging app, browser field, multiline note.
3. Shell fixes found by review (rev), in this order, each a commit: `onStartInputView(restarting = true)` must not reset the panel or restart capture while recording; permanent permission denial must show a message, not relaunch the prompt on every show; `Recorder` must stop on read errors instead of spinning; decide and record whether auto-start on every show is wanted or whether Record must be tapped.
4. Signing (rev): generate a release key now, stored outside Git at a path Oriol chooses, referenced from `local.properties`. All phone installs use it from here on so upgrades never require uninstall and re-setup.
5. Wrapper changes, applied by Oriol (rev): log filter by our tags; `test` subcommand for instrumentation tests; `subtypes` subcommand running `ime list -a` read-only; `pull-report` subcommand that copies only the app's own report directory, debug build only, used solely with a per-run yes.
6. Write `docs/setup.md`: enable the input method, grant microphone, toggle, and the exact reverse in the safe order. Gate: roadmap milestone 1 gate met; tag `m1`.

## Milestone 2: local Parakeet TDT 0.6B v3 on CPU

Runtime, proposed: sherpa-onnx, pending evidence. Before the spike, add to research.md (rev): license, prebuilt arm64 Android artifacts, Parakeet TDT v3 export and INT8 support, each with a link and the inspected version. Alternative: ONNX Runtime directly, following Parakeeb.

Spike (rev): on the phone via WAV replay, at least five German and five English clips, compare INT8 output against fp16 or reference output for parity, and record cold load, warm latency, peak memory. Record the outcome as ADR-003 before product code. The "about 1 GB" memory figure is a guess until measured.

Steps, each its own commit:

1. Asset pipeline: pinned runtime release and model export with SHA-256 checksums in `assets.lock`; a laptop script fetches into a gitignored `models/` folder. Bundled as uncompressed assets (`noCompress`) so they are memory-mapped, not copied on first run (rev). The phone never downloads.
2. `Recognizer` interface with `FakeRecognizer` for tests and `ParakeetRecognizer` wrapping the runtime. Loaded once per process and reused; cold and warm timings logged separately.
3. Milestone 1 pipeline feeds 16 kHz mono PCM; end-of-recording decoding only. Partial decoding waits for milestone 3 evidence.
4. Instrumentation test `ReplayTest` inside the app sandbox: replays WAV files from the app's private directory, checks output against references, writes per-clip timing JSON to the app's report directory, read back only via `pull-report` with a per-run yes.
5. Privacy review: merged manifest has no INTERNET; dependency tree contains no network library (`gradle dependencies` output committed); airplane-mode test. Gate: roadmap milestone 2 gate; tag `m2`.

Known risks, measured not assumed: cold load of a 600M-parameter model; memory; heat under repeated use; wrong language on short utterances.

## Milestone 3: measurement (rev, aligned with roadmap)

Corpus recorded on the phone's own microphone, not the laptop (acoustic confound). Our app never saves production audio, so recording uses a separate Oriol-operated recorder app or a benchmark-only mode in our app that is off by default and visibly on. Clips are copied to `corpus/` on the laptop only with a per-run yes. 20 German and 20 English clips, 5 to 30 s, plus a few 60 s takes, Swiss-accented German included because that is the installed locale, languages interleaved.

Baseline: first attempt identical-audio replay through FUTO (file import or a harness with FUTO's model and decoding settings, labelled model-level). Only if that fails, matched live takes, labelled as such; distinct spoken takes are never called identical-input comparisons. Report per language: word error rate, corrections counted by Oriol, names/numbers, punctuation/capitalization, wrong-language count, tap-to-ready, model load, stop-to-final with median and slow tail, cold and warm, memory, heat and battery over repeated sessions. Gate: R4 holds in both languages, otherwise milestone 4.

## Decisions requested from Oriol

- D1: apply the proposed `tools/phone` replacement (log filter, `test`, `subtypes`; `pull-report` added later with the report format).
- D2: sherpa-onnx as the first runtime to spike, after its evidence is added to research.md.
- D3: auto-start recording when the panel opens, matching FUTO's microphone-key behaviour, or require a Record tap.
- D4: corpus recording method on the phone (separate recorder app, or an opt-in benchmark mode in our app) and consent per copy to the laptop.
- D5: location of the release signing key outside Git.
