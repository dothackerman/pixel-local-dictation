# Next direction: phrase boosting on the shipped engine, with a laptop-first loop

Written 2026-10-22. Revised 2026-10-25 (revision 4).
Source: two arena rounds of Opus 5.5 proposals, two adversarial Fable reviews, one audit of all agent sessions
from October 9 to 22, and the owner's review of the explainer on October 25. Nothing ran on the phone.
No inference ran. No recording was opened. All new numbers are re-scores of text already on disk.
This note is a proposal. A decision needs an ADR. A specification goes to a GitHub issue.

## 1. What changed on October 25 (owner decisions)

1. **The baseline is the current default, not FUTO.** Speed against FUTO is won (about 1 s against 80 to 100 s).
   The goal is now: better accuracy than the shipped default, at the same or an acceptable Stop wait.
   All FUTO comparison work stops. No replay recordings are needed.
2. **The Stop wait may grow for a material accuracy gain.** A median Stop wait of 2 to 3 s is acceptable if the
   accuracy gain is clear, because the wait can be made pleasant in the UI. "Must not grow" is retired.
3. **The panel must appear at once.** Model loading or any other startup work must never delay the panel or the
   start of recording. This is a new requirement (proposed R8). It needs a measurement first; see section 7.
4. **Formatting differences do not count now.** "4.80" against "4,80" and "Tensor-Chip" against "Tensor Chip" are
   not accuracy. The scorer must treat them as equal. Word accuracy is the target.

## 2. The facts that set the direction

**Fact 1. The gain comes from the word list.** Laptop errors, German/English, ADR-007: FP32 greedy 45/24;
FP32 beam 8 42/26; FP32 beam 8 with the 43-entry list **35/11**. Beam search and FP32 alone did not reduce English errors.

**Fact 2. Our own errors by type** (six-script subset, default INT8 greedy, laptop feed 640, `corpus/lab/baseline.json`;
range across the two other feeds in brackets):

| | total | listed term | number or format | other |
|---|---|---|---|---|
| German (468 words) | 39 (36–43) | 16 (16–18) | 8 (5–11) | 15 (13–15) |
| English (506 words) | 17 (17–22) | 8 (8–9) | 2 (0–2) | 7 (7–12) |

Listed terms are 41% of German errors and 47% of English errors. A word list targets that share.
Every name in the scripts is in the list, so the development takes cannot measure unlisted names.
The text is from before the chunker fix of October 12; a fresh decode comes first.

**Fact 3. The scorer is stable.** Today's `score.py` gives 45/22, 45/24, 42/26 and 35/11 from retained text, exactly.
On all 10 takes the default moves between German 43–50 and English 22–27 across feed sizes. Every gate compares pairs.

**Fact 4. Two list bugs.** "Franken" after a price is lost in 4 of 5 cases; it was never listed.
"Aylin" alone is wrong 4 times, because only "Aylin Demir" is listed.

**Fact 5. The decoder has memory inside a chunk only.** The decoder is a two-layer LSTM (640 units) with a
state input and output. sherpa-onnx creates one stream per chunk, so the state restarts at each chunk, and the
encoder sees one chunk at a time. That memory holds the last tokens, not vocabulary. Carrying it across chunks
would help a word cut by a pause, not a name repeated in a later sentence. The runtime session stays loaded
and warm between chunks and between takes.

## 3. Two candidates, in order

**Candidate A: ship the measured best, now.** FP32 encoder, beam 8, the word list. Already measured: 35/11 on the
10 takes, Stop wait 2.0 to 2.5 s on two long takes (ADR-025), memory 3.1 GB. Zero new code: the variant
`dict-beam8-fp32-s20` exists. With rule 2 above this is a product candidate. First step: one phone visit that
measures its Stop wait on all 10 takes against the default, paired, and its cold load. If the median is at or
below 2.5 s, adopt it in ADR-026 with a waiting-state UI, and fix the two list bugs.

**Candidate B: the same accuracy at today's speed.** Phrase boosting inside greedy search on the shipped INT8
engine, with the scoring rule of NeMo's phrase-boosting tree (TurboBias, arXiv 2508.07014). Greedy keeps one
decoder and one joiner call per step, so the Stop wait stays near today's. The paper reports greedy recovers 48 to
57% of listed phrases against 64 to 76% for beam search, on three English RNN-T sets (not TDT). B is the long-term
path; A buys time.

