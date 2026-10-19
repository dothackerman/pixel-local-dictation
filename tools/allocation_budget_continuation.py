#!/usr/bin/env python3
"""Exact approved budget exceptions; original evidence and scheduling stay frozen.

The laptop mode reuses the earlier independent laptop ordering amendment. Phone
mode reuses adaptive thermal scheduling and requires its own fresh dated window.
This helper returns commands; it never launches inference or contacts a device.
"""
import argparse
import copy
import hashlib
import json
from pathlib import Path
import subprocess

import allocation_execution as original
import allocation_laptop_continuation as laptop
import allocation_thermal_continuation as thermal
import check_allocation
import measurement_identity as identity
from release_execution import read

ROOT, PLAN = laptop.ROOT, laptop.PLAN
NEW_FILES = {'tools/allocation_budget_continuation.py',
             'tools/test_allocation_budget_continuation.py'}
ALLOWED_FILES = thermal.ALLOWED_FILES | NEW_FILES
CAPS = {'3:laptop_seconds': {'previous': 28800, 'approved': 29121},
        '2:phone_seconds': {'previous': 14400, 'approved': 18000}}
SCOPE = 'allocation-exact-budget-exceptions-20261019-v1'


def verify_record(manifest_path, record, owner, root=ROOT):
    """Validate new approval and its unchanged predecessor before using caps."""
    if (record.get('schema') != 1 or record.get('scope') != SCOPE
            or record.get('authorized_by') != 'Oriol' or record.get('session_id') != owner
            or not isinstance(record.get('authorization'), str) or not record['authorization'].strip()
            or Path(record['manifest']).resolve() != Path(manifest_path).resolve()
            or record.get('manifest_sha256') != identity.sha(manifest_path)
            or record.get('caps') != CAPS
            or set(record['administrative_files']) != NEW_FILES | {PLAN}):
        raise ValueError('invalid exact budget exception authorization')
    for name, expected in record['administrative_files'].items():
        if identity.sha(root / name) != expected:
            raise ValueError('budget source changed: ' + name)
    pin = record['thermal_predecessor']
    if identity.sha(pin['path']) != pin['sha256']:
        raise ValueError('thermal predecessor changed')
    previous = read(pin['path'])
    old_plan = subprocess.check_output(['git', 'show', previous['source_checkpoint'] + ':' + PLAN], cwd=root)
    if hashlib.sha256(old_plan).hexdigest() != previous['administrative_files'][PLAN]:
        raise ValueError('predecessor plan checkpoint differs')
    plan = (root / PLAN).read_bytes()
    if not plan.startswith(old_plan):
        raise ValueError('predecessor plan prefix changed')
    # The new approval changes only the append-only plan identity. Verify the
    # complete predecessor chain with precisely that approved successor view.
    successor = copy.deepcopy(previous)
    successor['administrative_files'][PLAN] = identity.sha(root / PLAN)
    old_laptop = read(previous['laptop_amendment']['path'])
    successor['appendices_sha256'] = hashlib.sha256(plan[old_laptop['original_plan_bytes']:]).hexdigest()
    thermal.verify_record(manifest_path, successor, owner, root)
    manifest = read(manifest_path)
    for key, cap in CAPS.items():
        stage, field = key.split(':')
        if manifest['stages'][stage]['limits'][field] != cap['previous']:
            raise ValueError('original budget differs: ' + key)
    return previous


