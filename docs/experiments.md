# Accuracy experiment ideas

Status: proposals collected on 2026-10-10 for discussion with Oriol. Nothing here is decided, scheduled or tested unless a line says so. The current state lives in [plan](plan.md), measured numbers in [results](results.md), requirements in [requirements](requirements.md).

## Why accuracy, and what the budget is

The first measurement run showed the wait after Stop at about 1 s against FUTO's 80–100 s, and accuracy level with FUTO in both languages. Decoding during the recording makes the wait depend only on the last chunk, and the current model needs about 6 % of real time on the phone. A candidate may therefore be roughly ten times slower before the user notices; that reserve can be spent on accuracy. Unverified: whether a much larger model fits in memory, and what it does to heat and battery.

## How candidates can be tested without Oriol

Ten takes of his voice are saved with reference scripts (`corpus/`, not in Git): D6 ×2, E6 ×2, de-1 to de-3, en-1 to en-3, with FUTO's output for the six new ones. `tools/replay.py` decodes them on the laptop with any sherpa-onnx transducer model directory, `tools/compare.py` and `tools/score.py` score every variant against the scripts. A finalist then needs one phone run for speed and memory (`ReplayTest`, phone plugged in). Limits: ten read-aloud takes of fictional text, about 1,200 words; differences under about 2 points are noise; tuning on these takes and judging on the same takes overstates gains.

## Ideas

| Idea | What it might fix | Known so far | Open questions |
|---|---|---|---|
| Larger general model: Whisper large-v3-turbo | Unusual names; FUTO's small Whisper got "Cem Yilmaz" and "Winterthur" where Parakeet did not | Detects the language itself; sherpa-onnx has a Whisper path | Phone speed and memory; Whisper's known tendency to invent text on silence; accuracy on these takes |
| NVIDIA Canary 1B v2 | Overall accuracy | Needs the language as input | Only usable with in-app language detection (allowed by the 2026-10-10 clarification of R2); sherpa-onnx support and size to be checked |
| Moonshine streaming models (roadmap milestone 4) | Latency and possibly accuracy per language | Models are per language | Same detection requirement; German quality unproven |
| In-app language detection | Unlocks the two rows above | Nothing selected | Detector choice, delay, error rate on short takes, behaviour when wrong |
| Modified beam search with the current model | Small general gain | sherpa-onnx offers it for transducers | Whether it works with this model type; speed cost |
| Cutting rules | Seam errors, context | Tested: contiguous chunks of at least 20 s cut at pauses took English from 6.9 % to 4.0 % on the six new takes; whole-take decoding was no better and once dropped the last sentence | Longer chunks, overlapping chunks with merged text, cut position inside the pause |
| Hotwords and a personal dictionary | Names | Requested by Oriol as a later feature, then deferred by him on 2026-10-10 | Works with this model? Gain on names, cost in speed, false insertions |
| Quantisation variants | Size and memory, not accuracy | Tested: full precision scores the same as the shipped MatMul-only INT8 encoder; the published INT8 files are worse | Finer per-layer schemes to shrink the 880 MB encoder; what accelerators need |
| Other runtimes and Tensor acceleration (roadmap milestone 5) | Speed, heat, battery | Nothing tested | Only worth it if a slower, more accurate model is chosen |
| Warm-up decode when recording starts | The roughly 0.9 s penalty on the first decode after an idle pause | Observed in the app log, cause unknown | Whether a dummy decode removes it; battery cost |
| Loading the model from a file instead of assets | Peak memory at the 2 GB gate | The library offers it | Doubles storage use; effect on peak unmeasured |

## What would need Oriol again

A verdict on R4 for a new candidate needs fresh FUTO takes only if the scripts change; the existing FUTO texts stay valid for the six new scripts. Real-vocabulary accuracy, short takes and noisy surroundings are not covered by the saved audio and would need new recordings. Oriol has said he does not want to repeat a recording session, so any such request should be small and justified by a result.
