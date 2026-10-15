#!/usr/bin/env python3
"""Execute the approved ordering amendment through the original host ledger.

Returns frozen commands; never launches inference or contacts a device. The
caller pins the separately recorded user authorization with --amendment-sha256.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess

import allocation_execution
import allocation_report
import check_allocation
import measurement_identity as identity
from release_execution import read

ROOT = Path(__file__).resolve().parents[1]
PLAN = 'docs/experiments/current.md'
ADMIN_FILES = {PLAN, 'tools/allocation_laptop_continuation.py',
               'tools/test_allocation_laptop_continuation.py'}


def verify_amendment(manifest, amendment, owner, root=ROOT):
    if (amendment.get('authorized_by') != 'Oriol' or amendment.get('session_id') != owner
            or amendment.get('scope') != 'stage-3-laptop-only'
            or amendment.get('manifest_sha256') != identity.sha(amendment['manifest'])):
        raise ValueError('invalid laptop-only authorization')
    plan = (root / PLAN).read_bytes()
    prefix = amendment['original_plan_bytes']
    if (hashlib.sha256(plan[:prefix]).hexdigest() != manifest['source_files'][str(root / PLAN)]
            or hashlib.sha256(plan[prefix:]).hexdigest() != amendment['appendix_sha256']):
        raise ValueError('frozen plan or approved appendix changed')
    if set(amendment['administrative_files']) != ADMIN_FILES:
        raise ValueError('unexpected administrative source scope')
    for path, expected in amendment['administrative_files'].items():
        if identity.sha(root / path) != expected:
            raise ValueError('amendment source changed: ' + path)


class Execution(allocation_execution.Execution):
    def __init__(self, manifest_path, owner, amendment_path, amendment_sha256, now=None):
        super().__init__(manifest_path, owner, now)
        self.amendment_path = Path(amendment_path)
        self.amendment_sha256 = amendment_sha256

    def ready(self):
        if identity.sha(self.amendment_path) != self.amendment_sha256:
            raise ValueError('amendment record changed')
        amendment = read(self.amendment_path)
        if Path(amendment['manifest']).resolve() != self.path.resolve():
            raise ValueError('amendment addresses another manifest')
        verify_amendment(self.manifest, amendment, self.owner)
        # Only the proven append-only plan change and administrative checkpoint
        # replace original readiness requirements. Every other check still runs.
        permitted = {'frozen artifact changed: ' + str(ROOT / PLAN),
                     'checkout commit differs from frozen execution source'}
        errors = [e for e in check_allocation.launch_readiness(self.manifest) if e not in permitted]
        source = self.manifest['source_commit']
        subprocess.run(['git', 'merge-base', '--is-ancestor', source, 'HEAD'], cwd=ROOT, check=True)
        changed = set(subprocess.check_output(['git', 'diff', '--name-only', source, 'HEAD'], cwd=ROOT, text=True).splitlines())
        if not changed <= ADMIN_FILES:
            errors.append('non-administrative committed source changed')
        if subprocess.run(['git', 'diff', '--quiet', 'HEAD', '--', *sorted(ADMIN_FILES)], cwd=ROOT).returncode:
            errors.append('administrative checkpoint is not committed')
        tracked = set(subprocess.check_output(['git', 'ls-files', '--', *sorted(ADMIN_FILES)], cwd=ROOT, text=True).splitlines())
        if tracked != ADMIN_FILES:
            errors.append('administrative checkpoint is not tracked')
        launch = read(self.manifest['launch_record'])
        if (launch.get('authorized') is not True or launch.get('authorized_by') != 'Oriol'
                or launch.get('session_id') != self.owner
                or launch.get('source_commit') != source
                or launch.get('experiment') != 'allocation-v1'
                or launch.get('authorized_stages') != [1, 2, 3]):
            errors.append('original launch authorization mismatch')
        events = [json.loads(line) for line in self.log.read_text().splitlines()]
        for event in events:
            if event['action'] == 'begin-work' and event.get('amendment_sha256'):
                if event['amendment_sha256'] != self.amendment_sha256 or Path(event['amendment']).resolve() != self.amendment_path.resolve():
                    errors.append('continuation amendment differs from prior work')
            if event['action'] == 'begin-work' and event['platform'] == 'phone':
                if identity.sha(event['launch']) != event['launch_sha256']:
                    errors.append('prior phone window authorization changed')
        if errors:
            raise ValueError('; '.join(errors))
        return launch

    def selected_steps(self, stage):
        comparisons = {c['id']: c for c in self.manifest['comparisons']}
        for step in self.manifest['execution_steps']:
            if step.get('stage', 1) != stage:
                continue
            ids = [step['run']] if 'run' in step else [comparisons[step['compare']][k] for k in ('candidate', 'control')]
            if all(self.runs[r]['platform'] == 'laptop' for r in ids):
                yield step

    def comparison_barrier(self, step, replacements):
        original = next(c for c in self.manifest['comparisons'] if c['id'] == step['compare'])
        comparison = self.comparison(original, replacements)
        if not Path(comparison['output']).exists():
            return dict(kind='compare', id=comparison['id'], command=comparison['command'])
        result = allocation_report.compare(self.manifest, comparison)
        if result['evidence'] != 'complete' or read(comparison['output']) != result:
            raise ValueError('missing, stale or invalid comparison barrier')

    def prerequisites(self, events):
        _, _, _, finishes, replacements = self.owned(events)
        if allocation_report.stage_gate(self.manifest, 1, replacements)['evidence'] != 'complete':
            raise ValueError('stage-1 evidence incomplete')
        for stage in (1, 2):
            for step in self.manifest['execution_steps'] if stage == 1 else self.selected_steps(2):
                if step.get('stage', 1) != stage:
                    continue
                if 'run' in step:
                    run = self.runs[replacements.get(step['run'], step['run'])]
                    finish = finishes.get(run['id'])
                    if not finish or finish['outcome'] != 'complete':
                        raise ValueError('prior required run incomplete')
                    self.run_evidence(run, finish)
                elif self.comparison_barrier(step, replacements):
                    raise ValueError('prior required comparison incomplete')

    def next_step(self, events):
        _, active, starts, finishes, replacements = self.owned(events)
        launch = self.ready()
        if active:
            raise ValueError('active process reservation; confirm exit before scheduling')
        self.prerequisites(events)
        for step in self.selected_steps(3):
            if 'compare' in step:
                pending = self.comparison_barrier(step, replacements)
                if pending:
                    return pending
                continue
            run = self.runs[replacements.get(step['run'], step['run'])]
            if run['stage'] != 3 or run['platform'] != 'laptop':
                raise ValueError('replacement outside laptop amendment')
            finish = finishes.get(run['id'])
            if finish is None:
                if run['id'] in starts:
                    raise ValueError('incomplete run')
                work, _ = self.work(events)
                if work is None:
                    return dict(kind='begin-work', stage=3, platform='laptop')
                if (work['stage'], work['platform']) != (3, 'laptop'):
                    raise ValueError('unexpected active work interval')
                try:
                    self.budget(run, events, launch)
                except ValueError as error:
                    return dict(kind='handback', status='partial', reason=str(error))
                if any(Path(run[k]).exists() for k in ('output', 'validation_output', 'raw_output') if k in run):
                    raise ValueError('unlogged existing run evidence')
                return dict(kind='run', id=run['id'], run=run)
            if finish['outcome'] != 'complete':
                raise ValueError('failed run requires handback')
            if not Path(run['validation_output']).exists():
                return dict(kind='validate', id=run['id'], command=run['validate_command'])
            self.run_evidence(run, finish)
        return dict(kind='laptop-complete', overall_status='partial', pending='stage-2 and stage-3 phone')

    def transition(self, action, run_id=None, outcome=None, platform=None, **kwargs):
        if action == 'begin-work':
            if str(run_id) != '3' or platform != 'laptop':
                raise ValueError('only stage-3 laptop work authorized')
            with self.locked() as events:
                _, active, _, _, _ = self.owned(events)
                if active or self.work(events)[0]:
                    raise ValueError('active work/process must finish and pause first')
                self.ready()
                self.prerequisites(events)
                data = dict(stage=3, platform='laptop', storage_baseline=self.storage(),
                            amendment=str(self.amendment_path), amendment_sha256=self.amendment_sha256)
                self.now = self.clock()
                self.append(events, 'begin-work', **data)
                return data
        if action not in ('next', 'start', 'finish', 'pause-work', 'note'):
            raise ValueError('action outside laptop continuation scope')
        if action == 'finish' and (self.runs[run_id]['stage'], self.runs[run_id]['platform']) != (3, 'laptop'):
            raise ValueError('cannot finish a run outside laptop amendment')
        return super().transition(action, run_id, outcome, platform, **kwargs)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('next', 'start', 'finish', 'begin-work', 'pause-work', 'note'))
    parser.add_argument('--manifest', type=Path, default=allocation_execution.LAB / 'manifest.json')
    parser.add_argument('--owner', required=True)
    parser.add_argument('--amendment', type=Path, required=True)
    parser.add_argument('--amendment-sha256', required=True)
    parser.add_argument('--run')
    parser.add_argument('--platform', choices=('laptop',))
    parser.add_argument('--outcome')
    parser.add_argument('--note')
    args = parser.parse_args()
    try:
        result = Execution(args.manifest, args.owner, args.amendment, args.amendment_sha256).transition(
            args.action, args.run, args.outcome, args.platform, note=args.note)
    except (KeyError, TypeError, ValueError, OSError, StopIteration, subprocess.CalledProcessError) as error:
        parser.exit(1, 'scheduling refused: ' + str(error) + '\n')
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
