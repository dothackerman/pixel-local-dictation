# Experiment loop: instructions for the loop session

## Setup amendment, 2026-10-12

Oriol authorized fixing Qwen configuration and evaluator gaps and investigating beam collapse on
the existing feature branch, with laptop comparisons before any new phone work. Schema 3 adds
explicit Qwen context/generation budgets and a validated chunk cap; schema 1/2 variants still load,
but their unbounded Qwen settings migrate to the safe configuration. Historical Qwen scores do
not describe that migrated configuration. Use `qwen3-context1024.json` for the corrected candidate.
The source rationale and limitations are in [ADR-006](decisions.md#adr-006--validate-qwen-input-budgets-and-enforce-complete-phone-evidence).

Evaluator revision 3 uses the maximum memory high-water mark across unpaced and paced runs and
enforces the existing 3.5 GB ceiling. Phone scoring requires the matching laptop evidence and
reapplies the existing accuracy rules to actual phone text, including paced final text; laptop
guard/silence evidence remains explicitly laptop evidence. Dictionary comparisons require complete
guard/take coverage and the actual no-dictionary twin. Original ledger rows and reports remain
historical; re-evaluation must identify its evaluator revision.

The instructions below record revision 2 and its night-two experiment order. Their exclusion of
Qwen and full precision is superseded for this authorized setup investigation: Qwen had a context
overflow, and full-precision greedy parity did not validate beam/hotword decoding. See the
[beam diagnosis](beam-investigation-2026-10-12.md). Do not restart the night-two order or run phone
stage 2 from this amendment; laptop results are reviewed first.

The six completed setup replays and their limits are in
[measurement results](results.md#laptop-setup-validation-2026-10-12). FP32 beam with dictionary
is the strongest laptop candidate, but its one false guard insertion leaves the recording trigger
unmet; phone memory and latency for that combination remain unmeasured.

Status: revision 2, written 2026-10-11 by the setup session after the first night and its review,
agreed with Oriol. This is the whole brief for a fresh session that runs accuracy experiments
unattended, modelled on karpathy/autoresearch: change one thing, measure, keep or discard, log,
repeat. Read it completely before the first command. Project rules in [AGENTS.md](../AGENTS.md)
still apply; where this file is stricter, this file wins. The first night is summarised in
`corpus/lab/handback-2026-10-10.md`; its 41 ledger rows are still in the ledger.

## What you are optimising, and what the first night taught

`ch.og.dictation` dictates German and English on Oriol's phone. Speed is solved (about 1 s after
Stop). Accuracy is level with the previous recognizer, not better ([results](results.md)).

The first night ran 40 variants of cutting and decoding knobs. None passed. The reason is in the
error anatomy of the baseline: of its 67 word errors, 38 are names and terms (the same dozen names
in every variant), 10 are numbers and formats, 16 are ordinary words, 3 are insertions. Cutting and
decoding knobs can only reach the 16 ordinary-word errors, and the keep margin is 8 words.
Consequences, all built into this revision:

- **Range-find before you search.** For any knob family, run its extremes first. If the best of
  them does not beat experiment 0 by the keep margin, the family is not an experiment; log that in
  the hand-back and move on. `tools/lab.py range NAME...` prints the span and the best gain over
  ledger rows (total errors; for dictionary variants look at terms recovered instead). The cutting and decoding
  families of schema 1 are already known to be flat (span 68.7 to 86.0, best gain 3.7); do not
  re-run them.
- **Names are the lever.** Schema 2 adds a dictionary via hotwords ([ADR-005](decisions.md)) with
  its own metrics, and two new model families.
- **A no-speech gate** (`post.skip_silent_chunks`) stops models from writing on silence, which was
  the failure of beam search and Whisper.

The evaluator is frozen. The ten takes are the only personal audio. Four more scripts exist as a
hold-out; they are outside the repository and you never read them. The final word on any winner is
a recording session by Oriol that only happens when the trigger below fires.

## Fixed parts (never edit; `tools/lab.py check` refuses to run if they changed)

| Part | File | What it does |
|---|---|---|
| Scorer | `tools/score.py` | normalisation and word-error count |
| Evaluator | `tools/evaluate.py` | per-take scoring, keep rules, phone budget |
| Pipeline | `tools/pipeline.py` | laptop twin of the app's decode path, driven by a variant |
| Schema | `tools/variant.py` | the closed set of keys a variant may have (schema 2) |
| Runner | `tools/lab.py` | check, run, phone-prepare, phone-score, ledger, dictionary, range |
| Take index | `corpus/takes.json` | which take is which script |
| Guard | `models/guard/refs.tsv` | 210 public clips used as a breakage and false-insertion detector |

Also fixed: everything under `app/`, `corpus/`, `models/`, `docs/`, the wrapper and the gate.
If you find a quirk in the normaliser (there are several, for example "I'll" counts as two tokens),
write it in the ledger note and move on. Do not fix it.

## The editable surface

One directory: `variants/`. A variant is a JSON file with the keys documented in
`tools/variant.py` (schema 2): family and model, thread count, transducer decoding, Whisper and
Cohere language, language identification (`lid`), `hotwords` (the dictionary), cutting, and three
post-processing switches. Unknown keys are rejected on both the laptop and the phone. There is no
lexical post-processing on purpose; a variant that contains a reference term is refused.

Prepared starting files, all run once by the setup session (their rows are in the ledger):

| File | What it is |
|---|---|
| `baseline.json` | the shipped app; experiment 0 |
| `beam4-gate.json` | modified beam search, 4 paths, with the gate: beam search without its silence failure |
| `dict-beam4.json`, `dict-beam4-s3.json` | the dictionary mechanism test at hotword scores 1.5 and 3.0 |
| `qwen3.json` | Qwen3-ASR 0.6B INT8, language-model decoder, detects the language itself |
| `cohere.json` | Cohere Transcribe 14 languages INT8, language per take from Whisper tiny |
| `whisper-turbo.json`, `canary-180m.json` | known from night one: not candidates |

Each experiment is a new file `variants/<name>.json` whose `name` equals the file name. Keep the
starting files unchanged.

Models available under `models/` (nothing else, download nothing):

| Directory | Family | Notes |
|---|---|---|
| `parakeet-v3-matmul8` | parakeet | shipped model (ADR-003); the baseline; has `bpe.vocab` for hotwords |
| `parakeet-v3-fp32`, `var-B-dec8` | parakeet | same accuracy within noise; have `bpe.vocab` |
| `sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25` | qwen3 | 0.9 GB; laptop real-time factor about 0.2; takes `hotwords` as a prompt |
| `sherpa-onnx-cohere-transcribe-14-lang-int8-2026-04-01` | cohere | 2.9 GB; needs the language, so `lid`; laptop real-time factor about 0.25 |
| `sherpa-onnx-whisper-turbo` | whisper | night one: level with the baseline at best, half real time, not a candidate |
| `sherpa-onnx-nemo-canary-180m-flash-en-es-de-fr-int8` | canary | night one: worse; not a candidate |
| `sherpa-onnx-whisper-tiny` | lid | language identification for canary and cohere |

The dictionary file is `corpus/lab/dictionary.txt`: the 43 names of the ten scripts, written by
`tools/lab.py dictionary`. Do not edit it; the mechanism test is "names in the list are recognised".

## Keep rules (implemented in `tools/evaluate.py`; this is the explanation)

Stage 1, laptop, against `corpus/lab/baseline.json` (experiment 0):

- Error counts are averaged over three recorder feed sizes per take, because the chunk boundary
  alone moves the ten-take count by several words.
- **General rule** (variants without `hotwords`): total errors fall by at least 8 words, more takes
  improve than get worse, neither language worse by more than 1 word, terms missed grow by at most
  1, punctuation/case issues by at most 2, no letters on 5 s of silence or faint noise, and when
  the guard was run on both, no language's guard word error rate rises by more than 1 point.
- **Dictionary rule** (variants with `hotwords`, always run with `--guard` and
  `--reference NAME`, where NAME is the same pipeline without hotwords, run with `--guard` before):
  at least 5 more of the 130 terms come out right than in experiment 0; errors outside dictionary
  words grow by at most 3 against the reference; at most 1 dictionary word appears in the 210 public
  clips (where none is spoken); silence clean; guard not worse than the reference by more than 1
  point. The reference matters: modified beam search by itself raises the guard's German rate from
  4.8 % to 6.6 %, so a dictionary on beam search carries that regression, and the ledger note shows
  it. The claim a passing dictionary variant earns is "names in the user's list are recognised", not
  "the recognizer got better".

Stage 2, phone, from the LabTest result: median real-time factor per chunk under 0.3; wait after
Stop on the paced longest and shortest take under 2 s; thermal status never above moderate. Peak
memory above 2 GB is a flag; **a flag above 3.5 GB is not a keep** (an input method holding that
much is killed in daily use): mark the row `discard` in the note and write the request for a
single-instance variant into the hand-back. Phone text is compared with the laptop report of the
same variant; a few words of difference are expected, a larger divergence is flagged and then the
phone text's numbers count.

Budget: stop after 40 experiments or when the range-finding says nothing left can move, whichever
comes first, and write the hand-back.

## The loop

Setup, once (steps 2 to 4 are done when the ledger already has the rows; check with `tools/lab.py ledger --all`):

1. `git status` clean on `main` at the commit "Permission allow rule" or later (schema 2;
   `tools/variant.py` says `SCHEMA = 2`); create the branch `autoresearch/<date>` from there.
2. `tools/lab.py check` prints `check ok`.
3. Experiment 0 exists (`corpus/lab/baseline.json`). Do not re-run it.
4. Phone control row for the new build: `tools/lab.py phone-prepare variants/baseline.json`, the
   three wrapper commands it prints, `tools/lab.py phone-score`. If the phone is not reachable,
   continue laptop-only and say so in the hand-back.
5. Range-finding results of the setup session are in the ledger (the prepared files above). Read
   them before choosing the first idea.

Each experiment:

1. Write `variants/<name>.json` with one idea. Commit it (a message that names the idea; no numbers).
2. `tools/lab.py run variants/<name>.json` (dictionary variants: always `--guard --reference <twin>`;
   others: add `--guard` after a pass and run again). Read the summary block. A crash is a ledger row with
   status `crash`; fix a typo, give up on a broken idea after two attempts.
3. `stage1: fail`: `git reset --hard HEAD~1`. The ledger row and the report stay (not in git).
4. `stage1: pass`: stage 2 on the phone. `tools/lab.py phone-prepare variants/<name>.json`, then
   exactly these, one per Bash call, nothing else on the line:
   `tools/phone push-variant corpus/lab/push/<name>` (run with a 15-minute timeout: up to 3 GB over
   USB; it ends by printing one line per file with its size, and `lab` is run only after that list
   appeared; a push that printed no list is not a push),
   `tools/phone lab` (run in the background and poll; 5 to 20 minutes),
   `tools/phone pull-results`, then
   `tools/lab.py phone-score corpus/lab/phone/<file>.json variants/<name>.json`.
   `stage2: pass` keeps the commit (status `keep`); fail resets it as in step 3.
5. The ledger is written by the runner only. Amend a keep's commit message with the runner's summary line.
6. Next idea. Comparison stays against experiment 0, so gains are absolute; build on the best kept
   variant by copying its file.

Rank order when ideas compete: fewer total errors on the ten takes, then fewer German errors, then
fewer terms missed; dictionary variants rank by terms recovered. `tools/lab.py ledger` shows keeps;
`--all` is the accuracy-only ranking regardless of the phone budget.

## Range-finding results of the setup session (2026-10-11, laptop), and the order of work

| Variant | Total | de | en | Terms missed | Guard de / en | Laptop rtf | Verdict |
|---|---|---|---|---|---|---|---|
| baseline (experiment 0) | 71.3 | 46.0 | 25.3 | 24 | 4.8 / 2.0 | 0.05 | reference |
| cohere | 45.7 | 24.3 | 21.3 | 17 | 2.8 / 1.4 | 0.25 | **stage 1 pass**, gain 25.7, 9 takes better; language right on all ten |
| cohere-noitn (number formatting off) | 47.0 | 26.7 | 20.3 | 17 | - | 0.25 | stage 1 pass, gain 24.3: the gain is recognition, not formatting |
| dict-beam4 (score 1.5) | 59.7 | 41.0 | 18.7 | 18 | 6.0 / 1.8 | 0.08 | dictionary rule pass against beam4-gate: 6 terms recovered, 1 guard insertion |
| dict-beam4-s3 (score 3.0) | 69.0 | 42.0 | 27.0 | 22 | 5.7 / 2.6 | 0.08 | fail: 7 guard insertions; score 6 was destructive in the smoke test |
| beam4-gate | 68.7 | 46.3 | 22.3 | 24 | 6.6 / 1.8 | 0.08 | fail: 2.7 words, and worse on the guard's German |
| qwen3 | 180.3 | 74.7 | 105.7 | 40 | - | 0.24 | out for the night; its German text reads well, so the English figure may be a language or configuration problem: a hand-back item, not an experiment |

Order of work for the night:

1. **Cohere on the phone first, as a measurement, not a verdict.** Its laptop row passes stage 1.
   On the phone it will very likely miss the 2 s wait after Stop by construction: the wait is the
   real-time factor times the final chunk, a laptop factor of 0.25 maps to about 0.33 on the phone,
   so a 12 s final chunk waits about 4 s. It also runs two model instances (German and English) plus
   the identifier, so expect a peak around 6 GB. Run it once, record `phone_rtf_median`,
   `phone_stop_max_ms`, `phone_vmhwm_mb` and the phone text's accuracy in the hand-back, and spend
   no further phone cycles on it: each costs a 3 GB push. Whether a 25-word gain is worth a longer
   wait or a single-instance rebuild is Oriol's decision, not the loop's. Range-find the chunk
   length for cohere **on the laptop only** (`min_chunk_s` 8, 10, 12, 15 with `vad_max_speech_s`
   10 to 15): `laptop_last_chunk_max_s` is the wait proxy and the error columns say what short
   chunks cost. A cohere variant sets either `cohere.language` or `lid.model`, never both (the two
   sides would disagree on which wins). `cohere.use_itn` false is already in the ledger.
2. **Dictionary on the shipped model**: scores 1.5, 2.0, 2.5 with beam width 4 and 8, always with
   `--reference beam4-gate` or `beam8-gate`. A twin (the same pipeline without hotwords) is run once
   with `--guard`, committed with "twin" in the message, and never reset even though it fails the
   general rule: the dictionary rows are judged against it. For every dictionary pass, also count
   the dictionary words in the ten take texts that are not in the take's reference (the logic of the
   guard-insertion check, applied to `takes` in the report) and write the number into the ledger
   note; more than 1 is a fail in spirit, say so in the hand-back.
