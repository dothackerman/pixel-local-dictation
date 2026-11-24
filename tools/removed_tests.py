#!/usr/bin/env python3
"""Removed-test check: no host or device test disappears unnoticed (#73, #77).

Names every @Test in the host and device test sources as package.Class.method, at a base
commit and in this checkout. A name present at the base and gone here needs an entry in
tools/removed_tests.json: {"removed name": "name of the test that replaces it"} or
{"removed name": "retired: why nothing replaces it"}. A renamed class or method counts
as removed; a test moved between device and host under the same name does not.
"""
import argparse
import json
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
SOURCES = ('app/src/test', 'app/src/testBenchmark', 'app/src/testRelease', 'app/src/androidTest')
MAPPING = 'tools/removed_tests.json'
RETIRED = 'retired:'
_PACKAGE = re.compile(r'^package\s+([\w.]+)', re.M)
_CLASS = re.compile(r'^(?:\w+\s+)*class\s+(\w+)', re.M)
_TEST = re.compile(r'@Test\b(?:(?!\bfun\b).)*?\bfun\s+(`[^`]+`|\w+)\s*\(', re.S)


def names_in(text):
    """Test names of one Kotlin source file."""
    package = _PACKAGE.search(text)
    prefix = package.group(1) + '.' if package else ''
    classes = [(match.start(), match.group(1)) for match in _CLASS.finditer(text)]
    names = set()
    for test in _TEST.finditer(text):
        owner = [name for start, name in classes if start < test.start()]
        if owner:
            names.add(prefix + owner[-1] + '.' + test.group(1).strip('`'))
    return names


def current_names(root):
    names = set()
    for source in SOURCES:
        for path in sorted((Path(root) / source).rglob('*.kt')):
            names |= names_in(path.read_text())
    return names


def base_names(root, base):
    git = lambda *arguments: subprocess.check_output(['git', *arguments], cwd=root, text=True)
    names = set()
    for path in git('ls-tree', '-r', '--name-only', base, '--', *SOURCES).splitlines():
        if path.endswith('.kt'):
            names |= names_in(git('show', f'{base}:{path}'))
    return names


def problems(root, base):
    root = Path(root)
    mapping_file = root / MAPPING
    mapping = json.loads(mapping_file.read_text()) if mapping_file.is_file() else {}
    current = current_names(root)
    found = []
    for name in sorted(base_names(root, base) - current):
        replacement = mapping.get(name)
        if replacement is None:
            found.append(f'removed test {name} has no entry in {MAPPING}')
        elif replacement.startswith(RETIRED):
            if not replacement[len(RETIRED):].strip():
                found.append(f'removed test {name} is retired without a reason')
        elif replacement not in current:
            found.append(f'removed test {name} is mapped to {replacement}, which is no test')
    return found


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--base', required=True, help='commit the test names are compared against')
    args = parser.parse_args()
    found = problems(ROOT, args.base)
    print('\n'.join(found) if found else f'no test removed since {args.base} without a mapping entry',
          file=sys.stderr if found else sys.stdout)
    return 1 if found else 0


if __name__ == '__main__':
    raise SystemExit(main())
