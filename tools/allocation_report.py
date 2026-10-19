#!/usr/bin/env python3
"""Offline allocation evidence checks and scientific summary; never infer.

Historical execution accounting remains in the immutable completed handback.
Outputs are exclusively created; no ledger or launch-state dependency."""
import argparse
import json
from pathlib import Path
import evaluate_v4 as evaluator
import measurement_identity as identity

RULE = {"normalized_text_mismatches_allowed": 0}


def read(path):
    return json.loads(Path(path).read_text())


def input_sample_errors(envelope,expected):
    errors=[]
    samples=expected.get('input_samples',{})
    for section,key in (('takes','file'),('endpoints','id')):
        for row in envelope['report'].get(section,[]):
            if row.get('inputSamples')!=samples.get(row.get(key)) or row.get('coverageSamples')!=samples.get(row.get(key)):
                errors.append('input length differs from frozen suite: '+str(row.get(key)))
    return errors


def validate_run(manifest, run_id):
    run=next(r for r in manifest['runs']+manifest['retry_options'] if r['id']==run_id)
    expected=read(run['expected_manifest'])
    envelope=read(run['output'])
    result=evaluator.compare(envelope,envelope,expected,expected)
    result['check']='single-run evidence and diagnostics; not independent repetition'
    result['run_id']=run_id
    if run.get('mode') not in ('quality-full','historical-full'):
        errors=input_sample_errors(envelope,expected)
        if errors:
            result['evidence']='incomplete'
            result['evidence_errors']+=errors
    return result,run['validation_output']


def resource_metrics(envelope):
    report=envelope['report']
    memory=report.get('memory',[])
    measured=evaluator.resources(envelope)
    load=report.get('coldLoadMs')
    if load is None and envelope.get('provenance',{}).get('mode')=='quality-full' and evaluator.finite(report.get('loadSeconds')):
        load=report['loadSeconds']*1000
    result=dict(cold_load_ms=load,endpoint_median_ms=measured.get('wait_median_ms'),endpoint_max_ms=measured.get('wait_max_ms'))
    for output,key in (('rss_max_kb','rssKb'),('pss_max_kb','pssKb'),('high_water_max_kb','highWaterKb'),('thermal_max_status','thermalStatus')):
        result[output]=max((r[key] for r in memory if evaluator.finite(r.get(key))),default=None)
    return result


def compare(manifest, comparison):
    runs = {r['id']: r for r in manifest['runs'] + manifest['retry_options']}
    a, b = (runs[comparison[k]] for k in ('candidate', 'control'))
    contract = read(comparison['manifest'])
    result = evaluator.compare(read(a['output']), read(b['output']), contract['candidate'],
                               contract['control'], contract.get('repeat_rule'))
    if comparison.get('purpose') == 'allocation-transparency' and result['evidence'] == 'complete':
        # Expected provenance already pins both allocation configurations.
        # A transparency comparison intentionally changes only that hash;
        # model, dictionary, APK identities and normalized outputs must match.
        errors = [e for e in evaluator.repeat_errors(read(a['output']), read(b['output']), contract['candidate'], RULE)
                  if e != 'repeat changed configuration_sha256']
        if errors:
            result['evidence'] = 'incomplete'
            result['evidence_errors'] += ['allocation changed normalized text: ' + e for e in errors]
    candidate,control=resource_metrics(read(a['output'])),resource_metrics(read(b['output']))
    result['matched_resources']=dict(candidate=candidate,control=control,delta={key:candidate[key]-control[key]
        if evaluator.finite(candidate[key]) and evaluator.finite(control[key]) else None for key in candidate})
    return result


def persistent_pass_errors(envelope, expected):
    """Compare loaded-process passes; freshness applies between processes only."""
    errors = []
    for section in ('takes', 'endpoints'):
        passes = []
        for repetition in (0, 1):
            part = dict(envelope, report={section: [dict(row, repetition=0)
                for row in envelope['report'].get(section, []) if row.get('repetition') == repetition]})
            passes.append(part)
        passes[1] = dict(passes[1], provenance=dict(passes[1]['provenance'], run_id='pass-1', process_start_id='pass-1'))
        errors += evaluator.repeat_errors(*passes, expected, RULE)
    return errors


