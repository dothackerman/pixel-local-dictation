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
The first transition from the frozen baseline, or back from empty, prepares a new model.
Valid user input does
not imply measured recognition accuracy for that input.

Preserve #31's existing empty-dictionary contract: an explicit saved empty revision selects
the complete historical INT8 pipeline. Explain this before deleting the final entry; never
reseed an intentionally empty dictionary on restart. The alternative of keeping FP32 without
dictionary help was discussed but not approved. No import/export, language selector, weights
or new recognition model is introduced.
