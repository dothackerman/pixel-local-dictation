#!/usr/bin/env python3
"""Host check (ADR-028, #81): every device class has a known purpose, and Lab runs stay apart from tests.

`app/src/androidTest/device-classes.json` classifies every class of the test APK:
  test           passes or fails; runs in the named phone batch through `phone run`
  lab_run        measures, completed or incomplete; lives in the Lab run package, runs through `phone measure`
  guided_check   a script the owner performs on the lab build; started by hand, never in the batch
  helper         shared code without tests of its own
`retired` names classes that are gone, each with its reason. `phoneBatch` is the named phone batch: the
classes of the one batched run, and the classes that each need a fresh process of their own; each maps to
the reason it needs the real phone (ADR-028: what the laptop can check runs on the laptop).

A file without a top-level class is named after its file, as Kotlin does (`SessionReplayKt`).

Checked as well: Lab run code reads app storage only through the lab folder, every Lab run goes through
the harness (`LabRun.measure`), and the wrapper's Lab run package, input folder and outcome lines are the
harness's.

python3 tools/device_classes.py          prints each problem; exit 1 when any exists.
python3 tools/device_classes.py --batch  prints the `phone run` arguments of the named phone batch.
"""
import importlib.machinery
import importlib.util
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCES = 'app/src/androidTest/kotlin'
CLASSIFICATION = 'app/src/androidTest/device-classes.json'
WRAPPER = 'tools/phone'
LAB_FOLDER = 'files/lab'  # Storage.labFolder, relative to the app's data folder
KINDS = ('test', 'lab_run', 'guided_check', 'helper')
_PACKAGE = re.compile(r'^package\s+([\w.]+)', re.M)
_CLASS = re.compile(r'^(?:\w+\s+)*(?:class|object|interface)\s+(\w+)', re.M)
_TEST = re.compile(r'@Test\b')
_THROUGH_HARNESS = re.compile(r'@Test\s+fun\s+\w+\s*\(\s*\)\s*=\s*LabRun\.measure\(')
_STORAGE = re.compile(r'\bstorage\b(?!\s*\.\s*labFolder\b)')
_STORAGE_IMPORT = re.compile(r'^import\s+ch\.og\.dictation\.storage\b', re.M)


def code(source):
    """Source without comments; line structure kept."""
    blank = lambda match: re.sub(r'[^\n]', ' ', match.group(0))
    return re.sub(r'(?<![:"\w])//[^\n]*', '', re.sub(r'/\*.*?\*/', blank, source, flags=re.S))


def device_classes(root):
    """name -> (package, source text without comments, path) of every class of the test APK."""
    found = {}
    for path in sorted((Path(root) / SOURCES).rglob('*.kt')):
        text = code(path.read_text(encoding='utf-8'))
        package = _PACKAGE.search(text)
        package = package.group(1) if package else ''
        names = _CLASS.findall(text) or [path.stem + 'Kt']
        for name in names:
            found[(package + '.' if package else '') + name] = (package, text, path.relative_to(root).as_posix())
    return found


def wrapper(root):
    loader = importlib.machinery.SourceFileLoader('phone_wrapper_for_device_classes', str(Path(root) / WRAPPER))
    module = importlib.util.module_from_spec(importlib.util.spec_from_loader(loader.name, loader))
    loader.exec_module(module)
    return module