def stage_gate(manifest, stage, replacements=None):
    replacements = replacements or {}
    spec = manifest['stages'][str(stage)]
    runs = {r['id']: r for r in manifest['runs'] + manifest['retry_options']}
    errors, rows, deltas = [], [], []
    if stage == 1 and sum(runs[r]['platform']=='phone' and runs[r].get('mode')=='persistent' for r in spec['runs']) != 2:
        errors.append('stage one requires two independent persistent phone repetitions')
    for run_id in spec['runs']:
        run = runs[replacements.get(run_id, run_id)]
        try:
            result, _ = validate_run(manifest, run['id'])
            if result != read(run['validation_output']) or result['evidence'] != 'complete':
                errors.append('invalid/stale validation: ' + run['id'])
            report = read(run['output'])['report']
            memory = report.get('memory', [])
            if run['platform']=='phone' and run['mode']!='historical-full' and (not memory or any(type(r.get('thermalStatus')) is not int or not 0 <= r['thermalStatus'] <= 6 for r in memory)):
                errors.append('missing/invalid phone thermal evidence: '+run['id'])
            rows.append(dict(run=run['id'], configuration=run['configuration'], platform=run['platform'], mode=run['mode'],
                             quality=result['quality'], resources=result['resources'],
                             quality_companions=[replacements.get(r,r) for r in run.get('quality_companions',[])],
                             guard_silence_platform='laptop companion; phone guard/silence unavailable' if run.get('quality_companions') else 'laptop direct' if run['mode']=='quality-full' else 'unavailable',
                             cold_load_ms=resource_metrics(read(run['output']))['cold_load_ms'],
                             thermal_statuses=[r.get('thermalStatus') for r in memory],
                             battery_percent=[r.get('batteryPercent') for r in memory],
                             memory_max_kb={key:max((r[key] for r in memory if evaluator.finite(r.get(key))),default=None)
                                            for key in ('rssKb','pssKb','highWaterKb')},
                             endpoints=result.get('endpoints'), heat='thermal status and battery percentage where exposed; temperature/energy unavailable',
                             full_take_waits=[{key:r.get(key) for key in ('file','repetition','stopToFinalMs','inputSamples','coverageSamples','stopState','residualSamples')} for r in report.get('takes',[])],
                             historical_paced_waits=[{key:r.get(key) for key in ('file','stopToFinalMs','thermalAfter')} for r in report.get('paced',[])],
                             backend='pinned CPU runtime; profiling off, kernel assignment unavailable'))
            if stage == 1 and run['platform'] == 'phone' and run.get('mode') == 'persistent':
                envelope = read(run['output'])
                expected = read(run['expected_manifest'])
                if persistent_pass_errors(envelope, expected):
                    errors.append('persistent passes changed text: ' + run['id'])
                if any(r.get('missingFinalOutput') for r in result.get('endpoints', [])):
                    errors.append('persistent missing endpoint output: ' + run['id'])
                if any(not r['text'].strip() for r in envelope['report']['takes']):
                    errors.append('persistent missing full-take output: ' + run['id'])
        except (OSError, ValueError, KeyError, TypeError) as failure:
            errors.append('missing/malformed evidence: ' + run_id + ': ' + str(failure))
    for cid in spec['comparisons']:
        comparison = next(c for c in manifest['comparisons'] if c['id'] == cid)
        changed = [r for r in (comparison['candidate'], comparison['control']) if r in replacements]
        if len(changed) > 1:
            errors.append('both comparison sides replaced: ' + cid)
            continue
        if changed:
            comparison = next(c for c in runs[replacements[changed[0]]]['comparisons'] if c['id'] == cid)
        try:
            result = compare(manifest, comparison)
            if result['evidence'] != 'complete' or result != read(comparison['output']):
                errors.append('invalid/stale comparison: ' + cid)
            deltas.append(dict(comparison=cid, purpose=comparison['purpose'], **result))
        except (OSError, ValueError, KeyError, TypeError) as failure:
            errors.append('missing comparison: ' + cid + ': ' + str(failure))
    return dict(schema=1, stage=stage, evidence='incomplete' if errors else 'complete',
                errors=errors, mode='persistent', next_stage=stage + 1 if stage < 3 and not errors else None,
                action='interpretation handback' if errors else 'aggregate handback' if stage == 3 else 'advance',
                rows=rows, comparisons=deltas, acceptance='diagnostic-only', recording_trigger='unchanged; not evaluated')


