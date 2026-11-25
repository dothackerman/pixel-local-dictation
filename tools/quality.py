#!/usr/bin/env python3
"""Host-only quality gate: Python regressions (host checks included), Kotlin unit tests for the lab
build and release, Android lint and the build markers of the merged manifests.

--staged requires tracked worktree/index parity and staged source/test inputs.
It tests this checkout; stage relevant files first. No instrumentation runs.
"""
import argparse
import os
from pathlib import Path
import subprocess
import sys

from prepare_checkout import ROBOLECTRIC_JARS

ROOT = Path(__file__).resolve().parents[1]


def staged_errors(root=ROOT):
    errors = []
    if subprocess.run(['git', 'diff', '--quiet'], cwd=root).returncode:
        errors.append('tracked working-tree changes differ from the index; stage or stash them')
    untracked = subprocess.check_output(['git', 'ls-files', '--others', '--exclude-standard'], cwd=root, text=True).splitlines()
    if any(p.startswith(('tools/', 'app/src/', 'gradle/', 'native-role-profiler/')) or p.endswith(('.gradle.kts', '.gradle')) for p in untracked):
        errors.append('untracked source/test/build inputs must be staged or removed')
    return errors


def preparation_problem(root=ROOT):
    """A linked worktree lacks the links tools/prepare_checkout.py makes; the main checkout owns its inputs."""
    root = Path(root)
    git = lambda option: subprocess.check_output(['git', 'rev-parse', '--path-format=absolute', option],
                                                 cwd=root, text=True).strip()
    if git('--git-dir') == git('--git-common-dir'):
        return None
    needed = ('local.properties', 'app/libs', 'app/src/main/assets', str(ROBOLECTRIC_JARS))
    missing = [name for name in needed if not (root / name).exists()]
    return ('unprepared worktree, missing ' + ', '.join(missing)
            + ': run python3 tools/prepare_checkout.py') if missing else None


def jvm_environment(root, environment):
    """JVM options for the agent sandbox: temp files under $TMPDIR and, once
    tools/prepare_checkout.py linked the jars, Robolectric without its $HOME lock."""
    options = []
    if environment.get('TMPDIR'):
        options.append('-Djava.io.tmpdir=' + environment['TMPDIR'])
    jars = Path(root) / ROBOLECTRIC_JARS
    if jars.is_dir():
        options += ['-Drobolectric.offline=true', '-Drobolectric.dependency.dir=' + str(jars)]
    if not options:
        return environment
    if environment.get('JAVA_TOOL_OPTIONS'):
        options.append(environment['JAVA_TOOL_OPTIONS'])
    return {**environment, 'JAVA_TOOL_OPTIONS': ' '.join(options)}


def test_environment(root, environment):
    """What the tests run with: the JVM options, and no GIT_* variable, so a hook's GIT_DIR never
    points a test's own `git init` at this repository."""
    return jvm_environment(root, {k: v for k, v in environment.items() if not k.startswith('GIT_')})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--staged', action='store_true')
    args = parser.parse_args()
    environment = test_environment(ROOT, os.environ.copy())
    conventional_sdk = Path.home() / 'Android/Sdk'
    if 'ANDROID_HOME' not in environment and conventional_sdk.is_dir():
        environment['ANDROID_HOME'] = str(conventional_sdk)
    if problem := preparation_problem():
        print(problem, file=sys.stderr)
        return 1
    index_before = subprocess.check_output(['git', 'ls-files', '--stage'], cwd=ROOT) if args.staged else None
    if args.staged:
        errors = staged_errors()
        if errors:
            print('\n'.join(errors), file=sys.stderr)
            return 1
    for command in ([sys.executable, '-W', 'ignore::ResourceWarning', '-m', 'unittest', 'discover', '-s', 'tools', '-p', 'test_*.py'],
                    # Release is compiled and its own host tests assert the owner layout and marker (ADR-028).
                    [str(ROOT / 'gradlew'), '--offline', 'testBenchmarkUnitTest', 'testReleaseUnitTest',
                     'compileBenchmarkAndroidTestKotlin', 'lintBenchmark', 'processReleaseManifest',
                     'processBenchmarkManifest', 'processBenchmarkAndroidTestManifest'],
                    # Release owner; lab build and its test APK test and equal; one build.
                    [sys.executable, str(ROOT / 'tools/build_marker.py'), '--check']):
        result = subprocess.run(command, cwd=ROOT, env=environment)
        if result.returncode:
            return result.returncode
    # Detect edits during the gate too; no cached success claims.
    if args.staged and (staged_errors() or index_before != subprocess.check_output(['git', 'ls-files', '--stage'], cwd=ROOT)):
        print('checkout changed during quality gate', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
