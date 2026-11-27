#!/usr/bin/env python3
"""Host check (ADR-029, slimmed by #100): only Storage writes the clipboard; nothing runs a shell.

Both survive the lab app's own package (#98): the system clipboard is one for all apps, and a shell in
the lab app's instrumentation can act on release (`pm clear ch.og.dictation`). Checked: app, lab-build
and device-test sources. Storage is `ch/og/dictation/storage/` in the app's source sets. A banned call
anywhere else needs a named exception; EXCEPTIONS is empty. An exception nothing needs fails too.

Also checked: app and test manifest carry the build marker entry the wrapper reads.

python3 tools/storage_guard.py   prints each problem; exit 1 when any exists.
"""
import re
import sys
from pathlib import Path

from kotlin_source import code

ROOT = Path(__file__).resolve().parents[1]
SOURCES = ('app/src/main', 'app/src/release', 'app/src/benchmark', 'app/src/androidTest')
STORAGE = tuple(f'{source}/kotlin/ch/og/dictation/storage/' for source in SOURCES[:3])
MANIFESTS = ('app/src/main/AndroidManifest.xml', 'app/src/androidTest/AndroidManifest.xml')
MARKER = re.compile(r'android:name="ch\.og\.dictation\.build"\s+android:value="\$\{buildMarker\}"')

RULES = {name: re.compile(pattern) for name, pattern in {
    'clipboard service': r'\bCLIPBOARD_SERVICE\b|\bClipboardManager\b',
    'process execution': r'\bProcessBuilder\b|\.\s*exec\s*\(',
    'test-automation shell': r'\bexecuteShellCommand(Rw|Rwe)?\b|\bUiDevice\b',
}.items()}

# path -> {banned kind: why it is still needed}. A new entry needs a reason that survives review.
EXCEPTIONS = {}


def problems(root=ROOT, exceptions=None):
    root = Path(root)
    exceptions = EXCEPTIONS if exceptions is None else exceptions
    found, used = [], set()
    for source in SOURCES:
        for path in sorted((root / source).rglob('*')):
            name = path.relative_to(root).as_posix()
            if path.suffix not in ('.kt', '.java') or name.startswith(STORAGE):
                continue
            text = path.read_text(encoding='utf-8')
            for number, (line, original) in enumerate(zip(code(text).splitlines(), text.splitlines()), 1):
                for rule, pattern in RULES.items():
                    if not pattern.search(line):
                        continue
                    if rule in exceptions.get(name, {}):
                        used.add((name, rule))
                    else:
                        found.append(f'{name}:{number}: {rule}: {original.strip()}')
    for name in sorted(exceptions):
        for rule, reason in exceptions[name].items():
            if (name, rule) not in used:
                found.append(f'{name}: exception no longer needed: {rule} ({reason})')
    return found


def marker_problems(root=ROOT):
    """App and test manifest both carry the build marker entry Gradle fills in."""
    return [f'{name}: no build marker entry' for name in MANIFESTS
            if not MARKER.search((Path(root) / name).read_text(encoding='utf-8'))]


def main():
    found = problems() + marker_problems()
    if found:
        print('\n'.join(found), file=sys.stderr)
    return 1 if found else 0


if __name__ == '__main__':
    raise SystemExit(main())
