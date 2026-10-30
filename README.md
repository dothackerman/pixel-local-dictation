# Pixel Local Dictation

On-device English and German dictation for the GrapheneOS Pixel 10 Pro XL. Open it through FUTO Keyboard’s microphone key, speak, then tap Stop to insert the text. Speech recognition runs locally, without an Internet permission, cloud fallback or telemetry.

This is an experimental Android app developed for one device and workflow, not a general-purpose keyboard replacement. Other devices and keyboard combinations have not been verified.

## Build

Build from source with an Android SDK configured. Model assets and signing material are not included in Git.

```sh
tools/fetch-assets.sh
tools/set-signing-password.sh
ANDROID_HOME=~/Android/Sdk ./gradlew :app:assembleRelease
```

[assets.lock](assets.lock) pins downloaded assets. The selected FP32 baseline requires additional local model and dictionary provisioning; building the APK alone does not reproduce the installed baseline. See [build details](DEVELOPMENT.md#build-and-host-checks) and [baseline configuration](DEVELOPMENT.md#current-design). A clean full FP32 export remains unverified.

## Use

Enable the app’s input method, grant microphone permission, and enable FUTO Keyboard’s **Disable built-in voice input** setting. Then use FUTO’s microphone key:

- **Stop** inserts the recognized text once and returns to the keyboard.
- **Cancel** discards the take without inserting text.
- Open the app’s **Settings → Dictionary** to edit vocabulary. Changes apply to later takes, not a recording already in progress.

English or German is selected automatically, with one language per take. German output uses Swiss spelling (`ss` instead of `ß`). Mixed-language conversation and speaker diarization are outside scope.

To return to FUTO’s built-in recognition, disable its **Disable built-in voice input** setting before removing this app. See [daily use and rollback](DEVELOPMENT.md#daily-use-and-rollback).

## Status and limits

The provisional baseline uses Parakeet v3 FP32 on CPU, beam 8 and dictionary score 1.5. It was selected for its accuracy/wait-time trade-off, not because it passed every original acceptance threshold. Missing provisioning can select the historical INT8 fallback; an explicitly saved empty dictionary keeps the FP32 configuration without vocabulary assistance.

Recognition can still change meaning, including negation, numbers and units. Review dictated text before sending it. Dictionary edits are supported, but arbitrary new entries have not been accuracy-validated. Startup delays, longer battery-only model retention and some keyboard handoff behavior remain unresolved or unverified. Recorded timings are specific observations, not performance guarantees.

See [the baseline decision and evidence](adrs/026-dictionary-first.md), [dictionary behavior](adrs/027-editable-dictionary.md), and [known gaps](DEVELOPMENT.md#known-gaps). Current work is tracked in [GitHub Issues](https://github.com/dothackerman/pixel-local-dictation/issues).

## Privacy

Normal-use audio stays in memory and is discarded after the take. Benchmark recording is opt-in and visibly marked. Model downloads are separate from on-device inference.

Personal recordings, transcripts, dictionaries, signing credentials and evaluation evidence stay outside Git. Private lab files belong in gitignored `corpus/`; do not include them in issues or pull requests.

## Contribute

Read [AGENTS.md](AGENTS.md) before agent-assisted work. The [development reference](DEVELOPMENT.md) preserves requirements, architecture, phone procedures and evaluation limits; [ADRs](adrs/) explain the decisions behind them.

Run host checks with:

```sh
python3 tools/quality.py
```

This runs Python regression tests, Kotlin benchmark unit tests, instrumentation compilation and Android lint offline—not device tests. The pre-commit hook runs the staged version of this gate. Phone validation uses the protected workflow in [AGENTS.md](AGENTS.md); host checks alone do not establish recognition quality or keyboard usability.

## License

Copyright 2026 dothackerman. [MIT License](LICENSE). Third-party components and model assets retain their respective licenses.
