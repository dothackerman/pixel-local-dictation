#!/usr/bin/env python3
"""Wrap a newly measured historical protocol without relabelling old saved reports.

Requires the provenance fields emitted by the prepared APK. It preserves every
raw field; the device must supply measured artifact/input hashes from preflight.
"""
import argparse
import json


def wrap(raw, manifest):
    if raw.get('measurementProtocol') != 'historical-full-v1':
        raise ValueError('report lacks the new historical protocol identity')
    expected = manifest['provenance']
    if raw.get('buildSha256') != expected['build_sha256'] or raw.get('configurationSha256') != expected['configuration_sha256']:
        raise ValueError('unexpected build or variant')
    if raw.get('testBuildSha256') != expected.get('test_build_sha256') or not raw.get('testBuildSha256'):
        raise ValueError('unexpected instrumentation build')
    if not raw.get('runId') or not raw.get('processStartId'):
        raise ValueError('missing fresh-process identity')
    inputs = {item['path']: item['sha256'] for item in manifest['inputs']}
    if not inputs or len(inputs) != len(manifest['inputs']) or any('reference' in item for item in manifest['inputs']):
        raise ValueError('historical manifest must contain unique full takes')
    if not manifest['artifacts'] or raw.get('verifiedArtifacts') != manifest['artifacts']:
        raise ValueError('missing or mismatched measured artifact identities')
    if raw.get('verifiedInputs') != inputs:
        raise ValueError('missing or mismatched measured input identities')
    provenance = dict(expected, platform='phone', mode='historical-full', feed=640,
                      run_id=raw['runId'], process_start_id=raw['processStartId'])
    return dict(schema=4, outcome='complete', provenance=provenance, report=raw)

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('raw', 'manifest', 'output'):
        parser.add_argument(name)
    args = parser.parse_args()
    with open(args.raw) as source: raw = json.load(source)
    with open(args.manifest) as source: manifest = json.load(source)
    result = wrap(raw, manifest)
    with open(args.output, 'x') as target: json.dump(result, target, indent=2)