def aggregate(manifest, replacements=None):
    stages = [stage_gate(manifest, i, replacements) for i in (1, 2, 3)]
    invalid = False
    for run in manifest['runs'] + manifest['retry_options']:
        if not Path(run['output']).exists():
            if run.get('raw_output') and Path(run['raw_output']).exists():
                invalid=True
            continue
        try:
            result, _ = validate_run(manifest,run['id'])
            if result['evidence'] != 'complete' or result != read(run['validation_output']):
                invalid = True
        except (OSError, ValueError, KeyError, TypeError):
            invalid = True
    by_id={r['id']:r for r in manifest['runs']+manifest['retry_options']}
    for original in manifest['comparisons']:
        comparison=original
        changed=[r for r in (original['candidate'],original['control']) if r in (replacements or {})]
        if len(changed)==1:
            comparison=next(c for c in by_id[replacements[changed[0]]]['comparisons'] if c['id']==original['id'])
        if not Path(comparison['output']).exists():
            continue
        try:
            result=compare(manifest,comparison)
            if result['evidence']!='complete' or result!=read(comparison['output']):
                invalid=True
        except (OSError, ValueError, KeyError, TypeError):
            invalid=True
    for stage in stages:
        if any(not error.startswith(('missing/malformed evidence:', 'missing comparison:')) for error in stage['errors']):
            invalid = True
    decisions=[]
    for stage in stages[1:]:
        selected=[c for c in stage['comparisons'] if c['purpose']=='allocation-transparency']
        grouped={}
        for row in selected:
            comparison=next(c for c in manifest['comparisons'] if c['id']==row['comparison'])
            run=by_id[comparison['candidate']]
            if run['mode']=='persistent':
                grouped.setdefault((run['platform'],run['configuration']),[]).append(row)
        for (platform,configuration),rows in grouped.items():
            deltas=[value for r in rows for key,value in r['matched_resources']['delta'].items() if platform=='phone' or key!='thermal_max_status']
            evidence_complete=len(rows)==2 and all(r['evidence']=='complete' for r in rows)
            complete=evidence_complete and all(evaluator.finite(abs(v)) for v in deltas if v is not None) and all(v is not None for v in deltas)
            outcome='unresolved' if not complete else 'observed dominance' if all(v<=0 for v in deltas) and any(v<0 for v in deltas) else 'observed no gain' if all(v>=0 for v in deltas) else 'tradeoff'
            decisions.append(dict(stage=stage['stage'],platform=platform,configuration=configuration,
                quality='exact normalized stock text' if all(r['evidence']=='complete' for r in rows) else 'unresolved',resources=outcome,
                evidence='complete' if evidence_complete else 'incomplete',repetitions=rows,heat='thermal status' if platform=='phone' else 'unavailable',meaningful_gain='not evaluated; no approved threshold'))
    result = dict(schema=1, manifest_sha256=identity.digest(manifest),
                status='completed' if all(s['evidence'] == 'complete' for s in stages) else 'blocked' if invalid else 'partial',
                stages=stages, decisions=decisions, ranking=manifest['ranking'], recommendation='Review matched deltas and tradeoffs; no meaningful-gain threshold is approved.',
                acceptance='diagnostic-only', recording_trigger='unchanged; not evaluated')
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('validate', 'compare', 'gate', 'aggregate'))
    parser.add_argument('--manifest', type=Path, required=True)
    parser.add_argument('--id')
    parser.add_argument('--replacements', help='historical effective run replacement mapping (JSON)')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.action != 'aggregate' and args.id is None:
        parser.error('--id is required for validate, compare and gate')
    manifest = read(args.manifest)
    if args.action == 'validate':
        result, _ = validate_run(manifest, args.id)
    elif args.action == 'compare':
        comparison = next(c for c in manifest['comparisons'] + [c for r in manifest['retry_options'] for c in r['comparisons']]
                          if c['id'] == args.id)
        result = compare(manifest, comparison)
    else:
        replacements = read(args.replacements) if args.replacements else {}
        if args.action == 'gate':
            result = stage_gate(manifest, int(args.id), replacements)
        else:
            result=aggregate(manifest,replacements)
    with args.output.open('x') as target:
        json.dump(result, target, indent=2)
    raise SystemExit(result.get('evidence', 'complete') != 'complete')


if __name__ == '__main__':
    main()
