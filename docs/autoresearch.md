# Experiment loop: instructions for the loop session

**Historical brief:** revision 3 below governed the completed third night. For the proposed next round, read [setup and experiment execution plan](next-experiment-plan.md). Its setup handoff must produce a new frozen batch brief before GPT 6.1 Sol is launched; this file does not authorize rerunning night three or beginning the new work.

Status: revision 3, written 2026-10-12 by the setup session after two nights, two reviews and a
research round, agreed with Oriol. This is the whole brief for a fresh session that runs the third
night unattended. Read it completely before the first command. Project rules in
[AGENTS.md](../AGENTS.md) still apply; where this file is stricter, this file wins. Earlier nights:
`corpus/lab/handback-2026-10-10.md`, `corpus/lab/handback-2026-10-11.md`; their ledger rows stay.

## Where things stand

`ch.og.dictation` dictates German and English on Oriol's phone with Parakeet TDT 0.6B v3 (INT8
encoder, greedy, 20 s chunks at pauses, 0.8 s wait after Stop). Of its 67 word errors on the ten
saved takes, 38 are names. Night one showed that cutting and decoding knobs are flat. Night two
showed that a hotword dictionary with modified beam search recovers names, but beam search on the
INT8 encoder collapsed one public German clip and carried a public-guard regression. The setup
investigation of 2026-10-12 ([ADR-006](decisions.md), [beam investigation](beam-investigation-2026-10-12.md))
found that the collapse is an INT8-encoder interaction: with the full-precision encoder, beam
search is clean, and **full-precision beam 8 with the dictionary at score 2.0 scores 47.0 errors
against the baseline's 71.3, recovers 13 of 24 missed names, and improves both public guard rates**
(laptop). Cohere Transcribe scores 45.7 but needs 10 s after Stop; it now runs as one instance
instead of two ([ADR-007](decisions.md)), which the phone has not measured yet.

The remaining questions are phone questions. Night three answers them and nothing else:

1. Does the full-precision encoder fit the phone (memory under the 3.5 GB limit; the file is 2.4 GB)?
2. Which beam width holds the 2 s wait after Stop on the phone, and does the answer repeat?
3. What does single-instance Cohere cost in memory and wait?

## Fixed parts (never edit; `tools/lab.py check` refuses to run if they changed)

| Part | File | What it does |
|---|---|---|
| Scorer | `tools/score.py` | normalisation and word-error count |
| Evaluator | `tools/evaluate.py` (revision 3) | per-take scoring, keep rules, phone budget, phone-text accuracy, evidence checks |
| Pipeline | `tools/pipeline.py` | laptop twin of the app's decode path, driven by a variant |
| Schema | `tools/variant.py` (schema 3) | the closed set of keys a variant may have |
| Runner | `tools/lab.py` | check, run, phone-prepare, phone-score, ledger, dictionary, range |
| Take index | `corpus/takes.json` | which take is which script |
| Guard | `models/guard/refs.tsv` | 210 public clips used as a breakage and false-insertion detector |

Also fixed: everything under `app/`, `corpus/`, `models/`, `docs/`, `variants/` files that already
exist, the wrapper and the gate. Quirks of the normaliser go into the ledger note, not into code.

## The night's variants (all stage-1 rows exist in the ledger; do not re-run stage 1)

