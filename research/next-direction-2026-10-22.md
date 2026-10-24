# Next direction: phrase boosting on the shipped engine, with a laptop-first loop

Written 2026-10-22. Revised 2026-10-23 (revision 3).
Source: two arena rounds of Opus 5.5 proposals, two adversarial Fable reviews, one audit of all agent sessions
from October 9 to 22. Nothing ran on the phone. No inference ran. No recording was opened.
All new numbers are re-scores of text that is already on disk, with `tools/score.py`.
This note is a proposal. A decision needs an ADR. A specification goes to a GitHub issue.

## 1. Context in five sentences

The app already wins on speed. One take on the app has a Stop wait near 1 s; FUTO waits 80 to 100 s.
The open goal is accuracy (R4). The only measured accuracy gain is from a supplied word list (ADR-007).
From October 13 to 22 the work tried to make the FP32 encoder and beam search fast. That was the wrong lever.

## 2. The facts that set the direction

**Fact 1. The gain comes from the word list, not from precision or beam search.**
Laptop errors, German/English, ADR-007: FP32 greedy 45/24; FP32 beam 8 42/26; FP32 beam 8 with the 43-entry list 35/11.
On the 10 development takes, beam search and FP32 did not reduce English errors.

**Fact 2. A FUTO error picture already exists.** `corpus/futo/*.txt` holds live FUTO text for six scripts
(October 10, different takes). Table: errors by type on the six-script subset. Default = app 0.2.2, INT8 greedy,
laptop, feed 640 (`corpus/lab/baseline.json`). The range in brackets is the two other feed sizes.

| | total | listed term | other name | number or format | other |
|---|---|---|---|---|---|
| German, default (468 words) | 39 (36–43) | 16 (16–18) | 0 | 8 (5–11) | 15 (13–15) |
| German, FUTO | 48 | 13 | 0 | 4 | 31 |
| English, default (506 words) | 17 (17–22) | 8 (8–9) | 0 | 2 (0–2) | 7 (7–12) |
| English, FUTO | 19 | 4 | 0 | 3 | 12 |

FUTO leads only on listed terms: by 3 in German and by 4 in English. A word list targets exactly that type.
Limits of this table:
- The takes are different. The take-to-take spread is unknown.
- "Other name" is 0 because every name in the scripts is in the list. The development takes cannot measure unlisted names.
- The app text is from before the chunker fix of October 12.
- If the scorer ignores compound spacing, the totals become FUTO 39/15 and default 37/15. The German lead comes mostly from the spacing rule.

**Fact 3. The scorer is stable.** Today's `score.py` gives 45/22, 45/24, 42/26 and 35/11 from the retained text, exactly.
On all 10 takes the default moves between German 43–50 and English 22–27 across feed sizes. Every gate must compare pairs.

**Fact 4. Two list bugs.** "Franken" after a price is lost in 4 of 5 cases in every variant; it was never listed.
"Aylin" alone is wrong 4 times, because only "Aylin Demir" is listed. These are list bugs, not model bugs.

## 3. The bet

Keep the shipped engine: Parakeet TDT 0.6B v3, selective INT8 encoder, sherpa-onnx 1.13.8, CPU, greedy search.
Add a phrase tree that biases the greedy choice between non-blank tokens. Use the scoring rule of NeMo's phrase
boosting tree (TurboBias paper, arXiv 2508.07014). Use one merged German and English list: the owner's words, given
names and surnames as separate entries, and about 30 built-in unit and currency words (Franken, Rappen, CHF, Uhr).

**The greedy rule.** At each frame the joiner gives one set of token scores and one set of duration scores.
Take the best token, blank included, and the best duration, both without a bonus. If the best token is blank, emit
nothing and skip that many frames; the tree state does not change. If it is not blank, add α times a tree bonus to each
non-blank token and emit the best one. The bonus is the arc weight when the token continues a phrase: the context
score for the first token, then context score times depth scaling plus ln(i+1). The bonus is a negative step-back when
the token breaks a partial match. The bonus is 0 for all other tokens. Then move the tree state. The bonus never
changes whether a token is emitted or how far to skip. With an empty list the output equals stock greedy byte for byte.
Sources: NeMo 2.5.0 `tdt_label_looping.py` lines 392–409; `context_graph_universal.py` line 241;
sherpa `context-graph.cc` lines 70–107.

**Why speed holds.** The bonus is read only on frames that already emit a token. The encoder, decoder and joiner run
the same number of times, also on one-chunk takes. One laptop check: the joiner call count stays within 2% of stock.
The phone measures the rest (section 6). The cost of one tree lookup is not measured yet.

**The ceiling.** In the TurboBias paper, greedy boosting recovers 48 to 57% of listed phrases and beam search 64 to 76%,
on three English RNN-T test sets. The model there is not a TDT model. The pass margin of 40% of the beam gain in
section 7 is a chosen margin, not a derived one.

**What a list cannot fix.** Unlisted names, German compounds and word forms, errors at chunk seams, punctuation and case.
No lever for these fits the Stop-wait budget in this window. The product lever is a list the owner can edit.
A spacing error is not boostable. Section 8, step 4, checks how many listed-term errors are boostable before any build.

