#!/usr/bin/env python3
"""Host-only quality gate: Python regressions, Kotlin unit tests and Android lint.

--staged requires tracked worktree/index parity and staged source/test inputs.
It tests this checkout; stage relevant files first. No instrumentation runs.
"""
import argparse
import os
from pathlib import Path
import subprocess
import sys

from prepare_checkout import FILL_CACHES, GATE_TASKS, GRADLE_HOME, ROBOLECTRIC_JARS

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
    """A checkout lacks its own Gradle home, or a linked worktree the links tools/prepare_checkout.py
    makes; the main checkout owns its inputs."""
    root = Path(root)
    git = lambda option: subprocess.check_output(['git', 'rev-parse', '--path-format=absolute', option],
                                                 cwd=root, text=True).strip()
    home = os.environ.get('GRADLE_USER_HOME')
    if home and Path(home).resolve() == (Path.home() / '.gradle').resolve():
        return 'GRADLE_USER_HOME is the shared ~/.gradle; the gate builds only in its own home (ADR-028): unset it'
    needed = [] if home else [str(GRADLE_HOME)]
    if git('--git-dir') != git('--git-common-dir'):
        needed += ['local.properties', 'app/libs', 'app/src/main/assets', str(ROBOLECTRIC_JARS)]
    missing = [name for name in needed if not (root / name).exists()]
    return ('unprepared checkout, missing ' + ', '.join(missing)
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


def gradle(command, root, environment):
    """Runs an offline Gradle build; a dependency missing from the caches names the fill."""
    missing = False
    with subprocess.Popen(command, cwd=root, env=environment, stdout=subprocess.PIPE,
                          stderr=subprocess.STDOUT, text=True) as process:
        for line in process.stdout:
            sys.stdout.write(line)
            sys.stdout.flush()
            missing = missing or 'No cached version available for offline mode' in line
    code = process.returncode
    if code and missing:
        print(f'quality: a dependency is missing from the read-only copy; {FILL_CACHES}', file=sys.stderr)
    return code


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--staged', action='store_true')
    args = parser.parse_args()
    environment = jvm_environment(ROOT, os.environ.copy())
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
    tests = [sys.executable, '-W', 'ignore::ResourceWarning', '-m', 'unittest', 'discover', '-s', 'tools', '-p', 'test_*.py']
    if code := subprocess.run(tests, cwd=ROOT, env=environment).returncode:
        return code
    if code := gradle([str(ROOT / 'gradlew'), '--offline', *GATE_TASKS], ROOT, environment):
        return code
    # Detect edits during the gate too; no cached success claims.
    if args.staged and (staged_errors() or index_before != subprocess.check_output(['git', 'ls-files', '--stage'], cwd=ROOT)):
        print('checkout changed during quality gate', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
