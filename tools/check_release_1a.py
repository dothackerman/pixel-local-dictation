#!/usr/bin/env python3
"""Fail-closed release readiness check. Read-only; never launches a measurement."""
import json
import argparse
from pathlib import Path
import sys
import measurement_identity as identity

ROOT = Path(__file__).resolve().parents[1]


def check(manifest, setup_only=False):
    errors=[]
    if not setup_only:
        if manifest.get('status') != 'ready': errors.append('release is not ready')
        if not manifest.get('phone_window'): errors.append('missing phone_window')
        for key in ('owner', 'procedure', 'artifact_sha256'):
            if not manifest.get('restoration', {}).get(key): errors.append('missing restoration '+key)
    for key in ('setup_complete','phone_build_verified','phone_activation_verified','runs','comparisons'):
        if not manifest.get(key): errors.append('missing '+key)
    if identity.sha(ROOT/manifest['brief']) != manifest['brief_sha256']: errors.append('brief changed')
    proof=json.loads((ROOT/manifest['device_setup']).read_text())
    if identity.sha(ROOT/proof['report']) != proof['sha256']: errors.append('device evidence changed')
    for key, field in (('apk','app_sha256'),('test_apk','test_apk_sha256')):
        if manifest[key]['sha256'] != proof[field]: errors.append('device build differs: '+key)
    suite=json.loads((ROOT/'corpus/lab/release-1a/quality-suite.json').read_text())
    if identity.quality_inputs(ROOT,suite['inputs']) != suite['quality_inputs_sha256']: errors.append('quality inputs changed')
    for path, expected in manifest.get('frozen_contracts',{}).items():
        if identity.sha(path)!=expected: errors.append('contract changed: '+path)
    for line in (ROOT/'tools/frozen-v4.sha256').read_text().splitlines():
        if line and not line.startswith('#'):
            expected, path = line.split(maxsplit=1)
            if identity.sha(ROOT/path)!=expected: errors.append('frozen source changed: '+path)
    for path, expected in manifest.get('source_files',{}).items():
        if identity.sha(path)!=expected: errors.append('source changed: '+path)
    for key in ('apk','test_apk'):
        entry=manifest[key]
        if identity.sha(ROOT/entry['path'])!=entry['sha256']: errors.append(key+' changed')
    for run in manifest['phone_runs']:
        directory=ROOT/run['staging']
        filename='historical-manifest.json' if run['mode']=='historical-full' else 'measurement.json'
        stage=json.loads((directory/filename).read_text())
        for name, expected in stage['artifacts'].items():
            if identity.sha(directory/name)!=expected: errors.append('staged artifact changed: '+name)
    return errors

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--setup-only', action='store_true')
    args=parser.parse_args()
    manifest=json.loads((ROOT/'corpus/lab/release-1a/manifest.json').read_text())
    errors=check(manifest,args.setup_only)
    print('\n'.join(errors) if errors else 'setup verified' if args.setup_only else 'ready')
    sys.exit(bool(errors))
