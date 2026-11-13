# Spoken numbers as digits (ITN)

2026-11-11. Source/package inspection only: the pinned AAR was unzipped and
`javap`-inspected, upstream sources read at pinned tags/commits. No FST was
built, loaded or run; nothing touched the phone. "Unverified" marks claims not
checked against a primary source or not executed. Measured Parakeet behaviour
(mixed digits/words, cased, punctuated) is the owner's, not re-measured here.

Pins: sherpa-onnx `v1.13.8`; kaldifst `v1.8.0` (pinned by
[kaldi-decoder v0.3.0 `cmake/kaldifst.cmake#L4`](https://github.com/k2-fsa/kaldi-decoder/blob/v0.3.0/cmake/kaldifst.cmake#L4),
which sherpa-onnx 1.13.8 pulls via
[`cmake/kaldi-decoder.cmake#L4`](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/cmake/kaldi-decoder.cmake#L4));
NeMo-text-processing `main@ddadfb2a` (latest release `r1.2.0`, 2026-06-05).
Short links: `S` = `https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/`,
`N` = `https://github.com/NVIDIA/NeMo-text-processing/blob/ddadfb2a38d2bc6b8cc6232c4f915eb60f500688/`.

## 1. sherpa-onnx rule FSTs

- **Exposed in Kotlin 1.13.8: yes.** `OfflineRecognizerConfig(… ruleFsts: String = "", ruleFars: String = "", …)`
  ([`S/sherpa-onnx/kotlin-api/OfflineRecognizer.kt#L166-L178`](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/sherpa-onnx/kotlin-api/OfflineRecognizer.kt#L166-L178));
  `javap` on `app/libs/sherpa-onnx-1.13.8.aar` `classes.jar` shows both fields with getters/setters;
  JNI reads them ([`S/sherpa-onnx/jni/offline-recognizer.cc#L32-L34`](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/sherpa-onnx/jni/offline-recognizer.cc#L32-L34));
  arm64 `libsherpa-onnx-jni.so` contains `ruleFsts`/`ruleFars` strings. Our `Engine.kt` does not set them.
- **Format:** comma-separated paths; `.fst` (single FST) or `.far` (every FST in the
  archive is loaded) ([`S/sherpa-onnx/csrc/offline-recognizer.cc#L61-L69`](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/sherpa-onnx/csrc/offline-recognizer.cc#L61-L69)).
  Both file-path and `AssetManager` constructors load them
  ([`S/sherpa-onnx/csrc/offline-recognizer-impl.cc#L815-L911`](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/sherpa-onnx/csrc/offline-recognizer-impl.cc#L815-L911)).
- **Model types:** loaded in the shared `OfflineRecognizerImpl` base; NeMo transducer
  (our TDT path) calls `ApplyInverseTextNormalization` on each result after BPE
  leading-space strip, before the homophone replacer
  ([`S/sherpa-onnx/csrc/offline-recognizer-transducer-nemo-impl.h#L218-L219`](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/sherpa-onnx/csrc/offline-recognizer-transducer-nemo-impl.h#L218-L219)).
  Other impls not individually checked.
- **Semantics:** FSTs applied sequentially to the whole result string
  ([`offline-recognizer-impl.cc#L913-L924`](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/sherpa-onnx/csrc/offline-recognizer-impl.cc#L913-L924)).
  Each: input → byte-level linear FST (UTF-8 bytes as labels), compose with rule,
  shortest path, read output
  ([kaldifst v1.8.0 `text-normalizer.cc#L29-L49`, `#L186-L200`](https://github.com/k2-fsa/kaldifst/blob/v1.8.0/kaldifst/csrc/text-normalizer.cc#L186-L200)).
  **If the rule does not accept the input, output is `""`**
  ([`#L87-L103`](https://github.com/k2-fsa/kaldifst/blob/v1.8.0/kaldifst/csrc/text-normalizer.cc#L87-L103)):
  a rule must be total over Σ*, e.g. `pynini.cdrewrite(number, "", "", sigma)` as in
  the official zh notebook ([k2-fsa/colab `itn_zh_number.ipynb`](https://github.com/k2-fsa/colab/blob/master/sherpa-onnx/itn_zh_number.ipynb)).
- **Per-language:** no. One global list per recognizer; no language argument.
  NeMo transducer impl never sets `lang` (no `lang` reference in that file), so
  `OfflineRecognizerResult.lang` stays empty for Parakeet. Switching rules needs
  `setConfig`/a new recognizer (reload cost unmeasured).
- **Published FSTs:** the `asr-models` release lists only `itn_zh_number.fst`
  (+ `itn-zh-number.wav`) among ITN assets (GitHub API asset listing, 2026-11-11).
  No en/de rule FST published by k2-fsa.
- Unrelated: `useItn`/`<|noitn|>` strings in the `.so` belong to model-internal ITN
  of other models (e.g. `OfflineCohereTranscribeModelConfig.useItn`), not Parakeet.

## 2. NeMo-text-processing ITN (en, de)

- **Licence:** Apache-2.0 ([`N/LICENSE`](https://github.com/NVIDIA/NeMo-text-processing/blob/ddadfb2a38d2bc6b8cc6232c4f915eb60f500688/LICENSE)).
- **Classes** (`inverse_text_normalization/{en,de}/taggers/`): en = cardinal, date,
  decimal, electronic, fraction, measure, money, ordinal, punctuation, telephone,
  time, whitelist, word; de = same minus own punctuation (reuses en) and word file
  (repo tree at pinned commit). DE ITN grammars are inversions of DE TN grammars
  ([`N/…/de/taggers/cardinal.py#L45`](https://github.com/NVIDIA/NeMo-text-processing/blob/ddadfb2a38d2bc6b8cc6232c4f915eb60f500688/nemo_text_processing/inverse_text_normalization/de/taggers/cardinal.py#L45), time.py#L43).
- **Input expectation:** "no punctuation outside of apostrophe (') and dash (-) and be
  lower cased"; `cased` flag exists, and `lower_cased` "won't lower case input"
  ([`N/…/inverse_normalize.py#L29-L33`](https://github.com/NVIDIA/NeMo-text-processing/blob/ddadfb2a38d2bc6b8cc6232c4f915eb60f500688/nemo_text_processing/inverse_text_normalization/inverse_normalize.py#L29-L33)).
  en `cased` only allows a capitalised first letter (`TO_LOWER + NEMO_SIGMA`,
  [`N/…/text_normalization/en/graph_utils.py#L149-L162`](https://github.com/NVIDIA/NeMo-text-processing/blob/ddadfb2a38d2bc6b8cc6232c4f915eb60f500688/nemo_text_processing/text_normalization/en/graph_utils.py#L149-L162)).
  **de ignores `input_case`** except for the cache file name
  ([de `tokenize_and_classify.py#L72-L79`](https://github.com/NVIDIA/NeMo-text-processing/blob/ddadfb2a38d2bc6b8cc6232c4f915eb60f500688/nemo_text_processing/inverse_text_normalization/de/taggers/tokenize_and_classify.py#L72-L79); no `input_case` in de cardinal/time/date/decimal);
  de time expects lowercase `uhr` (TN verbalizer emits `" uhr"`,
  [`N/…/text_normalization/de/verbalizers/time.py#L63`](https://github.com/NVIDIA/NeMo-text-processing/blob/ddadfb2a38d2bc6b8cc6232c4f915eb60f500688/nemo_text_processing/text_normalization/de/verbalizers/time.py#L63)).
  So a cased "elf Uhr dreiundzwanzig" would likely not match as time (inference, not run).
- **Ambiguity handling:** en standalone cardinals 0–12 are excluded (stay words)
  ([en `cardinal.py#L219-L237`](https://github.com/NVIDIA/NeMo-text-processing/blob/ddadfb2a38d2bc6b8cc6232c4f915eb60f500688/nemo_text_processing/inverse_text_normalization/en/taggers/cardinal.py#L219-L237));
  en money special-cases "one" ([en `money.py#L74-L126`](https://github.com/NVIDIA/NeMo-text-processing/blob/ddadfb2a38d2bc6b8cc6232c4f915eb60f500688/nemo_text_processing/inverse_text_normalization/en/taggers/money.py#L74)).
  de excludes single digits only ([de `cardinal.py#L57-L59`](https://github.com/NVIDIA/NeMo-text-processing/blob/ddadfb2a38d2bc6b8cc6232c4f915eb60f500688/nemo_text_processing/inverse_text_normalization/de/taggers/cardinal.py#L57-L59)),
  so standalone "elf", "zwölf" become 11, 12. Handling of "a", "ein/eins/einen" and
  idioms ("zwei, drei Tage", "one of them") not traced: unverified.
  Selection between classes is by weights in tokenize_and_classify (en #L94-L104, de #L124-L135).
- **Output style:** de time verbalizer emits `H:MM Uhr` (colon,
  [de `verbalizers/time.py#L43-L46`](https://github.com/NVIDIA/NeMo-text-processing/blob/ddadfb2a38d2bc6b8cc6232c4f915eb60f500688/nemo_text_processing/inverse_text_normalization/de/verbalizers/time.py#L43-L46)),
  not Swiss `H.MM Uhr` (§4). Other de output formats (thousands, money) unverified.
- **Export:** `tools/text_processing_deployment/pynini_export.py` writes
  `classify/tokenize_and_classify.far` (`TOKENIZE_AND_CLASSIFY`) and
  `verbalize/verbalize.far` (`ALL`, plus `REDUP` = acceptor of the literal "REDUP")
  for Sparrowhawk ([#L26-L44](https://github.com/NVIDIA/NeMo-text-processing/blob/ddadfb2a38d2bc6b8cc6232c4f915eb60f500688/tools/text_processing_deployment/pynini_export.py#L26-L44)).
  The runtime is tagger → token parse/permutation (Python `TokenParser`) → verbalizer
  ([inverse_normalize.py#L152-L157](https://github.com/NVIDIA/NeMo-text-processing/blob/ddadfb2a38d2bc6b8cc6232c4f915eb60f500688/nemo_text_processing/inverse_text_normalization/inverse_normalize.py#L152-L157)).
- **With sherpa-onnx:** not drop-in. Passing both FARs to `ruleFars` would chain
  tagger output (`tokens { … }`) into `ALL`, then into `REDUP`, which accepts only
  "REDUP" → empty text (follows from §1 semantics; not run). Usable route: own pynini
  script composing a Σ*-total single FST from NeMo sub-grammars (e.g. cdrewrite of
  cardinal∘verbalizer), written as `.fst`. Size, build time and correctness: unverified.
- **LM/audio variants:** `normalize_with_audio.py`, `tokenize_and_classify_lm.py`,
  `hybrid/` exist under `text_normalization` (TN, written→spoken), not ITN (repo tree).

## 3. Parakeet TDT 0.6b v3 model card

[huggingface.co/nvidia/parakeet-tdt-0.6b-v3](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3):
"Automatic punctuation and capitalization"; "All transcriptions preserve punctuation
and capitalization"; language "automatically" detected (no language output promised).
Nothing on numbers, digits, ITN or written form. Training: Granary pseudo-labels
(660k h) + NeMo ASR Set 3.0 (10k h). Granary paper: Whisper pseudo-labels, P&C
restored by Qwen 2.5-7B, fallback to Whisper text if >5% CER deviation, character-set
filtering; no number-format statement ([arXiv 2505.13404 §3.1.4–3.1.5](https://arxiv.org/html/2505.13404)).
So digit/word mix is inherited training-label style (inference), not a guaranteed behaviour.

## 4. Style

**Swiss Federal Chancellery, *Schreibweisungen*, 2. Aufl. 2013 (korr. 2015)**; read from
a canton-hosted copy ([kr-geschaefte.zug.ch PDF](https://kr-geschaefte.zug.ch/dokumente/11187/3468-2-17179_Beilage2-Schreibweisungen-Bund.pdf));
no newer edition found (unverified). Rz = Randziffer.

| Topic | Rule | Rz |
|---|---|---|
| Words vs digits | One-/two-syllable numbers normally in words (null … zwölf … zwanzig, dreissig …); 21–29 etc. in digits; old "1–12 in words" printers' rule no longer strict; readability first | 501 |
| Same context | several numbers together → all digits | 502, 507, 508 |
| Before abbreviations/symbols | always digits: `12 km`, `20 Fr.`, `3 %`; before written-out units either (`zwölf Kilometer`, `12 Kilometer`) | 503, 505 |
| Times, years, centuries | digits: `9 Uhr`, `12.30 Uhr`, `1985`, `19. Jahrhundert` | 504 |
| Percent | digits normally; words allowed for an isolated value in non-technical text | 506 |
| Grouping | groups of three with fixed (non-breaking) space `1 222 333`; four digits ungrouped `1234`; **apostrophe "should no longer be used"**; points/commas wrong | 512, 251 |
| Decimals | comma `88,5 m`; money uses point `Fr. 23.50` (wrong: `Fr. 23,50`); `Fr. 20.–`; but `4,16 Millionen Franken` | 514, 543 |
| Money in running text | number then unit written out (`20 Franken`); exact amounts abbreviated before (`Fr. 327.65`, not `327.65 Franken`); `€ 64.15`/`EUR 64.15` | 539–541 |
| Time of day | 24 h, **point not colon**: `7.33 Uhr`, `14.30 Uhr`; no leading zero (lists may) | 533 |
| Dates | `2. September 2006`; numeric `2.9.2006` (leading zero not recommended) | 535–536 |
| Ordinals | `am 20. März` | 516 |
| Phone | `026 324 11 13`, `+41 26 324 11 13` | 519–520 |
| % sign | fixed space `5 %`; none in compounds `0,5‰-Grenze` | 554–555 |
| Units | fixed space `10 g`; unit with spelled number must be written out (`zehn Gramm`) | 549 |

**Duden** ([Rechtschreibregeln "Zahlen und Ziffern"](https://www.duden.de/sprachwissen/rechtschreibregeln/zahlen-und-ziffern)):
one-/two-syllable numbers in words in general texts, longer in digits ("eins, sieben,
zwölf, dreißig, hundert", but 37); numbers, years, measures, currency and dates in
digits even when short; ≥5-digit integers grouped by spaces.

**English:** Microsoft Writing Style Guide ([Numbers](https://learn.microsoft.com/en-us/style-guide/numbers)):
spell out zero–nine, numerals ≥10; numerals for measurements, percentages and times
even <10 (`3 cm`, `10:45 AM`); dates `June 1`, not "June first"; commas from four digits.
Chicago (zero–one hundred, CMOS 9.2) and AP (one–nine): paywalled, **unverified**.

## 5. Conservative ITN practice

- Production ITN is mostly hand-written WFST grammars; NeMo ITN is such a library,
  deployable via Sparrowhawk ([Zhang, Bakhturina, Gorman, Ginsburg 2021, arXiv 2104.05055](https://arxiv.org/abs/2104.05055)).
- Kestrel (Google): grammars compiled to WFSTs; separates tokenize/classify from
  verbalize ([Ebden & Sproat 2015, NLE 21(3):333–353](https://doi.org/10.1017/S1351324914000175); DOI unverified);
  open-source counterpart [google/sparrowhawk](https://github.com/google/sparrowhawk).
- Neural TN reaches high overall accuracy but its errors "would convey completely the
  wrong message"; an FST filter reduces them ([Sproat & Jaitly 2016, arXiv 1611.00068](https://arxiv.org/abs/1611.00068)).
  Same precision-over-recall stance as ours (wrong digit ≫ spelled word).
- Neural ITN (seq2seq) plus an FST to fix recoverable errors ([Sunkara et al. 2021, arXiv 2102.06380](https://arxiv.org/abs/2102.06380));
  Apple frames ITN as labelling with handcrafted grammars + small biLSTM
  ([Pusateri et al. 2017, Interspeech](https://www.isca-archive.org/interspeech_2017/pusateri17_interspeech.html)). Both need training data; not for us.
- Context gating in practice: NeMo leaves standalone small numbers as words (§2), money
  special-cases "one", classes chosen by weights. Requiring a unit/keyword/second number
  as context is the grammar's job; no source gives precision numbers for en/de ITN on
  cased ASR output (unverified gap).

## Implications for us

Shared facts: no language from Parakeet in sherpa (§1), so any language-specific
rule must use a text heuristic (the app has no language setting); Parakeet output is cased,
punctuated and already partly digitised, which none of the NeMo grammars target (§2).

**(A) sherpa-onnx `ruleFsts` built from NeMo grammars.**
Pro: zero app code at runtime; Apache-2.0; broad class coverage; runs inside `decode`.
Con: NeMo FARs are not drop-in (two-stage + `REDUP` → empty output); needs a custom
pynini build producing a Σ*-total FST; a non-total rule blanks the transcript;
global per recognizer (language switch = reconfigure, en/de grammars would fight on
shared words like "elf"); en `cased` covers only first-letter caps, de not at all
(`Uhr`, `Juni`, `Gleis`); de time output `12:46` not Swiss `12.46`; byte-level
composition on whole utterances, cost on phone unmeasured; big generated binary to audit.

**(B) Own small deterministic Kotlin post-step.**
Pro: targets exactly the observed residue (ordinals in dates, teens/tens before units,
`Uhr` times, "Gleis drei"); can gate on context (unit, `Uhr`, month, currency) and
leave standalone small numbers as words per Schreibweisungen Rz 501/Duden/Microsoft;
Swiss format choices (`12.46 Uhr`, `Fr. 4.80`, non-breaking-space grouping) explicit;
case/punctuation-tolerant; unit-testable on the owner's examples; per-language via a
text heuristic (the app has no language setting: R2, `res/xml/method.xml`; number words
are mostly language-unique, "elf"/"null" are not). Con: we own the grammar; coverage grows slowly; English
"and"/"a"/"oh" and German "ein/eine/einen" need explicit tests; risk of inconsistent
style with digits Parakeet already wrote (e.g. `10.15 Uhr` vs our `12.46 Uhr`—same
rule, fine; `9:20 Uhr` would need normalising or leaving).

**(C) Leave as is.**
Pro: no wrong-digit risk added; most de numbers already digits. Con: inconsistency
across takes stays; en leaves more words (spelled cardinals before units, ordinals in dates).

Leaning (not a decision): (B), narrow and context-gated, with a test corpus of the
measured strings; (A) only if coverage needs exceed what a small rule set can hold.
