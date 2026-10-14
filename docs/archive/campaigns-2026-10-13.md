# ARCHIVED — Experiment nights 1–3

**Execution: completed October 10–13. Outcome: development findings and resource failures; no deployment winner.** The old commands, windows and ledger-overwrite procedure are retired. The successor is [the allocation experiment](../experiments/current.md); [the shared protocol](../experiments/protocol.md#recording-policy) owns the still-binding recording trigger.

| Campaign | Question and decision record | Evidence |
|---|---|---|
| Night one | Closed knob search; no kept variant. [ADR-004](../decisions.md#adr-004--experiment-loop-with-a-closed-variant-schema-and-the-phone-as-second-judge) | `corpus/lab/handback-2026-10-10.md` |
| Night two | Range-finding, dictionary mechanism, Qwen and Cohere. [ADR-005](../decisions.md#adr-005--schema-2-range-finding-a-dictionary-as-mechanism-test-and-two-new-model-families) | `corpus/lab/handback-2026-10-11.md`; [failure diagnosis](../research/loop-investigation-2026-10-12.md) |
| Night three | FP32 dictionary beam widths 4/6/8 with repeat phone waits, score neighbours, single-instance Cohere. [ADR-007](../decisions.md#adr-007--night-three-the-full-precision-dictionary-family-and-single-instance-cohere-go-to-the-phone) | `corpus/lab/handback-2026-10-13.md`; [evaluation audit](../research/evaluation-audit-2026-10-14.md) and [runtime findings](../research/runtime-options-2026-10-14.md) |

For reproducing an old verdict, use its report, variant, evaluator version and source revision. [Evaluator revision 3](../../tools/evaluate.py) owns the historical quality/resource rules; [ADR-006](../decisions.md#adr-006--validate-qwen-input-budgets-and-enforce-complete-phone-evidence) explains its memory and phone-text corrections. Laptop guard/silence evidence did not become phone evidence. A discarded resource result did not establish poor accuracy.

The night-three laptop score-neighbour evidence is useful for the recording-policy trade-off: FP32 beam 8 at score 1.75 recovered nine terms with zero guard insertions; score 2.0 recovered thirteen with one insertion; score 2.25 recovered fourteen with four insertions and failed stage 1. No result justified relaxing the trigger. Its authoritative text is now [the recording policy](../experiments/protocol.md#recording-policy).

The complete retired briefs remain in Git at `0cc6dc8:docs/autoresearch.md` and `0cc6dc8:docs/plan.md` for historical reconstruction. They grant no current execution authority.