## 4. The screening tool

Write a Python copy of sherpa's greedy TDT decoder (about 200 lines). It runs the shipped INT8 encoder, decoder and
joiner ONNX files through onnxruntime. It caches the encoder output once per chunk, so one grid cell runs in seconds.
It is also the executable specification for the C++ patch. Run it in a venv with onnxruntime 1.23.2, the version
inside the laptop sherpa wheel; the export venv has 1.17.1 and the phone has 1.31.0, and three runtimes make parity
soft. Copy NeMo's pure-Python tree builder (about 300 lines) into the tool; do not load NeMo at run time.
Parity rule: with α = 0 the tool must give the same text as the sherpa wheel on every chunk, byte for byte. No tolerance.

Compile the list on the laptop with sentencepiece. For each entry insert the canonical segmentation, the ß form and
the ss form, the hyphen form and the space form, and the given name and the surname as separate entries. Do not list
number strings; the old list had "4.80" and the model writes "4,80". Log each listed term that the model emits with a
segmentation that is not in the tree. Add more segmentations only when that log shows misses.
No listed name is one token in the 8,192-piece vocabulary.

## 5. The FUTO comparison

Do not build a laptop copy of FUTO with whisper.cpp. FUTO's fork restricts the language list, sets a prompt from Java
and clamps the audio context. Stock whisper.cpp cannot reproduce it, and no FUTO audio exists to check it against.
The only matched comparison is a replay: the owner plays each of the 10 development takes from the laptop speaker
into installed FUTO once and into the app twice, with benchmark storage on. The two app passes give the replay noise.
Owner cost is 70 to 90 minutes, plus copying FUTO text out by hand. Loudspeaker replay is not live speech.
**This creates new recordings. README and ADR-013 authorize none. The owner decides this before day 1.**
Without the replay, this plan can give a mechanism verdict only. It cannot give an R4 verdict.
The owner also writes FUTO's model and voice settings into `corpus/futo/settings.txt`. FUTO recommends
Multilingual-74 for German; R4 names Multilingual-244.

## 6. The loop

Laptop, run by the agent:
- `pipeline.py` gets one flag that exports chunk audio at feeds 640, 512 and 800.
- `score.py` is extended, not replaced, so that calibration stays exact. New columns per row: errors by type,
  punctuation and case differences on raw text, insertions and substitutions toward list words, tree misses, joiner
  calls, and the `meta.json` path for phone rows.
- One row per run goes to the gitignored file `corpus/lab/results.csv`.
- Hours per hypothesis: a new α or list on the cached encoder output takes minutes; a new rule takes an hour.

Phone, finalist only, one lab visit:
snapshot → `check` → `install` (owner approves) → `push` variant and list → timed replay of the 10 takes, arms
alternated per take, 3 rounds → `pull` single files → `restore` (owner approves).
The build stays on ORT 1.31; CPU parity passed in ADR-025. Re-pin to 1.28.2 only if the stock-1.31 pair fails gate K4.

Resolution:
- Accuracy, same chunks, list against no list: the smallest detectable change is 1 to 2 errors per language.
  A change counts only if it shows on all three feeds and at least 7 of 10 takes do not get worse.
- Accuracy against FUTO: the smallest detectable change is the replay noise.
- Stop wait: run default against default first (30 paired differences). The smallest detectable change (MDE) is about
  0.46 times the standard deviation of those differences. If the MDE is above 100 ms, add rounds up to six. If it is
  still above 100 ms, report "unresolved" and ship nothing. Re-measure the default in the same visit; do not use the
  0.65 s figure from October 10.

Stop: frozen plans, reservations and receipts for screening runs; one ADR per step; reviewer subagents per patch;
the 3.5 GB memory ceiling; all accelerator work; whisper.cpp; FLEURS; re-quantization; NeMo `transcribe()` screens;
the width-1 hotword probe (it produces a number people will quote).
Keep: matched audio; both languages reported apart; a no-list control; the three-feed noise floor; a snapshot before
every phone visit that changes state; phone numbers only from the phone; recordings only in `corpus/`.

## 7. Gates

| Gate | When | Stop if | Pass means |
|---|---|---|---|
| K0 | before day 1 | Fewer than 3 boostable listed-term errors per language in the default text (section 8, step 4). | The bet can show an effect. |
| K1 | day 2 | A fresh three-feed decode of the default is outside German 43–50 or English 22–27: diagnose the chunker first. The tool at α = 0 differs from the sherpa wheel on any chunk: fix once, else screen on the C++ patch. | The tool is the specification. |
| K2 | day 4 | No α in {0.5, 1, 2, 3, 4, 6} meets all of: listed errors down by 3 or more per language on all three feeds and by 40% or more of the beam gain; non-listed errors up by at most 1 per language per feed; 0 list-word insertions or substitutions on the 210 guard clips; punctuation and case up by at most 2; leave-scripts-out unlisted-name errors not up; default-correct words changed at most 1 per 1,000 words. | Take the highest α that passes on feeds 512 and 800. |
| K3 | day 7 | The C++ patch with an empty list differs from stock, or recovers under 80% of the tool's terms, or under 5 terms, or the joiner count is outside 2%. Fix once, else stop. | The port fires and matches the specification. |
| K4 | day 8 | Median paired Stop-wait difference above max(50 ms, MDE); max difference above 300 ms; cold load up by over 300 ms; memory up by over 50 MB; any false accept that changes meaning (R5). | Adopt or stop in ADR-026. |
| K5 | after ADR-026 | Only after the owner records held-out speech under ADR-005 or amends it. List on minus list off under 3 term errors per language; non-term up by 2; not below the FUTO replay by more than the replay noise. | Held-out evidence toward R4. |

