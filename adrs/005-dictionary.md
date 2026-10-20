# ADR-005 — Dictionary mechanism and recording trigger

2026-10-11 · Mechanism approved; dictionary UX undecided.

Test vocabulary through decoder hotwords, not lexical rewriting.
The 43-name script dictionary tests mechanism, not unseen-name recognition.
Non-term errors/guards compare with an otherwise identical no-dictionary twin; terms compare with baseline.
VAD-gate no-speech chunks; gated silence does not establish bare-model safety.
Qwen/Cohere joined prepared families.
Mechanism evaluator: ≥5 recovered terms; ≤1 guard dictionary insertion.
Recording trigger: ≥10 recovered terms, zero guard dictionary insertions, guard WER ≤experiment 0 +1 percentage point, historical phone stage-2 acceptance.
That stage is not allocation stage 2; the complete trigger requires manual checking.
Score 1.75 recovered nine terms/zero insertions; 2.0 thirteen/one; 2.25 fourteen/four.
No result justified relaxing the trigger; allocation cannot reopen it.
Four reserved scripts stay outside the repo; the user records/reviews only after authorized validation.
Earlier ~two-point sample-expansion agreement remains unreconciled; neither policy authorizes collection now.
Evidence: private `corpus/retained/evidence.json` indexes retained reports and original run identities.
Revisit policy only with explicit approval; measure mechanism before building dictionary UI.
