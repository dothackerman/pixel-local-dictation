# ADR-028 — Owner data isolated by build type

2026-11-24 · Accepted by the owner (#73). Amends ADR-010 and ADR-024.

Phone tests and the owner's daily keyboard share one app (`ch.og.dictation`) and one data folder; owner
data was protected only by each test picking another folder or undoing its changes (#68). We fix storage
by build type, inside the app, for every process. Release is the product and the daily build; it resolves
only owner folders. The lab build (Gradle `benchmark`, debuggable) resolves only test folders and the lab
folder, has no owner path at all, and is installed only during a Visit. One storage module behind `Wiring`
is the only code that resolves app storage or writes the clipboard; a host check fails any other use. Each
APK carries a marker (layout, commit, build hash); the wrapper refuses a build whose marker and
debuggability disagree. Collected in daily use, retrieved during a Visit: the wrapper copies owner
recordings and receipts out read-only, and places copies for Lab runs in the lab folder.

## Owner data

Dictionary, History, History notices, settings, recordings and receipts from daily use, clipboard. Not: the
speech model, test output.

## Decision

- Two layouts, chosen at compile time; no run-time switch. `Wiring` is built in every process.
- Release is the product only: no Benchmark switch, no saving of benchmark recordings; the delete control
  for old recordings stays. One reserved exception: timing receipts (numbers only, off by default, one
  switch) because usage and performance patterns only show over long real use (#63).
- One applicationId, signer and versionCode; switching is an in-place update, never an uninstall.
- Visit: pull the installed release APK → install the lab build → copy owner data at once → empty test
  folders → fingerprinted runs → after-check (app data and model inventory unchanged) → reinstall the
  pulled release. Any failure: restore if needed, release back in the same session, debug afterwards.
- A new release is installed only by a named promotion followed by a Smoke check.
- The phone is reached only from the main checkout; the wrapper is installed only from the main checkout and
  never without the marker guard.

## Consequences

- The daily binary is never the tested binary: the lab build differs by layout, marker, debuggability and
  diagnostics. Smoke checks on release cover that gap only partly.
- No backup between Visits. The Visit copy is recovery for the Visit, not a backup.
- Same uid: a hand-built path can still reach owner files. The host check and the fingerprint answer it;
  the host check stops merges only once the gate runs in CI (#71).
- After release is installed nothing can read app data; the release update is trusted as the same update
  mechanism the switch proved on the lab build, checked by package facts and a Smoke check.
- The fingerprint cannot see the clipboard; protection there is by construction only.
- Owner takes on the lab build during a Visit are test data.
- Owner contact for work of this kind: one wrapper install and two Visits; images otherwise.

## Considered options

- Separate applicationId (Signal's pattern): kernel-enforced, but FUTO's microphone key reaches only the
  first enabled voice keyboard; the owner would toggle keyboards each Visit, plus 3.4 GB storage and an
  ADR-024 package change. Rejected.
- Read-only view of owner recordings in the lab build: rejected by owner decision; copies placed by the
  wrapper instead.
- Run listener or custom runner swapping roots: misses `Application.onCreate` and a later second process;
  fails open. Rejected.
- Second Android user: strongest, but the phone is unusable during runs and needs owner setup. Rejected.
- Orchestrator with `clearPackageData`: wipes owner data. Rejected.
- Debuggable build as the daily build (#63's earlier plan): keeps `run-as` daily, so any test APK reaches
  owner data. Rejected.

Revisit when an emulator runs the remaining phone checks, or a second device exists.

## As built (#74, History)

- Marker: manifest entry `ch.og.dictation.build` in app and test APK, value
  `layout=<owner|test>;commit=<40 hex>;hash=<64 hex>`. The hash covers the commit and every build input as
  it is on disk (`tools/build_marker.py`), so a dirty tree differs from the clean build of its commit.
- Layouts are source sets: `app/src/release` holds the owner layout, `app/src/benchmark` the test layout;
  the `debug` build type is switched off, so no third build exists.
- Test folders, relative to the app's data folder and fixed: `files/test`, `no_backup/test`,
  `shared_prefs/test_history_notices.xml`, `shared_prefs/test_settings.xml`; lab folder `files/lab`.
  Published for the wrapper in `app/src/benchmark/test-folders.json`.
- Owner layout: History in `no_backup/history`; `files/timing-receipts` is reserved (#63).
- Host check: `tools/storage_guard.py`; its exception list names the stores not yet behind Storage.

## Visible change (#77)

A differing frame or image stops the work: the owner decides from the BEFORE/AFTER pairs; text alone is
not accepted.

- Frames: scripted take scenarios run on the host through the service and record every frame a panel
  shows; one golden file per scenario in `app/src/test/golden/panel-frames`
  (`PanelFrameSequenceTest`). A golden file is rewritten only after the owner decided.
- Images: the panel gallery renders every panel state, and every frame of a differing sequence, in
  portrait, narrow and wide landscape, light, dark and at font 2×, to `app/build/panel-gallery`
  (Robolectric native graphics, the same bytes on every run). `tools/visible_change.py before` stores
  them at a ticket's start; `compare` gives the pairs and the list and prints the rule. The PR names
  the folder by absolute path.
- No test disappears unnoticed: `tools/removed_tests.py` in the gate; a removed name needs an entry in
  `tools/removed_tests.json`.

## As built (#79, History notices, settings, clipboard)

- Storage has one accessor each for the History notices, the settings and the clipboard. Preferences
  names: owner `history_notices` and `settings` (unchanged), test `test_history_notices` and
  `test_settings`. The owner layout's clipboard is the system clipboard; the test layout's keeps the
  last copied text in the process, so nothing copied in the lab build can be pasted.
- A temporary root (host tests) keeps preferences as files in `shared_prefs` below it and a file
  `clipboard` as its stand-in for the system clipboard.
- The History and Settings screen suites run on the host, on the test layout; their captures are
  gallery images in `app/build/panel-gallery/after/screens` (portrait, light, font 1× and 2×; fixed
  dates, so no Today or Yesterday header), compared like the panel images.

## As built (#80, recordings, receipts and traces)

- Storage has one accessor each for the recordings and the lab folder. Recordings: owner
  `files/benchmark` (unchanged), test `files/test/recordings`. The lab folder is `files/lab` in both
  builds: release reads the speech model there and writes no receipt or trace; the lab build writes its
  receipts and traces (startup, geometry, live results, recovery timing, rotation trace, editor
  geometry) and test output there. Scratch files of device tests: `files/test/scratch`.
- Whether a build saves recordings is part of its layout, not a setting: release never does, whatever
  its settings hold from an earlier build. Release Settings has no Benchmark switch and no
  explanation of it; the count of old recordings and "Delete benchmark data" stay and act on
  `files/benchmark`. Release lists that folder to count and deletes it; it never opens a recording.
- The lab build keeps the switch, the banner and the saving, into its test recordings folder.
- Host check: the exception list is empty. Every storage, clipboard and shell call in app, lab-build
  and device-test sources goes through Storage.
- The startup-trace device test wrote through an overridden files folder, a seam Storage closes; it
  runs on the host on a temporary root.

## Reserved rule: timing receipts in release (#63, not built)

The one kind of receipt release may write. Numbers only: no audio, no text, no editor identity. Off by
default, behind one switch. Owner data. Folder `files/timing-receipts`, reserved in the owner layout
so the wrapper can pull it read-only without a later change. Until #63 builds it, release writes no
receipt of any kind.

## Amendment (2026-11-25, #98): the lab build is its own app

Supersedes "One applicationId, signer and versionCode" and the rejection of a separate applicationId.
The lab build is `ch.og.dictation.lab` (Gradle `applicationIdSuffix`; test package
`ch.og.dictation.lab.test`), installed beside release and kept there; Android gives it its own storage,
so nothing done to it reaches owner data. Release keeps `ch.og.dictation`, its signer and versionCode,
and updates in place. The code namespace stays `ch.og.dictation`. The lab keyboard is "Local dictation
lab" with its one voice subtype; the owner enables it and grants its microphone once, and switches
FUTO's voice input to it around keyboard-handoff tests (the FUTO cost named under Considered options).
The lab app gets its own model copy, pushed from the kept model copy.

Wrapper: every data verb acts on the lab app; a Visit starts when the lab app is installed and ends
when it is removed; a release install no longer ends a Visit. The one-time switch is release
installed with `--promote` over the unmarked build, given a current switch copy; afterwards no verb
reads or writes release data: `restore --switch`, the switch's undo, works only before it, while
the copy's unmarked build is release. Guards that only the shared package needed are walked back by #99 (wrapper) and #100 (app).

## Amendment (2026-11-26, #100): app-side guards walked back

The lab app has its own storage (#98), so the app no longer splits storage by build type. Removed: the
two layouts (`OwnerLayout`, `TestLayout`), the test folders as the lab build's data paths and their
published list `app/src/benchmark/test-folders.json`, the History-root check, and the storage-path rules of the host check
`tools/storage_guard.py`. That check keeps two rules no package guards: the clipboard is written only in
Storage, and no app, lab-build or device-test code runs a shell (a lab shell could `pm clear` release). Storage stays as the one accessor per data kind, with release's paths in both
builds; release paths and behaviour are unchanged. What still differs by build type is in
`app/src/{release,benchmark}/kotlin/ch/og/dictation/storage/BuildType.kt`: whether recordings are saved
(release is the product only) and Copy's target (the lab build keeps an in-app clipboard: the system
clipboard is one for all apps). Device tests keep sibling History roots and scratch files under
`no_backup/test` and `files/test` of the lab app. The build marker stays: the wrapper tells the builds
apart by it (`layout=owner|test`). `files/timing-receipts` stays reserved in this ADR only.

## Status (#91, 2026-11-27): what still needs the phone

Of the 58 phone tests of #73's start, 11 test cases in 7 classes still need the phone; each class names
its reason in `app/src/androidTest/device-classes.json` (`phoneBatch`), and `tools/device_classes.py` fails
an entry without one. They need what the laptop does not have:

- The real phone's state: phone readiness (`PhoneReadyTest`, 1).
- The real speech model on the Pixel's CPU and native runtime: Candidate A baseline parity
  (`NormalBaselineTest`, 3).
- The real keyboard's insets: the Dictionary add sheet above it in landscape at large text
  (`DictionarySettingsTest`, 1).
- Real Android honouring our panel request around a recreated editor, through FUTO's key: one
  rotation-continuity case and one rotation-finishing case (`RotationContinuityDemoTest`,
  `RotationFinishingDemoTest`, 1 each).
- A real fresh process: cold Settings next to the real model (`ColdSettingsTest`, 1) and History
  across the death of the checkpoint producer's process (`HistoryProcessTest`, 3).

Everything else is decided or rendered on the laptop: take rules in `TakeControllerTest`, the panel in
`RenderPanelTest`, the gallery and the golden frame sequences, the History and Settings screens in
Robolectric. Not tests: the Guided check (the owner, on the lab build) and the Lab runs (measurements,
through `phone measure`). Whether an emulator can run some of the 11 is #93's spike.
