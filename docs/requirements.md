# Requirements

Confirmed by Oriol on 2026-10-09. These are product constraints, independent of engine choice or implementation status.

| ID | Requirement | Verification |
|---|---|---|
| R1 | Keep FUTO Keyboard. Press its existing microphone and speak; no added per-recording choice, picker or confirmation. | Exercise the installed keyboard's actual microphone path. One-time installation and permission setup may be necessary. |
| R2 | Automatically recognize English OR German, one language per recording. | Interleave English/German sessions, including short ambiguous utterances; check wrong-language output and stale state. Mixed-language conversation is out of scope, not forbidden ordinary names/loanwords. |
| R3 | All speech processing stays on the phone. No audio leaves it; no cloud fallback. | Recognizer APK omits Internet permission; review merged manifest, dependencies and export paths. Test with airplane mode and Wi-Fi off after models are provisioned. |
| R4 | Improve speed AND accuracy against the installed Multilingual-244 setup in each language. | Per-language personal comparison under the roadmap's measurement protocol. Neither a publisher benchmark nor an aggregate score establishes success. |
| R5 | Preserve spoken meaning; transcribe, rather than translate or generatively rewrite. | Score names, numbers, omissions, invented words, capitalization and punctuation as well as ordinary word errors. |
| R6 | Preserve normal GrapheneOS security and a reversible return to the existing recognizer. | Verify without root/security weakening; exercise rollback with FUTO and its baseline model retained. |
| R7 | German text uses Swiss spelling: "ss" where Standard German writes "ß". Added by Oriol on 2026-10-10 after the first live takes. | Dictate German words with "ß" (weiss, Strasse, gross); no "ß" appears. Scoring treats "ß" and "ss" as equal. |

Clarification of R1/R2 by Oriol on 2026-10-10: the hard requirement is that the user never chooses a language before or during a recording. How the app achieves that is a design matter; models that must be told the language are acceptable if the app determines it by itself.

Privacy handling: bundle model files or import them locally. Any developer downloads happen separately from recognizer inference. Capture only during an explicit recording; release the microphone afterwards. Keep production audio in memory and discard it after transcription. Saving benchmark audio/transcripts or exporting them requires explicit user agreement; provide deletion. Include no audio/transcript telemetry, backup of recordings, or external handoff. Git must contain no personal recordings, transcripts or signing credentials.

Speaker identification (diarization) is unnecessary for this workflow. If introduced later, R3 still applies.

## Proposed performance targets—not approved numerical commitments

Aim for about 3× shorter waiting, warm short-message manual-stop-to-final usually below one second, and 10–20% fewer corrections **in each language**. Baseline measurements must establish whether these targets are realistic; changing them cannot redefine R4 away. Report failure or uncertainty explicitly.

Unresolved: Standard German versus Swiss-accented Standard German versus Swiss dialect, current app/OS versions, preferred stop behavior and realistic battery/heat limits. Preserve the observed workflow; establish these during phone validation, without assuming dialect support or adding a language selector.
