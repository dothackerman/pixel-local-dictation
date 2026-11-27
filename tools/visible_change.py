#!/usr/bin/env python3
"""BEFORE/AFTER images of the recording panel, Settings and History, in one checkout (#73, #77, #79, ADR-029).

  before   render the gallery and store it as BEFORE; at a ticket's start
  compare  render it again and compare with BEFORE byte for byte

The render is the host tests PanelGalleryTest (every panel state in every look, to GALLERY/after/states),
SettingsHistoryScreenshotTest (Settings and History screens, to GALLERY/after/screens) and
PanelFrameSequenceTest (golden frame sequences; a scenario that differs leaves every frame of its
golden and of its new sequence in GALLERY/before|after/sequences). compare fails on any changed, added
or removed image and on any differing sequence, puts the pairs into GALLERY/pairs as
<image>.BEFORE.png and <image>.AFTER.png with list.txt, and prints the rule. --no-render compares
what GALLERY/after holds, for example right after the quality gate.

GALLERY is app/build/panel-gallery, a gitignored build folder: a Gradle clean deletes BEFORE with it.
"""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
GALLERY = ROOT / 'app/build/panel-gallery'
RULE = ('A differing frame or image stops the work: the owner decides from the BEFORE/AFTER pairs; '
        'text alone is not accepted.')
RENDERERS = ('ch.og.dictation.panel.PanelGalleryTest', 'ch.og.dictation.SettingsHistoryScreenshotTest',
             'ch.og.dictation.PanelFrameSequenceTest')


def gradle_render(gallery):
    """Runs the rendering host tests again. A differing golden sequence fails the run; its images are the result."""
    from quality import jvm_environment
    shutil.rmtree(gallery / 'after', ignore_errors=True)
    environment = jvm_environment(ROOT, os.environ.copy())
    conventional_sdk = Path.home() / 'Android/Sdk'
    if 'ANDROID_HOME' not in environment and conventional_sdk.is_dir():
        environment['ANDROID_HOME'] = str(conventional_sdk)
    command = [str(ROOT / 'gradlew'), '--offline', '-q', 'testBenchmarkUnitTest', '--rerun']
    for renderer in RENDERERS:
        command += ['--tests', renderer]
    subprocess.run(command, cwd=ROOT, env=environment, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def images(folder):
    return {path.relative_to(folder).as_posix(): path for path in sorted(folder.rglob('*.png'))} if folder.is_dir() else {}


def count(number):
    return f'{number} image' + ('' if number == 1 else 's')


def differing_sequences(gallery):
    names = {name.split('/')[1] for side in ('before', 'after') for name in images(gallery / side) if name.startswith('sequences/')}
    return sorted(names)


def store_before(gallery, out):
    if scenarios := differing_sequences(gallery):
        print(f'golden frame sequence differs: {", ".join(scenarios)}\nBEFORE is stored only while every sequence '
              'equals its golden file', file=out)
        return 1
    before = gallery / 'before'
    shutil.rmtree(before, ignore_errors=True)
    shutil.copytree(gallery / 'after', before)
    print(f'BEFORE: {count(len(images(before)))} stored in {before}', file=out)
    return 0


def compare(gallery, out):
    before, after, pairs = images(gallery / 'before'), images(gallery / 'after'), gallery / 'pairs'
    shutil.rmtree(pairs, ignore_errors=True)
    if not any(name.startswith('states/') for name in before):
        print(f'no BEFORE images in {gallery / "before"}: store them at the ticket\'s start with: '
              'python3 tools/visible_change.py before', file=out)
        return 1
    listed, differing = [], 0
    for name in sorted(before.keys() | after.keys()):
        sides = {'BEFORE': before.get(name), 'AFTER': after.get(name)}
        same = bool(sides['BEFORE'] and sides['AFTER']) and sides['BEFORE'].read_bytes() == sides['AFTER'].read_bytes()
        # Every frame of a differing sequence is shown, also one whose image did not change.
        if same and not name.startswith('sequences/'):
            continue
        differing += not same
        listed.append(('same' if same else 'changed' if sides['BEFORE'] and sides['AFTER'] else
                       'removed' if sides['BEFORE'] else 'added') + ' ' + name)
        for side, source in sides.items():
            if source:
                target = pairs / (name[:-len('.png')] + f'.{side}.png')
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(source, target)
    if not listed:
        print(f'{count(len(after))}, none differs from BEFORE', file=out)
        return 0
    listing = '\n'.join(listed) + '\n'
    (pairs / 'list.txt').write_text(listing)
    scenarios = differing_sequences(gallery)
    print(listing + (f'golden frame sequence differs: {", ".join(scenarios)}\n' if scenarios else '')
          + f'{differing} of {count(len(before.keys() | after.keys()))} differ{"s" if differing == 1 else ""} from BEFORE; '
          f'pairs and list.txt in {pairs}\n{RULE}', file=out)
    return 1


def run(verb, gallery=GALLERY, out=sys.stdout, render=gradle_render):
    gallery = Path(gallery).resolve()
    if render:
        render(gallery)
    if not images(gallery / 'after' / 'states'):
        print(f'no images in {gallery / "after" / "states"}: the gallery render failed', file=out)
        return 1
    return {'before': store_before, 'compare': compare}[verb](gallery, out)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('verb', choices=('before', 'compare'))
    parser.add_argument('--no-render', action='store_true', help='use the images GALLERY/after holds')
    args = parser.parse_args()
    return run(args.verb, render=None if args.no_render else gradle_render)


if __name__ == '__main__':
    raise SystemExit(main())
