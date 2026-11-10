"""Content identities for setup manifests and measured reports (no inference)."""
import hashlib
import csv
import json
from pathlib import Path

from score import BASELINE_REFERENCE_PATH


def sha(path):
    digest = hashlib.sha256()
    with Path(path).open('rb') as source:
        for block in iter(lambda: source.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def files(paths):
    return {str(path): sha(path) for path in sorted(map(Path, paths))}


def reference_files(root):
    root = Path(root)
    return files(list((root / 'corpus/scripts').glob('*.txt')) + list((root / 'corpus/scripts').glob('*.terms')) +
                 [root / 'corpus/takes.json', root / 'models/guard/refs.tsv', root / BASELINE_REFERENCE_PATH])


def quality_inputs(root, takes):
    root = Path(root)
    with (root / 'models/guard/refs.tsv').open() as source:
        rows = list(csv.DictReader(source, delimiter='\t'))
    return digest({'takes': takes, 'guard': files(root / 'models/guard' / row['lang'] /
        (row['id'] + '.wav') for row in rows),
        'synthetic': {'rate': 16000, 'warmup_seconds': 1, 'silence_seconds': 5,
                      'noise_seconds': 5, 'noise_seed': 0, 'noise_scale': 0.003}})
