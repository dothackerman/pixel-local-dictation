"""Content identities for setup manifests and measured reports (no inference)."""
import hashlib
import csv
import json
from pathlib import Path


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


def model_files(root, variant):
    paths = []
    for directory in (variant['model'], variant['lid']['model']):
        if directory:
            paths += [p for p in (Path(root) / 'models' / directory).rglob('*') if p.is_file()]
    paths.append(Path(root) / 'models/silero_vad.onnx')
    return files(paths)


def source_files(root):
    return files(Path(root) / 'tools' / name for name in (
        'measurement.py', 'quality_measurement.py', 'measurement_identity.py', 'runtime_config.py', 'pipeline.py', 'variant.py'))


def runtime_files():
    import sherpa_onnx
    root = Path(sherpa_onnx.__file__).resolve().parent.parent
    return files(p for p in root.rglob('*.so*') if ('sherpa' in str(p) or 'onnxruntime' in str(p)) and p.is_file())


def evaluator_files(root):
    return files(Path(root) / 'tools' / name for name in ('evaluate_v4.py', 'evaluate.py', 'score.py'))


def reference_files(root):
    root = Path(root)
    return files(list((root / 'corpus/scripts').glob('*.txt')) + list((root / 'corpus/scripts').glob('*.terms')) +
                 [root / 'corpus/takes.json', root / 'models/guard/refs.tsv', root / 'docs/evaluation/baseline.md'])


def quality_inputs(root, takes):
    root = Path(root)
    with (root / 'models/guard/refs.tsv').open() as source:
        rows = list(csv.DictReader(source, delimiter='\t'))
    return digest({'takes': takes, 'guard': files(root / 'models/guard' / row['lang'] /
        (row['id'] + '.wav') for row in rows),
        'synthetic': {'rate': 16000, 'warmup_seconds': 1, 'silence_seconds': 5,
                      'noise_seconds': 5, 'noise_seed': 0, 'noise_scale': 0.003}})
