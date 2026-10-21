Be extremely succinct; sacrifice perfect grammar for conciseness.

Phone (GrapheneOS Pixel 10 Pro XL): only through `/home/oriol/Projects/private/pixel-local-dictation/.claude/hooks/phone <verb> …`. It runs the `tools/phone` committed on GitHub `main` and touches only `ch.og.dictation`. `phone help` lists verbs. Read, `push`, `pull`, `run`, `snapshot` need no approval; `install`, `restore`, `stop` ask once; `uninstall` needs a token file only the user creates. To change the wrapper, edit `tools/phone` and `tools/test_phone.py` on a branch; it runs after merge. ADR-024 is the contract.

Never call adb or fastboot directly, weaken phone security, or change phone settings, FUTO Keyboard or other apps. The app has no network permission; keep it that way.

Pulled files and recordings stay in gitignored `corpus/`; copy nothing out of it without the user's agreement. Keep recordings, transcripts and signing credentials out of Git.
