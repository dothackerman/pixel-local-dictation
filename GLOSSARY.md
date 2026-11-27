# Pixel Local Dictation

Local English and German dictation on one GrapheneOS Pixel, opened from FUTO Keyboard's microphone key.

## Dictation

**Take**:
One recording, from panel open to Stop or cancellation.
_Avoid_: session, dictation session, recording session

**Stop wait**:
The time from pressing Stop until the text is inserted.
_Avoid_: latency, finalization time

**Rotation hold**:
The short time after an identified rotation in which the panel is gone but the take keeps recording (or, in the Stop wait, keeps finishing with its result held), nothing is inserted, and the panel is expected back.
_Avoid_: grace period, decision window

**Chunk**:
A part of a take, cut at a pause, decoded alone while recording continues.
_Avoid_: segment, window

**Feed size**:
How many audio samples the laptop replay hands to the chunker at once; three sizes give the noise floor.

**Visit**:
One connected phone lab period, from installing the lab app beside release until removing it; release is never touched.
_Avoid_: phone session, lab session

## Recognition models

**Encoder**:
The model stage that turns sound features into a description of the speech; the largest share of recognition work.

**Candidate A**:
Parakeet v3 FP32 on CPU, beam 8, dictionary score 1.5; the owner-selected provisional baseline since 2026-10-27.

**Historical control**:
The selective INT8 Parakeet pipeline with greedy decoding that the app shipped before Candidate A.
_Avoid_: old default, INT8 baseline

**Runtime session**:
One model file loaded and prepared by the runtime with fixed options, created once and reused by many takes.
_Avoid_: session (bare), model session, inference session

## Encoder placement (closed WebGPU campaign, ADR-020 to ADR-025)

**Execution provider**:
The runtime component that owns one kind of chip and executes the steps assigned to it, such as the CPU or WebGPU provider.
_Avoid_: backend, EP (in prose), worker

**Placement**:
The assignment of every model step to one execution provider, fixed when a runtime session is created.
_Avoid_: partitioning, fallback

**Shape work**:
Model steps on whole numbers or yes/no values that measure sizes, lengths and padding masks.
_Avoid_: counting work, bookkeeping, residual CPU nodes

**Tensor math**:
Model steps on decimal (floating-point) values: the convolutions, matrix products and normalizations that make up the encoder's real work.
_Avoid_: heavy math, compute nodes

**Placement check**:
Our own acceptance test of a placement: tensor math only on the intended provider; anything elsewhere is shape work.
_Avoid_: strict mode, CPU fallback check

## Dictionary

**Dictionary**:
The user's list of names and terms that biases decoding toward them.
_Avoid_: word list, lexicon, hotwords (the sherpa-onnx option name, in code only)

**Dictionary entry**:
A name or term the user wants recognised, possibly containing several words.

**Listed term**:
A word that belongs to a dictionary entry.

**Boost**:
The score bonus a token gets while it continues a dictionary entry.
_Avoid_: bias score, hotword score

## Phone work

**Owner data**:
The owner's Dictionary, History (transcripts and their checkpoints), History notices, app settings, recordings and receipts collected in daily use, and clipboard; never the speech model or test output.
_Avoid_: user data, personal data, app data, real data

**Lab build**:
The debuggable build, its own app `ch.og.dictation.lab` beside release, so Android keeps its data apart from owner data; it has the Benchmark switch, and its build marker says `layout=test` (Gradle calls it `benchmark`).
_Avoid_: benchmark build, test build, debug build

**Guided check**:
A scripted check on the lab build with test data, one step per screen, that the owner performs and marks Passed, Failed or Not observed.
_Avoid_: owner visit, manual test, acceptance run

**Smoke check**:
A short check by the owner on the release build with their own data, after a new release is installed.
_Avoid_: sanity check, manual check, owner test

**Lab run**:
A measurement on the phone that the owner asked for (accuracy, Stop wait, startup, geometry or rotation traces); it reports numbers, never passes or fails, and runs in the lab app on data pushed from the laptop.
_Avoid_: lab test, benchmark test, experiment run, phone test

**Kept model copy**:
The speech-model files on the laptop, laid out as in the phone's lab folder; its paths say which lab-folder files are the speech model, and a changed model is pushed back from it.
_Avoid_: model backup, model snapshot

**Switch copy**:
The copy of all app data except the speech model, with both APKs, taken once on the unmarked build before release replaces it; only the owner deletes it.
_Avoid_: switch snapshot, full backup

## Code

**Wiring**:
What one app process is built from: the recorder factory, the storage and the engine. One immutable value per process; a take keeps the value it started with, and tests replace it only for their own scope.
_Avoid_: environment, container, hooks

**Editor port**:
The only way take code reaches the editor: write text, read the text before the cursor, read the extracted text's length, and compare connections (the one a take started on, the one written to, the live one). It decides nothing.
_Avoid_: input connection wrapper, editor adapter, commit helper

**Take diagnostics**:
Everything take code traces. Each event gets the take's facts as arguments (take active, live session, view and take numbers, rotation); diagnostics never read take state back.
_Avoid_: trace helper, rotation logger

**Delivery hold**:
A reason, kept in the take session, why a finished result is not dispatched yet: a rotation hold, or the open finishing choice. The result is dispatched when no reason is left.
_Avoid_: hold flag, delivery lock, paused delivery

**Take controller**:
Holds all take state and makes every take decision, on the IME main thread, without Android. The keyboard service is its adapter and holds no take state: it forwards Android's callbacks to it in their order, counts rotations, builds the panel and carries out the effects it asks for.
_Avoid_: take manager, take state machine, service logic, take control

**Take host**:
What the take controller asks of the keyboard service: show the panel frame, return to the previous keyboard, request the panel, copy, ask for the microphone permission, record a History notice.
_Avoid_: callbacks, service interface, view

**Take scheduler**:
The take controller's one thread: post, cancellable delayed post, and the one FIFO executor that the take session and History deliver on. It has no clock.
_Avoid_: handler, main executor, timer

**Take opener**:
Opens a take's session from the engine with History attached, from the Wiring read at the take's start.
_Avoid_: take factory, session builder