3. **Dictionary through cohere is not possible** (no hotwords there) and through qwen3 is pointless
   (the model is out). If cohere keeps, the dictionary question moves to a later setup round.
4. Flat, do not re-run: cutting and decoding knobs on the shipped model, full precision, the spike
   quantisation variants, Whisper turbo, Canary 180M, qwen3.

Not in the schema and therefore not experiments: overlapping chunks, number and money formatting
rules, other runtimes, GPU or neural-core acceleration, new downloads, a bigger Canary. Write such
ideas, with evidence, into the hand-back as requests for a setup round.

## Phone rules

- The phone is reached only through the wrapper, one call per Bash command, exactly as printed by
  `phone-prepare`. A hook blocks any other shell command that mentions the wrapper path, the Android
  debug bridge, fastboot or platform-tools, including in commit messages and heredocs. Read the
  wrapper with the Read tool if you need to see it. Never propose or apply changes to it or to the
  gate files; write wrapper requests into the hand-back.
- Never call `install` or `uninstall`. Oriol installs the benchmark build before the night and the
  release build afterwards.
- Phone runs only at night: Oriol needs the phone during the day. If a run would start after
  06:30 local time, skip stage 2, mark the row `pending-phone`, and continue laptop-only.
- Serial phone runs only. If a `lab` run fails because the phone is not reachable, do not retry in a
  loop: note it, continue laptop-only, and tell Oriol in the hand-back. If the permission layer
  refuses a wrapper call, the same: note it, no workaround.
