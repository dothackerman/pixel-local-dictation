#!/usr/bin/env python3
"""Read-only three-stage contract/build readiness. No measurements or device access."""
import argparse
import json
from pathlib import Path
import subprocess

import check_release_1a
import measurement_identity as identity
import variant

ROOT = Path(__file__).resolve().parents[1]
LAB = ROOT / 'corpus/lab/allocation-v1'


def setup_errors(manifest):
    errors = []
    checked = {}
    def sha(path):
        stat = Path(path).stat()
        key = (stat.st_dev, stat.st_ino, stat.st_size, stat.st_mtime_ns, stat.st_ctime_ns)
        if key not in checked:
            checked[key] = identity.sha(path)
        return checked[key]
    if manifest.get('schema') != 1 or not manifest.get('setup_complete'):
        errors.append('unprepared allocation contract')
    legacy = manifest['legacy_manifest']
    if sha(legacy['path']) != legacy['sha256']:
        errors.append('legacy stage-one contract changed')
    errors += check_release_1a.check(json.loads(Path(legacy['path']).read_text()), setup_only=True)
    environment=json.loads((Path(legacy['path']).parent/'environment.json').read_text())
    for path,expected in environment['runtime'].items():
        if sha(path)!=expected:
            errors.append('laptop runtime changed: '+path)
    for path, expected in {**manifest['frozen_contracts'], **manifest['source_files']}.items():
        if sha(path) != expected:
            errors.append('frozen artifact changed: ' + path)
    runs = manifest['runs'] + manifest['retry_options']
    if len({r['id'] for r in runs}) != len(runs):
        errors.append('duplicate run identity')
    outputs = [r[k] for r in runs for k in ('output', 'validation_output')]
    if len(set(outputs)) != len(outputs):
        errors.append('colliding evidence namespace')
    models={}
    for run in runs:
        contract=json.loads(Path(run['expected_manifest']).read_text())
        expected=contract['provenance']
        if run['mode'] not in ('quality-full','historical-full') and (set(contract.get('input_samples',{}))!=set(contract['takes']+contract['endpoints']) or any(type(v) is not int or v<=0 for v in contract.get('input_samples',{}).values())):
            errors.append('missing exact input-sample coverage contract: '+run['id'])
        if run['platform']=='laptop':
            path=Path(run['command'][2])
            if sha(path)!=expected['configuration_sha256']:
                errors.append('laptop configuration changed: '+run['id'])
            config=variant.load(path)
            key=(config['model'],config['lid']['model'])
            if key not in models:
                paths=[p for name in key if name for p in (ROOT/'models'/name).rglob('*') if p.is_file()]
                paths.append(ROOT/'models/silero_vad.onnx')
                models[key]=identity.digest({str(p):sha(p) for p in sorted(paths)})
            if models[key]!=expected['model_sha256']:
                errors.append('laptop model changed: '+run['id'])
            if identity.digest(environment['runtime'])!=expected['runtime_sha256']:
                errors.append('laptop runtime inventory mismatch: '+run['id'])
            dictionary=sha(ROOT/'corpus/lab'/config['hotwords']['file']) if config['hotwords']['file'] else identity.digest([])
            if dictionary!=expected['dictionary_sha256']:
                errors.append('laptop dictionary changed: '+run['id'])
        for args in [run.get('command'), run['validate_command'], run.get('record_command')] + run.get('commands', []):
            if not args:
                continue
            if not Path(args[0]).is_file() or (args[0].endswith('python') and not Path(args[1]).is_file()):
                errors.append('missing scheduled executable: ' + run['id'])
        if run['platform'] == 'phone':
            stage = ROOT / run['staging']
            spec = json.loads((stage / ('historical-manifest.json' if run['mode']=='historical-full' else 'measurement.json')).read_text())
            for name, expected in spec['artifacts'].items():
                if sha(stage / name) != expected:
                    errors.append('staged artifact changed: ' + run['id'] + '/' + name)
            for item in spec['inputs']:
                path = stage / item['path']
                # Full-take paths are device-relative and staged by the existing
                # bounded lab operation; verify their host counterpart here.
                if item['path'].startswith('../benchmark/'):
                    path = ROOT / 'corpus/files/benchmark' / Path(item['path']).name
                if sha(path) != item['sha256']:
                    errors.append('input changed: ' + run['id'])
    if any(manifest['stages'][str(s)]['planned_processes']['laptop'] != n for s,n in ((1,10),(2,16),(3,16))):
        errors.append('planned process accounting changed')
    return errors


