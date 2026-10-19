#!/usr/bin/env python3
"""Exact approved budget exceptions; original evidence and scheduling stay frozen.

The laptop mode reuses the earlier independent laptop ordering amendment. Phone
mode reuses adaptive thermal scheduling and requires its own fresh dated window.
This helper returns commands; it never launches inference or contacts a device.
"""
import argparse
import copy
from datetime import datetime
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
from zoneinfo import ZoneInfo

import allocation_execution as original
import allocation_laptop_continuation as laptop
import allocation_thermal_continuation as thermal
import allocation_report
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
COMPLETION_SCOPE = 'allocation-finite-until-completion-20261019-v1'
COMPLETION_CAPS = {**CAPS,
    '2:phone_seconds': {'previous': 14400, 'approved': 19800},
    '3:phone_seconds': {'previous': 14400, 'approved': 21900},
    'whole:phone_seconds': {'previous': 43200, 'approved': 52500}}
COMPLETION_RUNS = ['s2-phone-07-dict-beam8-fp32-s20-stock', 's2-phone-08-baseline-stock',
    's3-phone-01-baseline-stock', 's3-phone-02-cohere-stock', 's3-phone-03-cohere-arena-off',
    's3-phone-04-cohere-pattern-off', 's3-phone-05-cohere-pattern-off',
    's3-phone-06-cohere-arena-off', 's3-phone-07-cohere-stock', 's3-phone-08-baseline-stock']


def historical_checkpoint(record, root):
    """Validate a predecessor exactly as committed, without rewriting its pins."""
    for name, expected in record['administrative_files'].items():
        content = subprocess.check_output(['git', 'show', record['source_checkpoint'] + ':' + name], cwd=root)
        if hashlib.sha256(content).hexdigest() != expected:
            raise ValueError('historical budget checkpoint differs: ' + name)
    return subprocess.check_output(['git', 'show', record['source_checkpoint'] + ':' + PLAN], cwd=root)


def completion_launch_errors(manifest, launch, owner, pin, now):
    """An explicit non-expiring access grant, constrained by the finite run set.

    A start/check date and fresh checks remain mandatory. There is deliberately
    no invented user deadline: stage/whole time budgets retain restoration time.
    """
    errors = []
    if (launch.get('authorized_by') != 'Oriol' or launch.get('authorized') is not True
            or launch.get('source_commit') != manifest['source_commit']
            or launch.get('session_id') != owner or launch.get('experiment') != 'allocation-v1'
            or launch.get('authorized_stages') != [2, 3]):
        errors.append('invalid finite completion launch authorization')
    if (launch.get('access') != {'mode': 'until-completion', 'runs': COMPLETION_RUNS}
            or launch.get('completion_authorization') != pin
            or launch.get('thermal_continuation') != pin or 'phone_window' in launch):
        errors.append('explicit until-completion grant and exact finite run set required')
    for key in ('hook_confirmed', 'guard_tests_passed', 'device_connection_and_versions_checked'):
        if launch.get(key) is not True:
            errors.append('missing fresh-session check: ' + key)
    try:
        start, checked = (datetime.fromisoformat(launch[key]) for key in ('access_started_at', 'checked_at'))
        zone = ZoneInfo('Europe/Zurich')
        if any(v.tzinfo is None or v.utcoffset() != v.astimezone(zone).utcoffset() for v in (start, checked)):
            raise ValueError('Europe/Zurich offsets required')
        if not start <= checked <= now or (now - checked).total_seconds() > 12 * 3600:
            raise ValueError('future or stale access checks')
    except (KeyError, TypeError, ValueError):
        errors.append('invalid dated Europe/Zurich access/check timestamps')
    restoration = launch.get('restoration', {})
    for key in ('owner', 'procedure'):
        value = restoration.get(key)
        if not isinstance(value, str) or not value.strip() or value.strip().lower() in ('tbd', 'unknown', 'none'):
            errors.append('missing restoration ' + key)
    artifact = restoration.get('artifact_path')
    if not isinstance(artifact, str) or not Path(artifact).is_file():
        errors.append('missing restoration artifact')
    elif identity.sha(artifact) != restoration.get('artifact_sha256'):
        errors.append('restoration artifact hash mismatch')
    return errors