The 2,000-entry distractor list from revision 2 is dropped; it had no source. Distractors are the homophone list
(Thun/tun, Wil/will, Buchs/Buch, Egg, Horn, Spiez) plus the guard-clip vocabulary.

## 8. Before day 1, the owner

1. Writes a draft ADR-026 that authorizes laptop inference for this bet. README and ADR-013 authorize none now.
2. Decides on the replay recordings (section 5). Without them, every "against FUTO" claim leaves this plan.
3. Decides the compound-spacing rule in `score.py`, or keeps spacing as a separate column. The German verdict depends on it.
4. Reads the K0 pre-screen: one hour, text only, from `corpus/lab/baseline.json`. Each listed-term error is a
   substitution with an emitted token (boostable), a deletion (not boostable) or a spacing or case error (not boostable).
5. Writes `corpus/futo/settings.txt`: model 244 or 74, language list, prompt and dictionary settings.
6. Writes a personal list of 30 or more real entries, not from the scripts, before any held-out work.
7. Confirms that the native sherpa and AAR build can run on the owner's machine on day 6 (the agent could not build on October 21).
8. States the ADR-005 position: record held-out speech in this window, or not.

## 9. Schedule (A = agent, O = owner)

- Days 1–2, A: extend `score.py`; write the Python decoder; fresh three-feed decode of the default; chunk export and
  encoder cache; compile the list; K1. A, phone without install: default against default timing, 3 rounds.
- Day 3, O: the replay, if authorized. A: score it; measure the replay noise.
- Day 4, A: α grid, 6 cells × 3 feeds; guard clips; leave-scripts-out; tree-miss log; K2. Stop here on a K2 fail and write ADR-026.
- Day 5, A: C++ patch for the greedy branch of `offline-recognizer-transducer-nemo-impl.h` and the NeMo decoder file,
  as a patch script for `tools/ort131-sherpa/build.py`. Kotlin: `Variant.kt` line 178 now requires beam search for
  hotwords; change the validation, the loader, the schema test and `tools/variant.py`.
- Day 6, O: run the native build and the AAR build (about 15 minutes). A: build the APKs; `phone check`.
- Day 7, A: Python wheel parity; K3.
- Day 8, O: approve snapshot, install and restore; phone connected about 90 minutes. A: default against default, then
  patch against stock, 3 rounds; K4; write ADR-026, adopt or stop, one page.
- After ADR-026 adopt: a list field in `SettingsActivity`; K5 if ADR-005 allows; an upstream patch to sherpa-onnx
  (upstream greedy search still has no hotwords); live gate #8 with the replay protocol; close #1 and #5 as superseded.

## 10. Rejected

WebGPU, LiteRT and TPU for FP32: measured slower, 2 GiB limit, failed numerics. FP32 CPU beam: Stop wait 2 to 2.5 s
and 3.1 GB. INT8 beam with the list as a product path: beam search multiplies decoder and joiner calls, and one-chunk
takes pay all of it. Re-quantization: it only rescues beam search. NeMo `transcribe()` screen: FP32 and hours per
grid. Laptop whisper.cpp copy of FUTO: cannot be validated. FLEURS: download and proxy. Cohere: English prefix
failures, 4.4 GB, 21.7 s first chunk. Canary 1B, Whisper large, Qwen, Moonshine: lost or slower. QNN: Qualcomm only.
Fine-tuning: no data. LLM post-correction: Stop wait and R5. Chunk-overlap decoding: on the shipped chunker 8 of 56
errors sit near cuts, below its own firing rule. A shipped 2,000-entry Swiss place list: no evidence, needs a download.

## 11. History audit (October 9–22, about 75 main-thread hours)

About 25% went to the phone wrapper, guard and sandbox, with 17 or more patches applied by hand. About 20% went to the
evaluation harness: 26k lines written, 16k deleted. About 17% went to GPU, TPU and WebGPU, with a slower result. About
15% went to docs and ADRs: 25 ADRs in 13 days. About 12% went to comparing models. Codex reviewer threads added about
31 hours; 71 of 148 subagents were reviewers. Re-invented tools: the scorer, the frozen-plan framework, the native
profiler, GPU feasibility through full app integration, and a LiteRT conversion started before the 2 GiB check.
The full audit and the arena files are in the session scratchpad, not in the repo.
