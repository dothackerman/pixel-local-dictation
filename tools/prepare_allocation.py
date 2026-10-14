#!/usr/bin/env python3
"""Prepare versioned three-stage contracts, hardlinked staging; never execute."""
import copy
import json
from pathlib import Path
import subprocess

import measurement_identity as identity
import variant
from prepare_release_1a import link

ROOT = Path(__file__).resolve().parents[1]
LAB = ROOT / 'corpus/lab/allocation-v1'
LEGACY = ROOT / 'corpus/lab/release-1a'
PYTHON = str(ROOT / 'models/venv/bin/python')


def read(path):
    return json.loads(Path(path).read_text())


def save(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + '\n')


def command(action, run_id=None, output=None):
    result = [PYTHON, str(ROOT / 'tools/allocation_report.py'), action,
              '--manifest', str(LAB / 'manifest.json')]
    if run_id:
        result += ['--id', run_id]
    if output:
        result += ['--output', str(output)]
    return result


def prepare():
    if (LAB / 'execution-events.jsonl').exists():
        raise ValueError('execution ledger exists; never refreeze an active experiment')
    legacy = read(LEGACY / 'manifest.json')
    if (LEGACY / 'execution-events.jsonl').exists():
        raise ValueError('legacy execution ledger exists; historical execution needs explicit migration')
    if any((ROOT / 'corpus/scripts' / (sid + '.txt')).exists() for sid in ('de-4', 'de-5', 'en-4', 'en-5')):
        raise ValueError('reserved scripts present')
    for directory in ('runs', 'reports', 'contracts', 'comparisons'):
        (LAB / directory).mkdir(parents=True, exist_ok=True)
    if list((LAB / 'runs').iterdir()) or list((LAB / 'reports').iterdir()):
        raise ValueError('existing evidence; use a new versioned namespace')
    runs, comparisons, stages = [], [], {}
    # A new namespace records every stage-one result without modifying its old
    # contracts, smoke reports, historical thresholds, order or commands.
    for original in legacy['runs']:
        run = copy.deepcopy(original)
        run.update(stage=1, configuration=run.get('command', ['','', 'stock'])[2] if run['platform'] == 'laptop' else 'fp32-stock')
        run['mode'] = read(run['expected_manifest'])['provenance']['mode']
        run['legacy_id'] = run['id']
        run['id'] = 's1-' + run['id']
        old_output = run['output']
        run['output'] = str(LAB / 'runs' / (run['id'] + '.json'))
        if 'command' in run:
            run['command'] = [run['output'] if arg == old_output else arg for arg in run['command']]
        if run['platform'] == 'phone':
            run['raw_output'] = str(Path(run['output']).with_suffix('.raw.json'))
            run['record_command'] = command('record', run['id']) + ['--source', 'RETURNED_JSON_PATH']
            run.pop('wrap_command', None)  # recorder owns historical wrapping
        runs.append(run)
    old_to_new = {r['legacy_id']: r['id'] for r in runs}
    for run in runs:
        if run['platform']=='phone':
            run['quality_companions']=[old_to_new[f'laptop-dict-beam8-fp32-s20-stock-quality-full-{repeat}'] for repeat in (1,2)]
    for original in legacy['comparisons']:
        comparison = copy.deepcopy(original)
        comparison.update(stage=1, id='s1-' + original['id'],
                          candidate=old_to_new[original['candidate']], control=old_to_new[original['control']])
        comparison['output'] = str(LAB / 'reports' / (comparison['id'] + '.json'))
        comparisons.append(comparison)
    takes = read(LEGACY / 'quality-suite.json')['inputs']
    diagnostic = read(LEGACY / 'diagnostic-suite.json')
    quality = read(LEGACY / 'quality-suite.json')
    guards = read(legacy['runs'][0]['expected_manifest'])['guard']
    languages = read(legacy['runs'][0]['expected_manifest'])['take_languages']
    dictionary = read(legacy['runs'][0]['expected_manifest'])['scoring_dictionary']
    suite_paths = {}
    for mode, suite in (('quality-full', quality), ('persistent', diagnostic)):
        suite_paths[mode] = LAB / (mode + '-suite.json')
        save(suite_paths[mode], suite)
    runtime = identity.digest(identity.runtime_files())
    source = identity.digest(identity.source_files(ROOT))
    evaluation = identity.digest(identity.evaluator_files(ROOT))
    references = identity.digest(identity.reference_files(ROOT))
    model_hashes = {}
    base = copy.deepcopy(read(legacy['runs'][0]['expected_manifest']))
    endpoint_contract = next(read(r['expected_manifest']) for r in legacy['runs'] if r['platform'] == 'laptop' and read(r['expected_manifest'])['provenance']['mode'] == 'persistent')
    for stage, family in ((2, 'dict-beam8-fp32-s20'), (3, 'cohere')):
        names = ['baseline-stock'] + [family + '-' + setting for setting in ('stock', 'arena-off', 'pattern-off')]
        stock=variant.load(ROOT/'variants'/(family+'-stock.json'))
        for setting,changed_key in (('arena-off','model_arena'),('pattern-off','model_pattern')):
            candidate=variant.load(ROOT/'variants'/(family+'-'+setting+'.json'))
            expected=copy.deepcopy(stock)
            expected['runtime'][changed_key]=False
            for configuration in (candidate,expected):
                configuration.pop('name',None)
                configuration.pop('description',None)
            if candidate != expected:
                raise ValueError('allocation is not sole change: '+family+'-'+setting)
        for mode in ('quality-full', 'persistent'):
            # Second pass reverses settings to balance sequence effects.
            order = [(name, 1) for name in names] + [(name, 2) for name in reversed(names)]
            for name, repetition in order:
                path = ROOT / 'variants' / (name + '.json')
                config = variant.load(path)
                key = (config['model'], config['lid']['model'])
                if key not in model_hashes:
                    model_hashes[key] = identity.digest(identity.model_files(ROOT, config))
                expected = copy.deepcopy(base if mode == 'quality-full' else endpoint_contract)
                expected['provenance'].update(build_sha256=source, runtime_sha256=runtime,
                    evaluator_sha256=evaluation, model_sha256=model_hashes[key],
                    configuration_sha256=identity.sha(path), references_sha256=references,
                    dictionary_sha256=identity.sha(ROOT / 'corpus/lab' / config['hotwords']['file']) if config['hotwords']['file'] else identity.digest([]))
                run_id = f's{stage}-laptop-{name}-{mode}-{repetition}'
                output = LAB / 'runs' / (run_id + '.json')
                adapter = 'quality_measurement.py' if mode == 'quality-full' else 'measurement.py'
                args = [PYTHON, str(ROOT / 'tools' / adapter), str(path), str(suite_paths[mode]), str(output)]
                if mode == 'persistent':
                    args += ['--mode', mode, '--repetitions', '2']
                template = next(r for r in legacy['runs'] if r['platform'] == 'laptop' and read(r['expected_manifest'])['provenance']['mode'] == mode)
                run = dict(id=run_id, stage=stage, platform='laptop', mode=mode, configuration=name,
                           command=args, output=str(output), timeout_seconds=3600,
                           audio_seconds=template['audio_seconds'], max_decodes=template['max_decodes'], expected=expected)
                runs.append(run)
        phone_order = names + list(reversed(names))
        phone_specs = {}
        for index, name in enumerate(phone_order, 1):
            config_path = ROOT / 'variants' / (name + '.json')
            config = variant.load(config_path)
            directory = LAB / 'push' / f's{stage}' / name
            directory.mkdir(parents=True, exist_ok=True)
            (directory / 'variant.json').write_bytes(config_path.read_bytes())
            for key, dest in (('model', 'model'),):
                for source_path in sorted((ROOT / 'models' / config[key]).iterdir()):
                    if source_path.is_file():
                        link(source_path, directory / dest / source_path.name)
            if config['lid']['model']:
                for source_path in sorted((ROOT / 'models' / config['lid']['model']).iterdir()):
                    if source_path.is_file():
                        link(source_path, directory / 'lid' / source_path.name)
            link(ROOT / 'models/silero_vad.onnx', directory / 'silero_vad.onnx')
            if config['hotwords']['file']:
                link(ROOT / 'corpus/lab' / config['hotwords']['file'], directory / config['hotwords']['file'])
            inputs = []
            for item in diagnostic['inputs']:
                if 'reference' in item:
                    source_path = ROOT / item['path']
                    link(source_path, directory / 'fixtures' / source_path.name)
                    inputs.append(dict(item, path='fixtures/' + source_path.name))
                else:
                    inputs.append(dict(item, path='../benchmark/' + item['id']))
            if name not in phone_specs:
                artifacts = identity.files(p for p in directory.rglob('*') if p.is_file() and p.name != 'measurement.json')
                phone_specs[name] = {str(Path(p).relative_to(directory)): h for p, h in artifacts.items()}
            artifacts = phone_specs[name]
            provenance = dict(endpoint_contract['provenance'], platform='phone', mode='persistent',
                build_sha256=legacy['apk']['sha256'], test_build_sha256=legacy['test_apk']['sha256'],
                runtime_sha256=identity.sha(ROOT / 'app/libs/sherpa-onnx-1.13.8.aar'), evaluator_sha256=evaluation,
                inputs_sha256=identity.digest(inputs), references_sha256=references,
                model_sha256=identity.digest({k:v for k,v in artifacts.items() if k.startswith('model/')}),
                configuration_sha256=identity.sha(config_path),
                dictionary_sha256=identity.sha(ROOT / 'corpus/lab' / config['hotwords']['file']) if config['hotwords']['file'] else identity.digest([]))
            save(directory / 'measurement.json', dict(provenance=provenance, mode='persistent', repetitions=2,
                                                       artifacts=artifacts, inputs=inputs))
            expected = copy.deepcopy(endpoint_contract)
            expected['provenance'] = provenance
            run_id = f's{stage}-phone-{index:02d}-{name}'
            output = LAB / 'runs' / (run_id + '.json')
            template = next(r for r in legacy['phone_runs'] if r['mode'] == 'persistent')
            runs.append(dict(id=run_id, stage=stage, platform='phone', mode='persistent', configuration=name,
                staging=str(directory.relative_to(ROOT)), timeout_seconds=2400, input_audio_seconds=template['input_audio_seconds'],
                max_decodes=template['max_decodes'], output=str(output), raw_output=str(output.with_suffix('.raw.json')),
                commands=[[str(ROOT / 'tools/phone'), 'push-variant', str(directory)], [str(ROOT / 'tools/phone'), 'lab'], [str(ROOT / 'tools/phone'), 'pull-results']],
                record_command=command('record', run_id) + ['--source', 'RETURNED_JSON_PATH'], expected=expected,
                quality_companions=[f's{stage}-laptop-{name}-quality-full-{r}' for r in (1, 2)]))
        stage_runs = [r for r in runs if r['stage'] == stage]
        for platform, modes in (('laptop', ('quality-full', 'persistent')), ('phone', ('persistent',))):
            for mode in modes:
                for name in names:
                    pair = [r for r in stage_runs if (r['platform'], r['mode'], r['configuration']) == (platform, mode, name)]
                    comparisons.append(dict(stage=stage, candidate=pair[1]['id'], control=pair[0]['id'], purpose='repeat'))
                for name in names[1:]:
                    for repeat in (0, 1):
                        candidate = [r for r in stage_runs if (r['platform'], r['mode'], r['configuration']) == (platform, mode, name)][repeat]
                        for control_name, purpose in ((names[0], 'quality-delta'), (names[1], 'allocation-transparency')):
                            if name == control_name:
                                continue
                            control = [r for r in stage_runs if (r['platform'], r['mode'], r['configuration']) == (platform, mode, control_name)][repeat]
                            comparisons.append(dict(stage=stage, candidate=candidate['id'], control=control['id'], purpose=purpose))
    by_id = {r['id']:r for r in runs}
    for run in runs:
        run['max_asr_decodes']=run['max_decodes']
        run['max_lid_decodes']=0
        run['lid_audio_seconds_upper']=0
        if run['stage']==3 and run['configuration'].startswith('cohere-'):
            if run['mode']=='quality-full':
                run['max_lid_decodes']=len(takes)*3+len(guards)
                run['lid_audio_seconds_upper']=run['audio_seconds']-11
            else:
                run['max_lid_decodes']=len(diagnostic['inputs'])*2
                run['lid_audio_seconds_upper']=run.get('audio_seconds',run.get('input_audio_seconds'))-1
            run['max_decodes']+=run['max_lid_decodes']
        run['decoder_audio_seconds_upper']=run.get('audio_seconds',run.get('input_audio_seconds'))+run['lid_audio_seconds_upper']
        expected = run.pop('expected', None) or read(run['expected_manifest'])
        if run['mode'] not in ('quality-full','historical-full'):
            expected['input_samples']={Path(i['path']).name if 'reference' not in i else i['id']:i.get('stop_sample',i.get('samples')) for i in diagnostic['inputs']}
        contract = LAB / 'contracts' / (run['id'] + '.json')
        save(contract, expected)
        run.update(expected_manifest=str(contract), validation_output=str(LAB / 'reports' / (run['id'] + '-validation.json')),
                   validate_command=command('validate', run['id']))
    for comparison in comparisons:
        comparison['id'] = comparison.get('id', comparison['candidate'] + '-vs-' + comparison['control'])
        contract = LAB / 'comparisons' / (comparison['id'] + '.json')
        expected = dict(candidate=read(by_id[comparison['candidate']]['expected_manifest']),
                        control=read(by_id[comparison['control']]['expected_manifest']),
                        repeat_rule={'normalized_text_mismatches_allowed':0} if comparison['purpose']=='repeat' else None)
        save(contract, expected)
        comparison.update(manifest=str(contract), output=str(LAB / 'reports' / (comparison['id'] + '.json')))
        comparison['command'] = command('compare', comparison['id'], comparison['output'])
    steps, retries = [], []
    completed = set()
    for run in runs:
        steps.append(dict(run=run['id'], stage=run['stage']))
        completed.add(run['id'])
        for comparison in comparisons:
            if run['id'] in (comparison['candidate'], comparison['control']) and {comparison['candidate'], comparison['control']} <= completed:
                steps.append(dict(compare=comparison['id'], stage=run['stage']))
        for slot in (1, 2):
            retry = copy.deepcopy(run)
            retry.update(id=run['id'] + f'-retry-{slot}', retry_of=run['id'], retry_slot=slot)
            retry['output'] = str(LAB / 'runs' / (retry['id'] + '.json'))
            retry['validation_output'] = str(LAB / 'reports' / (retry['id'] + '-validation.json'))
            retry['validate_command'] = command('validate', retry['id'])
            if 'command' in retry:
                retry['command'] = [retry['output'] if a == run['output'] else a for a in retry['command']]
            else:
                retry['raw_output'] = str(Path(retry['output']).with_suffix('.raw.json'))
                retry['record_command'] = command('record', retry['id']) + ['--source', 'RETURNED_JSON_PATH']
            retry['comparisons'] = []
            for comparison in comparisons:
                if run['id'] in (comparison['candidate'], comparison['control']):
                    replacement = copy.deepcopy(comparison)
                    replacement['candidate'] = retry['id'] if comparison['candidate'] == run['id'] else comparison['candidate']
                    replacement['control'] = retry['id'] if comparison['control'] == run['id'] else comparison['control']
                    replacement['output'] = str(LAB / 'reports' / (comparison['id'] + '-' + retry['id'] + '.json'))
                    replacement['command'] = command('compare', comparison['id'], replacement['output'])
                    retry['comparisons'].append(replacement)
            retries.append(retry)
    for stage in (1, 2, 3):
        limits = copy.deepcopy(legacy['limits'])
        limits['laptop_processes'] = 12 if stage == 1 else 18
        limits['storage_bytes'] = 10_000_000_000
        selected = [r for r in runs if r['stage']==stage]
        stages[str(stage)] = dict(runs=[r['id'] for r in selected], comparisons=[c['id'] for c in comparisons if c['stage']==stage], limits=limits,
            planned_processes={p:sum(r['platform']==p for r in selected) for p in ('laptop','phone')},
            planned_audio_seconds={p:sum(r.get('audio_seconds',r.get('input_audio_seconds',0)) for r in selected if r['platform']==p) for p in ('laptop','phone')},
            planned_max_decodes={p:sum(r['max_decodes'] for r in selected if r['platform']==p) for p in ('laptop','phone')},
            planned_decoder_audio_seconds_upper={p:sum(r['decoder_audio_seconds_upper'] for r in selected if r['platform']==p) for p in ('laptop','phone')},
            retry_audio_seconds={p:2*max(r.get('audio_seconds',r.get('input_audio_seconds',0)) for r in selected if r['platform']==p) for p in ('laptop','phone')},
            retry_max_decodes={p:2*max(r['max_decodes'] for r in selected if r['platform']==p) for p in ('laptop','phone')},
            retry_decoder_audio_seconds_upper={p:2*max(r['decoder_audio_seconds_upper'] for r in selected if r['platform']==p) for p in ('laptop','phone')})
    manifest = dict(schema=1, name='Allocation and measurement', source_commit=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip(),
        legacy_manifest=dict(path=str(LEGACY / 'manifest.json'), sha256=identity.sha(LEGACY / 'manifest.json')),
        launch_record=str(LAB / 'launch.json'), runs=runs, comparisons=comparisons, retry_options=retries, execution_steps=steps, stages=stages,
        limits=dict(laptop_processes=48, laptop_seconds=86400, phone_processes=30, phone_seconds=43200, storage_bytes=30_000_000_000, restoration_margin_seconds=900),
        ranking=dict(method='descriptive Pareto tradeoffs within matched platform/configuration repetitions',
                     dimensions=['per-language errors and term/non-term errors','current RSS/PSS and high-water kB','cold load ms','all endpoint waits, median/max ms','thermal status'],
                     quality_equivalence='exact normalized text for allocation versus stock and repeats',
                     dominance_rule='Both repetitions non-worse in every comparable resource dimension and strictly lower in at least one; laptop heat unavailable, phone thermal required; descriptive only',
                     meaningful_gain_threshold=None, acceptance='diagnostic-only; historical gate unchanged'),
        setup_complete=True, phone_validation='Existing adapters unchanged; stages 2/3 exact model/allocation suites not device-smoked; requires explicitly authorized bounded smoke before full readiness.')
    paths = [LAB / 'contracts' / (r['id'] + '.json') for r in runs] + [Path(c['manifest']) for c in comparisons] + list(suite_paths.values())
    paths += list((LAB / 'push').glob('**/measurement.json'))
    paths += [LEGACY/'environment.json',Path(PYTHON)]
    paths += list((ROOT/'models/venv/lib').glob('python*/site-packages/*.dist-info/METADATA'))
    manifest['frozen_contracts'] = identity.files(paths)
    manifest['source_files'] = identity.files(list((ROOT/'tools').glob('allocation_*.py'))+[ROOT/'tools/prepare_allocation.py', ROOT/'tools/check_allocation.py',
        ROOT/'tools/record_release_result.py', ROOT/'docs/experiments/allocation/workflow.md', ROOT/'docs/experiments/current.md', ROOT/'docs/experiments/protocol.md'])
    save(LAB / 'manifest.json', manifest)
    print('Prepared three stages without inference; launch/device validation remain separate.')


if __name__ == '__main__':
    prepare()
