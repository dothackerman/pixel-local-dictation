Be extremely succinct; sacrifice perfect grammar for conciseness.

Docs: `docs/adr/` (decisions: append or supersede, never rewrite), `GLOSSARY.md` (terms), `docs/worktrees.md` (new worktree, Gradle). Planned work lives only in GitHub Issues; a plan, gate, schedule or checklist in a tracked file is a defect.

Issue tracker: GitHub Issues via `gh`; sub-issues and blocked-by relations, labels; PRs say `Closes #N`.

Phone (GrapheneOS Pixel 10 Pro XL): only through `/home/oriol/Projects/private/pixel-local-dictation/.claude/hooks/phone <verb> …`, from any checkout or worktree, one call at a time, alone on its command line (no pipe or redirect, else it runs sandboxed without adb); its JSON is also in the main checkout's `corpus/lab/last.json`. It touches only release `ch.og.dictation` and the lab app `ch.og.dictation.lab`, whose data is test data; `phone help` has verbs, steps, the one-time switch and the owner's work, which agents never do. Run `phone check APP.apk TEST.apk` right after building; an APK without a build marker is never installed or run. Pull single files, not `files/lab` (the model is 2.4 GB). `push DIR` deletes test output absent from DIR: pull receipts first; `--model` only to push the kept copy (`/home/oriol/Projects/private/pixel-local-dictation/corpus/lab/model`) or when the owner asked for a model change. `measure` only when the owner asked for the measurement. `install`, `restore`, `stop`, `uninstall` ask once; the rest needs no approval. To change the wrapper, edit `tools/phone` and `tools/test_phone.py` on the branch and ask the user to run `bash tools/gate/apply.sh` in the main checkout (it refuses a worktree). ADR-024, amended by ADR-028, is the contract.

Never call adb or fastboot directly, weaken phone security, or change phone settings, FUTO Keyboard or other apps. The app has no network permission; keep it that way.

Git: run `git …` as the whole command line (no `cd … &&`, no pipe); it then runs outside the sandbox, which otherwise pins `.git` read-only. Plain git only: no `-c`, `-C`, `--git-dir`, `--exec-path` or `GIT_*` variables.

Pulled files and recordings stay in gitignored `corpus/`; copy nothing out of it without the user's agreement. Keep recordings, transcripts and signing credentials out of Git.
