#!/usr/bin/env python3
"""Stage accepted Candidate A inside the private corpus; never edits frozen evidence.

The owner acceptance waives only the historical Stop-wait rejection. Meaning,
live safety and phone approval remain separate requirements.
"""
import argparse
import json
from pathlib import Path
import zipfile
import hashlib
import shutil

import measurement_identity as identity
from dictionary_visit import read_receipt

ROOT = Path(__file__).resolve().parents[1]
RECORD = ROOT / 'app/src/main/res/raw/candidate_a.json'


def accepted_configuration(plan, record):
    """Reject any selection other than the exact replay identities the owner selected."""
    candidate = plan['setups']['candidate-a']
    if (record['decision'] != 'owner-accepted-stop-wait-issue-28' or
            record['plan_sha256'] != plan['sha256'] or
            identity.digest({k: v for k, v in plan.items() if k != 'sha256'}) != plan['sha256'] or
            record['variant'] != candidate['variant'] or
            record['modelDirectory'] != candidate['model_directory'] or
            record['artifacts'] != {k: v for k, v in candidate['artifacts'].items() if k != 'variant.json'} or
            any(record[k] != candidate['provenance'][k] for k in
                ('configuration_sha256', 'model_sha256', 'dictionary_sha256', 'runtime_sha256')) or
            record['historical_build_sha256'] != candidate['provenance']['build_sha256']):
        raise ValueError('selection differs from owner-accepted Candidate A')
    return candidate


def stage(root, folder, apk, record_path=RECORD):
    root, folder = Path(root).resolve(), Path(folder).resolve()
    if not folder.is_relative_to(root / 'corpus'):
        raise ValueError('provisioning must stay inside original private corpus')
    plan_path = root / 'corpus/lab/issue11/visit-a-s15/plan.json'
    plan = json.loads(plan_path.read_text())
    record = json.loads(Path(record_path).read_text())
    candidate = accepted_configuration(plan, record)
    # Native runtime stays byte-identical even though the application code/APK changes.
    with zipfile.ZipFile(apk) as archive:
        natives = {name: hashlib.sha256(archive.read(name)).hexdigest() for name in archive.namelist()
                   if name.startswith('lib/') and name.endswith('.so')}
        if identity.digest(natives) != record['runtime_sha256']:
            raise ValueError('APK runtime differs from tested A')
    sources = {}
    for relative, digest in record['artifacts'].items():
        source = plan_path.parent / 'stage' / relative
        if not source.is_file() or identity.sha(source) != digest:
            raise ValueError('candidate artifact changed: ' + relative)
        sources[relative] = source
    # Reuse one retained DE and EN replay for focused normal-path output parity.
    cases = {}
    receipts = list((plan_path.parent / 'receipts').glob('candidate-r1-*-candidate-a.json'))
    for lang in ('de', 'en'):
        name = next(k for k, v in plan['cases'].items() if v['lang'] == lang)
        ref = plan['cases'][name]
        number = list(plan['cases']).index(name) + 1
        receipt = next(p for p in receipts if f'-t{number}-' in p.name)
        measured = read_receipt(receipt, plan, root)['measurement']['report']['endpoints'][0]
        source = plan_path.parent / 'stage' / ref['path']
        if identity.sha(source) != ref['sha256']:
            raise ValueError('parity input changed')
        sources[ref['path']] = source
        cases[name] = dict(path=ref['path'], sha256=ref['sha256'],
                           output_sha256=hashlib.sha256(measured['text'].encode()).hexdigest())
    folder.mkdir(parents=True, exist_ok=False)
    for relative, source in sources.items():
        target = folder / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, target)
    (folder / 'baseline-parity.json').write_text(json.dumps(cases, indent=2)+'\n')
    (folder / 'provisioning.json').write_text(json.dumps(dict(
        protocol='candidate-a-provisioning-v1', decision=record['decision'],
        plan_sha256=record['plan_sha256'], build_sha256=identity.sha(apk),
        selection_sha256=identity.sha(record_path), artifacts=record['artifacts']), indent=2)+'\n')
    # No live.json or variant.json: both app types must use their normal packaged selection.
    return folder


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=ROOT)
    parser.add_argument('folder', type=Path)
    parser.add_argument('apk', type=Path)
    args = parser.parse_args()
    print(stage(args.root, args.folder, args.apk))


if __name__ == '__main__':
    main()