- Heat: if `phone_thermal_max` reaches 3, wait 15 minutes and rerun that variant once. If it happens
  again, stop phone runs for the night.
- Nothing is copied out of `corpus/`, and nothing from `corpus/` goes anywhere but `corpus/lab/`.
  `pull-results` copies JSON only.
- Last phone action of the night, before 06:30: `tools/phone push-variant corpus/lab/push/baseline`,
  so that the app's laboratory directory holds 1 GB and not a 3 GB model when Oriol uses the phone.

## Terminal states and the hand-back

Stop and write `corpus/lab/handback-<date>.md` when: the phone is unreachable twice in a row,
parity fails for the baseline control row, the recording trigger fires, the budget is exhausted, or
anything needs Oriol. The hand-back lists: the branch and last kept commit; the ledger path and a
copy of `tools/lab.py ledger --all`; the range-finding verdict per family; the control row; disk
used under `corpus/lab/`; which build is on the phone; open requests for a setup round; and, if the
trigger fired, the exact variant to record with.

Recording trigger (pre-registered): a kept variant under the general rule whose German error count
is at least 12 words below experiment 0, English not worse by more than 1 word, guard not worse,
stage 2 passed. For a dictionary variant the trigger is: at least 10 terms recovered, errors outside
dictionary words not worse by more than 3 against the twin, no guard insertions, **guard not worse
than experiment 0 by more than 1 point** (beam search alone costs 1.8 points of public German, and
Oriol must not record against a regressed recognizer), stage 2 passed; the recording then also
tests names that are not in the list. Then Oriol records the four hold-out scripts once, in the
app, benchmark mode on. The hold-out rule, also pre-registered: on identical audio, the candidate
must not be worse than the baseline variant on any hold-out take by more than 2 words and must have
fewer German errors in total; English may be worse by at most 1 point. The verdict is Oriol's.

## What the loop does not do

It does not touch `docs/`, does not merge to `main`, does not commit anything under `corpus/`,
does not change the app's default pipeline (porting a winner, including any dictionary UX, is a
later change with its own decision record), and does not ask whether to continue. It continues
until a terminal state.