def launch_readiness(manifest):
    errors = setup_errors(manifest)
    current=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip()
    if manifest.get('source_commit')!=current:
        errors.append('checkout commit differs from frozen execution source')
    paths=[str(Path(p).relative_to(ROOT)) for p in manifest.get('source_files',{})]
    if paths:
        tracked=set(subprocess.check_output(['git','ls-files','--',*paths],cwd=ROOT,text=True).splitlines())
        if tracked!=set(paths) or subprocess.run(['git','diff','--quiet','HEAD','--',*paths],cwd=ROOT).returncode:
            errors.append('frozen source is not represented by the committed checkout')
    errors += device_validation_errors(manifest)
    if not Path(manifest['launch_record']).is_file():
        errors.append('missing separate explicit launch authorization')
    return errors


def device_validation_errors(manifest):
    errors = []
    proof = manifest.get('allocation_device_validation')
    if not proof:
        errors.append('missing authorized device smoke for exact stage-2/3 model/allocation persistent contracts')
    else:
        record = json.loads(Path(proof['path']).read_text())
        if identity.sha(proof['path']) != proof['sha256'] or record.get('complete') is not True:
            errors.append('invalid device validation')
        legacy = json.loads(Path(manifest['legacy_manifest']['path']).read_text())
        if record.get('apk') != legacy['apk'] or record.get('test_apk') != legacy['test_apk']:
            errors.append('device smoke build mismatch')
        configurations = {r['configuration'] for r in manifest['runs'] if r['stage'] in (2,3) and r['platform']=='phone'}
        observed = set()
        identities = set()
        for entry in record.get('runs', []):
            configuration = entry.get('configuration')
            try:
                if identity.sha(entry['report']) != entry['report_sha256'] or identity.sha(entry['expected_manifest']) != entry['expected_sha256']:
                    raise ValueError('smoke artifacts changed')
                import evaluate_v4
                envelope = json.loads(Path(entry['report']).read_text())
                expected = json.loads(Path(entry['expected_manifest']).read_text())
                result = evaluate_v4.compare(envelope, envelope, expected, expected)
                if result['evidence'] != 'complete' or envelope['provenance']['mode'] != 'persistent' or envelope['provenance']['platform'] != 'phone':
                    raise ValueError('incomplete persistent smoke')
                for key in ('run_id', 'process_start_id'):
                    value = (key, envelope['provenance'][key])
                    if value in identities:
                        raise ValueError('smoke reused freshness identity')
                    identities.add(value)
                prepared = next(json.loads(Path(r['expected_manifest']).read_text()) for r in manifest['runs']
                                if r['platform']=='phone' and r['configuration']==configuration)
                for key in ('build_sha256','test_build_sha256','runtime_sha256','model_sha256','configuration_sha256'):
                    if expected['provenance'].get(key) != prepared['provenance'].get(key):
                        raise ValueError('smoke differs from prepared model/runtime/allocation')
                refs={key:expected.get('endpoint_references',{})[key] for key in set(expected.get('endpoints',[]))}
                if not refs or {v.get('lang') for v in refs.values() if v.get('reference')} != {'de','en'} or not any(v.get('reference')=='' for v in refs.values()):
                    raise ValueError('smoke lacks public DE/EN/silence coverage')
                for row in envelope['report']['endpoints']:
                    if refs[row['id']].get('reference') and not row['text'].strip():
                        raise ValueError('smoke has missing speech finalization')
                    if refs[row['id']].get('reference') == '' and row['text'].strip():
                        raise ValueError('smoke has unexpected silence text')
                if not envelope['report']['memory'] or any(type(row.get('thermalStatus')) is not int or not 0<=row['thermalStatus']<=2 for row in envelope['report']['memory']):
                    raise ValueError('smoke lacks valid thermal counters')
                import allocation_report
                if allocation_report.input_sample_errors(envelope,expected):
                    raise ValueError('smoke coverage differs from frozen public suite')
                if allocation_report.persistent_pass_errors(envelope, expected):
                    raise ValueError('smoke persistent passes changed normalized text')
                observed.add(configuration)
            except (OSError, ValueError, KeyError, TypeError, StopIteration):
                errors.append('invalid device smoke: ' + str(configuration))
        if observed != configurations or len(record.get('runs', [])) != len(configurations):
            errors.append('device smoke does not cover every prepared configuration')
    return errors


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', type=Path, default=LAB / 'manifest.json')
    parser.add_argument('--setup-only', action='store_true')
    parser.add_argument('--device-only', action='store_true')
    args = parser.parse_args()
    try:
        manifest = json.loads(args.manifest.read_text())
        errors = device_validation_errors(manifest) if args.device_only else setup_errors(manifest) if args.setup_only else launch_readiness(manifest)
    except (OSError, ValueError, KeyError, TypeError) as error:
        errors = ['invalid/missing prepared artifact: ' + str(error)]
    print('\n'.join(errors) if errors else 'device contracts validated; launch remains separate' if args.device_only else 'host contracts verified; launch remains separate' if args.setup_only else 'ready')
    raise SystemExit(bool(errors))


if __name__ == '__main__':
    main()
