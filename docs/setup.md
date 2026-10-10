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

Tap FUTO's microphone key. The panel replaces the keyboard and starts recording at once. Tap **Stop** to insert the text and return to FUTO, **Cancel** to discard and return, **Keyboard** to return without recording. Tapping into another field while recording discards the recording. The app has no network permission; recordings stay in memory and are discarded after use.

## Benchmark mode

Settings, System, Keyboard, On-screen keyboard, **Local dictation** opens the app's settings. **Benchmark mode: save recordings** is off by default. While it is on, the panel shows a red banner and every take ended with Stop is stored on the phone as audio plus a result file; **Delete benchmark data** removes them all. Reverse: switch it off and delete.

## Development connection

USB debugging stays off except while testing. To test: Settings, System, Developer options, **USB debugging** on, plug in, run `tools/phone status` on the laptop and tap **Allow** on the phone. When done: unplug, and optionally **Revoke USB debugging authorizations** in Developer options. Leave **OEM unlocking** off always.

## Known state after 2026-10-10

The debug-signed 0.1.1 was uninstalled and the release-signed 0.1.1 installed; Oriol repeated steps 2 to 4. Verified by Oriol: FUTO's microphone key opens the panel and Stop inserts the placeholder text (no recognizer yet). Later release and benchmark builds share the key and update in place. See [plan](plan.md) for status.
