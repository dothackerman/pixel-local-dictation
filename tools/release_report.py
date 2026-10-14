#!/usr/bin/env python3
"""Validate one frozen run immediately, or compare a frozen pair. No inference."""
import argparse
import json
from pathlib import Path

import evaluate_v4 as evaluator

LAB = Path(__file__).resolve().parents[1] / 'corpus/lab/release-1a'


def read(path):
    return json.loads(Path(path).read_text())


def evaluate(manifest, run_id):
    runs = manifest['runs'] + manifest['retry_options']
    run = next(r for r in runs if r['id'] == run_id)
    expected = read(run['expected_manifest'])
    envelope = read(run['output'])
    result = evaluator.compare(envelope, envelope, expected, expected)
    result['check'] = 'single-run evidence and diagnostics; not independent repetition'
    result['run_id'] = run_id
    return result, run['validation_output']


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('run_id')
    args = parser.parse_args()
    result, output = evaluate(read(LAB / 'manifest.json'), args.run_id)
    with Path(output).open('x') as target:
        json.dump(result, target, indent=2)
    raise SystemExit(result['evidence'] != 'complete')


if __name__ == '__main__':
    main()
