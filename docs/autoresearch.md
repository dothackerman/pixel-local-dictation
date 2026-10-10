# Experiment loop: instructions for the loop session

Status: written 2026-10-10 by the setup session, agreed with Oriol. This is the whole brief for a
fresh session that runs accuracy experiments unattended, modelled on karpathy/autoresearch: change
one thing, measure, keep or discard, log, repeat. Read it completely before the first command.
Project rules in [AGENTS.md](../AGENTS.md) still apply; where this file is stricter, this file wins.

## What you are optimising

`ch.og.dictation` dictates German and English on Oriol's phone. Speed is solved (about 1 s after
Stop). Accuracy is level with the previous recognizer, not better ([results](results.md)). Your job:
find a pipeline that makes fewer word errors on Oriol's ten saved takes without breaking the speed,
memory and heat budget on the phone, and without fooling the evaluator.

The evaluator is frozen. The ten takes are the only personal audio. Four more scripts exist as a
hold-out; they are outside the repository and you never read them. The final word on any winner is
a recording session by Oriol that only happens when the trigger below fires.

## Fixed parts (never edit; `tools/lab.py check` refuses to run if they changed)

| Part | File | What it does |
|---|---|---|
| Scorer | `tools/score.py` | normalisation and word-error count |
| Evaluator | `tools/evaluate.py` | per-take scoring, keep rule, phone budget |
| Pipeline | `tools/pipeline.py` | laptop twin of the app's decode path, driven by a variant |
| Schema | `tools/variant.py` | the closed set of keys a variant may have |
| Runner | `tools/lab.py` | check, run, phone-prepare, phone-score, ledger |
| Take index | `corpus/takes.json` | which take is which script |
| Guard | `models/guard/refs.tsv` | 210 public clips used as a breakage detector |

Also fixed: everything under `app/`, `corpus/`, `models/`, `docs/`, the wrapper and the gate.
If you find a quirk in the normaliser (there are several, for example "I'll" counts as two tokens),
write it in the ledger note and move on. Do not fix it.

## The editable surface

One directory: `variants/`. A variant is a JSON file with the keys documented in
`tools/variant.py` (schema 1): family and model, thread count, transducer decoding (method, beam
width, blank penalty), Whisper language, Canary language identification, cutting (minimum chunk,
pad, silence, speech, VAD maximum, hard maximum) and two post-processing switches. Unknown keys are
rejected on both the laptop and the phone. There is no lexical post-processing on purpose; a
variant that contains a reference term is refused.

Start from `variants/baseline.json` (the shipped app), `variants/whisper-turbo.json` and
`variants/canary-180m.json` (prepared families). Each experiment is a new file
`variants/<name>.json` whose `name` equals the file name. Keep the three starting files unchanged.

Models available under `models/` (nothing else, download nothing):

| Directory | Family | Notes |
|---|---|---|
| `parakeet-v3-matmul8` | parakeet | shipped model (ADR-003); the baseline |
| `parakeet-v3-fp32` | parakeet | full precision, same accuracy on these takes, larger |
| `sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8` | parakeet | published INT8, known to be worse |
| `var-A-enc8`, `var-B-dec8` | parakeet | quantisation variants from the spike |
| `sherpa-onnx-whisper-turbo` | whisper | large-v3-turbo INT8; detects the language itself; 30 s limit |
| `sherpa-onnx-nemo-canary-180m-flash-en-es-de-fr-int8` | canary | needs `lid`; the only Canary this runtime runs |
| `sherpa-onnx-whisper-tiny` | lid | language identification for Canary |

## Before the first night (Oriol, by hand; the agent may not do these)

1. Hold-out out of reach. From the repository root:
   `mkdir -p ../pixel-local-dictation-holdout/scripts && mv corpus/scripts/de-4.txt corpus/scripts/de-5.txt corpus/scripts/en-4.txt corpus/scripts/en-5.txt ../pixel-local-dictation-holdout/scripts/`
   and delete the de-4, de-5, en-4, en-5 sections from `corpus/reading-sheet.md` (keep a copy of them
   in the hold-out directory for recording day). `tools/lab.py check` confirms.
