Be extremely succinct; sacrifice perfect grammar for conciseness.

Phone (GrapheneOS Pixel 10 Pro XL): only through `/home/oriol/Projects/private/pixel-local-dictation/.claude/hooks/phone <verb> …`. It runs the user-installed copy of `tools/phone` and touches only `ch.og.dictation`. `phone help` lists verbs. Call it alone on its command line, no pipe or redirect (otherwise it runs sandboxed without adb); the same JSON is in `corpus/lab/last.json`. Run `phone check APP.apk TEST.apk` right after building, before any phone step. Pull single files, not `files/lab` (the model is 2.4 GB); `push` sends only what changed. Read, `check`, `push`, `pull`, `run`, `snapshot` need no approval; `install`, `restore`, `stop` ask once; `uninstall` needs a token file only the user creates. To change the wrapper, edit `tools/phone` and `tools/test_phone.py` on the branch and ask the user to run `bash tools/gate/apply.sh`. ADR-024 is the contract.

Never call adb or fastboot directly, weaken phone security, or change phone settings, FUTO Keyboard or other apps. The app has no network permission; keep it that way.

Pulled files and recordings stay in gitignored `corpus/`; copy nothing out of it without the user's agreement. Keep recordings, transcripts and signing credentials out of Git.
