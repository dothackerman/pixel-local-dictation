#!/usr/bin/env python3
"""Prepare and preserve bounded public setup evidence; never access a device."""
import argparse
import copy
from datetime import datetime, timezone
import json
import math
from pathlib import Path
import wave

import allocation_report
import evaluate_v4
import measurement_identity as identity
from prepare_allocation import ROOT, LAB, LEGACY, PYTHON, read, save
from prepare_release_1a import link
from record_release_result import record as preserve

SMOKE = ROOT / 'corpus/lab/allocation-smoke-20261014'
NAMES = ['dict-beam8-fp32-s20-stock', 'baseline-stock',
         'dict-beam8-fp32-s20-arena-off', 'dict-beam8-fp32-s20-pattern-off',
         'cohere-stock', 'cohere-arena-off', 'cohere-pattern-off']


def require_unstarted_experiment():
    if any((directory / 'execution-events.jsonl').exists() for directory in (LEGACY, LAB)):
        raise ValueError('cannot change setup bindings after experiment execution began')


def prepare():
    require_unstarted_experiment()
    if (SMOKE / 'manifest.json').exists():
        raise ValueError('smoke work order already frozen; preserve it')
    legacy, combined = read(LEGACY / 'manifest.json'), read(LAB / 'manifest.json')
    runs = []
    for name in NAMES:
        template = next(r for r in combined['runs'] if r['stage'] in (2, 3)
                        and r['platform'] == 'phone' and r['configuration'] == name)
        original = ROOT / template['staging']
        spec = read(original / 'measurement.json')
        stage = SMOKE / 'push' / name
        stage.mkdir(parents=True)
        for artifact in spec['artifacts']:
            if not artifact.startswith('fixtures/'):
                link(original / artifact, stage / artifact)
        inputs = []
        for item in read(LEGACY / 'allocation-smoke-suite.json')['inputs']:
            item = copy.deepcopy(item)
            path = ROOT / item['path']
            if identity.sha(path) != item['sha256']:
                raise ValueError('public fixture changed')
            with wave.open(str(path)) as audio:
                if audio.getframerate() != 16000 or audio.getnchannels() != 1:
                    raise ValueError('unexpected public audio format')
                item['samples'] = audio.getnframes()
            link(path, stage / 'fixtures' / path.name)
            item['path'] = 'fixtures/' + path.name
            inputs.append(item)
        spec.update(inputs=inputs, repetitions=2, mode='persistent')
        spec['artifacts'] = {str(p.relative_to(stage)): identity.sha(p)
                             for p in stage.rglob('*') if p.is_file()}
        spec['provenance'].update(build_sha256=legacy['apk']['sha256'],
            test_build_sha256=legacy['test_apk']['sha256'],
            inputs_sha256=identity.digest(inputs), evaluator_sha256=identity.digest(identity.evaluator_files(ROOT)))
        save(stage / 'measurement.json', spec)
        expected = dict(provenance=spec['provenance'], takes=[],
            endpoints=[i['id'] for i in inputs] * 2,
            endpoint_references={i['id']: {k: i[k] for k in ('reference', 'lang', 'cut_word')} for i in inputs},
            input_samples={i['id']: i['samples'] for i in inputs})
        contract = SMOKE / 'contracts' / (name + '.json')
        save(contract, expected)
        output = SMOKE / 'runs' / (name + '.json')
        runs.append(dict(id=name, configuration=name, platform='phone', mode='persistent',
            staging=str(stage.relative_to(ROOT)), output=str(output), raw_output=str(output.with_suffix('.raw.json')),
            expected_manifest=str(contract), expected_sha256=identity.sha(contract),
            timeout_seconds=420, audio_seconds=sum(i['samples'] for i in inputs) * 2 / 16000,
            commands=[[str(ROOT / 'tools/phone'), 'push-variant', str(stage)],
                      [str(ROOT / 'tools/phone'), 'lab'], [str(ROOT / 'tools/phone'), 'pull-results']]))
    (SMOKE / 'runs').mkdir()
    manifest = dict(schema=1, purpose='public setup smoke only; no personal experiment',
        apk=legacy['apk'], test_apk=legacy['test_apk'], runs=runs,
        owner='root', window=dict(start='2026-10-14T16:40:00+02:00', end='2026-10-14T19:40:00+02:00'),
        limits=dict(planned_processes=7, maximum_processes=12, inference_seconds=3600,
                    automatic_retries=0, timeout_seconds=420),
        restoration=dict(owner='root', configuration='baseline-stock', benchmark_app='retained'),
        frozen_files=identity.files([Path(r['expected_manifest']) for r in runs] +
                                   [ROOT / r['staging'] / 'measurement.json' for r in runs]))
    save(SMOKE / 'manifest.json', manifest)
    return manifest