**The greedy rule.** At each frame the joiner gives token scores and duration scores. Take the best token, blank
included, and the best duration, both without a bonus. If the best token is blank, emit nothing and skip; the tree
state does not change. Otherwise add α times a tree bonus to each non-blank token and emit the best one. The bonus
is the arc weight when the token continues a phrase (context score for the first token, then context score times
depth scaling plus ln(i+1)); a negative step-back when the token breaks a partial match; 0 otherwise. Then move the
tree state. The bonus never changes whether a token is emitted or how far to skip. With an empty list the output
equals stock greedy byte for byte. Sources: NeMo 2.5.0 `tdt_label_looping.py` lines 392–409;
`context_graph_universal.py` line 241; sherpa `context-graph.cc` lines 70–107.

**About the INT8 beam-search failure.** On one public 65-word clip, INT8 encoder with beam search gave 62 errors
against 5 with FP32 (ADR-007). The cause was never found; the workaround was the FP32 encoder. The shipped
encoder uses the simplest quantization (one scale per weight matrix). A one-flag alternative (one scale per
channel) was never tried. With rule 2, candidate A does not need this fixed. It stays a one-day option if memory
or Stop wait of A turn out too high.

**Optional cheap row: decoder memory across chunks.** In the Python decoder (section 4) the LSTM state can be
carried from chunk to chunk in one line. One CSV row tells whether it helps or hurts. The encoder cannot be
carried this way.

## 4. The screening tool (for candidate B)

A Python copy of sherpa's greedy TDT decoder (about 200 lines). It runs the shipped INT8 encoder, decoder and
joiner ONNX files through onnxruntime 1.23.2, the version inside the laptop sherpa wheel. It caches the encoder
output once per chunk, so one grid cell runs in seconds. It is the executable specification for the C++ patch.
Copy NeMo's pure-Python tree builder (about 300 lines) into the tool; do not load NeMo at run time.
Parity rule: with α = 0 the tool gives the same text as the sherpa wheel on every chunk, byte for byte.

Compile the list on the laptop with sentencepiece. For each entry insert the canonical segmentation, the ß form
and the ss form, and the given name and the surname as separate entries. Log each listed term that the model emits
with a segmentation not in the tree; add segmentations only when the log shows misses. Do not spend time on hyphen,
space or number forms (rule 4).

## 5. The loop

Laptop, agent-run: `pipeline.py` gets a flag that exports chunk audio at feeds 640, 512 and 800. `score.py` is
extended, not replaced: number and spacing forms count as equal; new columns per row for errors by type,
insertions and substitutions toward list words, tree misses, joiner calls and the `meta.json` path for phone rows.
One row per run goes to the gitignored `corpus/lab/results.csv`.

Phone, one visit per candidate: snapshot → `check` → `install` (owner approves) → `push` variant and list → timed
replay of the 10 takes, arms alternated per take, 3 rounds → `pull` single files → `restore` (owner approves).
The build stays on ORT 1.31 (CPU parity passed, ADR-025).

Resolution. Accuracy, same chunks, list against no list: 1 to 2 errors per language; a change counts only if it
shows on all three feeds and 7 of 10 takes do not get worse. Stop wait: default against default first, 30 paired
differences; the smallest detectable change is about 0.46 times their standard deviation. Report cold load and
peak memory in the same visit.

Stop: frozen plans and receipts for screening runs; one ADR per step; reviewer subagents per patch; the 3.5 GB
ceiling; accelerator work; whisper.cpp; FLEURS; all FUTO comparison work.
Keep: matched audio; both languages reported apart; a no-list control; the three-feed noise floor; a snapshot
before every phone visit that changes state; speed numbers only from the phone; recordings only in `corpus/`.

## 6. Gates

| Gate | Candidate | Stop if | Pass means |
|---|---|---|---|
| G1 | A, phone | Median paired Stop wait above 2.5 s, or max above 4 s, or cold load above default + 1 s, or memory above 3.5 GB, or any list false accept that changes meaning (R5). | Adopt A in ADR-026 with a waiting-state UI. |
| G2 | B, laptop | Fresh three-feed decode of the default outside German 43–50 or English 22–27: diagnose the chunker first. Tool at α = 0 differs from the wheel on any chunk: fix once, else screen on the C++ patch. | The tool is the specification. |
| G3 | B, laptop | No α in {0.5, 1, 2, 3, 4, 6} gives: listed errors down 3 or more per language on all three feeds and 40% or more of the beam gain; non-listed errors up at most 1 per language per feed; 0 list-word insertions or substitutions on the 210 guard clips; default-correct words changed at most 1 per 1,000 words. | Take the highest α that passes on feeds 512 and 800. |
| G4 | B, port | The C++ patch with an empty list differs from stock, or recovers under 80% of the tool's terms, or under 5 terms, or the joiner count is outside 2%. Fix once, else stop. | The port matches the specification. |
| G5 | B, phone | Median paired Stop wait above the default's by more than max(50 ms, detectable change); max above +300 ms; cold load above +300 ms; memory above +50 MB. | Adopt B; retire A's extra wait. |

