# ADR-026 — Accuracy over the shipped default; dictionary first; WebGPU stopped

2026-10-25 · Accepted by the user. Supersedes ADR-013 "Next decisions" 1–3 and README R4 (2026-10-09 wording).
Planned work for this decision lives in GitHub: spec #11 and its tickets. No plan document exists in the repo.

## Current delivery and accepted startup limits — 2026-10-27

Candidate A remains the installed provisional baseline, with its exact accepted normal
configuration, dictionary, model and runtime identities preserved. The final installation
restored the retained `0efa5b2` APK pair after rejecting the scheduling experiment;
rollback commit `1b2a04d` has the same tracked tree as `0efa5b2`.
App SHA-256: `a240610540469f27335a11f8251bc2dedf7bbc91a00feae6e56e0d7fa9efe8ad`.
Test SHA-256: `d6ebad1d6c1a0d618d47a1963033181a7bdaebe5d21fe854a52a553db11cc11a`.
Installation receipt: `corpus/lab/runs/20261027T125120-install-964943/meta.json`.
Earlier installation identities and verdicts below describe their dated stages.

The owner accepts the current observed handoff of roughly **1.3 s cold / 0.25 s warm**
for now. This is a temporary acceptance of startup cost, not an immediate-panel claim
or an R8 fix. Later true-cold timing separates process startup from service work;
most of the measured cold interval preceded service entry. Capture started before panel
draw, and model readiness did not gate panel drawing or microphone start. Video timing,
app callbacks and physical pixels remain distinct endpoints; these few observations
establish neither typical nor tail startup latency.

Benchmark candidate `70df5d7` retained eager runtime/config setup and moved explicit model
warm-up to a main-queue task posted from first draw. Receipts confirmed the scheduling
change, but the cold/warm visual comparison showed no gain. One pair does not establish
a causal regression. Revert `1b2a04d` restores eager warm-up; no replacement workaround
or panel redesign was adopted. Benchmark-only geometry, editor-animation and startup
diagnostics remain available; no personal recordings, transcripts or editor identity
are tracked.

The original #14 ten-take/key-timing acceptance remains partial: nine original complete
app-side traces exist, without the original external key timing or explicit live Stop
boundaries. Later probes do not retroactively complete that protocol. #17 is deferred
and nonblocking under the owner's acceptance above; no startup fix is claimed. Signal
8.32.3's observed cached-height jump is separate, with no adopted workaround or panel
redesign. Dictionary editing and other UI/UX remain deferred.

Delivery is tracked by [PR #29](https://github.com/dothackerman/pixel-local-dictation/pull/29),
ready for final review; #11/#28 close through that PR. Issues #23–27 remain separate
follow-ups. The frozen #15 rejection, owner-selected A trade-off, meaning-review limits,
recovery evidence and negative Candidate B result remain unchanged. Revisit startup
when the owner prioritizes it or new evidence justifies a specific change.

## Context

#1 asked whether the FP32 encoder on WebGPU cuts the Stop wait at equal text. ADR-025 (one take per language,
one run per setup): exact text, every chunk ~3.5× slower; the full suite and live gates were not run.
The only measured accuracy gain is supplied-vocabulary biasing (ADR-007: laptop DE/EN 45/24 greedy, 42/26 beam 8,
35/11 beam 8 + dictionary). Re-scored retained text (decoded before the 2026-10-12 chunker fix, six scripts, feed
640): listed terms are 16 of 39 German and 8 of 17 English errors; "4.80" against "4,80" style differences were
counted as errors. October 9–22 cost about 75 agent hours: ~25% wrapper and sandbox, ~17% accelerators ending
slower, ~15% docs; 25 ADRs in 13 days.

## Decision

1. R4 becomes: fewer wrong words than the shipped default, in each language, at a Stop wait the user accepts.
   Comparison with FUTO stops. The speed claim against FUTO rests on the 2026-10-10 non-matched takes (README).
