#!/usr/bin/env python3
"""Repository layout checks run by the host gate: relative Markdown links and agent skills.

python3 tools/repo_checks.py   prints each problem; exit 1 when any exists.
"""
import json
import re
import subprocess
import sys
from pathlib import Path
from urllib.parse import unquote

ROOT = Path(__file__).resolve().parents[1]
# Installer-owned copies are never hand-edited; their links are theirs.
VENDORED = ('.agents/skills/', '.claude/skills/', '.claude/agents/', 'licenses/')
LINK = re.compile(r'!?\[[^\]]*\]\(\s*<?([^)\s>]+)>?(?:\s+"[^"]*")?\s*\)')
FENCE = re.compile(r'^\s*(```|~~~)')
CODE_SPAN = re.compile(r'`[^`\n]*`')


def tracked_markdown(root):
    names = subprocess.check_output(['git', 'ls-files', '--cached', '--others', '--exclude-standard', '*.md'],
                                    cwd=root, text=True).splitlines()
    return sorted({n for n in names if not n.startswith(VENDORED) and (Path(root) / n).is_file()})


def link_targets(text):
    """Relative link targets outside fenced code and code spans."""
    fenced = False
    for number, line in enumerate(text.splitlines(), 1):
        if FENCE.match(line):
            fenced = not fenced
            continue
        if fenced:
            continue
        for match in LINK.finditer(CODE_SPAN.sub('', line)):
            target = match.group(1)
            if re.match(r'^[a-zA-Z][a-zA-Z0-9+.-]*:|^#|^/', target):
                continue
            yield number, target


def broken_links(root=ROOT, files=None):
    root = Path(root)
    problems = []
    for name in files if files is not None else tracked_markdown(root):
        source = root / name
        for number, target in link_targets(source.read_text(encoding='utf-8')):
            path = unquote(target.split('#', 1)[0].split('?', 1)[0])
            if not path:
                continue
            resolved = (source.parent / path).resolve()
            if not resolved.exists():
                problems.append(f'{name}:{number}: broken link {target}')
    return problems


def impeccable_version(skill_file):
    match = re.search(r'^\s*version:\s*([0-9][^\s]*)\s*$', skill_file.read_text(encoding='utf-8'), flags=re.M)
    return match.group(1) if match else None


def skill_problems(root=ROOT):
    """Matt Pocock skills: one copy in .agents/skills, symlinked from .claude/skills (skills-lock.json).
    Impeccable: one rendered copy per harness, never linked; both report one version."""
    root = Path(root)
    problems = []
    locked = json.loads((root / 'skills-lock.json').read_text())['skills']
    claude = root / '.claude/skills'
    for name in sorted(locked):
        source = root / '.agents/skills' / name
        link = claude / name
        if not (source / 'SKILL.md').is_file():
            problems.append(f'.agents/skills/{name}: locked skill missing')
        if not link.is_symlink():
            problems.append(f'.claude/skills/{name}: must be a symlink to .agents/skills/{name}')
        elif link.resolve() != source.resolve() or not link.exists():
            problems.append(f'.claude/skills/{name}: symlink does not resolve to .agents/skills/{name}')
    codex_impeccable = root / '.agents/skills/impeccable/SKILL.md'
    claude_impeccable = claude / 'impeccable'
    if claude_impeccable.is_symlink() or not (claude_impeccable / 'SKILL.md').is_file():
        problems.append('.claude/skills/impeccable: must be its own rendered copy, not a link')
    elif not codex_impeccable.is_file():
        problems.append('.agents/skills/impeccable: Codex copy missing')
    else:
        versions = {impeccable_version(codex_impeccable), impeccable_version(claude_impeccable / 'SKILL.md')}
        if len(versions) != 1 or None in versions:
            problems.append(f'Impeccable copies report different versions: {sorted(map(str, versions))}')
    for entry in sorted(claude.iterdir()) if claude.is_dir() else []:
        if entry.name not in locked and entry.name != 'impeccable':
            if entry.is_dir() and not entry.is_symlink():
                problems.append(f'.claude/skills/{entry.name}: real folder not owned by an installer')
            elif entry.is_symlink():
                problems.append(f'.claude/skills/{entry.name}: link without a skills-lock.json entry')
    return problems


def main():
    problems = broken_links() + skill_problems()
    print('\n'.join(problems), file=sys.stderr) if problems else None
    return 1 if problems else 0


if __name__ == '__main__':
    raise SystemExit(main())
