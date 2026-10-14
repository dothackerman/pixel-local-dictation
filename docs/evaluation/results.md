# Measurement results

Evidence for requirement R4. Raw recordings, transcripts and reports stay in the gitignored `corpus/`; this file holds the aggregate numbers and how they were obtained. The approved protocol and its limits are below; [the October 14 audit](../research/evaluation-audit-2026-10-14.md) owns the later phone comparisons.

## Approved measurement protocol

Revised with Oriol on 2026-10-10: five German and five English scripts about his topics, planned at 45–60 s, with names, dates, amounts and technical terms. Each was to be read into the benchmark app and FUTO on the same day, timing FUTO from Stop by stopwatch. This is matched live speech, not identical audio. Benchmark copies require per-run agreement; return to the release build afterwards.

The agreed expansion rule was five more scripts in a language if its difference fell within about two WER percentage points before a verdict. Only three scripts per language were recorded, and actual takes lasted 90–110 s. The later [recording policy](../experiments/protocol.md#recording-policy) restricted new collection; explicit reconciliation of that restriction with this expansion agreement is not recorded. Keep the accuracy verdict unresolved rather than silently cancelling either.

Private references, terms, FUTO outputs/times and take mappings live in `corpus/scripts/`, `corpus/futo/` and `corpus/takes.json`; [score.py](../../tools/score.py) owns normalization. The scorer also parses [baseline.md](baseline.md) for D6/E6 references and outputs. That legacy tracked transcript/fixture coupling conflicts with the current privacy boundary; moving it requires a separate code-and-fixture migration. It is left unchanged to preserve the frozen experiment inputs.

## Run of 2026-10-10

**What was recorded.** Six fictional scripts by Oriol (de-1 to de-3, en-1 to en-3; 156–170 words each after normalisation, 974 in total), dense in names, places, dates, times, amounts and codes. Each was read once into our app (0.2.1, benchmark build, audio saved, takes of 90–110 s) and once into FUTO Keyboard 0.1.30 with Multilingual-244. Scripts 4 and 5 of each language were not recorded. These are matched live takes, not identical audio.

**Speed.**

| | FUTO | Our app 0.2.1 |
|---|---|---|
| Wait from Stop to text, six takes | 79.9–99.7 s (stopwatch) | 0.01–1.04 s (app log) |

**Accuracy, word error rate after normalisation** (`tools/compare.py`; formatting of numbers, money, times, units, accents and "ß" is not counted, see `tools/score.py`):

| | German | English |
|---|---|---|
| FUTO, its own takes | 48/468 = 10.3 % | 19/506 = 3.8 % |
| Our app 0.2.1 as recorded on the phone | 40/468 = 8.5 % | 35/506 = 6.9 % |
| Same audio, 0.2.2 cutting, laptop replay | 43/468 = 9.2 % | 20/506 = 4.0 % |
| Same audio, 0.2.2 cutting, full-precision fp32 model, laptop | 41/468 = 8.8 % | 19/506 = 3.8 % |

0.2.1 lost words at the start of segments; 0.2.2 cuts contiguous chunks at pauses instead. The 0.2.2 rows are laptop replays of the phone's audio, not phone runs; on the phone, 0.2.2 cut the same takes into the same number of chunks (`PipelineTest` log), its texts were not copied. For these greedy replays, the fp32 row is close to the shipped model, so the quantisation of [ADR-003](../decisions.md#adr-003--parakeet-v3-via-sherpa-onnx-with-a-matmul-only-int8-encoder) does not explain a substantial accuracy gap. This does not establish beam-search parity; see the later precision diagnosis below.

**Reading.** Neither language shows a difference larger than the 2 points the plan requires for a verdict from this few takes; the German and English differences are one to five words out of about 500. Accuracy is therefore "comparable", not "better", and R4's accuracy half is not established. Speed is better by about two orders of magnitude.

**Error profiles differ** (0.2.2 replay against FUTO):

- Our app is weaker on unusual names: "Jem Chilmatz" for Cem Yilmaz, "Ventador" for Winterthur (once; correct later in the same take), "Lucas" for Lukas, "Aileen" for Aylin (FUTO: "Eileen"), "Mayo" for Maya once, and it misspelt two numbers written as words ("vierdzwanzig", "sechundvierzig").
- FUTO changed more values and ordinary words: "57 %" for 7.50 Franken, "2021" for 2027, "£0.95" for £4.95, "4" for "for", "the parent effort" for "apparent effort", "Bekrei", "Krittung".
- All negations in the six scripts ("nicht achtzehn", "not eighteen", "nicht beim Hafen") survived in both.

**Not measured.** Short single sentences, noisy surroundings, Oriol's real vocabulary, takes without reading from a script, scripts 4 and 5.

## Laptop setup validation, 2026-10-12

Six complete laptop replays validate the configuration/evaluator changes in commit `c63cb0d`
on `autoresearch/2026-10-11` (variant schema 3, evaluator revision 3). Each uses the same ten
personal takes with three feed schedules, all 210 public guard clips (2,491 seconds), and the
silence/noise checks. Personal error counts are means over the three feeds: 584 German and
632 English reference words, 1,216 total. The normalizer and acceptance thresholds are unchanged.
The baseline and existing INT8 controls reproduce their historical accuracy results.

| Variant | German errors | English errors | Total errors | Terms missed / 130 | Guard DE WER | Guard EN WER | Laptop rule |
|---|---:|---:|---:|---:|---:|---:|---|
| Baseline | 46.00 | 25.33 | 71.33 | 24 | 4.81% | 2.01% | Control |
| Qwen, corrected context 1024 | 73.67 | 33.33 | 107.00 | 36 | 12.00% | 2.62% | Fail |
| INT8 beam 8 | 47.00 | 21.67 | 68.67 | 25 | 6.53% | 1.78% | Fail |
| INT8 beam 8 + dictionary 2.0 | 37.00 | 13.33 | 50.33 | 15 | 5.98% | 1.88% | Dictionary pass |
| FP32 beam 8 | 43.33 | 25.33 | 68.66 | 22 | 4.81% | 1.78% | Fail |
| FP32 beam 8 + dictionary 2.0 | 35.33 | 11.67 | 47.00 | 11 | 4.62% | 1.75% | Dictionary pass |

The two plain-beam totals are both approximately 68.7; their 0.01 difference comes from rounding
the language means. All six variants emit zero characters on the silence/noise checks.

**Qwen.** Correcting its constructor context budget improves the historical broken result from
180.34 to 107.00 errors. All 30 take/feed pairs retain identical chunk boundaries, so different
cutting does not explain the improvement. It still loses to baseline on both languages, terms,
and the German public guard. This corrected export/configuration does not warrant phone testing
on this evidence. Configuration contract and limits: [ADR-006](../decisions.md#adr-006--validate-qwen-input-budgets-and-enforce-complete-phone-evidence).

**Beam collapse.** Replacing only the encoder with FP32 removes the reproduced catastrophic
deletion on the public German diagnostic clip: INT8 beam has 62 errors, FP32 beam has 5;
with dictionary 2.0 the corresponding counts are 44 and 6. The full guard also recovers.
This isolates a precision/search interaction, not a traced numerical defect or a general decoder
repair; see the [beam investigation](../research/beam-investigation-2026-10-12.md). Plain FP32 beam still
fails the general rule: about 2.7 fewer errors is below the eight-word margin, and four takes
improve while five worsen. Earlier greedy FP32 parity did not establish beam/dictionary parity.

**Best laptop candidate.** FP32 beam with dictionary 2.0 has 47.00 errors versus baseline's
71.33, recovers 13 additional terms, and improves both public guard scores. Both dictionary
variants improve all ten takes versus baseline and pass the dictionary mechanism rule against
their own no-dictionary twin. Non-dictionary errors fall from 63.00 to 44.67 for FP32 and from
63.33 to 48.00 for INT8. Neither inserts a dictionary word absent from the personal take's
reference. Each nevertheless has **one false dictionary insertion on the public guard**.
Consequently the stricter zero-insertion recording trigger is not met. At this setup checkpoint FP32 phone memory and
latency were unmeasured; subsequent phone evidence is in [the October 14 audit](../research/evaluation-audit-2026-10-14.md). These replays did not grant deployment acceptance.

**Evaluator validation.** Sixteen Python tests and 27 Kotlin tests pass, including configuration
budgets, evidence coverage, memory accounting, and accuracy checks on actual phone text.
Independent standards/spec reviews found two evidence holes (baseline exemption by name alone
and missing per-take timing coverage); both were fixed and regression-tested before these replays.
The frozen-file check passes. Re-evaluating ten saved phone reports with revision 3 changes no
verdicts: corrected Parakeet memory peaks are about 2.1 GB, while Cohere's saved peak is about
5.39 GB and fails the existing memory ceiling as well as stop latency. These are historical
reports, not new phone measurements. No phone access occurred in this work.

**Reproducibility.** Raw reports remain local under `corpus/lab/runs/`, named
`setup3-{baseline,qwen3-context1024,beam8-gate,dict-beam8-s20,beam8-fp32-gate,dict-beam8-fp32-s20}-20261012.json`.
The aggregate comparison is `corpus/lab/setup3-comparisons-20261012.json`; the five candidate
replays' successful exits and inference-source hashes are in
`corpus/lab/setup3-replay-manifest-20261012.json`. All hashes match the committed inference code.
The historical phone re-evaluation is `corpus/lab/setup3-historical-phone-reevaluation-20261012.json`.
Original ledger rows and reports remain unchanged. No personal audio or transcript was copied
outside `corpus/`.
