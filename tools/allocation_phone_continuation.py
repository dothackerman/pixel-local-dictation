#!/usr/bin/env python3
"""Phone continuation after the separately approved, append-only plan amendment.

Only readiness accepts the reviewed administrative checkpoint. The original
scheduler retains all scientific ordering, evidence, retry and budget rules.
This helper returns commands and never launches them or contacts a device.
"""
import argparse
import json
from pathlib import Path
import subprocess

import allocation_execution
from allocation_laptop_continuation import ADMIN_FILES, PLAN, ROOT, verify_amendment
import check_allocation
import measurement_identity as identity
from release_execution import read

PHONE_FILES = {'tools/allocation_phone_continuation.py',
               'tools/test_allocation_phone_continuation.py'}


def verify_record(manifest_path, record, owner, root=ROOT):
    if (record.get('authorized_by') != 'Oriol' or record.get('session_id') != owner
            or record.get('scope') != 'prepared-stage-2-3-phone-continuation'
            or record.get('authorized_stages') != [2, 3]
            or Path(record['manifest']).resolve() != Path(manifest_path).resolve()
            or record.get('manifest_sha256') != identity.sha(manifest_path)):
        raise ValueError('invalid prepared phone continuation authorization')
    if set(record['administrative_files']) != PHONE_FILES:
        raise ValueError('unexpected phone administrative source scope')
    for path, expected in record['administrative_files'].items():
        if identity.sha(root / path) != expected:
            raise ValueError('phone continuation source changed: ' + path)
    previous = record['laptop_amendment']
    if identity.sha(previous['path']) != previous['sha256']:
        raise ValueError('original laptop amendment changed')
    return read(previous['path'])


class Execution(allocation_execution.Execution):
    def __init__(self, manifest_path, owner, continuation_path, continuation_sha256, now=None):
        super().__init__(manifest_path, owner, now)
        self.continuation_path = Path(continuation_path)
        self.continuation_sha256 = continuation_sha256

    def ready(self):
        if identity.sha(self.continuation_path) != self.continuation_sha256:
            raise ValueError('phone continuation record changed')
        record = read(self.continuation_path)
        amendment = verify_record(self.path, record, self.owner)
        verify_amendment(self.manifest, amendment, self.owner)
        permitted = {'frozen artifact changed: ' + str(ROOT / PLAN),
                     'checkout commit differs from frozen execution source'}
        errors = [e for e in check_allocation.launch_readiness(self.manifest) if e not in permitted]
        source = self.manifest['source_commit']
        subprocess.run(['git', 'merge-base', '--is-ancestor', source, 'HEAD'], cwd=ROOT, check=True)
        allowed = ADMIN_FILES | PHONE_FILES
        changed = set(subprocess.check_output(['git', 'diff', '--name-only', source, 'HEAD'], cwd=ROOT, text=True).splitlines())
        if not changed <= allowed:
            errors.append('non-administrative committed source changed')
        if subprocess.run(['git', 'diff', '--quiet', 'HEAD', '--', *sorted(allowed)], cwd=ROOT).returncode:
            errors.append('continuation checkpoint is not committed')
        tracked = set(subprocess.check_output(['git', 'ls-files', '--', *sorted(allowed)], cwd=ROOT, text=True).splitlines())
        if tracked != allowed:
            errors.append('continuation checkpoint is not tracked')
        launch = read(self.manifest['launch_record'])
        if (launch.get('authorized') is not True or launch.get('authorized_by') != 'Oriol'
                or launch.get('session_id') != self.owner or launch.get('source_commit') != source
                or launch.get('experiment') != 'allocation-v1'
                or launch.get('authorized_stages') != [1, 2, 3]):
            errors.append('original experiment launch authorization mismatch')
        events = [json.loads(line) for line in self.log.read_text().splitlines()]
        for event in events:
            if event['action'] == 'begin-work' and event.get('amendment_sha256'):
                previous = record['laptop_amendment']
                if (event['amendment_sha256'] != previous['sha256']
                        or Path(event['amendment']).resolve() != Path(previous['path']).resolve()):
                    errors.append('prior laptop work amendment changed')
            if event['action'] == 'begin-work' and event['platform'] == 'phone':
                window = read(event['launch'])
                if identity.sha(event['launch']) != event['launch_sha256']:
                    errors.append('prior phone window authorization changed')
                continuation = window.get('phone_continuation')
                if continuation and (continuation['sha256'] != self.continuation_sha256
                        or Path(continuation['path']).resolve() != self.continuation_path.resolve()):
                    errors.append('phone continuation differs from prior window')
        if errors:
            raise ValueError('; '.join(errors))
        active, _ = self.work(events)
        return read(active['launch']) if active and active['platform'] == 'phone' else launch

    def next_step(self, events):
        step = super().next_step(events)
        platform = step.get('platform', step.get('run', {}).get('platform'))
        if platform == 'laptop':
            return dict(kind='handback', status='partial', reason='original laptop barrier remains; phone continuation retains original stage gates')
        return step

    def transition(self, action, run_id=None, outcome=None, platform=None, launch_path=None, restoration=None, note=None):
        if action == 'begin-work':
            if str(run_id) not in ('2', '3') or platform != 'phone':
                raise ValueError('only prepared stage-2/3 phone work authorized')
            window = read(launch_path)
            expected = dict(path=str(self.continuation_path.resolve()), sha256=self.continuation_sha256)
            if window.get('phone_continuation') != expected:
                raise ValueError('window must pin reviewed phone continuation')
        if action in ('start', 'finish', 'retry'):
            run = self.runs[run_id]
            if run['stage'] not in (2, 3) or run['platform'] != 'phone':
                raise ValueError('run outside prepared phone continuation')
        if action not in ('next', 'start', 'finish', 'retry', 'begin-work', 'pause-work', 'note', 'release'):
            raise ValueError('action outside phone continuation')
        return super().transition(action, run_id, outcome, platform, launch_path, restoration, note)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('next', 'start', 'finish', 'retry', 'begin-work', 'pause-work', 'note', 'release'))
    parser.add_argument('--manifest', type=Path, default=allocation_execution.LAB / 'manifest.json')
    parser.add_argument('--owner', required=True)
    parser.add_argument('--continuation', type=Path, required=True)
    parser.add_argument('--continuation-sha256', required=True)
    parser.add_argument('--run')
    parser.add_argument('--platform', choices=('phone',))
    parser.add_argument('--window', type=Path)
    parser.add_argument('--restoration', type=Path)
    parser.add_argument('--outcome')
    parser.add_argument('--note')
    args = parser.parse_args()
    try:
        result = Execution(args.manifest, args.owner, args.continuation, args.continuation_sha256).transition(
            args.action, args.run, args.outcome, args.platform, args.window,
            read(args.restoration) if args.restoration else None, args.note)
    except (KeyError, TypeError, ValueError, OSError, StopIteration, subprocess.CalledProcessError) as error:
        parser.exit(1, 'scheduling refused: ' + str(error) + '\n')
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
