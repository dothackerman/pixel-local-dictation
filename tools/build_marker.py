#!/usr/bin/env python3
"""Build marker (ADR-029): which storage layout an APK has and which build it comes from.

Manifest entry `ch.og.dictation.build` in app and test APK, value
`layout=<owner|test>;commit=<40 hex>;hash=<64 hex>`. Gradle adds the layout per build type and
takes the rest from this tool; the hash covers the build's inputs, so a dirty tree differs from
the clean build of its commit.

python3 tools/build_marker.py            prints commit=…;hash=… for this checkout
python3 tools/build_marker.py --check    compares the merged manifests the gate built; exit 1 on a problem
"""
import hashlib
import re
import subprocess
import sys
from pathlib import Path
import xml.etree.ElementTree as ElementTree

ROOT = Path(__file__).resolve().parents[1]
NAME = 'ch.og.dictation.build'
FORMAT = re.compile(r'layout=(owner|test);(commit=[0-9a-f]{40};hash=[0-9a-f]{64})')
# What a build reads: sources and build scripts by content, pinned binaries (gitignored) by content too.
INPUTS = ('app', 'gradle', 'build.gradle.kts', 'settings.gradle.kts', 'gradle.properties', 'assets.lock',
          'tools/build_marker.py')
BINARIES = ('app/libs', 'app/src/main/assets')
MANIFESTS = 'app/build/intermediates/{kind}/{variant}/process{task}Manifest/AndroidManifest.xml'


def _git(root, *arguments):
    return subprocess.check_output(['git', *arguments], cwd=root)


def _digest(path):
    if path.is_symlink() and not path.exists():
        return b'dangling'
    digest = hashlib.sha256()
    with open(path, 'rb') as stream:
        for block in iter(lambda: stream.read(1 << 20), b''):
            digest.update(block)
    return digest.digest()


def identity(root=ROOT):
    """`commit=…;hash=…`: HEAD and a hash over HEAD and every build input as it is on disk."""
    root = Path(root)
    commit = _git(root, 'rev-parse', 'HEAD').decode().strip()
    names = set(_git(root, 'ls-files', '-z', '--cached', '--others', '--exclude-standard', '--', *INPUTS)
                .decode().split('\0')) - {''}
    for folder in BINARIES:
        names |= {path.relative_to(root).as_posix() for path in (root / folder).rglob('*') if path.is_file()}
    digest = hashlib.sha256(commit.encode())
    for name in sorted(names):
        path = root / name
        if path.is_dir():
            continue
        digest.update(name.encode() + b'\0' + (_digest(path) if path.is_file() or path.is_symlink() else b'missing'))
    return f'commit={commit};hash={digest.hexdigest()}'


def manifest_marker(path):
    """The marker value in a text manifest, or None."""
    for entry in ElementTree.parse(path).getroot().iter('meta-data'):
        if entry.get('{http://schemas.android.com/apk/res/android}name') == NAME:
            return entry.get('{http://schemas.android.com/apk/res/android}value')
    return None


def manifest_problems(release, lab, test):
    """Release is owner; the lab build and its test APK are test and equal; all three are one build."""
    problems, parsed = [], {}
    for label, path, layout in (('release', release, 'owner'), ('lab build', lab, 'test'), ('test APK', test, None)):
        value = manifest_marker(path)
        match = FORMAT.fullmatch(value or '')
        if value is None:
            problems.append(f'{label}: no build marker')
        elif not match:
            problems.append(f'{label}: malformed build marker {value}')
        elif layout and match.group(1) != layout:
            problems.append(f'{label}: layout is {match.group(1)}, expected {layout}')
        parsed[label] = (value, match)
    lab_value, lab_match = parsed['lab build']
    if parsed['test APK'][1] and lab_match and parsed['test APK'][0] != lab_value:
        problems.append('test APK: marker differs from the lab build')
    if parsed['release'][1] and lab_match and parsed['release'][1].group(2) != lab_match.group(2):
        problems.append('release: build differs from the lab build')
    return problems


def built_manifests(root=ROOT):
    return [Path(root) / MANIFESTS.format(kind=kind, variant=variant, task=task) for kind, variant, task in (
        ('merged_manifests', 'release', 'Release'), ('merged_manifests', 'benchmark', 'Benchmark'),
        ('packaged_manifests', 'benchmarkAndroidTest', 'BenchmarkAndroidTest'))]


def main(arguments):
    if arguments == ['--check']:
        problems = manifest_problems(*built_manifests())
        if problems:
            print('\n'.join(problems), file=sys.stderr)
        return 1 if problems else 0
    if arguments:
        print(__doc__, file=sys.stderr)
        return 2
    print(identity())
    return 0


if __name__ == '__main__':
    raise SystemExit(main(sys.argv[1:]))
