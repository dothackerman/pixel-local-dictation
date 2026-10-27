# ADR-027 — Editable dictionary revisions beside the accepted baseline

2026-10-27 · Implementation for #31; owner smoke test pending.

Settings owns dictionary management. Each completed add/edit/delete saves independently;
there is no whole-page draft or recording-panel dictionary control. The accepted prototype
uses a searchable list, one-field editor, repeated Add without closing the keyboard, and
immediate Delete with Undo. A launcher entry opens Settings directly.

Store user edits as private, immutable dictionary revisions, separately from the provisioned
Candidate A dictionary and frozen experimental evidence. Prepare and verify a replacement
runtime before atomically publishing the saved revision. If preparation or saving fails,
keep the previous saved revision/runtime. Existing takes retain their runtime until capture
and queued native work finish; subsequent takes use the replacement, retained across takes.
Receipts distinguish edited dictionaries from tested Candidate A and bind their own hashes;
model, native-runtime and original-artifact checks remain enforced. Edited nonempty revisions
lease one verified loaded model and supply their own stream dictionary, with no default terms
to reintroduce deleted entries. Reusing that loaded model retains its acceptance check;
each revision still verifies its own bytes. This avoids reloading 2.4 GB for every edit.
Valid user input does
not imply measured recognition accuracy for that input.

The owner confirmed that an empty dictionary means no vocabulary help, with the same
FP32 model, beam search and post-processing. This supersedes #31's historical-INT8
empty-dictionary criterion for deliberate Settings edits. An intentionally empty dictionary
stays empty on restart. The original provisioned baseline and historical control remain
available under their original identities for experimental replay.

Use stream dictionaries from normal startup too: the first edit and clearing all entries
reuse the model already loaded for dictation. The native stream API adds constructor terms
to stream terms, so the recognizer must have no constructor dictionary; otherwise deleting
an entry would silently retain it. Receipts explicitly identify per-stream binding and derive
a new configuration hash, retaining the tested configuration hash separately. Retained DE/EN
output parity is checked; no broader accuracy claim follows. No import/export, language
selector, weights or new recognition model is introduced.
