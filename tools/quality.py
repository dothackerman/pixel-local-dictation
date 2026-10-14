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

ROOT = Path(__file__).resolve().parents[1]


def staged_errors(root=ROOT):
    errors = []
    if subprocess.run(['git', 'diff', '--quiet'], cwd=root).returncode:
        errors.append('tracked working-tree changes differ from the index; stage or stash them')
    untracked = subprocess.check_output(['git', 'ls-files', '--others', '--exclude-standard'], cwd=root, text=True).splitlines()
    if any(p.startswith(('tools/', 'app/src/', 'gradle/')) or p.endswith(('.gradle.kts', '.gradle')) for p in untracked):
        errors.append('untracked source/test/build inputs must be staged or removed')
    return errors


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--staged', action='store_true')
    args = parser.parse_args()
    environment = os.environ.copy()
    conventional_sdk = Path.home() / 'Android/Sdk'
    if 'ANDROID_HOME' not in environment and conventional_sdk.is_dir():
        environment['ANDROID_HOME'] = str(conventional_sdk)
    index_before = subprocess.check_output(['git', 'ls-files', '--stage'], cwd=ROOT) if args.staged else None
    if args.staged:
        errors = staged_errors()
        if errors:
            print('\n'.join(errors), file=sys.stderr)
            return 1
    for command in ([sys.executable, '-m', 'unittest', 'discover', '-s', 'tools', '-p', 'test_*.py'],
                    [str(ROOT / 'gradlew'), '--offline', 'testBenchmarkUnitTest', 'lintBenchmark']):
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
