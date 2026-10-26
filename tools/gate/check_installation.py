#!/usr/bin/env python3
"""Read-only installation validation. This does not prove a harness can sandbox.

After applying the gate, run this in a fresh agent session without escalation.
Successful sandbox launch and the host checks are separate from these file checks.
"""
import json
from pathlib import Path
import sys
import tomllib


def errors(root):
    root = Path(root)
    gate = root / 'tools/gate'
    problems = []
    for source, installed in (('AGENTS.md', 'AGENTS.md'), ('AGENTS.md', 'CLAUDE.md'),
                              ('config.toml', '.codex/config.toml'),
                              ('settings.json', '.claude/settings.json')):
        path = root / installed
        if path.is_symlink() or not path.is_file():
            problems.append(installed + ' must be an installed regular file, not a symlink')
        elif path.read_bytes() != (gate / source).read_bytes():
            problems.append(installed + ' differs from tools/gate/' + source)
    config = tomllib.loads((gate / 'config.toml').read_text())
    protected = set(config['permissions']['dictation']['filesystem'][':workspace_roots'])
    settings = json.loads((gate / 'settings.json').read_text())
    protected.update(settings['sandbox']['filesystem']['denyWrite'])
    for name in sorted(protected):
        path = root
        for part in Path(name).parts:
            path /= part
            if path.is_symlink():
                problems.append('protected path crosses symlink: ' + str(path.relative_to(root)))
                break
    return sorted(set(problems))


if __name__ == '__main__':
    try:
        problems = errors(Path(__file__).resolve().parents[2])
    except (OSError, ValueError, KeyError) as error:
        problems = [str(error)]
    if problems:
        print('\n'.join(problems), file=sys.stderr)
        raise SystemExit(1)
    print('Installation files match; sandbox launch and host checks must be verified separately.')