2. Wrapper: `cp tools/phone.proposed tools/phone && chmod +x tools/phone && rm tools/phone.proposed`
   (way back: `git checkout tools/phone`). Read the header of the proposed file first.
3. Phone: build and install the benchmark build ([setup](setup.md), "Loop nights"); USB debugging
   on, plug in, allow the laptop. Then one test: lock the screen, wait an hour, and run the wrapper's
   `status`. If the device is gone, the loop can only run while the screen is unlocked; say so.
4. Tell the loop session to start.

## The loop

Setup, once, with Oriol present:

1. `git status` must be clean on `main`. Create the branch `autoresearch/<date>` and work on it.
2. `tools/lab.py check` must print `check ok`. If it names the hold-out scripts, Oriol has not
   finished the hold-out move; stop and tell him.
3. Experiment 0: `tools/lab.py run variants/baseline.json --guard`. It prints the per-take table
   and a summary block and writes `corpus/lab/runs/baseline-<commit>.json`. Then
   `tools/lab.py adopt-baseline corpus/lab/runs/baseline-<commit>.json`, which freezes it as
   `corpus/lab/baseline.json`. Every later stage-1 verdict compares against it.
4. Phone control row: `tools/lab.py phone-prepare variants/baseline.json`, then the three wrapper
   commands it prints, then `tools/lab.py phone-score`. This is the parity and timing reference.
   If the phone is not connected, continue laptop-only and say so in the hand-back.
5. Confirm with Oriol, then run unattended.

Each experiment:

1. Write `variants/<name>.json` with one idea. Commit it (`git add variants/<name>.json`, a message
   that names the idea; no numbers yet).
2. `tools/lab.py run variants/<name>.json` (add `--guard` only when stage 1 passes, then run it
   again with `--guard`; the guard takes minutes with large models). Read the summary block. A crash
   is a ledger row with status `crash`; fix a typo, give up on a broken idea after two attempts.
3. Stage 1 verdict is printed as `stage1: pass|fail` with reasons. Fail: `git reset --hard HEAD~1`
   (the variant file goes away; the ledger row and the report stay, they are not in git).
4. Pass: stage 2 on the phone. `tools/lab.py phone-prepare variants/<name>.json`, then exactly
   these, one per Bash call, nothing else on the line:
   `tools/phone push-variant corpus/lab/push/<name>` (minutes, 1-3 GB over USB),
   `tools/phone lab` (run in the background and poll; a run takes 5-20 minutes),
   `tools/phone pull-results` (prints the result path), then
   `tools/lab.py phone-score corpus/lab/phone/<file>.json variants/<name>.json`.
   `stage2: pass` keeps the commit (status `keep`); fail resets it as in step 3.
5. Append nothing by hand: the ledger `corpus/lab/results.tsv` is written by the runner.
   Amend the commit message of a keep with the one-line summary the runner printed.
6. Next idea. The baseline for comparison stays experiment 0 (not the last keep): kept variants
   are compared against the shipped app, so gains are absolute, and a new variant builds on the
   best kept one by copying its file.

Rank order when ideas compete: fewer total errors on the ten takes, then fewer German errors, then
fewer terms missed. `tools/lab.py ledger` shows the ranking; `--all` includes discards, which is
the accuracy-only ranking regardless of the phone budget.

## Keep rules (implemented in `tools/evaluate.py`; this is the explanation)

Stage 1, laptop, against `corpus/lab/baseline.json`:

- Error counts are averaged over three feed sizes per take, because the chunk boundary moves with
  the recorder's read size and that alone moves the ten-take count by several words.
- Total errors fall by at least 8 words, and more takes improve than get worse (paired, per take).
- Neither language gets worse by more than 1 word; terms missed grows by at most 1;
  punctuation/case issues grow by at most 2; no letters on 5 s of silence or faint noise.
- When the guard was run on both: no language's guard word error rate rises by more than 1 point.

Stage 2, phone, from the LabTest result:

- Median real-time factor per chunk under 0.3; wait after Stop on the paced longest and shortest
  take under 2 s; thermal status never above moderate. Peak memory above 2 GB is a flag in the
  note, not a fail.