| Variant | What it is | Stage 1 (laptop, 2026-10-12): total errors, names recovered of 24, guard de / en, last-chunk decode |
|---|---|---|
| `baseline` | shipped app; control row on the phone first | 71.3, 0, 4.81 / 2.01 %, 1.2 s |
| `dict-beam4-fp32-s20` | full-precision encoder, beam 4, dictionary 2.0 | 60.0, 6, 4.74 / 2.10 %, 2.2 s; pass, 1 guard insertion |
| `dict-beam6-fp32-s20` | full-precision encoder, beam 6, dictionary 2.0 | 53.0, 10, 4.71 / 1.81 %, 2.8 s; pass, 1 guard insertion |
| `dict-beam8-fp32-s20` | full-precision encoder, beam 8, dictionary 2.0 | 47.0, 13, 4.62 / 1.75 %, 3.3 s; pass, 1 guard insertion |
| `dict-beam8-fp32-s175` | score neighbour at beam 8, score 1.75 | 52.3, 9, 4.65 / 1.68 %, 3.3 s; pass with **no guard insertion**: one name short of the recording trigger's 10 |
| `dict-beam8-fp32-s225` | score neighbour at beam 8, score 2.25 | 45.0, 14, but 4 guard insertions: stage 1 fail; run on the phone only if time is left, as a measurement |
| `beam4-fp32-gate`, `beam6-fp32-gate`, `beam8-fp32-gate` | the no-dictionary twins | 69.7 / 68.0 / 68.7; fail the general rule by design; needed as references |
| `cohere` | Cohere Transcribe, one instance, language per stream from Whisper tiny | 45.7, 7, 2.75 / 1.36 %, 6.6 s; identical to the two-instance run |

Laptop last-chunk decode times map to the phone roughly 1 to 1 for INT8; the full-precision encoder
has no INT8 kernel advantage on the phone, so expect it slower there. That is why the width sweep is
the night's question.

No new variants are written tonight. The phone is the experiment.

## The night, in order

Setup: `git status` clean on `main` at the commit "Brief revision 3" or later; create the branch
`autoresearch/<date>` from it; `tools/lab.py check` prints `check ok`; `tools/lab.py ledger --all`
shows a `pass` row with a filled `reference` column for every dictionary variant above and a row
for `cohere`, and no laptop decode is still running (the ledger is rewritten whole on every write).
Reading results: per-take errors and `terms_missed` of a phone result come from
`tools/evaluate.py takes corpus/lab/phone/<file>.json` (names recovered = 24 minus `terms_missed`);
both wait readings are `paced[].stopToFinalMs` in the result file (`phone-score` prints only the
larger one); the upper bound on the wait is the largest `chunks[-1].decodeMs` over the ten takes.
`phone-score` judges accuracy only when every budget rule passed, so for a run that fails memory or
wait, those two commands are the source of the accuracy columns.

1. **Control row.** `tools/lab.py phone-prepare variants/baseline.json`, the three wrapper
   commands, `tools/lab.py phone-score`. Expected: real-time factor about 0.06, wait under 1 s,
   peak about 2.1 GB (revision 3 counts the paced run too), thermal 0. If this fails, stop and write
   the hand-back: the phone or the build is wrong. `push-variant` and `lab` run with the Bash tool's
   background option (a foreground command is capped at 10 minutes; a 2.5 GB push takes up to 5,
   a `lab` run 8 to 20); `lab` starts only after the push printed its size list.
2. **Full-precision dictionary, narrowest first.** For `dict-beam4-fp32-s20`, then
   `dict-beam6-fp32-s20`, then `dict-beam8-fp32-s20`: prepare, push (the model is 2.5 GB), `lab`,
   pull, `phone-score ... --reference beam<N>-fp32-gate`. **Run each width twice**, the second run at least 10 minutes after the first,
   so the wait after Stop has two readings and the thermal state of each is known. The second run
   is `lab` and `pull-results` only (the files are already on the phone); before scoring it, check
   that `before.elapsedMs` in the new result differs from the first one's, otherwise `lab` did not
   run and the old result was pulled again. The gap is a background `sleep 600`. The runner writes
   the second run over the same ledger row; copy the first run's readings into the hand-back table
   before the second run. A width is "holding the wait" only if both readings are under 2 s.
   **Memory is a property of the encoder, not of the width:** the beam 4 run answers question 1 for
   all three widths. If it is above 3.5 GB, `phone-score` will mark every full-precision row
   `discard`; that is the answer, recorded once, and the sweep continues for the wait and name
   readings (the laptop needed 2.9 GB for one decode, so the phone may land either side of the
   limit). A width whose stage-1 row is `fail` is still run and recorded with
   `tools/evaluate.py phone <result>` (budget only) and `tools/evaluate.py takes <result>`, with
   "stage 1 fail" in the table.
