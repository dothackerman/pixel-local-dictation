#!/usr/bin/env python3
"""Read-only installation validation. This does not prove a harness can sandbox.

After applying the gate, run this in a fresh agent session without escalation.
Successful sandbox launch and the host checks are separate from these file checks.
"""
import json
import os
from pathlib import Path
import sys
import tomllib


def codex_config(root):
    """Render checkout protections without projecting them into auxiliary roots."""
    root = Path(root).resolve()
    template = (root / 'tools/gate/config.toml').read_text()
    table = '[permissions.dictation.filesystem.":workspace_roots"]'
    if template.count(table) != 1:
        raise ValueError('expected one checkout-protection table in tools/gate/config.toml')
    return template.replace(table, '[permissions.dictation.filesystem.' + json.dumps(str(root)) + ']')


def wrapper():
    return Path(os.environ.get('XDG_DATA_HOME') or Path.home() / '.local/share') / 'pixel-phone/phone'


def errors(root):
    root = Path(root)
    gate = root / 'tools/gate'
    problems = []
    # (source, installed copy): apply.sh installs each one
    copies = [(gate / 'AGENTS.md', 'AGENTS.md'), (gate / 'AGENTS.md', 'CLAUDE.md'),
              (gate / 'settings.json', '.claude/settings.json'), (gate / 'hooks.json', '.codex/hooks.json'),
              (gate / 'phone', '.claude/hooks/phone'), (gate / 'phone-guard.sh', '.claude/hooks/phone-guard.sh'),
              (gate / 'phone_guard.py', '.claude/hooks/phone_guard.py'), (root / 'tools/phone', wrapper())]
    for source, installed in copies:
        path = root / installed
        if path.is_symlink() or not path.is_file():
            problems.append(str(installed) + ' must be an installed regular file, not a symlink')
        elif path.read_bytes() != source.read_bytes():
            problems.append(str(installed) + ' differs from ' + str(source.relative_to(root)))
    installed_config = root / '.codex/config.toml'
    if installed_config.is_symlink() or not installed_config.is_file():
        problems.append('.codex/config.toml must be an installed regular file, not a symlink')
    elif installed_config.read_text() != codex_config(root):
        problems.append('.codex/config.toml is not rendered for this checkout; owner must apply the gate here')
    config = tomllib.loads((gate / 'config.toml').read_text())
    for name in sorted(config['permissions']['dictation']['filesystem'][':workspace_roots']):
        path = root
        for part in Path(name).parts:
            path /= part
            if path.is_symlink():
                problems.append('protected path crosses symlink: ' + str(path.relative_to(root)))
                break
    return sorted(set(problems))


if __name__ == '__main__':
    try:
        root = Path(__file__).resolve().parents[2]
        if sys.argv[1:] == ['--render-codex']:
            print(codex_config(root), end='')
            raise SystemExit(0)
        if sys.argv[1:]:
            raise ValueError('usage: check_installation.py [--render-codex]')
        problems = errors(root)
    except (OSError, ValueError, KeyError) as error:
        problems = [str(error)]
    if problems:
        print('\n'.join(problems), file=sys.stderr)
        raise SystemExit(1)
    print('Installation files match; sandbox launch and host checks must be verified separately.')