def problems(root=ROOT):
    root = Path(root)
    listing = json.loads((root / CLASSIFICATION).read_text(encoding='utf-8'))
    package, kinds, retired = listing['labRunPackage'], listing['classes'], listing['retired']
    batch, fresh = listing['phoneBatch']['batch'], listing['phoneBatch']['freshProcess']
    classes = device_classes(root)
    found = []
    for part in ('batch', 'freshProcess'):
        if not isinstance(listing['phoneBatch'][part], dict):
            found.append(f'{CLASSIFICATION}: phoneBatch.{part} maps each class to the reason it needs the real phone')
    batch, fresh = (part if isinstance(part, dict) else dict.fromkeys(part, 'unknown') for part in (batch, fresh))
    inside = lambda name: classes[name][0] == package or classes[name][0].startswith(package + '.')
    for name in sorted(classes):
        _, text, path = classes[name]
        kind = kinds.get(name)
        if kind is None:
            found.append(f'{path}: {name} is not classified in {CLASSIFICATION}')
            continue
        if kind not in KINDS:
            found.append(f'{CLASSIFICATION}: {name} has the unknown kind {kind}')
            continue
        tests = len(_TEST.findall(text))
        if kind == 'helper' and tests and len([n for n in classes if classes[n][2] == path]) == 1:
            found.append(f'{path}: {name} is classified as a helper but holds tests')
        if kind != 'helper' and not tests:
            found.append(f'{path}: {name} is classified as {kind} but holds no test method')
        if kind == 'lab_run' and not inside(name):
            found.append(f'{path}: Lab run class {name} sits outside the Lab run package {package}')
        if kind in ('test', 'guided_check') and inside(name):
            found.append(f'{path}: {name} ({kind}) sits in the Lab run package {package}; only Lab runs and their helpers do')
        if kind == 'lab_run' and len(_THROUGH_HARNESS.findall(text)) != tests:
            found.append(f'{path}: every @Test of a Lab run is `@Test fun name() = LabRun.measure(…)`: '
                         'completed or incomplete, never passed or failed')
        if inside(name):
            for number, line in enumerate(text.splitlines(), 1):
                if _STORAGE.search(line) or _STORAGE_IMPORT.search(line):
                    found.append(f'{path}:{number}: Lab run code reads app storage only as storage.labFolder: {line.strip()}')
    for name in sorted(set(kinds) - set(classes)):
        found.append(f'{CLASSIFICATION}: {name} is classified but no such class exists')
    for name in sorted(retired):
        if name in classes or name in kinds:
            found.append(f'{CLASSIFICATION}: {name} is retired but still exists or is still classified')
        if not str(retired[name]).strip():
            found.append(f'{CLASSIFICATION}: {name} is retired without a reason')
    listed = list(batch) + list(fresh)
    for name in sorted(set(listed)):
        if listed.count(name) > 1:
            found.append(f'{CLASSIFICATION}: {name} is in the phone batch more than once')
        reason = batch.get(name, fresh.get(name))
        if not isinstance(reason, str) or not reason.strip():
            found.append(f'{CLASSIFICATION}: {name} is in the phone batch without a reason it needs the real phone')
        if kinds.get(name) != 'test':
            found.append(f'{CLASSIFICATION}: {name} is in the phone batch but is {kinds.get(name, "not classified")}, not a test')
    for name in sorted(name for name, kind in kinds.items() if kind == 'test' and name not in listed):
        found.append(f'{CLASSIFICATION}: test class {name} is not in the phone batch')
    return found + wrapper_problems(root, package, classes)


def wrapper_problems(root, package, classes):
    """The wrapper tells Lab runs by this package, names this input folder and reads these outcome lines."""
    phone = wrapper(root)
    found = []
    if phone.LAB_RUN != package:
        found.append(f'{WRAPPER}: LAB_RUN is {phone.LAB_RUN}, the Lab run package is {package}')
    harness = classes.get(package + '.LabRun')
    if harness is None:
        return found + [f'{SOURCES}: the Lab run harness {package}.LabRun is missing']
    text = harness[1]
    lab = LAB_FOLDER
    inputs = re.search(r'const\s+val\s+INPUTS\s*=\s*"([^"]*)"', text)
    if not inputs or lab + '/' + inputs.group(1) != phone.LAB_RUN_INPUTS:
        found.append(f'{harness[2]}: INPUTS must name the folder the wrapper names, {phone.LAB_RUN_INPUTS}, inside {lab}')
    for line in (phone.LAB_RUN_COMPLETED, phone.LAB_RUN_INCOMPLETE):
        if f'"{line}"' not in text:
            found.append(f'{harness[2]}: the harness must report "{line}", which the wrapper reads')
    return found


def batch_arguments(root=ROOT):
    """One line per `phone run`: the batched run first, then each fresh-process class."""
    listing = json.loads((Path(root) / CLASSIFICATION).read_text(encoding='utf-8'))['phoneBatch']
    return [','.join(listing['batch']), *listing['freshProcess']]


def main(argv):
    if argv == ['--batch']:
        print('\n'.join(batch_arguments()))
        return 0
    found = problems()
    if found:
        print('\n'.join(found), file=sys.stderr)
    return 1 if found else 0


if __name__ == '__main__':
    raise SystemExit(main(sys.argv[1:]))
