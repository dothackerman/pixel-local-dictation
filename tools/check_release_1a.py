#!/usr/bin/env python3
"""Fail-closed release readiness check. Read-only; never launches a measurement."""
import json
import argparse
from pathlib import Path
import sys
from datetime import datetime, timezone
from zoneinfo import ZoneInfo
import measurement_identity as identity

ROOT = Path(__file__).resolve().parents[1]


def launch_errors(manifest, launch, session_id, now=None):
    errors = []
    now = now or datetime.now(timezone.utc)
    if launch.get('authorized_by') != 'Oriol' or launch.get('authorized') is not True:
        errors.append('missing explicit Oriol launch authorization')
    if launch.get('source_commit') != manifest['source_commit']:
        errors.append('launch refers to a different source commit')
    if not session_id or launch.get('session_id') != session_id:
        errors.append('missing or mismatched fresh session ID')
    for key in ('hook_confirmed', 'guard_tests_passed', 'device_connection_and_versions_checked'):
        if launch.get(key) is not True:
            errors.append('missing fresh-session check: '+key)
    try:
        start = datetime.fromisoformat(launch['phone_window']['start'])
        end = datetime.fromisoformat(launch['phone_window']['end'])
        checked = datetime.fromisoformat(launch['checked_at'])
        zone = ZoneInfo('Europe/Zurich')
        if launch['phone_window']['timezone'] != 'Europe/Zurich' or any(
                value.tzinfo is None or value.utcoffset() != value.astimezone(zone).utcoffset()
                for value in (start, end, checked)):
            raise ValueError('timestamps must use Europe/Zurich offsets')
        if end <= start or (end-start).total_seconds() <= manifest['limits']['restoration_margin_seconds']:
            errors.append('phone window has no measurement time')
        if (end-now).total_seconds() <= manifest['limits']['restoration_margin_seconds']:
            errors.append('phone window expired or restoration margin reached')
        if checked > now or (now-checked).total_seconds() > 12*3600:
            errors.append('fresh-session checks are stale or future-dated')
    except (KeyError, TypeError, ValueError):
        errors.append('invalid dated Europe/Zurich phone window or check timestamp')
    restoration = launch.get('restoration', {})
    for key in ('owner', 'procedure'):
        value = restoration.get(key)
        if not isinstance(value, str) or not value.strip() or value.strip().lower() in ('tbd', 'unknown', 'none'):
            errors.append('missing restoration '+key)
    artifact = restoration.get('artifact_path')
    if not isinstance(artifact, str) or not Path(artifact).is_file():
        errors.append('missing restoration artifact')
    elif identity.sha(artifact) != restoration.get('artifact_sha256'):
        errors.append('restoration artifact hash mismatch')
    return errors


def check(manifest, setup_only=False, session_id=None):
    errors=[]
    if not setup_only:
        launch = Path(manifest['launch_record'])
        if not launch.is_file():
            errors.append('missing separate launch record')
        else:
            errors += launch_errors(manifest,json.loads(launch.read_text()),session_id)
    for key in ('setup_complete','runs','comparisons','execution_steps'):
        if not manifest.get(key): errors.append('missing '+key)
    if identity.sha(ROOT/manifest['brief']) != manifest['brief_sha256']: errors.append('brief changed')
    proof=json.loads((ROOT/manifest['device_setup']).read_text())
    if identity.sha(ROOT/proof['report']) != proof['sha256']: errors.append('device evidence changed')
    if manifest['apk']['sha256'] != proof['app_sha256']: errors.append('app differs from setup smoke build')
    # Instrumentation can receive reviewed preflight/evaluation fixes after the smoke.
    # Its new hash is locally built and verified before inference inside each device run.
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
    parser.add_argument('--session-id')
    args=parser.parse_args()
    manifest=json.loads((ROOT/'corpus/lab/release-1a/manifest.json').read_text())
    try:
        errors=check(manifest,args.setup_only,args.session_id)
    except (KeyError, TypeError, ValueError, OSError) as failure:
        errors=['invalid or missing readiness evidence: '+str(failure)]
    print('\n'.join(errors) if errors else 'setup verified' if args.setup_only else 'ready')
    sys.exit(bool(errors))