2. A median Stop wait of 2–3 s is acceptable for a clear accuracy gain; the UI may show a waiting state.
3. R8 (new): the panel and recording start at once; model loading never delays them. Measure before fixing.
4. Scoring: number, spacing and hyphen forms count as equal; word accuracy is the target (extends R7).
5. The next spec is dictionary biasing (#11). Laptop inference on `corpus/` and phone replay visits are authorized
   for it; each visit still prompts for install and restore (ADR-024). One supervised live check per ADR-017
   precedes adoption. Candidate A (existing FP32 beam-8 + dictionary variant) has no phone Stop-wait number yet;
   ADR-025's 2.0–2.5 s was beam 4 without a dictionary.
6. Process: no frozen comparison plan for laptop screening; phone visits keep a snapshot and `meta.json` receipts;
   a frozen plan is required only for the adoption-deciding comparison (ADR-017 as amended today). One ADR per
   decision, not per step. Gates, thresholds and schedules live only in tickets.
7. "Dictionary" is the term (GLOSSARY); "word list", "lexicon" and "hotwords" are avoided in prose.

## Parked and rejected

Parked: WebGPU, reopen only when encoder-only time per chunk is located (ADR-025 limits); per-channel INT8
quantization (ADR-013) if candidate A needs less memory. Rejected now: a laptop copy of FUTO with whisper.cpp
(its fork cannot be reproduced), FLEURS (download, proxy), LLM post-correction (Stop wait, R5), chunk-overlap
decoding (8 of 56 errors near cuts on the shipped chunker, below its own firing rule).

Review when #11 closes with an adoption verdict, or when the R8 measurement shows the panel delay is not startup work.

## Candidate A phone result — 2026-10-26

The authorized #15 replay rejects FP32 beam 8 with dictionary score 1.5 under the ticket's existing
2.5 s median Stop-wait limit. All 120 frozen measurements validated: 30 default/default pairs, then
30 default/candidate pairs, three rounds with alternating arms. All control pairs produced identical
text; the largest absolute paired Stop difference was 205.83 ms.

Candidate median Stop wait was **3153.69 ms**, maximum **5456.04 ms**; paired slowdown median
**2019.61 ms**, maximum **3880.28 ms**. The endpoint is simulated editor acceptance, not observed
live insertion. Across three repeats, German errors fell 108 → 93 and English 66 → 45, equivalent
to the development-suite totals 36 → 31 and 22 → 15 per round. These remain known-script results.
The earlier held-script German harm (+1/0/+2 across feeds) remains a separate limitation.

| Resource | Default | Candidate A |
|---|---:|---:|
| Median cold load (ms) | 3746.5 | 4084.0 |
| Maximum cold load (ms) | 5281 | 4146 |
| Peak memory (KiB) | 1630812 | 3177308 |
| Maximum thermal status | 0 | 0 |

The default resource column includes both phases' default arms. No meaning-changing false-accept
verdict or supervised live result is claimed; the latency failure alone rejects the candidate.
No dictionary editor or daily-default change advances. Candidate B already stopped without a
passing alpha (#16); #18/#19 could not advance. Startup measurement #14 and its evidence-dependent
fix #17 remain open.

Private evidence: `corpus/lab/issue11/visit-a-s15/plan.json`,
`report-c8612eb71eb64c7188431bf00a825308.json` and matching CSV, plus `raw/` and `receipts/`.
Snapshot `issue11-20261026-resume` was verified. Install and restore were approved; restoration
initially waited for the owner's final startup takes. Recovery is recorded below. #11 is incomplete.

This records the measured rejection under existing authorization, not a new owner-approved
adoption decision. Revisit Candidate A only with evidence addressing its Stop wait and held-script
harm; do not relax the completed experiment's gate retrospectively.

## Greedy boosting rerun result — 2026-10-27

This section concerns candidate B only. Candidate A's status is recorded above and is under separate
deliberation; nothing here changes it.

The #16 screen tested a tree with canonical token paths only; its miss log showed 26 distinct listed-term
paths the model emits that the tree could not boost, and its guard gate was absolute although the stock
output already inserts the listed function word "and" once. #22 reran the screen with all 26 emitted
paths added (0 misses after), generic words removed from the listed set, and a gate on false accepts
introduced against the zero-bonus control. Zero-bonus output stayed byte-equal to stock; alpha-0 totals
equal #16's. Alphas 0, 0.5, 0.75, 1, 1.5, 2; 3 skipped because 2 introduced 12 false accepts.

| alpha | EN listed Δ (640/512/800) | DE listed Δ | DE non-listed Δ | Introduced guard false accepts |
|---|---|---|---|---|
| 0.75 | 0 / −2 / 0 | −2 / −3 / −4 | 0 | 0 |
| 1 | −2 / −2 / −3 | −2 / −3 / −4 | +2 / +2 / +2 | 1 |
| 1.5 | −5 / −6 / −5 | −3 / −5 / −2 | +2 / +2 / +3 | 6 |
| 2 | −5 / −7 / −5 | 0 / −3 / −2 | +3 / +3 / +3 | 12 |

No alpha passes in either language. English reaches the error thresholds only at strengths that insert
real dictionary names into public speech; German gains below the harm threshold stay under 3 errors on
at least one feed. **Greedy dictionary boosting on the shipped INT8 engine is stopped for #11.** The
sherpa-onnx patch (#18), its phone visit (#19) and the upstream pull request (#20) cannot start.
Carrying decoder state across chunks was rejected in #16 (English errors rose from 18 to about 135 even
without a bonus) and is not revisited.

Evidence: `corpus/lab/issue11/greedy-screen-rerun/` (manifest, dictionary, per-alpha rows, outcomes,
verdict); tools on `codex/issue-11-dictionary` from commit 94600aa. Scorer revision unchanged
(dictionary-words-v2); strict listed counts are reported beside the as-scored ones.

## Startup observations — 2026-10-27

The owner reported completing ten short takes; nine complete startup traces were captured
(one cold, eight warm). Do not infer a tenth measurement. One process survived across all nine
service instances. These timings start at the app's panel request, not the FUTO microphone tap;
panel draw is a callback, not physical display timing.

| App panel request → | Cold (ms) | Warm median (ms) |
|---|---:|---:|
| Panel draw | 125.156 | 47.179 |
| Recording started | 98.726 | 27.246 |
| First audio | 291.021 | 230.089 |

The cold model became ready 2561.693 ms after the request, after recording and panel draw.
Warm panel draw ranged 32.213–54.471 ms; recording start 18.942–31.179 ms. The measured model
load did not hold recording or drawing until completion. Time before the app callback remains
unknown; no end-to-end startup cause or speculative #17 fix follows from this evidence.

The startup file and retained app logs contain no explicit Stop/commit boundaries. Live Stop
wait cannot be recovered; the assistant's earlier assurance that these takes would measure it
was incorrect. Decode duration and take end are not substitutes. #14 remains partial: nine
app-side rows and process continuity are known; a tenth row and external key timing are absent.

Private raw timing file, app logs, per-take CSV and analysis:
`corpus/lab/issue11/startup-20261027/`. These startup receipts contain no speech or transcript.

Restoration completed after collection. Independent snapshot `issue11-20261027-restored` has
byte-identical app APK, test APK and complete data archive to `issue11-20261026-resume`; grants
and observed IME state also match. Evidence: `startup-20261027/recovery-verification.json` and
the original restore receipt. The benchmark visit is over; #15 is complete with a reject verdict.
#14 remains partial, #17 awaits a measured cause, and #18/#19 could not advance after the negative
greedy screen. These startup observations alone establish no startup fix or dictionary adoption.

## Owner selects Candidate A as the provisional baseline — 2026-10-27

After reviewing the accuracy and Stop-wait trade-off and #22's final stop for Candidate B,
the owner selected Candidate A as the current winner and directed that it become the app's
new baseline for now. The selected configuration is Parakeet v3 FP32, beam 8, dictionary
score 1.5. The owner accepts the measured median Stop wait of 3153.69 ms and observed maximum
5456.04 ms for the known-script gain (German errors 36 → 31; English 22 → 15).

This owner decision supersedes rejection of A solely for exceeding the earlier 2500 ms
criterion. The frozen comparison, original gate and report remain historical evidence;
no measurement is changed to a pass. German held-script harm +1/0/+2 and the absence
of a supervised exact-candidate live check remain explicit limits. The retained-output
meaning review is recorded below. Selection does not assert every output preserves meaning.

At selection, the phone had been restored to INT8 after the visit. The subsequent
normal-path implementation and installation are recorded below; promotion is tracked in #28. UI/UX, including dictionary editing, is deferred to later work at the owner's request.
Existing safety behavior and no-network constraints remain. #14/#17 retain their unresolved
startup evidence; selecting A does not complete them. Candidate B's stop after #22 is unchanged.

The owner confirmed that B-only follow-ups #18 (native patch), #19 (phone integration) and
#20 (upstream contribution) should close. They are closed as **not planned**, not completed
implementations. The completed negative experiments #16/#22 remain closed as completed.

Future comparisons must name their actual control. Preserve the old INT8 baseline and all
frozen results under their original identities; do not silently relabel them as Candidate A.
Review this provisional choice when daily-use evidence or a new candidate changes the trade-off.


## Provisional baseline integration — 2026-10-27

#28 implements the normal Candidate A path using the packaged acceptance record
`app/src/main/res/raw/candidate_a.json`. The record binds the exact #15 plan,
configuration, dictionary, model/VAD artifacts and native runtime. The owner selected A
for its overall error/Stop-wait trade-off and directed installation. Frozen #15 plans/reports
retain their original reject verdict; this selection does not establish perfect meaning
preservation or waive lifecycle, thermal or other safety requirements.

Normal release selection uses provisioned files independently of benchmark live selection.
Missing or blank dictionary returns the complete historical INT8 pipeline from APK assets;
a nonempty changed dictionary or model/runtime fails closed. This preserves #11's empty
no-change behavior against the formerly shipped default. Capture, pause cuts, Stop, waiting
and cancellation remain on their existing shared runtime. The tested A silent-chunk filter
remains enabled; it is disabled in the historical control as before.

Retained-output meaning review is complete with the limitations below. The earlier held-script
German harm +1/0/+2 remains disclosed. Installation and the completed supervised live
check are recorded below. UI/UX, dictionary editing and the unresolved #14/#17 startup
work remain deferred.

The retained-output review found no observed false acceptance of specific listed names/content
terms in exact A on the known-script, held-script and public guard outputs. It also found
negation and quantity/unit transcription regressions versus the phone INT8 control. A matching
no-dictionary FP32 host control already contains some errors, so dictionary boosting alone is
not an established cause. Zero specific listed-term false accepts does not mean zero
meaning-changing ASR errors. The earlier treatment of these broader transcription errors as
a separate installation blocker overstated that specific review requirement. The owner
selected A for the overall error/Stop-wait trade-off; these concrete limitations remain
disclosed, without a blanket safety waiver or perfect-meaning claim. Private examples and
review limits remain only in `corpus/lab/issue11/promotion-a28/meaning-review.md`.


## Candidate A installed — 2026-10-27

Historical installation stage; the current APK pair is identified above.

The checked benchmark APKs from integration commit `a36f362` were installed under the owner's
direction, then the verified normal A artifacts were provisioned without a benchmark override.
The corrected build from `cbead7a` was installed at this stage. App SHA256:
`ace6fa094259b15ce4621f7ee3ac943afcbf9492c6b90b72bcf10b0fe7da8bd1`; test SHA256:
`ca7c76e19a503fcfbb7da2c578b7decb692d0e8f5ef1f27b959625bbad9612e3`.

Private initial installation receipt: `corpus/lab/runs/20261027T080126-install-494811/meta.json`;
normal-artifact push receipt: `corpus/lab/runs/20261027T080356-push-4f81f0/meta.json`.
Corrected installation: `corpus/lab/runs/20261027T081141-install-0ddfb5/meta.json`.
Recovery snapshot `issue11-20261027-before-a28` preserves the pre-promotion installation.
A remained installed after this promotion; no restoration is claimed for it.

The first normal-path check failed before model inference: Android's JSON encoder escaped
slashes in the native-library inventory, changing its digest despite identical library bytes.
Private failure trace: `corpus/lab/runs/20261027T080413-run-6cea46/instrumentation.txt`.
The correction uses the frozen Python inventory encoding on both platforms; manifest and
native hashes remain unchanged. The rebuilt normal-path phone test passed, including the
independent canonical-encoding regression. One retained German and one retained English take
matched the exact frozen A output hashes, with exactly one simulated insertion each.

Private run receipt: `corpus/lab/runs/20261027T081239-run-007ac9/meta.json`; result:
`corpus/lab/issue11/promotion-a28/normal-parity/files/lab/normal-baseline-result.json`.
Its verified identity names the installed APK above and the original A configuration, model,
dictionary and runtime hashes. This focused replay establishes normal-path parity; live
evidence is recorded separately below.


## Supervised live check and promotion complete — 2026-10-27

The owner confirmed two Stop takes inserted correctly, the third cancelled take inserted
nothing, and every take returned to the normal keyboard; the flow behaved as expected.
The two completed receipts bind verified Candidate A and the installed APK above, with
commit and return accepted. Stop → commit measured **6082.646 ms** and **8280.698 ms**.
These fresh takes are not paired benchmark measurements and do not replace #15's frozen
comparison or original rejection. No audio or recognized text was retained.

The cancelled receipt reports `CANCELLED`, no accepted commit, incomplete identity
verification and no build hash; it does not establish a verified cancellation identity
or a return-result boolean. The owner observed no insertion and normal keyboard return;
the protected phone wrapper independently records the installed APK identity.

Private metadata: `corpus/lab/issue11/promotion-a28/live-check/files/lab/live-results.jsonl`;
summary: `corpus/lab/issue11/promotion-a28/live-check-summary.json`.
Promotion #28 completed; A remained the installed provisional baseline at this stage. Historical
INT8 evidence, the recovery snapshot and the disclosed quality limits remain unchanged.
This does not complete the unresolved #14/#17 startup work or add dictionary editing/UI work.


## Candidate A runtime retention correction — 2026-10-27

The earlier promotion's normal-app integration had a runtime retention defect introduced
by `f81a8eb`. Kotlin bound the trailing `.also { runtime = it }` to the nested fallback/live
conditional, excluding the new normal Candidate A arm. A compiled minimal reproduction
returned different runtime objects for successive normal requests; both other arms reused
the object. Restoring the former two-arm conditional or assigning after the entire
conditional retained the object on every path.

Consequently, service warm-up and each normal panel request constructed separate runtimes
and baseline verification records. Each runtime loaded its own model and verified artifacts
on first recognition. The process-wide `modelReadyNs` remained set after service warm-up,
so `modelReadyAtPanelRequest=true` did not establish readiness of the take's actual runtime.
The cancelled receipt's unverified identity and null build hash are consistent with a fresh
baseline record; cancellation ordering is not established by that receipt alone.

Fix `a9a4b3e` assigns the created runtime outside all conditional arms. Its Android regression
checks the public `Engine.runtime` interface before/after `Engine.warmUp`, object identity on
repeated panel requests, and retained verified build identity. Host quality checks passed.
Corrected APK checks and installation passed. The installed app SHA-256 is
`a95a05cf7c3dce2672b1d0fa3b12d28184ed964132220f199357f72e59a9074c`; test APK SHA-256 is
`beb196742948d63e850e61755d7286b694b61c290871b1a940b9f7d2ae04752a`.
The retained pair is `corpus/lab/issue11/promotion-a28/build-a9a4b3e/app-benchmark.apk`
and `app-benchmark-androidTest.apk`. Installation receipt:
`corpus/lab/runs/20261027T082821-install-e82944/meta.json`.

All three `NormalBaselineTest` phone checks passed: canonical runtime fingerprint,
retained DE/EN output parity, and runtime reuse across warm-up/repeated panel requests.
Run receipt: `corpus/lab/runs/20261027T082928-run-7395a5/meta.json`.
Parity evidence: `corpus/lab/issue11/promotion-a28/runtime-fix-parity/files/lab/normal-baseline-result.json`.
This corrected A installation was retained. The owner subsequently reported that it was definitely faster,
estimating roughly 3–5 s. Two new completed live receipts measured Stop → commit at
**2253.113 ms** and **1757.637 ms**, with recording durations **10.294 s** and **7.764 s**.
Both bind verified A and the corrected installed APK, with commit and keyboard return
accepted, in the same process. Capture drain was **90.669/80.534 ms**; final → commit was
**1.731/0.705 ms**. Initial model readiness occurred about **4.946 s** after service creation,
before the first Stop; the second panel reported ready and used the retained runtime.
Private evidence: `corpus/lab/issue11/promotion-a28/runtime-fix-live-final/files/lab/live-results.jsonl`
and `startup.jsonl` in the same directory.

The original **6082.646 ms** and **8280.698 ms** live measurements remain immutable evidence
of the defective integration, not representative measurements of intended A with runtime
reuse. Historical frozen replay median **3.154 s** is not an estimate of typical live Stop
wait. The new live takes support repeated verification/model loading as the main explanation
for the earlier waits; retaining the runtime eliminates that extra load. The recordings
differ, so they do not establish a controlled numerical speedup or typical live Stop wait.
The original gate rejection, owner selection, model/configuration/dictionary/runtime
identities, meaning-review limits and recovery snapshot remain unchanged.
