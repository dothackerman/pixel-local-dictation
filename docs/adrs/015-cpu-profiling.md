# ADR-015 — Encoder opportunity, timing uncertainty

2026-10-20 · Completed authorized profiling; retain current default.

Eight phone processes: Parakeet FP32 beam8/dictionary2.0 and INT8 Cohere/automatic LID; U/P/P/U each, reversed second-pair case order.
Ten fixed cases/382.065 s per process, four threads; outputs/routing/coverage/thermal/memory checks passed.
Both setups failed timing comparability: Parakeet 6/10 and 7/10 cases; Cohere 3/10 and 1/10.
Bounds were 10% recognition-wall or 100 ms Stop change; do not predict ordinary execution from intrusive profiles.
Encoder spans: Parakeet ~43–47%, Cohere ~44–52% of worker-service wall, excluding capture idle.
Parakeet decoder ~22–23%, joiner ~20%; Cohere decoder ~32–42%. Remainder is not isolated native-search time.
These are wall-span shares, not CPU utilization or fractions of user Stop wait.
Unprofiled Stop ranges: Parakeet 0.543–7.069 s; Cohere 0.013–6.035 s.
DE/EN errors: Parakeet 41/374 and 14/367; Cohere 26/374 and 115/367; this prefix suite differs from historical 584/632-word data.
Two English Cohere prefixes contributed 90/115 errors despite correct routing; cause unresolved.
Cohere first chunk closed ~21.7 s after capture, then LID cost 0.84–0.97 s; earlier routing/opening replay is a separate hypothesis.
Thermal severity stayed zero; Cohere high-water 4,421,752–4,450,200 kB exceeds the historical gate.
Conditional FIFO simulations are not GPU/TPU or unprofiled forecasts; faster kernels need not reduce Stop wait.
Original APP/TEST and 11 lab files restored/verified October 20 at 08:18:41 Europe/Zurich; owner/journal cleared.
18/18 campaign phone slots consumed including ten earlier functional probes; campaign closed.
Evidence: `corpus/lab/campaign-20261019/profile-execution-20261020/corrected-combined-analysis.json` and companion summary.
Revisit one bounded encoder experiment; preserve timing validity, language-specific quality and full-path costs.
