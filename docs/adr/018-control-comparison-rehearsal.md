# ADR-018 — Fabricated invalid-control rehearsal

2026-10-20 · Host-only outcome for issue #2. **All values and receipts fabricated.**
No inference, recordings, phone commands or real accelerator measurements.

## Decision

A required CPU control exceeding either resource limit makes the whole comparison
inconclusive, including when the candidate also fails. Candidate-only breaches
reject the candidate only after every required control is valid. Keep every phase's
breach, candidate quality scores and available timing observations.
Missing receipts remain unresolved execution; reservation cannot silently retry.
Incomplete or incompatible rows remain labelled unvalidated observations.
Historical evaluator rules are unchanged.

## Fabricated aggregate outcome

Real `lab.py prepare/check/freeze/reserve/record/compare` commands exercised four
fabricated attempts in order A-0, C-0, C-1, A-1. All four receipts retained; zero
retries. Decision: **inconclusive**, blocked by the later repeated control A-1.
Three resource observations breach the frozen 3,500,000 KB / thermal NONE limits:
C-0 before-load: 4,000,000 KB, thermal 1; A-1 before-load: 4,000,000 KB, thermal 1;
A-1 after-load: 4,500,000 KB, thermal 0. Every other fabricated phase is 200 KB / 0.
A Stop median/max: 2,000 ms; C: 1,000 ms for each language. Apparent savings:
1,000 ms / 50%, paired savings 1,000 ms twice, repeat spread 0 ms. These are
fabricated observations, never evidence of a speed gain. Cold load: 100 ms each;
first preview: 500 ms every case; only C-0/de supplies an encoder time (123 ms).
German normalized errors: A 0/4 words, C 1/4 (25%); English: A 0/4, C 0/4.
One German output differs; candidate quality failure is retained alongside the
control blocker. No live editor/keyboard claim; replay receipts are simulated.

The CLI regressions additionally cover control-only, candidate-only and simultaneous
breaches in conversion-parity and repeated GPU-wait plans; missing, failed,
duplicate, changed and incompatible evidence; immutable reservation/recording.

## Evidence identities

Private **fabricated** inputs, preparation, plan, reservations, receipts and report
retained in `corpus/lab/fabricated-control-issue-2/`. No personal data included.
Frozen plan binding SHA-256: `f51eed6e4a74d136cdda8b28ca3df30604ab2c535d36610ffc22b2903dc5341b`.
Report `report-82ca5f39a877495eafe5d05831fcd2bf.json` SHA-256: `d84123b7f1d9ca24202a9587c3e01efa475dd2a1e57537406dc73489b9da11f1`.
Aggregate `fabricated-aggregate.json` SHA-256: `379e22b1448c6f15e75bc4a6835cf6de49c42909770b4d46180fb603901ffa7e`.

Receipt SHA-256:

- A-0: `7ad4f61b442a1729bd90d55d0cc67d0a35f5930d41a817b53a14e81f1f4ee628`.
- C-0: `baaece94d6cdc17872362658d4d3f2f088d94e0d6cd09c0bd0b199d9f528794c`.
- C-1: `601defc685956eb996a6e11362b5b1f1786293dafd6370042fb2216ea9a34144`.
- A-1: `3abd0e0b2daf3de0459e6c61c8bb3780e915bdfe59af284d4e64322a1bdc0c33`.

Frozen evaluator SHA-256:

- experiment.py: `06bca7ef8365bf28b5d06928ba773294e72c347a679bd18a043b7c52babeaa7e`.
- evaluate_v4.py: `b70304093ef49b714402b0593c265364e2139acdf5d516dad6df6591da8609e8`.
- evaluate.py: `19944d042affd339b959bdbac2a266daebd41a027cf9e43460e20e95b9f8047c`.
- score.py: `591904d967c28b65e7e72b850b319a53f9aa509a1e5c240053cc19b357bf34f8`.
- measurement_identity.py: `7be84d8a7ce3241c24f21a3d4e220097a9d7277450d73a58779eed09571448cf`.

## Limits and next question

This establishes host decision/accounting behavior only. It establishes no speech
conversion correctness, device resources, GPU speed, or product winner.
Next: can the actual converted FP32 encoder preserve all frozen tensor outputs
against ONNX CPU? Resolve that host correctness question before phone tickets.