def validate(manifest, run):
    expected = read(run['expected_manifest'])
    envelope = read(run['output'])
    result = evaluate_v4.compare(envelope, envelope, expected, expected)
    errors = result['evidence_errors'] + allocation_report.input_sample_errors(envelope, expected)
    for path, digest in manifest.get('frozen_files', {}).items():
        if identity.sha(path) != digest:
            errors.append('frozen smoke artifact changed: ' + path)
    for key in ('apk', 'test_apk'):
        if identity.sha(ROOT / manifest[key]['path']) != manifest[key]['sha256']:
            errors.append('smoke binary changed: ' + key)
    if identity.sha(run['expected_manifest']) != run['expected_sha256']:
        errors.append('smoke contract changed')
    rows = envelope.get('report', {}).get('endpoints', [])
    refs = expected['endpoint_references']
    for row in rows:
        reference = refs[row['id']]['reference']
        if reference and not row['text'].strip():
            errors.append('missing public speech finalization')
        if reference == '' and row['text'].strip():
            errors.append('unexpected text on silence')
    memory = envelope.get('report', {}).get('memory', [])
    if not memory or any(type(r.get('thermalStatus')) is not int or not 0 <= r['thermalStatus'] <= 2 for r in memory):
        errors.append('missing or excessive thermal status')
    errors += allocation_report.persistent_pass_errors(envelope, expected)
    return dict(evidence='incomplete' if errors else 'complete', errors=errors,
                quality='public speech/silence smoke only; no personal or guard ranking',
                resources=result.get('resources'))


def record(manifest, run_id, source, elapsed):
    if elapsed is None or not math.isfinite(elapsed) or elapsed < 0 or elapsed > 420:
        raise ValueError('timeout; retain failed attempt in root setup ledger, stop smoke')
    run = next(r for r in manifest['runs'] if r['id'] == run_id)
    # Existing legacy/combined reports also participate in freshness checks.
    context = dict(manifest, runs=manifest['runs'] + read(LEGACY / 'manifest.json')['runs'] + read(LAB / 'manifest.json')['runs'])
    preserve(context, run_id, source)
    result = validate(manifest, run)
    save(Path(run['output']).with_suffix('.validation.json'), dict(result, elapsed_seconds=elapsed,
         recorded_at=datetime.now(timezone.utc).isoformat(), report_sha256=identity.sha(run['output'])))
    return result


def stage1_proof(manifest):
    require_unstarted_experiment()
    run = next(r for r in manifest['runs'] if r['id'] == NAMES[0])
    if validate(manifest, run)['evidence'] != 'complete':
        raise ValueError('current FP32 stock persistent smoke has not passed')
    result = dict(report=str(Path(run['output']).relative_to(ROOT)), sha256=identity.sha(run['output']),
        app_sha256=manifest['apk']['sha256'], test_apk_sha256=manifest['test_apk']['sha256'],
        note='current public DE/EN/silence two-pass persistent setup smoke; historical raw evidence unchanged')
    path = SMOKE / 'stage1-device-setup.json'
    save(path, result)
    legacy = read(LEGACY / 'manifest.json')
    legacy['device_setup'] = str(path.relative_to(ROOT))
    save(LEGACY / 'manifest.json', legacy)
    return result


def proof(manifest):
    if [r['configuration'] for r in manifest['runs']] != NAMES:
        raise ValueError('smoke does not cover frozen seven-configuration order')
    entries, elapsed = [], 0
    identities = set()
    for run in manifest['runs']:
        if validate(manifest, run)['evidence'] != 'complete':
            raise ValueError('invalid smoke: ' + run['id'])
        validation = read(Path(run['output']).with_suffix('.validation.json'))
        if validation['report_sha256'] != identity.sha(run['output']):
            raise ValueError('recorded evidence changed')
        seconds = validation['elapsed_seconds']
        if not math.isfinite(seconds) or not 0 <= seconds <= 420 or validation.get('evidence') != 'complete':
            raise ValueError('invalid recording/timeout')
        provenance = read(run['output'])['provenance']
        for key in ('run_id', 'process_start_id'):
            value = (key, provenance[key])
            if value in identities:
                raise ValueError('smoke reused freshness identity')
            identities.add(value)
        elapsed += validation['elapsed_seconds']
        entries.append(dict(configuration=run['configuration'], report=run['output'],
            report_sha256=identity.sha(run['output']), expected_manifest=run['expected_manifest'],
            expected_sha256=identity.sha(run['expected_manifest'])))
    if elapsed > manifest['limits']['inference_seconds']:
        raise ValueError('cumulative smoke inference budget exceeded')
    result = dict(complete=True, apk=manifest['apk'], test_apk=manifest['test_apk'],
                  runs=entries, inference_seconds=elapsed, purpose=manifest['purpose'])
    save(SMOKE / 'device-validation.json', result)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('prepare', 'record', 'validate', 'proof', 'stage1-proof', 'attach'))
    parser.add_argument('--id'); parser.add_argument('--source'); parser.add_argument('--elapsed-seconds', type=float)
    args = parser.parse_args()
    manifest = prepare() if args.action == 'prepare' else read(SMOKE / 'manifest.json')
    if args.action == 'record':
        result = record(manifest, args.id, args.source, args.elapsed_seconds)
    elif args.action == 'validate':
        result = validate(manifest, next(r for r in manifest['runs'] if r['id'] == args.id))
    elif args.action == 'proof':
        result = proof(manifest)
    elif args.action == 'stage1-proof':
        result = stage1_proof(manifest)
    elif args.action == 'attach':
        require_unstarted_experiment()
        result = proof(manifest)
        combined = read(LAB / 'manifest.json')
        path = SMOKE / 'device-validation.json'
        combined['allocation_device_validation'] = dict(path=str(path), sha256=identity.sha(path))
        save(LAB / 'manifest.json', combined)
    else:
        result = dict(prepared=len(manifest['runs']), manifest=str(SMOKE / 'manifest.json'))
    print(json.dumps(result, indent=2))
    return result.get('evidence') == 'incomplete'


if __name__ == '__main__':
    raise SystemExit(main())
