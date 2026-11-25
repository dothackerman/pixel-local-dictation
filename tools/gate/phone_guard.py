#!/usr/bin/env python3
"""Shared Claude/Codex PreToolUse guard. The OS sandbox is the wall (adb key, binaries
and server out of reach); this guards the one way out of it the agents keep: `git` runs
outside the sandbox, so deny git invocations that could carry a program along:
`-c`/`-C`/`--config-env`/`--exec-path`/`--git-dir`/`--work-tree`/`--namespace` options and
`GIT_*` environment assignments in front of git. The command is tokenized like a shell,
so words inside quotes and heredoc bodies never count.
"""
import json
import re
import shlex
import sys

SKIP = {'env', 'command', 'exec', 'nohup', 'time', 'builtin', 'sudo', 'doas'}
SKIP_ONE = {'timeout', 'stdbuf', 'nice', 'ionice', 'chrt'}  # wrapper plus one argument (or -flag value)
SEPARATORS = {';', '&&', '||', '|', '|&', '&', '(', ')', ';;', '\n'}
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
        # Unbalanced quoting: fall back to whitespace words so a stray git still shows.
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


def smuggles(command):
    for raw in simple_commands(command):
        words = head(raw)
        if not words or words[0].rsplit('/', 1)[-1] != 'git':
            continue
        env = [w for w in raw[:len(raw) - len(words)] if re.match(r'GIT_\w*=', w)]
        if env or any(GIT_SMUGGLE.match(w) for w in words[1:]):
            return 'git with -c/-C/--exec-path/--git-dir or GIT_* environment is not allowed; plain git only'
    return None


def check(event):
    args = event.get('tool_input')
    if not isinstance(args, dict):
        return deny('invalid hook input')
    if event.get('tool_name') not in ('Bash', 'exec_command'):
        return 0
    command = args.get('command', args.get('cmd'))
    if isinstance(command, list):
        command = ' '.join(shlex.quote(str(c)) for c in command)
    if not isinstance(command, str) or not command.strip():
        return deny('missing shell command')
    reason = smuggles(command)
    return deny(reason) if reason else 0


def main():
    try:
        event = json.load(sys.stdin)
        return check(event) if isinstance(event, dict) else deny('invalid hook input')
    except (ValueError, OSError, TypeError) as error:
        return deny('cannot validate hook input: ' + str(error))


if __name__ == '__main__':
    sys.exit(main())
