# Phone setup and rollback

For Oriol's Pixel 10 Pro XL on GrapheneOS with FUTO Keyboard (Play Store build 0.1.30). Every step is reversible; the reverse is listed next to it. Nothing here touches system or security settings.

## Setup (one time)

| Step | Where | Reverse |
|---|---|---|
| 1. Install the app | Laptop: `tools/phone install app/build/outputs/apk/release/app-release.apk` | `tools/phone uninstall`, only after step 4 is reversed |
| 2. Enable it as an input method | Settings, System, Keyboard, On-screen keyboard: switch on **Local dictation**. Android warns that a keyboard can read what you type; this applies to every keyboard, FUTO included. | Switch it off in the same list |
| 3. Grant microphone | The first time the panel opens it asks; choose **While using the app**. If you chose "Don't ask again", grant it under Settings, Apps, Dictation, Permissions. | Revoke in the same place |
| 4. Point FUTO at it | FUTO Keyboard settings, Voice Input: switch on **Disable built-in voice input** (subtitle "use voice input provided by external app"). | Switch it off. FUTO's own recognizer and the Multilingual-244 model are untouched and come back immediately. |

Order matters for removal: reverse step 4 before step 1. With the FUTO toggle still on and the app gone, FUTO's microphone key does nothing until the toggle is turned off.

## Daily use

Tap FUTO's microphone key. The panel replaces the keyboard and starts recording at once; while you speak, the text recognised so far appears in the panel only. Tap **Stop** to insert the whole text and return to FUTO; **Cancel** or **Keyboard** discard the take and return. Tapping into another field, hiding the keyboard or locking the screen during a take discards it and FUTO comes back. The first take after the app process starts waits about 4 s longer while the model loads. The app has no network permission; recordings stay in memory and are discarded after use.

## Benchmark mode

Settings, System, Keyboard, On-screen keyboard, **Local dictation** opens the app's settings. **Benchmark mode: save recordings** is off by default. While it is on, the panel shows a red banner and every take ended with Stop is stored on the phone as audio plus a result file; **Delete benchmark data** removes them all. Reverse: switch it off and delete.

## Loop nights (experiment loop, [autoresearch](autoresearch.md))

The benchmark build (debuggable, same key, updates the release build in place) is installed for the night: `ANDROID_HOME=~/Android/Sdk ./gradlew :app:assembleBenchmark`, then the wrapper's `install` with `app/build/outputs/apk/benchmark/app-benchmark.apk`. USB debugging stays on and the phone stays plugged in. The loop writes only to the app's own `files/lab` directory (a model of up to 1.5 GB plus a result file) and reads the saved takes. Reverse in the morning: install the release build the same way; revoke the USB debugging authorisation if wanted. GrapheneOS's USB-C port setting defaults to charging-only when locked; whether the debug connection survives a locked screen overnight is tested once before the first unattended night.

## Development connection

USB debugging stays off except while testing and loop nights. To test: Settings, System, Developer options, **USB debugging** on, plug in, run `tools/phone status` on the laptop and tap **Allow** on the phone. When done: unplug, and optionally **Revoke USB debugging authorizations** in Developer options. Leave **OEM unlocking** off always.

## Known state after 2026-10-10

The debug-signed 0.1.1 was uninstalled and the release-signed 0.1.1 installed; Oriol repeated steps 2 to 4. Verified by Oriol: FUTO's microphone key opens the panel and Stop inserts the placeholder text (no recognizer yet). Later release and benchmark builds share the key and update in place. See [plan](plan.md) for status.
