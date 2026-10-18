"""Read-only stability evidence around full allocation readiness validation.

Content validation remains the frozen checker's responsibility. This inventory
records its transitive local manifest/configuration inputs, runtime and model
trees, and device-side input counterparts, then detects changes after validation.
"""
import json
import os
from pathlib import Path
import re


def metadata(path):
    path = Path(path)
    values = []
    for info in (path.lstat(), path.stat()):
        values.append([info.st_dev, info.st_ino, info.st_mode, info.st_size,
                       info.st_mtime_ns, info.st_ctime_ns])
    return dict(resolved=str(path.resolve()), metadata=values)


def inventory(manifest_path, root):
    root = Path(root)
    paths, documents, expanded, inspected = set(), [], set(), set()

    def add(candidate, tree=False):
        candidate = Path(os.path.abspath(candidate))
        if candidate in paths or not candidate.exists():
            return
        if candidate.is_dir() and not tree:
            return
        paths.add(candidate)
        if candidate.is_dir():
            resolved = candidate.resolve()
            if resolved in expanded:
                return
            expanded.add(resolved)
            for child in sorted(candidate.iterdir()):
                add(child, tree=True)
        elif candidate.suffix == '.json':
            documents.append(candidate)

    def inspect(value, parent, field=None):
        if isinstance(value, dict):
            for key, item in value.items():
                inspect(key, parent)
                inspect(item, parent, key)
        elif isinstance(value, list):
            for item in value:
                inspect(item, parent)
        elif (isinstance(value, str) and value and '\x00' not in value
              and '\n' not in value and len(value) < 4096
              and all(len(part.encode()) <= 255 for part in value.split('/'))):
            path = Path(value)
            if value in ('.', '..') or (parent, field, value) in inspected:
                return
            inspected.add((parent, field, value))
            if '/' not in value and not path.suffix:
                if field == 'model' and re.fullmatch(r'[A-Za-z0-9_-]+', value):
                    add(root / 'models' / value, tree=True)
                return
            if path.is_absolute():
                model_tree = (path.is_relative_to(root / 'models')
                              and not path.is_relative_to(root / 'models/venv')
                              and path != root / 'models')
                add(path, tree=model_tree or '/push/' in str(path))
            else:
                # Config model names are rooted in models/; JSON-relative
                # staged artifacts and repository-relative source/input paths
                # cover the frozen checker's other local path conventions.
                for candidate in (root / path, parent / path, root / 'models' / path,
                                  root / 'corpus/lab' / path):
                    canonical = Path(os.path.abspath(candidate))
                    model_tree = (canonical.is_relative_to(root / 'models')
                                  and not canonical.is_relative_to(root / 'models/venv')
                                  and canonical != root / 'models')
                    add(canonical, tree=model_tree or '/push/' in str(canonical))

    add(manifest_path)
    manifest = json.loads(Path(manifest_path).read_text())
    add(Path(manifest['legacy_manifest']['path']).parent / 'environment.json')
    add(root / 'corpus/files/benchmark', tree=True)
    add(root / 'models/guard', tree=True)
    add(root / 'corpus/scripts', tree=True)
    add(root / 'corpus/takes.json')
    add(root / 'docs/evaluation/baseline.md')
    frozen_index = root / 'tools/frozen-v4.sha256'
    add(frozen_index)
    if frozen_index.is_file():
        for line in frozen_index.read_text().splitlines():
            if line and not line.startswith('#'):
                add(root / line.split(maxsplit=1)[1])
    while documents:
        document = documents.pop()
        inspect(json.loads(document.read_text()), document.parent)
    return {str(path): metadata(path) for path in sorted(paths)}


def verify(snapshot):
    for path, expected in snapshot.items():
        if metadata(path) != expected:
            raise ValueError('validated artifact changed after readiness: ' + path)
