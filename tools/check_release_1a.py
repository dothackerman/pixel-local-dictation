#!/usr/bin/env python3
"""Fail-closed release readiness check. Read-only; never launches a measurement."""
import json
from pathlib import Path
import sys
import measurement_identity as identity

ROOT = Path(__file__).resolve().parents[1]


def check(manifest):
    errors=[]
    if manifest.get('status') != 'ready': errors.append('release is not ready')
    for key in ('phone_window','phone_build_verified','phone_activation_verified'):
        if not manifest.get(key): errors.append('missing '+key)
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
    manifest=json.loads((ROOT/'corpus/lab/release-1a/manifest.json').read_text())
    errors=check(manifest)
    print('\n'.join(errors) if errors else 'ready')
    sys.exit(bool(errors))
