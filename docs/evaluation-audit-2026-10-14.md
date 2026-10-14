# Evaluation audit, 2026-10-14

Status: read-only investigation of the saved experiments, with aggregate recalculation. No new model inference, phone access, acceptance-rule change or production change. Personal text and audio remain under `corpus/`. Requirements and the historical decisions remain authoritative in [requirements](requirements.md) and [ADRs](decisions.md).

## Accuracy already improved on the development recordings

The latest evidence is night three, `corpus/lab/handback-2026-10-13.md`, rather than the older summary in the experiment brief. Independently rescoring its saved phone reports with [the frozen evaluator](../tools/evaluate.py) gives:

| Actual phone output | German errors / 584 words | English errors / 632 words | Total |
|---|---:|---:|---:|
| Current baseline | 46 | 22 | 68 |
| FP32 Parakeet, beam 8, dictionary 2.0 | 35 | 11 | 46 |
| Single-instance Cohere | 24 | 23 | 47 |

The dictionary candidate removes 22 of 68 errors, **32.4% on these recordings**, with improvements in both languages. Cohere's 22-word German gain is accompanied by one extra English error. These are comparisons with the app's Parakeet baseline; the installed FUTO comparison still uses different spoken takes, as [results](results.md) explains. The dictionary comes from these scripts, so its gain establishes useful contextual biasing on supplied vocabulary, not unseen-vocabulary accuracy.

The FP32 candidate's night-three failures were memory, Stop delay and, at beam 8, median chunk real-time factor. This is a deployment problem for a candidate with demonstrated development-set gains. It is incorrect to infer an accuracy ceiling from its final `discard` status. Keep quality, device cost and evidence strength as separate columns.

## The ten takes contain eight scripts, not ten independent content samples

`corpus/takes.json` contains six scripts recorded once and two scripts recorded twice: four unique scripts per language. The three feed sizes are additional executions of the same audio. They reveal sensitivity to chunk boundaries, not new speech examples. [Pipeline](../tools/pipeline.py) averages their word errors while terms and punctuation use only the primary feed.

For a bounded sensitivity check, this audit grouped repeated takes of the same script together and enumerated all 256 paired bootstrap resamples of the four script groups in each language. Both systems use the same groups on each draw; the statistic is the difference in summed errors divided by summed reference words. Positive values favor the candidate.

| Phone comparison | Per-script error reductions, sorted | Observed WER reduction | Exploratory 95% percentile interval |
|---|---|---:|---:|
| FP32 dictionary vs baseline, German | -1, 3, 4, 5 | 1.88 percentage points | 0.16 to 3.56 |
| FP32 dictionary vs baseline, English | 0, 0, 3, 8 | 1.74 points | 0.00 to 3.74 |
| Cohere vs baseline, German | 3, 5, 6, 8 | 3.77 points | 2.42 to 4.78 |
| Cohere vs baseline, English | -4, -1, 1, 3 | -0.16 points | -1.75 to 1.36 |

These intervals describe sensitivity within a repeatedly inspected development set with only four groups per language. They are **not confirmatory confidence claims for daily dictation**, do not correct selection across dozens of variants, and cannot model unobserved domains. They show why Cohere's German gain is a stronger lead than its English result, and why the dictionary's English gain still needs independent coverage. Correlated utterances require appropriate resampling blocks; ordinary resampling can underestimate uncertainty. [Liu and Peng, Interspeech 2020](https://www.isca-archive.org/interspeech_2020/liu20c_interspeech.html).

Reproduce without inference or emitting personal text:

```sh
models/venv/bin/python corpus/lab/audit-evaluation-2026-10-14.py
```

Inputs are `corpus/lab/phone/20261013-210335.json`, `20261013-222053.json` and `20261013-222941.json`. All arithmetic uses the current frozen normalizer.

## The keep thresholds are engineering rules, not uncertainty estimates

[The evaluator](../tools/evaluate.py) uses an eight-word total margin, more improving than worsening takes and language/terms/guard tolerances. Eight words over 1,216 words is 0.66 percentage points. Nothing in its implementation estimates a sampling standard error. Interpreting `sqrt(67)` as the uncertainty of a paired difference would need assumptions about independent error counts that this experiment does not establish. Repeated scripts, repeated feed schedules and overlapping candidate failures directly complicate that interpretation.

The dictionary mechanism rule asks for at least five recovered terms and allows one dictionary-token insertion on the public guard. The recording trigger instead asks for ten terms and zero insertions. Thus the FP32 score-1.75 candidate, with nine recovered terms and zero insertions, can be blocked while a score-2.0 candidate has thirteen recoveries and one insertion. That is the documented policy, not evidence of a statistically meaningful divide between them. Do not quietly relax it after seeing the results; a future setup should state what user cost it represents and separate a recording/validation decision from deployment approval.

The 210 public clips total 2,491 seconds. One observed insertion is roughly 1.45 events per hour of this particular guard audio. It does not establish a daily-use rate, and zero observed insertions would not establish zero risk. The guard has repeatedly influenced search decisions, so it is development evidence too.

## Measurement limitations and subsequent setup

- **Thermal attribution needs matched repeats.** Slower late runs of different variants cannot identify temperature as the cause. Repeat the same configurations with a frozen cooldown and counterbalanced order before attributing a timing change to heat.
- **Use the matching phone baseline for phone accuracy comparisons.** Release 1a's separately versioned evaluator now prepares this comparison; the following describes the historical evaluator. `phone_check` applies phone candidate text against the frozen laptop baseline, whose 71.33 averaged errors differ from the phone control's 68 single-feed errors. The raw gain can therefore be overstated by 3.33 words, and phone English differences can look better than they do against the phone control. This audit's table uses phone versus phone. This is a comparison-design limitation, not a claim that existing variants were wrongly deployed.
- **Short/noisy/spontaneous dictation is missing.** The takes are 28.2–109.7 seconds and scripted. The original [roadmap](roadmap.md) called for 20 clips per language including 5–30 second utterances, conditions and difficult content. Daily automatic language routing and short-message usefulness remain untested.
- **Stop latency coverage favors particular endpoints.** Only the longest and shortest takes are paced. Both have short residual chunks; night three reports larger unpaced final-chunk decode times elsewhere. A good two-take Stop score cannot establish a tail-latency guarantee. Test intermediate Stop positions through recorded audio and report the distribution, before requiring more recordings.
- **Word error normalization is not correction effort.** The scorer intentionally ignores case and punctuation; its companion punctuation metric counts sentence-final marks and lowercase sentence starts. A synthetic comparison of `Stop. Do not proceed.` with `Stop? Do not proceed!` scores zero punctuation issues. This is a reproducible metric blind spot, not a discovered cause of the current word-error rankings. User correction counts, names/numbers/negation errors and formatting require their own reporting, as R5 already says.
- **The current saved feed coverage is complete.** All 85 complete laptop reports inspected have two jitter executions plus the primary execution for all ten takes. There is no evidence that missing feed executions explain the current rankings. Do not invent an evaluation bug to explain every disappointing result.

## Follow-up

[The experiment plan](next-experiment-plan.md) incorporates matched controls, separate quality/resource reporting and varied Stop endpoints before reserved validation. Preserve the ten takes and repeatedly inspected public guard as development data. Final R4 validation still requires the complete automatic phone path against FUTO, each language separately, including correction effort and coverage absent here.
