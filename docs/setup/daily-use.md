# Phone setup and rollback

For Oriol's Pixel 10 Pro XL on GrapheneOS with FUTO Keyboard (Play Store build 0.1.30). Oriol performs the manual settings steps; reversals are listed beside them. Before agent phone access, follow [safeguard activation](../agent-safeguards.md). This guide supplies no authorization to install or change settings.

## Setup (one time)

| Step | Where | Reverse |
|---|---|---|
| 1. Install the app | Initial setup remains manual. For a compatible replacement of an installed build, use [the verified install/recovery workflow](../phone-lab.md). | Verified recovery for compatible replacements; destructive removal still requires reversing step 4 first. |
| 2. Enable it as an input method | Settings, System, Keyboard, On-screen keyboard: switch on **Local dictation**. Android warns that a keyboard can read what you type; this applies to every keyboard, FUTO included. | Switch it off in the same list |
| 3. Grant microphone | The first time the panel opens it asks; choose **While using the app**. If you chose "Don't ask again", grant it under Settings, Apps, Dictation, Permissions. | Revoke in the same place |
| 4. Point FUTO at it | FUTO Keyboard settings, Voice Input: switch on **Disable built-in voice input** (subtitle "use voice input provided by external app"). | Switch it off. FUTO's own recognizer and the Multilingual-244 model are untouched and come back immediately. |

Order matters for removal: reverse step 4 before step 1. With the FUTO toggle still on and the app gone, FUTO's microphone key does nothing until the toggle is turned off.

## Daily use

Tap FUTO's microphone key. The panel replaces the keyboard and starts recording at once; while you speak, the text recognised so far appears in the panel only. Tap **Stop** to insert the whole text and return to FUTO; **Cancel** or **Keyboard** discard the take and return. Tapping into another field, hiding the keyboard or locking the screen during a take discards it and FUTO comes back. The first take after the app process starts waits about 4 s longer while the model loads. The app has no network permission; recordings stay in memory and are discarded after use.

## Benchmark mode

Settings, System, Keyboard, On-screen keyboard, **Local dictation** opens the app's settings. **Benchmark mode: save recordings** is off by default. While it is on, the panel shows a red banner and every take ended with Stop is stored on the phone as audio plus a result file; **Delete benchmark data** removes them all. Reverse: switch it off and delete.

## Experiment builds

The benchmark build is debuggable and shares the release signing key, so it updates the release build in place. The [completed allocation archive](../archive/allocation-2026-10-19.md) records the last verified restoration to the original setup baseline. Any future provisioning and restoration belong to a separately authorized work order under [the protocol](../experiments/protocol.md).

## Development connection — Oriol only

USB debugging stays off except during authorized testing. Oriol enables it under Settings → System → Developer options, plugs in and approves the laptop connection. An authorized agent can then verify the connection through the approved access path. Afterwards Oriol can turn debugging off and revoke the laptop authorization in the same settings. Keep OEM unlocking off. A successful connection check does not establish locked-screen overnight survival.
