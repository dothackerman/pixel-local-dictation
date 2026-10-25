#!/usr/bin/env python3
"""Shared Claude/Codex PreToolUse fast-fail (ADR-024). The sandbox is the boundary.

Three rules only:
1. Shell: deny a command that *invokes* adb or fastboot (directly, via sh -c,
   $(...), backticks, xargs, env/timeout/nice, or a Gradle device task).
   The command is tokenized like a shell, so the words inside quotes, greps,
   docs and heredoc bodies never count.
2. Shell: `git` runs outside the sandbox (excludedCommands; the sandbox pins
   .git read-only), so deny git invocations that could carry a program along:
   `-c`/`--config-env`/`--exec-path`/`--git-dir`/`--work-tree` options and
   `GIT_*` environment assignments in front of git.
3. Edits: deny writes to the hook and harness configuration and AGENTS/CLAUDE.md.
"""
import json
import re
import shlex
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PROTECTED = ('.claude/hooks', '.claude/settings.json', '.codex', '.git', 'tools/git-hooks',
             'AGENTS.md', 'CLAUDE.md')
TOOLS = {'adb', 'fastboot'}
SHELLS = {'sh', 'bash', 'zsh', 'dash', 'ksh'}
SKIP = {'env', 'command', 'exec', 'nohup', 'time', 'builtin', 'sudo', 'doas'}
SKIP_ONE = {'timeout', 'stdbuf', 'nice', 'ionice', 'chrt'}  # wrapper plus one argument (or -flag value)
SEPARATORS = {';', '&&', '||', '|', '|&', '&', '(', ')', ';;', '\n'}
GRADLE_TASK = re.compile(r'(?i)(install|connected|uninstall|device)')
GIT_SMUGGLE = re.compile(r'^(?:-c|-C|--config-env(?:=.*)?|--exec-path(?:=.*)?|--git-dir(?:=.*)?|--work-tree(?:=.*)?|--namespace(?:=.*)?)$')
HEREDOC = re.compile(r"<<-?\s*(['\"]?)(\w+)\1[^\n]*\n(.*?\n)?\2[ \t]*(?=\n|$)", re.S)


def deny(reason):
    print('phone-guard: ' + reason, file=sys.stderr)
    return 2


def simple_commands(command):
    """Split a shell command line into its simple commands (lists of words)."""
    command = HEREDOC.sub('', command)
    lexer = shlex.shlex(command, posix=True, punctuation_chars=';&|()')
    lexer.whitespace = ' \t\r'
    lexer.whitespace_split = True
    lexer.commenters = ''
    try:
        tokens = list(lexer)
    except ValueError:
        # Unbalanced quoting: fall back to whitespace words so a stray adb still shows.
        tokens = re.split(r'[\s;&|()]+', command)
    current = []
    for token in tokens:
        if token in SEPARATORS:
            if current:
                yield current
            current = []
            continue
        for index, part in enumerate(token.split('\n')):  # a newline inside a token ends the command
            if index and current:
                yield current
                current = []
            if part:
                current.append(part)
    if current:
        yield current


def head(words):
    """Strip env assignments and transparent wrappers; return the command word and its arguments."""
    words = list(words)
    while words:
        first = words[0].lstrip('`$').strip()
        base = first.rsplit('/', 1)[-1]
        if re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]*=.*', first) or base in SKIP:
            words.pop(0)
        elif base in SKIP_ONE:
            words.pop(0)
            if words and words[0].startswith('-') and len(words) > 1:
                words.pop(0)
            if words:
                words.pop(0)
        elif base == 'xargs':
            words.pop(0)
            while words and words[0].startswith('-'):
                words.pop(0)
        else:
            words[0] = first
            return words
    return words


def invokes_tool(command):
    for raw in simple_commands(command):
        words = head(raw)
        if not words:
            continue
        base = words[0].rsplit('/', 1)[-1]
        if base == 'git':
            env = [w for w in raw[:len(raw) - len(words)] if re.match(r'GIT_\w*=', w)]
            if env or any(GIT_SMUGGLE.match(w) for w in words[1:]):
                return 'git with -c/-C/--exec-path/--git-dir or GIT_* environment is not allowed; plain git only'
        if base in TOOLS:
            return 'direct adb/fastboot is not allowed; use .claude/hooks/phone'
        if base in SHELLS and '-c' in words:
            inner = words[words.index('-c') + 1:]
            if inner and (reason := invokes_tool(inner[0])):
                return reason
        if base in ('gradlew', 'gradle') and any(GRADLE_TASK.search(w) for w in words[1:] if not w.startswith('-')):
            return 'Gradle device tasks talk to adb; build on the host, then .claude/hooks/phone install'
    return None


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
            command = ' '.join(shlex.quote(str(c)) for c in command)
        if not isinstance(command, str) or not command.strip():
            return deny('missing shell command')
        reason = invokes_tool(command)
        return deny(reason) if reason else 0
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
