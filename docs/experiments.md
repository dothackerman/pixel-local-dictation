# Accuracy experiment ideas

Status: proposals collected on 2026-10-10 and, on the same day, turned into an unattended experiment loop ([ADR-004](decisions.md#adr-004--experiment-loop-with-a-closed-variant-schema-and-the-phone-as-second-judge), instructions in [autoresearch](autoresearch.md)). The table below says for each idea whether the loop can try it. The current state lives in [plan](plan.md), measured numbers in [results](results.md), requirements in [requirements](requirements.md).

## Why accuracy, and what the budget is

The first measurement run showed the wait after Stop at about 1 s against FUTO's 80–100 s, and accuracy level with FUTO in both languages. Decoding during the recording makes the wait depend only on the last chunk, and the current model needs about 6 % of real time on the phone. A candidate may therefore be roughly ten times slower before the user notices; that reserve can be spent on accuracy. Unverified: whether a much larger model fits in memory, and what it does to heat and battery.

## How candidates can be tested without Oriol

Ten takes of his voice are saved with reference scripts (`corpus/`, not in Git): D6 ×2, E6 ×2, de-1 to de-3, en-1 to en-3, with FUTO's output for the six new ones. `tools/replay.py` decodes them on the laptop with any sherpa-onnx transducer model directory, `tools/compare.py` and `tools/score.py` score every variant against the scripts. A finalist then needs one phone run for speed and memory (`ReplayTest`, phone plugged in). Limits: ten read-aloud takes of fictional text, about 1,200 words; differences under about 2 points are noise; tuning on these takes and judging on the same takes overstates gains.

## Ideas

| Idea | What it might fix | Known so far | In the loop? |
|---|---|---|---|
| Larger general model: Whisper large-v3-turbo | Unusual names; FUTO's small Whisper got "Cem Yilmaz" and "Winterthur" where Parakeet did not | Prepared as `variants/whisper-turbo.json`. Laptop real-time factor about 0.5, so outside the phone budget by construction; writes "Thank you." on silence; this runtime drops audio beyond 30 s, so it needs its own cutting | Yes, for the accuracy ranking |
| NVIDIA Canary | Overall accuracy | Canary 1B v2 is not runnable with sherpa-onnx 1.13.8; the 180M flash model is, prepared as `variants/canary-180m.json` with Whisper-tiny language identification on the first chunk | Yes (180M); 1B needs a runtime change, a setup round |
| Moonshine streaming models (roadmap milestone 4) | Latency and possibly accuracy per language | Models are per language; not prepared | No; setup round |
| In-app language detection | Unlocks Canary | Implemented as the `lid` step of the variant schema (`CanaryRecognizer`); its accuracy on the ten takes shows in the evaluator's `lid` column | Yes, with Canary |
| Modified beam search with the current model | Small general gain | Schema keys `decoding.method`, `max_active_paths`, `blank_penalty` | Yes |
| Cutting rules | Seam errors, context | Contiguous chunks at pauses are the baseline; the boundary alone moves the ten-take count by several words, so counts are averaged over three feed sizes. Schema keys under `cutting`; overlapping chunks are not in the schema | Yes, except overlap |
| Hotwords and a personal dictionary | Names | Excluded from the loop by Oriol on 2026-10-10: a list taken from the scripts would leak the corpus | No |
| Quantisation variants | Size and memory, not accuracy | Any model directory under `models/` can be a variant's `model` | Yes, for existing directories |
| Other runtimes and Tensor acceleration (roadmap milestone 5) | Speed, heat, battery | Out of scope for the loop (ADR-004); the accuracy-only ranking shows the prize | No |
| Warm-up decode when recording starts | The roughly 0.9 s penalty on the first decode after an idle pause | Observed in the app log, cause unknown | No; app change |
| Loading the model from a file instead of assets | Peak memory at the 2 GB gate | `LabTest` loads every variant from files; its peak memory for the baseline variant against the asset path's 2.05 GB is the measurement | Measured by the phone control row |

## What the loop needs from Oriol

See [autoresearch](autoresearch.md) and [plan](plan.md), "Next session": the hold-out move, the wrapper change, the benchmark build on the phone with USB debugging on for loop nights, and one recording session of the four hold-out scripts if the trigger fires.

## What would need Oriol again

A verdict on R4 for a new candidate needs fresh FUTO takes only if the scripts change; the existing FUTO texts stay valid for the six new scripts. Real-vocabulary accuracy, short takes and noisy surroundings are not covered by the saved audio and would need new recordings. Oriol has said he does not want to repeat a recording session, so any such request should be small and justified by a result.