class Execution(thermal.Execution):
    selected_steps = laptop.Execution.selected_steps
    comparison_barrier = laptop.Execution.comparison_barrier
    prerequisites = laptop.Execution.prerequisites

    def __init__(self, manifest_path, owner, continuation_path, continuation_sha256,
                 platform, now=None):
        super().__init__(manifest_path, owner, continuation_path, continuation_sha256, now)
        if platform not in ('laptop', 'phone'):
            raise ValueError('explicit laptop or phone mode required')
        self.platform = platform

    def verified(self):
        if identity.sha(self.continuation_path) != self.continuation_sha256:
            raise ValueError('budget exception record changed')
        record = read(self.continuation_path)
        predecessor = verify_record(self.path, record, self.owner)
        head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
        if record.get('source_checkpoint') != head:
            raise ValueError('budget source checkpoint differs')
        return record, predecessor

    def ready(self):
        record, predecessor = self.verified()
        permitted = {'frozen artifact changed: ' + str(ROOT / PLAN),
                     'checkout commit differs from frozen execution source'}
        errors = [e for e in check_allocation.launch_readiness(self.manifest) if e not in permitted]
        source = self.manifest['source_commit']
        subprocess.run(['git', 'merge-base', '--is-ancestor', source, 'HEAD'], cwd=ROOT, check=True)
        changed = set(subprocess.check_output(['git', 'diff', '--name-only', source, 'HEAD'], cwd=ROOT, text=True).splitlines())
        if not changed <= ALLOWED_FILES:
            errors.append('non-administrative source changed')
        if subprocess.run(['git', 'diff', '--quiet', 'HEAD', '--', *sorted(ALLOWED_FILES)], cwd=ROOT).returncode:
            errors.append('budget checkpoint is not committed')
        tracked = set(subprocess.check_output(['git', 'ls-files', '--', *sorted(ALLOWED_FILES)], cwd=ROOT, text=True).splitlines())
        if tracked != ALLOWED_FILES:
            errors.append('budget checkpoint is not tracked')
        launch = read(self.manifest['launch_record'])
        if (launch.get('authorized') is not True or launch.get('authorized_by') != 'Oriol'
                or launch.get('session_id') != self.owner or launch.get('source_commit') != source
                or launch.get('experiment') != 'allocation-v1' or launch.get('authorized_stages') != [1, 2, 3]):
            errors.append('original launch authorization mismatch')
        events = [json.loads(line) for line in self.log.read_text().splitlines()]
        for event in events:
            if event['action'] != 'begin-work':
                continue
            if event.get('amendment_sha256'):
                pin = predecessor['laptop_amendment']
                if event['amendment_sha256'] != pin['sha256'] or Path(event['amendment']).resolve() != Path(pin['path']).resolve():
                    errors.append('prior laptop amendment differs')
            if event.get('budget_exception') and event['budget_exception'] != self.pin():
                errors.append('prior budget exception differs')
            if event['platform'] == 'phone':
                if identity.sha(event['launch']) != event['launch_sha256']:
                    errors.append('prior phone window changed')
                pin = read(event['launch']).get('thermal_continuation')
                if pin and pin not in (record['thermal_predecessor'], self.pin()):
                    errors.append('prior thermal continuation differs')
        if errors:
            raise ValueError('; '.join(errors))
        active, _ = self.work(events)
        return read(active['launch']) if active and active['platform'] == 'phone' else launch

    def budget(self, run, events, launch):
        self.verified()
        # A separate view supplies only the two approved limit values to the
        # frozen checker. The original manifest stays authoritative for evidence.
        checker = copy.copy(self)
        checker.manifest = copy.deepcopy(self.manifest)
        for key, cap in CAPS.items():
            stage, field = key.split(':')
            checker.manifest['stages'][stage]['limits'][field] = cap['approved']
        try:
            original.Execution.budget(checker, run, events, launch)
        except ValueError as error:
            if str(error) != 'five-minute phone cooldown incomplete' or run['platform'] != 'phone':
                raise
        finally:
            self.now = checker.now

    def release_checkpoint(self):
        record, predecessor = self.verified()
        legacy = read(self.manifest['legacy_manifest']['path'])
        sources = {**legacy.get('source_files', {}), **self.manifest['source_files']}
        sources.update({str(ROOT / name): sha for name, sha in predecessor['administrative_files'].items()})
        sources.update({str(ROOT / name): sha for name, sha in record['administrative_files'].items()})
        for path, expected in sources.items():
            if identity.sha(path) != expected:
                raise ValueError('release source changed: ' + path)
        for key in ('apk', 'test_apk'):
            artifact = legacy[key]
            if identity.sha(ROOT / artifact['path']) != artifact['sha256']:
                raise ValueError('release build changed: ' + key)
        if subprocess.run(['git', 'diff', '--quiet', 'HEAD', '--', *sorted(sources)], cwd=ROOT).returncode:
            raise ValueError('release sources differ from committed checkpoint')
        return identity.digest(dict(record=self.pin(), sources=sources, builds=[legacy['apk'], legacy['test_apk']]))

    def next_step(self, events):
        if self.platform == 'laptop':
            return laptop.Execution.next_step(self, events)
        return thermal.Execution.next_step(self, events)

    def transition(self, action, run_id=None, outcome=None, platform=None,
                   launch_path=None, restoration=None, note=None):
        if platform and platform != self.platform:
            raise ValueError('mode/platform mismatch')
        if action in ('start', 'finish', 'retry'):
            run = self.runs[run_id]
            if run['platform'] != self.platform or (self.platform == 'laptop' and run['stage'] != 3):
                raise ValueError('run outside approved platform/stage')
        if self.platform == 'phone':
            return super().transition(action, run_id, outcome, platform, launch_path, restoration, note)
        if action == 'begin-work':
            if str(run_id) != '3':
                raise ValueError('only original stage-3 laptop ordering is approved')
            with self.locked() as events:
                _, active, _, _, _ = self.owned(events)
                if active or self.work(events)[0]:
                    raise ValueError('active work/process must finish and pause first')
                self.ready()
                self.prerequisites(events)
                data = dict(stage=3, platform='laptop', storage_baseline=self.storage(),
                            budget_exception=self.pin())
                self.now = self.clock()
                self.append(events, 'begin-work', **data)
                return data
        if action not in ('next', 'start', 'finish', 'pause-work', 'note'):
            raise ValueError('action outside laptop continuation scope')
        return original.Execution.transition(self, action, run_id, outcome, platform,
                                             launch_path, restoration, note)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('next', 'start', 'finish', 'retry', 'begin-work',
                        'pause-work', 'note', 'release', 'prepare-probe', 'authorize-inference'))
    parser.add_argument('--manifest', type=Path, default=original.LAB / 'manifest.json')
    parser.add_argument('--owner', required=True)
    parser.add_argument('--continuation', type=Path, required=True)
    parser.add_argument('--continuation-sha256', required=True)
    parser.add_argument('--platform', choices=('laptop', 'phone'), required=True)
    parser.add_argument('--run')
    parser.add_argument('--outcome')
    parser.add_argument('--window', type=Path)
    parser.add_argument('--restoration', type=Path)
    parser.add_argument('--note')
    parser.add_argument('--sample', type=Path)
    parser.add_argument('--previous-inference-not-started', action='store_true')
    args = parser.parse_args()
    try:
        executor = Execution(args.manifest, args.owner, args.continuation, args.continuation_sha256, args.platform)
        if args.action in ('prepare-probe', 'authorize-inference'):
            if args.platform != 'phone':
                raise ValueError('thermal operations require phone mode')
            if args.action == 'prepare-probe':
                result = executor.prepare_probe(args.run)
            else:
                result = executor.authorize_inference(args.run, args.sample, args.previous_inference_not_started)
        else:
            result = executor.transition(args.action, args.run, args.outcome, args.platform,
                        args.window, read(args.restoration) if args.restoration else None, args.note)
    except (KeyError, TypeError, ValueError, OSError, StopIteration, subprocess.CalledProcessError) as error:
        parser.exit(1, 'scheduling refused: ' + str(error) + '\n')
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
