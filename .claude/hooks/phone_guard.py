#!/usr/bin/env python3
"""Shared Claude/Codex PreToolUse fast-fail (ADR-024). The sandbox is the boundary.

Two rules only:
1. Shell: deny a command that *invokes* adb or fastboot (directly, via sh -c,
   $(...), xargs, env/timeout, or a Gradle device task). Mentioning the words
   in greps, docs or heredocs is fine.
2. Edits: deny writes to the hook and harness configuration and AGENTS/CLAUDE.md.
"""
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PROTECTED = ('.claude/hooks', '.claude/settings.json', '.codex', '.git', 'tools/git-hooks',
             'AGENTS.md', 'CLAUDE.md')
TOOL = r'(?:\S*/)?(?:adb|fastboot)(?=\s|$)'
PREFIX = r'(?:env\s+(?:\w+=\S*\s+)*|timeout\s+\S+\s+|nice\s+(?:-n\s*\d+\s+)?|xargs\s+(?:-\S+\s+)*)?'
INVOKES = re.compile(r'(?:^|[;&|(\n]|\$\(|`|-c\s+["\']?)\s*' + PREFIX + TOOL)
GRADLE_DEVICE_TASK = re.compile(r'gradlew?\b[^\n;&|]*\b\w*(?:[Ii]nstall|[Cc]onnected|[Uu]ninstall|[Dd]evice)\w*')


def deny(reason):
    print('phone-guard: ' + reason, file=sys.stderr)
    return 2


def protected(path, cwd):
    candidate = Path(path)
    if not candidate.is_absolute():
        candidate = cwd / candidate
    for target in (candidate.absolute(), candidate.resolve()):
        for relative in PROTECTED:
            gate = ROOT / relative
            if target == gate or gate in target.parents:
                return True
    return False


def check(event):
    tool = event.get('tool_name')
    args = event.get('tool_input')
    if not isinstance(args, dict):
        return deny('invalid hook input')
    cwd = Path(event.get('cwd') or ROOT).resolve()
    if tool in ('Bash', 'exec_command'):
        command = args.get('command', args.get('cmd'))
        if isinstance(command, list):
            command = ' '.join(command)
        if not isinstance(command, str) or not command.strip():
            return deny('missing shell command')
        if INVOKES.search(command):
            return deny('direct adb/fastboot is not allowed; use .claude/hooks/phone')
        if GRADLE_DEVICE_TASK.search(command):
            return deny('Gradle device tasks talk to adb; build on the host, then .claude/hooks/phone install')
        return 0
    if tool in ('Edit', 'Write', 'MultiEdit', 'NotebookEdit'):
        path = args.get('file_path', args.get('notebook_path'))
        if not isinstance(path, str) or not path:
            return deny('missing edit path')
        return deny('harness files are read-only; change them through the user') if protected(path, cwd) else 0
    if tool == 'apply_patch':
        patch = args.get('command', args.get('patch'))
        if not isinstance(patch, str):
            return deny('missing patch')
        paths = re.findall(r'^\*\*\* (?:Add File|Update File|Delete File|Move to): (.+)$', patch, re.M)
        if any(protected(p, cwd) for p in paths):
            return deny('harness files are read-only; change them through the user')
        return 0
    return 0


def main():
    try:
        event = json.load(sys.stdin)
        return check(event) if isinstance(event, dict) else deny('invalid hook input')
    except (ValueError, OSError, TypeError) as error:
        return deny('cannot validate hook input: ' + str(error))


if __name__ == '__main__':
    sys.exit(main())
