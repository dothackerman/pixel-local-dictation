# Measurement results

Evidence for requirement R4. Raw recordings, transcripts and reports stay in the gitignored `corpus/`; this file holds the aggregate numbers and how they were obtained. Protocol and its limits: [plan](plan.md), milestone 3.

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

0.2.1 lost words at the start of segments; 0.2.2 cuts contiguous chunks at pauses instead. The 0.2.2 row is a laptop replay of the phone's audio, not a phone run.

**Reading.** Neither language shows a difference larger than the 2 points the plan requires for a verdict from this few takes; the German and English differences are one to five words out of about 500. Accuracy is therefore "comparable", not "better", and R4's accuracy half is not established. Speed is better by about two orders of magnitude.

**Error profiles differ** (0.2.2 replay against FUTO):

- Our app is weaker on unusual names: "Jem Chilmatz" for Cem Yilmaz, "Ventador" for Winterthur (once; correct later in the same take), "Lucas" for Lukas, "Aileen" for Aylin (FUTO: "Eileen"), "Mayo" for Maya once, and it misspelt two numbers written as words ("vierdzwanzig", "sechundvierzig").
- FUTO changed more values and ordinary words: "57 %" for 7.50 Franken, "2021" for 2027, "£0.95" for £4.95, "4" for "for", "the parent effort" for "apparent effort", "Bekrei", "Krittung".
- All negations in the six scripts ("nicht achtzehn", "not eighteen", "nicht beim Hafen") survived in both.

**Not measured.** Short single sentences, noisy surroundings, Oriol's real vocabulary, takes without reading from a script, scripts 4 and 5.