def verify_record(manifest_path, record, owner, root=ROOT):
    """Validate new approval and its unchanged predecessor before using caps."""
    completion = record.get('scope') == COMPLETION_SCOPE
    caps = COMPLETION_CAPS if completion else CAPS
    if (record.get('schema') != 1 or record.get('scope') not in (SCOPE, COMPLETION_SCOPE)
            or record.get('authorized_by') != 'Oriol' or record.get('session_id') != owner
            or not isinstance(record.get('authorization'), str) or not record['authorization'].strip()
            or Path(record['manifest']).resolve() != Path(manifest_path).resolve()
            or record.get('manifest_sha256') != identity.sha(manifest_path)
            or record.get('caps') != caps
            or set(record['administrative_files']) != NEW_FILES | {PLAN}):
        raise ValueError('invalid exact budget exception authorization')
    if completion:
        if record.get('remaining_runs') != COMPLETION_RUNS or record.get('access_mode') != 'until-completion':
            raise ValueError('invalid finite completion scope')
        policy = record['environment_policy']
        if (identity.sha(policy['path']) != policy['sha256']
                or read(policy['path']).get('schema') != 'allocation-environment-cohorts-v1'):
            raise ValueError('environment cohort policy changed')
        pin = record['budget_predecessor']
        if identity.sha(pin['path']) != pin['sha256']:
            raise ValueError('budget predecessor changed')
        prior = read(pin['path'])
        if (prior.get('scope') != SCOPE or prior.get('schema') != 1
                or prior.get('authorized_by') != 'Oriol' or prior.get('session_id') != owner
                or prior.get('manifest') != record['manifest']
                or prior.get('manifest_sha256') != record['manifest_sha256']
                or prior.get('caps') != CAPS or prior.get('thermal_predecessor') != record['thermal_predecessor']
                or set(prior['administrative_files']) != NEW_FILES | {PLAN}):
            raise ValueError('invalid original budget predecessor')
        old_budget_plan = historical_checkpoint(prior, root)
        if not (root / PLAN).read_bytes().startswith(old_budget_plan):
            raise ValueError('original budget plan prefix changed')
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
    for key, cap in caps.items():
        stage, field = key.split(':')
        limits = manifest['limits'] if stage == 'whole' else manifest['stages'][stage]['limits']
        if limits[field] != cap['previous']:
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
        if record.get('scope') == COMPLETION_SCOPE:
            prior = read(record['budget_predecessor']['path'])
            subprocess.run(['git', 'merge-base', '--is-ancestor', prior['source_checkpoint'], 'HEAD'], cwd=ROOT, check=True)
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
            valid_budget_pins = [self.pin()]
            if record.get('budget_predecessor'):
                valid_budget_pins.append(record['budget_predecessor'])
            if event.get('budget_exception') and event['budget_exception'] not in valid_budget_pins:
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
        record, _ = self.verified()
        # A separate view supplies only the two approved limit values to the
        # frozen checker. The original manifest stays authoritative for evidence.
        checker = copy.copy(self)
        checker.manifest = copy.deepcopy(self.manifest)
        for key, cap in record.get('caps', CAPS).items():
            stage, field = key.split(':')
            limits = checker.manifest['limits'] if stage == 'whole' else checker.manifest['stages'][stage]['limits']
            limits[field] = cap['approved']
        try:
            if record.get('scope') == COMPLETION_SCOPE:
                checker.completion_budget(run, events, launch)
            else:
                original.Execution.budget(checker, run, events, launch)
        except ValueError as error:
            if str(error) != 'five-minute phone cooldown incomplete' or run['platform'] != 'phone':
                raise
        finally:
            self.now = checker.now

    def completion_budget(self, run, events, launch):
        """Frozen count/storage/accounting checks with until-completion access.

        This version cannot use the old dated-window checker. It retains its
        cumulative accounting and reserves 900 seconds in BOTH remaining time
        allowances instead of comparing against an artificial window endpoint.
        """
        active, _ = self.work(events)
        stage, platform = run['stage'], run['platform']
        if platform != 'phone' or not active or (active['stage'], active['platform']) != (stage, platform):
            raise ValueError('begin-work required for finite phone continuation')
        self.now = self.clock()
        _, spent = self.work(events)
        _, _, starts, _, _ = self.state(events)
        limits = self.manifest['stages'][str(stage)]['limits']
        selected = [e for e in starts.values() if (self.runs[e['run']]['stage'], self.runs[e['run']]['platform']) == (stage, platform)]
        total = [e for e in starts.values() if self.runs[e['run']]['platform'] == platform]
        if len(selected) >= limits['phone_processes'] or len(total) >= self.manifest['limits']['phone_processes']:
            raise ValueError('cumulative process count exhausted')
        timeout, reserve = run['timeout_seconds'], self.manifest['limits']['restoration_margin_seconds']
        elapsed = spent.get((stage, platform), 0)
        cumulative = sum(v for (_, p), v in spent.items() if p == platform)
        if (timeout <= 0 or elapsed + timeout + reserve > limits['phone_seconds']
                or cumulative + timeout + reserve > self.manifest['limits']['phone_seconds']):
            raise ValueError('active-work budget cannot fit full timeout')
        baseline = next(e['storage_baseline'] for e in events if e['action'] == 'acquire')
        stage_baseline = next(e['storage_baseline'] for e in events if e['action'] == 'begin-work' and e['stage'] == stage)
        if self.storage() - stage_baseline >= limits['storage_bytes']:
            raise ValueError('stage storage budget exhausted')
        if self.storage() - baseline >= self.manifest['limits']['storage_bytes'] or shutil.disk_usage(self.path.parent).free < 100_000_000:
            raise ValueError('combined storage budget exhausted')
        errors = completion_launch_errors(self.manifest, launch, self.owner, self.pin(), self.now)
        if errors:
            raise ValueError('; '.join(errors))
        if identity.sha(active['launch']) != active['launch_sha256']:
            raise ValueError('completion launch authorization changed')

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
        record = read(self.continuation_path)
        if record.get('scope') == COMPLETION_SCOPE:
            if self.platform != 'phone':
                raise ValueError('finite completion scope is phone-only; laptop work is complete')
            if action in ('start', 'finish', 'retry'):
                original_run = self.runs[run_id].get('retry_of', run_id)
                if original_run not in COMPLETION_RUNS:
                    raise ValueError('run outside finite completion set')
            if action == 'begin-work':
                return self.begin_completion(run_id, launch_path)
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

    def begin_completion(self, stage_id, launch_path):
        if str(stage_id) not in ('2', '3'):
            raise ValueError('only original stage-2/3 phone completion is authorized')
        with self.locked() as events:
            _, active, _, finishes, replacements = self.owned(events)
            if active or self.work(events)[0]:
                raise ValueError('active work/process must finish and pause first')
            self.ready()
            stage = int(stage_id)
            if allocation_report.stage_gate(self.manifest, stage - 1, replacements)['evidence'] != 'complete':
                raise ValueError('previous stage has invalid/incomplete evidence')
            if not any(self.runs[r]['stage'] == stage and r not in finishes for r in COMPLETION_RUNS):
                raise ValueError('no remaining finite runs in requested stage')
            path = str(Path(launch_path).resolve())
            launch = read(path)
            self.now = self.clock()
            errors = completion_launch_errors(self.manifest, launch, self.owner, self.pin(), self.now)
            if errors:
                raise ValueError('; '.join(errors))
            if any(e['action'] == 'begin-work' and e.get('launch') == path for e in events):
                raise ValueError('new interval requires a fresh immutable check record')
            closed = [e for e in events if e['action'] == 'pause-work' and e.get('restoration')]
            if closed and original.timestamp(launch['checked_at']) < original.timestamp(closed[-1]['at']):
                raise ValueError('resume requires fresh checks after previous restoration')
            data = dict(stage=stage, platform='phone', storage_baseline=self.storage(),
                        launch=path, launch_sha256=identity.sha(path), budget_exception=self.pin())
            self.append(events, 'begin-work', **data)
            return data


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