- Parity: the phone's text is compared with the laptop report of the same variant. Differences of
  a few words are expected (boundary sensitivity, arm64 arithmetic). A larger divergence is flagged;
  then the phone text's numbers in the note are the ones that count, and you say so in the hand-back.

Budget exhaustion: stop after 40 experiments or when ideas within the schema are exhausted, whichever
comes first, and write the hand-back.

## Ideas worth trying first (tier A, cheap, same model)

Cutting: `min_chunk_s` 25 or 30; `pad_s` 0.2, 0.6, 1.0; `min_silence_s` 0.5, 1.2; `vad_max_speech_s`
15, 30; `max_chunk_s` as a safety cap. Decoding: `modified_beam_search` with `max_active_paths`
4, 8, 16 (expect a speed cost; the phone decides); `blank_penalty` 0.5, 1.0, 1.5 (fewer dropped words
at chunk starts?). Threads: speed only, skip stage 1, judge on the phone against the control row.
Post: `seam_capitalize` true. Combine the winners.

Tier B (prepared families, expected to rank on accuracy, not to pass the budget):

- `whisper-turbo`: laptop real-time factor about 0.5, so probably outside the phone budget;
  wrote "Thank you." on silence in the smoke test, which fails the silence rule. Try it anyway for
  the accuracy ranking; try `min_chunk_s` 15-20 with `max_chunk_s` 26.
- `canary-180m`: a small model; the interesting part is whether the language identification holds
  on every take (`lid` column) and what it does on names.

Things that are not in the schema and therefore not experiments: hotwords, overlapping chunks,
other runtimes, GPU or neural-core acceleration, new model downloads. Write such ideas, with the
evidence that made you want them, into the hand-back as requests for a setup round.

## Phone rules

- The phone is reached only through the wrapper, one call per Bash command, exactly as printed by
  `phone-prepare`. A hook blocks any other shell command that mentions the wrapper path, the Android
  debug bridge, fastboot or platform-tools, including in commit messages and heredocs. Read the
  wrapper with the Read tool if you need to see it. Never propose or apply changes to it or to
  the gate files; write wrapper requests into the hand-back.
- Never call `install` or `uninstall`. The benchmark build is installed by Oriol before the night
  and reinstalled with the release build by him afterwards.
- Serial phone runs only. If a `lab` run fails because the phone is not reachable, do not retry in
  a loop: note it, continue laptop-only, and tell Oriol in the hand-back.
- Heat: if `phone_thermal_max` reaches 3, wait 15 minutes and rerun that variant once. If it
  happens again, stop phone runs for the night.
- Nothing is copied out of `corpus/`, and nothing from `corpus/` goes anywhere but `corpus/lab/`.
  `pull-results` copies JSON only.

## Terminal states and the hand-back

Stop and write `corpus/lab/handback-<date>.md` when: the phone is unreachable twice in a row,
parity fails for the baseline control row (then laptop accuracy is not trusted; say so), the
recording trigger fires, the budget is exhausted, or anything needs Oriol. The hand-back lists:
the branch and last kept commit; the ledger path and a copy of `tools/lab.py ledger --all`; the
control rows (baseline on the phone at the start and the end of the night); disk used under
`corpus/lab/`; which build is on the phone; open requests for a setup round; and, if the trigger
fired, the exact variant to record with.

Recording trigger (pre-registered): a kept variant whose German error count is at least 12 words
(about 2 points) below experiment 0, English not worse than experiment 0 by more than 1 word,
guard not worse, stage 2 passed. Then Oriol records the four hold-out scripts once, in the app,
benchmark mode on. The hold-out rule, also pre-registered: on identical audio, the candidate must not
be worse than the baseline variant on any hold-out take by more than 2 words and must have fewer
German errors in total; English may be worse by at most 1 point. The verdict is Oriol's.

## What the loop does not do

It does not touch `docs/` (the setup and hand-back sessions do), does not merge to `main`, does not
commit anything under `corpus/`, does not change the app's default pipeline (porting a winner is a
later change with its own decision record), and does not ask whether to continue. It continues
until a terminal state.
