#!/usr/bin/env python3
"""Explicit version-4 envelope for the existing complete laptop quality/guard adapter.

This callable is prepared by setup; running it on the development corpus belongs to
an authorized batch. It does not change the historical evaluator or ledger.
"""
import argparse
import json
from pathlib import Path
import os
import uuid
import pipeline
import variant
import measurement_identity as identity

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('variant'); parser.add_argument('manifest'); parser.add_argument('output')
    args = parser.parse_args()
    manifest = json.loads(Path(args.manifest).read_text())
    v = variant.load(args.variant)
    takes = []
    for item in manifest['inputs']:
        path = (ROOT / item['path']).resolve()
        if not path.is_relative_to(ROOT / 'corpus') or identity.sha(path) != item['sha256']:
            raise ValueError('unexpected development input')
        takes.append(str(path))
    provenance = dict(manifest['provenance'], platform='laptop', mode='quality-full', feed=[640, 512, 800],
        build_sha256=identity.digest(identity.source_files(ROOT)), runtime_sha256=identity.digest(identity.runtime_files()),
        model_sha256=identity.digest(identity.model_files(ROOT, v)), configuration_sha256=identity.sha(args.variant),
        evaluator_sha256=identity.digest(identity.evaluator_files(ROOT)), run_id=str(uuid.uuid4()),
        process_start_id=f"{os.getpid()}:{Path('/proc/self/stat').read_text().split()[21]}")
    provenance['dictionary_sha256'] = identity.sha(ROOT / 'corpus/lab' / v['hotwords']['file']) if v['hotwords']['file'] else identity.digest([])
    provenance['inputs_sha256'] = identity.digest(manifest['inputs'])
    provenance['references_sha256'] = identity.digest(identity.reference_files(ROOT))
    result = dict(schema=4, outcome='running', provenance=provenance)
    with open(args.output, 'x') as destination:
        json.dump(result, destination); destination.flush()
        try:
            from runtime_config import set_profile_directory
            set_profile_directory(str(Path(args.output).with_suffix('.profiles').resolve()))
            hotwords = str(ROOT / 'corpus/lab' / v['hotwords']['file']) if v['hotwords']['file'] else ''
            result['report'] = pipeline.run(v, str(ROOT / 'models'), takes, str(ROOT / 'models/guard'),
                                           str(ROOT / 'models/silero_vad.onnx'), hotwords)
            result['outcome'] = 'complete'
        except Exception as failure:
            result['outcome'] = 'failed'
            result['failure'] = str(failure)
            raise
        finally:
            destination.seek(0); json.dump(result, destination, indent=2); destination.truncate()

if __name__ == '__main__':
    main()