The 40% margin is a chosen margin. Thresholds on accuracy are errors per language on 584/632 words.

## 7. The instant panel (proposed R8)

Design today: `Engine.warmUp` loads the model on the decode executor when the IME service is created, and
recording starts when the panel opens without waiting for the model (`DictationImeService`, `DictationRuntime`).
The owner still sees the panel appear slowly. Possible causes, unmeasured: the IME process starts cold when FUTO
hands over; the model load runs on all cores at the same time as the panel inflates; the hand-over itself.
First step: time-stamp microphone-key press, service create, panel shown, recording started and model ready, and
log them for ten real takes. Then fix in this order: show the panel before any other work; load with lower thread
priority; keep the process alive. Decoding of the first chunk may queue until the model is ready; the user records
meanwhile. The same waiting-state UI serves candidate A's longer Stop wait.

## 8. Before day 1, the owner

1. Writes a draft ADR-026 that authorizes laptop inference and one phone visit for candidate A.
2. Writes a personal list of 30 or more real names and terms, not from the scripts.
3. Confirms the native build can run on the owner's machine (needed only for candidate B, day 6).
4. Decides whether candidate A ships before B exists, if G1 passes.

## 9. Schedule (A = agent, O = owner)

- Day 1, A: fix the two list bugs; score `dict-beam8-fp32-s20` against the default with the equal-formatting
  scorer; add the startup time stamps. O: approve snapshot, install and restore; phone connected about 60 min.
  A: phone visit for candidate A, default against A, 3 rounds; G1; ten real takes for the startup log.
- Day 2, A: ADR-026 draft (adopt A or not); waiting-state UI sketch; extend `score.py`; write the Python decoder;
  fresh three-feed decode; chunk export and encoder cache; G2.
- Days 3–4, A: α grid, guard clips, leave-scripts-out, tree-miss log, the decoder-memory row; G3.
- Day 5, A: C++ patch and the Kotlin change (`Variant.kt` line 178 requires beam search for hotwords today:
  validation, loader, schema test, `tools/variant.py`).
- Day 6, O: native build and AAR build. A: APKs; `phone check`.
- Day 7, A: wheel parity; G4. Day 8, O: approve the visit. A: default against patch, 3 rounds; G5; ADR-027.
- After adoption: list field in `SettingsActivity`; upstream patch to sherpa-onnx (upstream greedy search has no
  hotwords); close #1 and #5 as superseded.

## 10. Rejected

WebGPU, LiteRT and TPU for FP32: measured slower, 2 GiB limit, failed numerics. NeMo `transcribe()` screen: FP32
and hours per grid. Laptop whisper.cpp copy of FUTO and any FUTO comparison: out of scope since October 25.
FLEURS: download and proxy. Cohere: English prefix failures, 4.4 GB, 21.7 s first chunk. Canary 1B, Whisper
large, Qwen, Moonshine: lost or slower. QNN: Qualcomm only. Fine-tuning: no data. LLM post-correction: Stop wait
and R5. Chunk-overlap decoding: 8 of 56 errors near cuts, below its own firing rule. Hyphen and number list
forms: not accuracy (rule 4).

## 11. History audit (October 9–22, about 75 main-thread hours)

About 25% went to the phone wrapper, guard and sandbox, with 17 or more patches applied by hand. About 20% went to
the evaluation harness: 26k lines written, 16k deleted. About 17% went to GPU, TPU and WebGPU, with a slower
result. About 15% went to docs and ADRs: 25 ADRs in 13 days. About 12% went to comparing models. Reviewer threads
added about 31 hours. Re-invented tools: the scorer, the frozen-plan framework, the native profiler, GPU
feasibility through full app integration, a LiteRT conversion started before the 2 GiB check.
