# Pixel Local Dictation

Local English and German dictation on one GrapheneOS Pixel, opened from FUTO Keyboard's microphone key.

## Dictation

**Take**:
One recording, from panel open to Stop or cancellation.
_Avoid_: session, dictation session, recording session

**Stop wait**:
The time from pressing Stop until the text is inserted.
_Avoid_: latency, finalization time

## Recognition models

**Encoder**:
The model stage that turns sound features into a description of the speech; the largest share of recognition work.

**Runtime session**:
One model file loaded and prepared by the runtime with fixed options, created once and reused by many takes.
_Avoid_: session (bare), model session, inference session

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
