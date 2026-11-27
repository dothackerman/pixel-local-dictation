#!/usr/bin/env python3
"""Make this checkout pass `python3 tools/quality.py` inside the agent sandbox, offline.

In a linked worktree, links the main checkout's gitignored build inputs (signing in
local.properties, app/libs, app/src/main/assets); links, never copies; never replaces a
real file. In any checkout, links the cached Robolectric Android jar into
.gradle/robolectric, which quality.py hands to Robolectric's offline mode. Checks the
Gradle caches; downloads nothing. Every check runs before any write. Gate
installation (ADR-024) is reported, never done.
"""
import os
from pathlib import Path
import re
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tools/gate'))  # check_installation, when run directly
from check_installation import errors as gate_installation_errors  # noqa: E402
FILL_CACHES = 'in the main checkout, outside the sandbox: ./gradlew testBenchmarkUnitTest'
# name in a worktree -> how to fill it in the main checkout
INPUTS = {
    'local.properties': 'tools/set-signing-password.sh',
    'app/libs': 'tools/assets/fetch-assets.sh',
    'app/src/main/assets': 'tools/assets/fetch-assets.sh',
}
SIGNING_KEYS = ('dictation.keystore', 'dictation.keyAlias', 'dictation.keystorePassword')
# Android release of the SDK the Robolectric tests run (@Config(sdk = [36])).
ROBOLECTRIC_RELEASE = '16'
ROBOLECTRIC_JARS = Path('.gradle/robolectric')


def main_checkout(root):
    common = subprocess.check_output(['git', 'rev-parse', '--path-format=absolute', '--git-common-dir'],
                                     cwd=root, text=True).strip()
    return Path(common).parent


def signing_problem(properties):
    present = set()
    for line in properties.read_text().splitlines():
        key, sep, value = line.partition('=')
        if sep and value.strip():
            present.add(key.strip())
    missing = [key for key in SIGNING_KEYS if key not in present]
    return 'main checkout local.properties lacks ' + ', '.join(missing) if missing else None


def input_links(root, main, problems):
    links = []
    for name, fill in INPUTS.items():
        source, target = main / name, root / name
        if not source.exists() or (source.is_dir() and not any(p.is_file() for p in source.rglob('*'))):
            problems.append(f'main checkout lacks {name}; run {fill} in the main checkout')
            continue
        if name == 'local.properties' and (problem := signing_problem(source)):
            problems.append(f'{problem}; run {fill} in the main checkout')
            continue
        links.append((target, source))
    return links


def robolectric_jars(root, gradle_home, m2, problems):
    """The cached android-all jars the pinned Robolectric version asks for."""
    gradle = root / 'app/build.gradle.kts'
    version = re.search(r'org\.robolectric:robolectric:([^"]+)"', gradle.read_text())
    if not version:
        problems.append(f'{gradle} names no org.robolectric:robolectric version')
        return []
    version = version.group(1)
    jars = sorted((gradle_home / 'caches/modules-2/files-2.1/org.robolectric/robolectric' / version)
                  .glob(f'*/robolectric-{version}.jar'))
    if not jars:
        problems.append(f'Gradle cache {gradle_home} lacks Robolectric {version}; fill it {FILL_CACHES}')
        return []
    with zipfile.ZipFile(jars[0]) as jar:
        provider = jar.read('org/robolectric/plugins/DefaultSdkProvider.class')
    # The provider class holds each SDK's Robolectric build number as a string constant;
    # an older cached build of the same Android release does not appear in it.
    android_all = m2 / 'org/robolectric/android-all-instrumented'
    jars = [directory / f'android-all-instrumented-{directory.name}.jar'
            for directory in sorted(android_all.glob(f'{ROBOLECTRIC_RELEASE}-robolectric-*-i*'))
            if directory.name.split('-')[2].encode() in provider]
    # Every cached instrumentation level of that build; Robolectric picks its own by name.
    jars = [jar for jar in jars if jar.is_file()]
    if jars:
        return jars
    problems.append(f'Maven cache {android_all} lacks the Android {ROBOLECTRIC_RELEASE} jar '
                    f'Robolectric {version} uses; fill it {FILL_CACHES}')
    return []


def gradle_problems(root, gradle_home):
    problems = []
    wrapper = (root / 'gradle/wrapper/gradle-wrapper.properties').read_text()
    distribution = re.search(r'distributionUrl=.*/(gradle-[^/]+)\.zip', wrapper)
    if not distribution:
        problems.append('gradle/wrapper/gradle-wrapper.properties names no Gradle distribution')
    elif not (gradle_home / 'wrapper/dists' / distribution.group(1)).is_dir():
        problems.append(f'Gradle cache {gradle_home} lacks {distribution.group(1)}; fill it {FILL_CACHES}')
    if not os.access(gradle_home, os.W_OK):
        problems.append(f'Gradle home {gradle_home} is not writable in this sandbox')
    return problems


def prepare(root, main, gradle_home, m2, out):
    root, main = Path(root).resolve(), Path(main).resolve()
    problems = gradle_problems(root, gradle_home)
    links = input_links(root, main, problems) if root != main else []
    cached = robolectric_jars(root, gradle_home, m2, problems)
    jars = root / ROBOLECTRIC_JARS
    links += [(jars / jar.name, jar) for jar in cached]
    if cached and jars.is_dir():
        # Jars from an earlier Robolectric version: only our own links go (source None).
        names = {jar.name for jar in cached}
        links.extend((stale, None) for stale in jars.iterdir() if stale.name not in names and stale.is_symlink())
    changes = []
    for target, source in links:
        if target.is_symlink() and source is not None and target.resolve() == source.resolve():
            continue
        if source is not None and (target.exists() or target.is_symlink()):
            problems.append(f'{target} exists and is not a link to {source}; move it away yourself')
            continue
        changes.append((target, source))
    if problems:
        print('\n'.join(problems), file=out)
        print('nothing changed', file=out)
        return 1
    for target, source in changes:
        if source is None:
            target.unlink()
            print(f'removed stale {target}', file=out)
        else:
            target.parent.mkdir(parents=True, exist_ok=True)
            target.symlink_to(source)
            print(f'linked {target} -> {source}', file=out)
    if not changes:
        print('nothing to do', file=out)
    return 0


def main():
    gradle_home = Path(os.environ.get('GRADLE_USER_HOME') or Path.home() / '.gradle')
    code = prepare(ROOT, main_checkout(ROOT), gradle_home, Path.home() / '.m2/repository', sys.stdout)
    if gate_installation_errors(ROOT):
        print('note: no gate installation here (ADR-024); laptop work does not need it. '
              'The owner runs bash tools/gate/apply.sh; this command does not.')
    return code


if __name__ == '__main__':
    raise SystemExit(main())