3. **Score neighbours** at the widest width that held the wait: `dict-beam8-fp32-s175` and
   `dict-beam8-fp32-s225` if beam 8 held, otherwise skip them (their phone speed equals beam 8's).
4. **Cohere, one instance, as a measurement.** One run: prepare, push (3 GB), lab, pull,
   phone-score. Record memory, wait, cold load and the phone text's accuracy in the hand-back. No
   second run, no variants: the wait cannot be fixed by settings (night two measured that), only by
   a design change that is not in the schema.
5. **End of night, before 06:30:** `tools/phone push-variant corpus/lab/push/baseline` so the app's
   laboratory directory holds 1 GB, then the hand-back.

Budget: at most 12 phone runs. Laptop runs: none expected; if one is needed (a missing twin row),
run it with `--guard`, commit the variant file with "twin" in the message, never reset it.

## Keep rules (implemented in `tools/evaluate.py` revision 3; this is the explanation)

Stage 2, phone: median real-time factor per chunk under 0.3; wait after Stop on the paced longest
and shortest take under 2 s; thermal status never above moderate; **full-run peak memory under
3.5 GB** (hard); above 2 GB is a flag. The evaluator re-applies the accuracy rules to the phone's
own text, both the unpaced and the paced transcripts, against the frozen baseline and, for
dictionary variants, against the twin. Guard and silence evidence stay laptop evidence.

The general rule and the dictionary rule are unchanged from revision 2 (8-word margin; dictionary:
at least 5 terms recovered, other-word errors not worse than the twin by more than 3, at most 1
dictionary word in the 210 public clips, silence clean, guard not worse than the twin by more than
1 point).

Recording trigger, unchanged and pre-registered: for a dictionary variant, at least 10 terms
recovered, no guard insertions, guard not worse than experiment 0 by more than 1 point, stage 2
passed. The best laptop candidate has one guard insertion, so the trigger will not fire tonight
unless a phone run changes that; say so plainly in the hand-back rather than bending the rule.

## Phone rules

- The phone is reached only through the wrapper, one call per Bash command, exactly as printed by
  `phone-prepare`. A hook blocks any other shell command that mentions the wrapper path, the Android
  debug bridge, fastboot or platform-tools, including in commit messages and heredocs. Read the
  wrapper with the Read tool if you need to see it. Never propose or apply changes to it or to the
  gate files; write wrapper requests into the hand-back.
- Never call `install` or `uninstall`. Oriol installs benchmark build 0.2.5 before the night and
  the release build afterwards.
- Phone runs only at night. No `lab` run starts after 06:00 (a Cohere run lasts up to 20 minutes);
  the phone is Oriol's during the day.
- Serial phone runs only. If a `lab` run fails because the phone is not reachable, or the
  permission layer refuses a wrapper call, note it, do not retry in a loop, continue with the
  hand-back.
- Heat: if `phone_thermal_max` reaches 3, wait 15 minutes and rerun that variant once. The
  repeated runs of step 2 exist because night two saw the wait move with temperature.
- Nothing is copied out of `corpus/`, and nothing from `corpus/` goes anywhere but `corpus/lab/`.
  `pull-results` copies JSON only.
- Git: before any `git reset`, check that `HEAD` is a commit this session made and the tree is
  clean; otherwise remove with a forward commit. Night two lost a docs commit to a blind reset.

## Terminal states and the hand-back

Stop and write `corpus/lab/handback-<date>.md` when: the control row fails, the phone is
unreachable twice in a row, the budget is exhausted, or step 5 is done. The hand-back lists: the
branch and commits; `tools/lab.py ledger --all`; a table with, per phone run, variant, run number,
time, thermal state, real-time factor, wait after Stop (both paced takes), peak memory, phone-text
errors, names recovered; the control row; disk under `corpus/lab/`; which build is on the phone;
and a decision table for Oriol: for each width, names recovered, errors, both wait readings, memory.
That table is the night's product.

## What the loop does not do

It does not write variants, does not touch `docs/`, does not merge to `main`, does not commit
anything under `corpus/`, does not change the app's default pipeline, and does not ask whether to
continue. It continues until a terminal state.
