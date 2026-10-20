#!/usr/bin/env python3
"""Plan completed lab cleanup; apply only the unchanged, private hash manifest.

python3 tools/prune_corpus.py EXPERIMENT [EXPERIMENT ...]
python3 tools/prune_corpus.py --apply corpus/retained/prune-TIMESTAMP.json
Stop experiment writers first. Archive decision evidence before planning.
Original corpus, recovery assets, baseline and dictionary are never targets.
"""
import argparse
from collections import Counter
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import stat

ROOT = Path(__file__).resolve().parents[1]
PROTECTED = {'phone-operations', 'baseline.json', 'dictionary.txt'}


def targets(root, names):
    lab = root / 'corpus/lab'
    if lab.resolve() != lab.absolute():
        raise ValueError('corpus/lab must not traverse symlinks')
    if not names or len(set(names)) != len(names):
        raise ValueError('provide distinct completed lab children')
    result = []
    for name in names:
        if name in PROTECTED or name in {'.', '..'} or Path(name).name != name:
            raise ValueError('target must be an unprotected immediate lab child')
        path = lab / name
        if path.is_symlink() or path.resolve().parent != lab.resolve():
            raise ValueError('symlink or escaped target')
        if not path.exists():
            raise ValueError(f'missing target: {name}')
        result.append(path)
    return result


def inventory(root, names):
    entries, hashes, links, states = {}, {}, Counter(), {}
    for target in targets(root, names):
        paths = [target, *target.rglob('*')] if target.is_dir() else [target]
        for path in sorted(paths):
            s = path.lstat()
            if stat.S_ISDIR(s.st_mode):
                entries[str(path.relative_to(root))] = {'kind': 'directory'}
                continue
            if not stat.S_ISREG(s.st_mode):
                raise ValueError(f'non-regular file: {path}')
            key = (s.st_dev, s.st_ino)
            if key not in hashes:
                with path.open('rb') as f:
                    hashes[key] = hashlib.file_digest(f, 'sha256').hexdigest()
            entries[str(path.relative_to(root))] = {'kind': 'file', 'bytes': s.st_size,
                'sha256': hashes[key], 'device': s.st_dev, 'inode': s.st_ino,
                'links': s.st_nlink, 'mtime_ns': s.st_mtime_ns}
            links[key] += 1
            states[key] = s
    reclaim = sum(s.st_blocks * 512 for key, s in states.items() if links[key] == s.st_nlink)
    return {'targets': names, 'entries': entries, 'files': sum(links.values()),
            'reclaimable_bytes': reclaim}


def apply(root, plan):
    current = inventory(root, plan['targets'])
    if current != plan['inventory']:
        raise ValueError('inventory changed; create a fresh plan')
    # Validate every entry before the first deletion. Writers must remain stopped.
    for relative, item in sorted(current['entries'].items(), key=lambda kv: len(Path(kv[0]).parts), reverse=True):
        path = root / relative
        if item['kind'] == 'file':
            path.unlink()
        else:
            path.rmdir()
    return current


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('targets', nargs='*')
    parser.add_argument('--apply', type=Path)
    args = parser.parse_args()
    retained = ROOT / 'corpus/retained'
    if retained.resolve() != retained.absolute():
        parser.error('retained directory must not traverse symlinks')
    retained.mkdir(exist_ok=True)
    if args.apply:
        if args.targets or args.apply.is_symlink() or args.apply.resolve().parent != retained.resolve():
            parser.error('--apply requires a plan directly inside corpus/retained and no targets')
        plan = json.loads(args.apply.read_text())
        if plan.get('schema') != 1 or plan.get('applied_at'):
            parser.error('unsupported or already applied plan')
        result = apply(ROOT, plan)
        plan['applied_at'] = datetime.now(timezone.utc).isoformat()
        args.apply.write_text(json.dumps(plan, indent=2) + '\n')
        print(f"Deleted {result['files']} files; estimated reclaimed {result['reclaimable_bytes']} bytes")
    else:
        result = inventory(ROOT, args.targets)
        stamp = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
        path = retained / f'prune-{stamp}.json'
        plan = {'schema': 1, 'created_at': stamp, 'targets': args.targets, 'inventory': result}
        with path.open('x') as output:
            json.dump(plan, output, indent=2)
            output.write('\n')
        print(f"Dry run: {result['files']} files; reclaimable {result['reclaimable_bytes']} bytes")
        print(path.relative_to(ROOT))


if __name__ == '__main__':
    main()
