#!/usr/bin/env python3
"""Host-only release-1a next-step checks and cooperative ownership.

Never executes returned commands. --owner is the fresh session ID, not launch
authorization: a separate authorized launch record and readiness checks are required.
The persistent lease coordinates callers of this tool only. It cannot exclude
arbitrary tools or terminate a device process. An active reservation needs an
explicit finish after the process has exited; timeout alone never releases it.
"""
import argparse
from contextlib import contextmanager
from datetime import datetime, timezone
import fcntl
import json
import os
from pathlib import Path
import shutil

import check_release_1a as readiness
import evaluate_v4 as evaluator
import measurement_identity as identity
import release_report

LAB = Path(__file__).resolve().parents[1] / 'corpus/lab/release-1a'


def read(path):
    return json.loads(Path(path).read_text())


def timestamp(value):
    result = datetime.fromisoformat(value)
    if result.tzinfo is None:
        raise ValueError('execution timestamp needs timezone')
    return result


class Execution:
    def __init__(self, manifest_path, owner, now=None):
        self.path = Path(manifest_path)
        self.manifest = read(self.path)
        self.owner = owner
        if not isinstance(owner, str) or not owner.strip():
            raise ValueError('fresh session owner required')
        self.clock = now if callable(now) else (lambda: now) if now is not None else (lambda: datetime.now(timezone.utc))
        self.now = self.clock()
        self.log = self.path.parent / 'execution-events.jsonl'
        self.runs = {r['id']: r for r in self.manifest['runs'] + self.manifest['retry_options']}

    @contextmanager
    def locked(self):
        # This advisory OS lock serializes transitions; the log lease persists
        # while the caller runs the returned command outside this process.
        with (self.path.parent / 'execution.lock').open('a') as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            events = []
            if self.log.exists():
                for line in self.log.read_text().splitlines():
                    event = json.loads(line)
                    previous = identity.digest(events[-1]) if events else None
                    if event['sequence'] != len(events) or event['previous'] != previous:
                        raise ValueError('execution log chain changed')
                    if timestamp(event['at']) > self.now or (events and timestamp(event['at']) < timestamp(events[-1]['at'])):
                        raise ValueError('invalid execution event time')
                    if event['manifest_sha256'] != identity.sha(self.path):
                        raise ValueError('execution manifest changed')
                    events.append(event)
            yield events

    def append(self, events, action, **data):
        event = dict(sequence=len(events), previous=identity.digest(events[-1]) if events else None,
                     manifest_sha256=identity.sha(self.path), owner=self.owner,
                     at=self.now.isoformat(), action=action, **data)
        with self.log.open('a') as output:
            output.write(json.dumps(event, sort_keys=True) + '\n')
            output.flush()
            os.fsync(output.fileno())
        events.append(event)

    def state(self, events):
        owner, active = None, None
        starts, finishes, replacements = {}, {}, {}
        for event in events:
            action = event['action']
            if action == 'acquire':
                if owner is not None:
                    raise ValueError('duplicate ownership')
                owner = event['owner']
            elif event['owner'] != owner:
                raise ValueError('event without ownership')
            elif action == 'release':
                if active:
                    raise ValueError('ownership released during active process')
                owner = None
            elif action == 'start':
                if active or event['run'] in starts:
                    raise ValueError('duplicate or overlapping process')
                active = event['run']
                starts[active] = event
            elif action == 'finish':
                if active != event['run']:
                    raise ValueError('finish without matching active process')
                finishes[active] = event
                active = None
            elif action == 'retry':
                if event['original'] in replacements:
                    raise ValueError('original already retried')
                replacements[event['original']] = event['run']
            else:
                raise ValueError('unknown execution event')
        return owner, active, starts, finishes, replacements

    def owned(self, events):
        state = self.state(events)
        if state[0] != self.owner:
            raise ValueError('different or missing owner; stale leases require explicit owner handback')
        return state

    def ready(self):
        errors = list(readiness.check(self.manifest, session_id=self.owner))
        launch = read(self.manifest['launch_record'])
        errors += readiness.launch_errors(self.manifest, launch, self.owner, self.now)
        if errors:
            raise ValueError('; '.join(errors))
        return launch

    def run_evidence(self, run, finish):
        paths = [run['output'], run['expected_manifest']]
        if finish.get('evidence') != identity.files(paths):
            raise ValueError('run evidence changed since finish')
        result, _ = release_report.evaluate(self.manifest, run['id'])
        stored = read(run['validation_output'])
        if result['evidence'] != 'complete' or stored != result:
            raise ValueError('missing, stale or invalid single-run validation')
        return result

    def comparison(self, comparison, replacements):
        changed = [r for r in (comparison['candidate'], comparison['control']) if r in replacements]
        if len(changed) > 1:
            raise ValueError('both comparison sides need replacing; return to setup')
        if changed:
            retry = self.runs[replacements[changed[0]]]
            comparison = next(c for c in retry['comparisons'] if c['id'] == comparison['id'])
        return comparison

    def next_step(self, events):
        _, active, starts, finishes, replacements = self.owned(events)
        launch = self.ready()
        if active:
            raise ValueError('active process reservation; confirm process exit and finish before scheduling')
        comparisons = {c['id']: c for c in self.manifest['comparisons']}
        for step in self.manifest['execution_steps']:
            if 'run' in step:
                run = self.runs[replacements.get(step['run'], step['run'])]
                finish = finishes.get(run['id'])
                if finish is None:
                    if run['id'] in starts:
                        raise ValueError('incomplete run')
                    self.budget(run, events, launch)
                    if any(Path(run[key]).exists() for key in ('output', 'validation_output', 'raw_output') if key in run):
                        raise ValueError('unlogged existing run evidence; return to setup')
                    return dict(kind='run', id=run['id'], run=run)
                if finish['outcome'] not in ('complete', 'thermal'):
                    raise ValueError('failed run requires permitted retry or handback')
                # A complete run blocks until its immediate validation exists.
                if not Path(run['validation_output']).exists():
                    return dict(kind='validate', id=run['id'], command=run['validate_command'])
                self.run_evidence(run, finish)
                if finish['outcome'] == 'thermal':
                    raise ValueError('thermal evidence requires permitted retry or restoration handback')
            else:
                comparison = self.comparison(comparisons[step['compare']], replacements)
                if not Path(comparison['output']).exists():
                    return dict(kind='compare', id=comparison['id'], command=comparison['command'])
                contract = read(comparison['manifest'])
                candidate = self.runs[replacements.get(comparison['candidate'], comparison['candidate'])]
                control = self.runs[replacements.get(comparison['control'], comparison['control'])]
                result = evaluator.compare(read(candidate['output']), read(control['output']),
                                           contract['candidate'], contract['control'], contract.get('repeat_rule'))
                if result['evidence'] != 'complete' or read(comparison['output']) != result:
                    raise ValueError('missing, stale or invalid comparison barrier')
        return dict(kind='complete', handback='preserve events and evidence; return to restoration owner')

    def budget(self, run, events, launch):
        _, _, starts, _, _ = self.state(events)
        limits = self.manifest['limits']
        platform = run['platform']
        selected = [e for e in starts.values() if self.runs[e['run']]['platform'] == platform]
        baseline = next(e['storage_baseline'] for e in events if e['action'] == 'acquire')
        used = self.storage() - baseline
        if used >= limits['storage_bytes'] or shutil.disk_usage(self.path.parent).free < 100_000_000:
            raise ValueError('storage budget exhausted or insufficient free space')
        # Readiness, evidence and storage checks can be expensive. Sample the
        # live clock after them, immediately before reserving the command.
        self.now = self.clock()
        if len(selected) >= limits[platform + '_processes']:
            raise ValueError('process count exhausted (failures and timeouts count)')
        # Wall time spans all overhead and pauses since first platform reservation.
        elapsed = (self.now - timestamp(selected[0]['at'])).total_seconds() if selected else 0
        timeout = run['timeout_seconds']
        if timeout <= 0 or elapsed + timeout > limits[platform + '_seconds']:
            raise ValueError('platform time budget cannot fit full timeout')
        if platform == 'phone':
            start, end = (timestamp(launch['phone_window'][k]) for k in ('start', 'end'))
            if self.now < start or (end - self.now).total_seconds() < timeout + limits['restoration_margin_seconds']:
                raise ValueError('outside phone window or insufficient restoration margin')
            if selected:
                finished = [e for e in events if e['action'] == 'finish' and self.runs[e['run']]['platform'] == 'phone']
                if not finished or (self.now - timestamp(finished[-1]['at'])).total_seconds() < 300:
                    raise ValueError('five-minute phone cooldown incomplete')

    def storage(self):
        return sum(p.stat().st_size for p in self.path.parent.rglob('*') if not p.is_symlink() and p.is_file())

    def transition(self, action, run_id=None, outcome=None):
        with self.locked() as events:
            owner, active, starts, finishes, replacements = self.state(events)
            if action == 'acquire':
                if owner is not None:
                    raise ValueError('output namespace already owned; no automatic stale-owner takeover')
                self.ready()
                self.append(events, 'acquire', storage_baseline=self.storage())
                return {'owner': self.owner}
            self.owned(events)
            if action == 'next':
                return self.next_step(events)
            if action == 'release':
                if active:
                    raise ValueError('cannot release active process reservation')
                self.append(events, 'release')
                return {'released': self.owner}
            if action == 'start':
                step = self.next_step(events)
                if step['kind'] != 'run' or step['id'] != run_id:
                    raise ValueError('requested run is not the permitted next step')
                self.append(events, 'start', run=run_id)
                return step
            if action == 'finish':
                if active != run_id or outcome not in ('complete', 'transient', 'thermal', 'failed', 'timeout'):
                    raise ValueError('matching active run and explicit outcome required')
                run = self.runs[run_id]
                # Caller confirms exit; this tool cannot observe/terminate device processes.
                elapsed = (self.now - timestamp(starts[run_id]['at'])).total_seconds()
                if elapsed >= run['timeout_seconds']:
                    outcome = 'timeout'
                evidence = {}
                if outcome in ('complete', 'thermal'):
                    result, _ = release_report.evaluate(self.manifest, run_id)
                    if result['evidence'] != 'complete':
                        raise ValueError('invalid evidence; finish as failed and preserve it')
                    envelope = read(run['output'])
                    for previous in finishes:
                        previous_path = Path(self.runs[previous]['output'])
                        if previous_path.exists():
                            prior = read(previous_path).get('provenance', {})
                            if any(envelope['provenance'][key] == prior.get(key) for key in ('run_id', 'process_start_id')):
                                raise ValueError('reused run or process identity; finish as failed')
                    report = envelope['report']
                    statuses = [report.get(k, {}).get('thermalStatus', 0) for k in ('before', 'after')]
                    statuses += [r.get('thermalAfter', 0) for r in report.get('paced', [])]
                    statuses += [r.get('thermalStatus', 0) for r in report.get('memory', [])]
                    hot = run['platform'] == 'phone' and max(statuses) > 2
                    if hot:
                        outcome = 'thermal'
                    elif outcome == 'thermal':
                        raise ValueError('thermal retry requires measured phone thermal status above 2')
                    evidence = identity.files([run['output'], run['expected_manifest']])
                elif outcome == 'transient' and (run['platform'] != 'laptop' or Path(run['output']).exists()):
                    raise ValueError('transient retry only for laptop failure before complete output')
                self.append(events, 'finish', run=run_id, outcome=outcome, evidence=evidence)
                return {'outcome': outcome}
            if action == 'retry':
                self.ready()
                if active:
                    raise ValueError('active process cannot be retried')
                retry = self.runs[run_id]
                original = retry.get('retry_of')
                finish = finishes.get(original, {})
                if not original or original in replacements or finish.get('outcome') not in ('transient', 'thermal'):
                    raise ValueError('no permitted transient or thermal retry')
                if finish['outcome'] == 'transient' and sum(e['action'] == 'finish' and e.get('outcome') == 'transient' for e in events) >= 2:
                    raise ValueError('second transient laptop failure stops the batch')
                if finish['outcome'] == 'thermal':
                    self.run_evidence(self.runs[original], finish)
                attempts = [e for e in events if e['action'] == 'retry' and self.runs[e['run']]['platform'] == retry['platform']]
                maximum = self.manifest['limits'].get(retry['platform'] + '_retries',
                    self.manifest.get('retry_rule', {}).get(retry['platform'] + '_processes', 2))
                if len(attempts) >= maximum:
                    raise ValueError('retry budget exhausted')
                if any(e['action'] == 'retry' and self.runs[e['run']]['platform'] == retry['platform'] and
                       self.runs[e['run']]['retry_slot'] == retry['retry_slot'] for e in events):
                    raise ValueError('retry slot already consumed')
                if finish['outcome'] == 'thermal' and any(e['action'] == 'retry' and self.runs[e['run']]['platform'] == 'phone' and
                        self.runs[e['run']].get('mode') == retry.get('mode') for e in events):
                    raise ValueError('thermal retry for this mode already consumed')
                # Retry must replace the current barrier, not an earlier successful run.
                for step in self.manifest['execution_steps']:
                    if 'run' in step:
                        effective = replacements.get(step['run'], step['run'])
                        if effective == original:
                            downstream = [r['run'] for r in starts.values() if r['sequence'] > starts[original]['sequence']]
                            if downstream or any(Path(c['output']).exists() for c in self.manifest['comparisons'] if original in (c['candidate'], c['control'])):
                                raise ValueError('downstream work already started; cannot replace earlier evidence')
                            break
                        if finishes.get(effective, {}).get('outcome') not in ('complete', 'thermal'):
                            if effective != original:
                                raise ValueError('retry does not repair current run barrier')
                            break
                    elif not Path(self.comparison(next(c for c in self.manifest['comparisons'] if c['id'] == step['compare']), replacements)['output']).exists():
                        raise ValueError('pending comparison before retry')
                self.append(events, 'retry', original=original, run=run_id)
                return {'retry': run_id}
            raise ValueError('unknown action')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('acquire', 'next', 'start', 'finish', 'retry', 'release'))
    parser.add_argument('--manifest', type=Path, default=LAB / 'manifest.json')
    parser.add_argument('--owner', required=True, help='fresh session ID matching separate launch record')
    parser.add_argument('--run')
    parser.add_argument('--outcome', help='finish confirms process exit: complete/transient/thermal/failed/timeout')
    args = parser.parse_args()
    try:
        result = Execution(args.manifest, args.owner).transition(args.action, args.run, args.outcome)
    except (KeyError, ValueError, TypeError, OSError, StopIteration) as error:
        parser.exit(1, 'scheduling refused: ' + str(error) + '\n')
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
