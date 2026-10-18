#!/usr/bin/env python3
"""Versioned adaptive-thermal continuation; returns commands, never executes them.

Requires separately approved protected access revision, a committed pinned source
checkpoint and a fresh dated window. Existing scientific contracts stay frozen.
"""
import argparse
from datetime import timedelta
import hashlib
import json
from pathlib import Path
import subprocess
import uuid

import allocation_artifact_snapshot as artifacts
import allocation_execution
import allocation_phone_continuation as previous
from allocation_laptop_continuation import ADMIN_FILES, PLAN, ROOT
from allocation_thermal import PROTOCOL, sample_readiness
import check_allocation
import measurement_identity as identity
from release_execution import read, timestamp


NEW_FILES = {'tools/allocation_thermal.py', 'tools/test_allocation_thermal.py',
             'tools/allocation_artifact_snapshot.py', 'tools/test_allocation_artifact_snapshot.py',
             'tools/allocation_thermal_continuation.py', 'tools/test_allocation_thermal_continuation.py',
             'tools/thermal_status.py', 'tools/test_thermal_status.py'}
ACCESS_FILE = 'tools/phone'
ALLOWED_FILES = ADMIN_FILES | previous.PHONE_FILES | NEW_FILES | {ACCESS_FILE}


def verify_record(manifest_path, record, owner, root=ROOT):
    if (record.get('schema') != 1 or record.get('authorized_by') != 'Oriol'
            or record.get('session_id') != owner
            or record.get('scope') != 'prepared-stage-2-3-phone-thermal-continuation-v1'
            or record.get('authorized_stages') != [2, 3]
            or record.get('protocol') != PROTOCOL
            or Path(record['manifest']).resolve() != Path(manifest_path).resolve()
            or record.get('manifest_sha256') != identity.sha(manifest_path)):
        raise ValueError('invalid thermal continuation authorization')
    approval = record['protected_change']
    if (approval.get('authorized_by') != 'Oriol'
            or not isinstance(approval.get('authorization'), str)
            or not approval['authorization'].strip()
            or approval.get('access') != 'read-only-android-thermalservice-status'
            or approval.get('file') != ACCESS_FILE):
        raise ValueError('explicit protected revision and thermal access approval required')
    if set(record['administrative_files']) != ALLOWED_FILES:
        raise ValueError('unexpected thermal administrative source scope')
    for name, expected in record['administrative_files'].items():
        if identity.sha(root / name) != expected:
            raise ValueError('thermal continuation source changed: ' + name)
    if approval.get('approved_sha256') != record['administrative_files'][ACCESS_FILE]:
        raise ValueError('protected revision differs from approval')
    historical = []
    for key in ('laptop_amendment', 'phone_continuation'):
        pin = record[key]
        if identity.sha(pin['path']) != pin['sha256']:
            raise ValueError('historical administrative authorization changed')
        historical.append(read(pin['path']))
    laptop, phone = historical
    if phone.get('laptop_amendment') != record['laptop_amendment']:
        raise ValueError('historical amendment chain differs')
    for historical_record in historical:
        for name, expected in historical_record['administrative_files'].items():
            if name != PLAN and identity.sha(root / name) != expected:
                raise ValueError('historical administrative source changed: ' + name)
    historical_plan = subprocess.check_output(['git', 'show', phone['source_checkpoint'] + ':' + PLAN], cwd=root)
    if hashlib.sha256(historical_plan).hexdigest() != laptop['administrative_files'][PLAN]:
        raise ValueError('historical plan checkpoint differs')
    manifest = read(manifest_path)
    plan = (root / PLAN).read_bytes()
    if not plan.startswith(historical_plan):
        raise ValueError('historical plan appendix changed')
    prefix = laptop['original_plan_bytes']
    if hashlib.sha256(plan[:prefix]).hexdigest() != manifest['source_files'][str(root / PLAN)]:
        raise ValueError('original frozen plan changed')
    if hashlib.sha256(plan[prefix:]).hexdigest() != record['appendices_sha256']:
        raise ValueError('reviewed plan appendices changed')


class Execution(allocation_execution.Execution):
    def __init__(self, manifest_path, owner, continuation_path, continuation_sha256, now=None):
        super().__init__(manifest_path, owner, now)
        self.continuation_path = Path(continuation_path)
        self.continuation_sha256 = continuation_sha256

    def ready(self):
        if identity.sha(self.continuation_path) != self.continuation_sha256:
            raise ValueError('thermal continuation record changed')
        record = read(self.continuation_path)
        verify_record(self.path, record, self.owner)
        permitted = {'frozen artifact changed: ' + str(ROOT / PLAN),
                     'checkout commit differs from frozen execution source'}
        errors = [error for error in check_allocation.launch_readiness(self.manifest) if error not in permitted]
        source = self.manifest['source_commit']
        subprocess.run(['git', 'merge-base', '--is-ancestor', source, 'HEAD'], cwd=ROOT, check=True)
        head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
        if record.get('source_checkpoint') != head:
            errors.append('thermal source checkpoint differs')
        changed = set(subprocess.check_output(['git', 'diff', '--name-only', source, 'HEAD'], cwd=ROOT, text=True).splitlines())
        if not changed <= ALLOWED_FILES:
            errors.append('non-administrative source changed')
        if subprocess.run(['git', 'diff', '--quiet', 'HEAD', '--', *sorted(ALLOWED_FILES)], cwd=ROOT).returncode:
            errors.append('thermal checkpoint is not committed')
        tracked = set(subprocess.check_output(['git', 'ls-files', '--', *sorted(ALLOWED_FILES)], cwd=ROOT, text=True).splitlines())
        if tracked != ALLOWED_FILES:
            errors.append('thermal checkpoint is not tracked')
        original_access = subprocess.check_output(['git', 'show', source + ':' + ACCESS_FILE], cwd=ROOT)
        if hashlib.sha256(original_access).hexdigest() != record['protected_change'].get('original_sha256'):
            errors.append('protected revision base differs')
        launch = read(self.manifest['launch_record'])
        if (launch.get('authorized') is not True or launch.get('authorized_by') != 'Oriol'
                or launch.get('session_id') != self.owner or launch.get('source_commit') != source
                or launch.get('experiment') != 'allocation-v1' or launch.get('authorized_stages') != [1, 2, 3]):
            errors.append('original experiment launch authorization mismatch')
        events = [json.loads(line) for line in self.log.read_text().splitlines()]
        for event in events:
            if event['action'] == 'begin-work' and event.get('amendment_sha256'):
                pin = record['laptop_amendment']
                if event['amendment_sha256'] != pin['sha256'] or Path(event['amendment']).resolve() != Path(pin['path']).resolve():
                    errors.append('prior laptop amendment differs')
            if event['action'] == 'begin-work' and event['platform'] == 'phone':
                if identity.sha(event['launch']) != event['launch_sha256']:
                    errors.append('prior phone window changed')
                prior_pin = read(event['launch']).get('thermal_continuation')
                if prior_pin and prior_pin != self.pin():
                    errors.append('prior thermal continuation differs')
        if errors:
            raise ValueError('; '.join(errors))
        active, _ = self.work(events)
        return read(active['launch']) if active and active['platform'] == 'phone' else launch

    def pin(self):
        return dict(path=str(self.continuation_path.resolve()), sha256=self.continuation_sha256)

    def release_checkpoint(self):
        """Recheck every scientific source/build and approved source identity.

        Full model/input/device-proof validation happens in prepare_probe, before
        querying. This bounded check runs after querying and detects checkpoint
        drift without spending the observation's freshness on large model hashes.
        """
        if identity.sha(self.continuation_path) != self.continuation_sha256:
            raise ValueError('thermal continuation record changed')
        record = read(self.continuation_path)
        verify_record(self.path, record, self.owner)
        head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
        if head != record['source_checkpoint']:
            raise ValueError('thermal source checkpoint differs')
        legacy = read(self.manifest['legacy_manifest']['path'])
        sources = {**legacy.get('source_files', {}), **self.manifest['source_files']}
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
        return identity.digest(dict(head=head, sources=sources, builds=[legacy['apk'], legacy['test_apk']]))

    def prepare_probe(self, run_id):
        with self.locked() as events:
            _, active, _, _, _ = self.owned(events)
            if active != run_id:
                raise ValueError('matching active prepared phone reservation required')
            snapshot = artifacts.inventory(self.path, ROOT)
            self.ready()
            artifacts.verify(snapshot)
            checkpoint = self.release_checkpoint()
            self.now = self.clock()
            work, _ = self.work(events)
            self.budget(self.runs[run_id], events, read(work['launch']))
            snapshot_dir = self.path.parent / 'artifact-snapshots'
            snapshot_dir.mkdir(exist_ok=True)
            snapshot_path = snapshot_dir / ('probe-' + str(uuid.uuid4()) + '.json')
            with snapshot_path.open('x') as output:
                json.dump(snapshot, output, sort_keys=True)
            self.append(events, 'note', reason='thermal probe readiness', run=run_id,
                        thermal_preflight=dict(checkpoint_sha256=checkpoint,
                                               artifacts=dict(path=str(snapshot_path), sha256=identity.sha(snapshot_path))))
            return dict(protocol=PROTOCOL, readiness_completed_at=self.now.isoformat(),
                        query_command=[str(ROOT / ACCESS_FILE), 'thermal'])

    def budget(self, run, events, launch):
        # The old fixed cooldown is the final check in the frozen budget method.
        # All preceding count/time/storage/window checks run without modification.
        try:
            return super().budget(run, events, launch)
        except ValueError as error:
            if str(error) != 'five-minute phone cooldown incomplete' or run['platform'] != 'phone':
                raise

    def next_step(self, events):
        step = super().next_step(events)
        platform = step.get('platform', step.get('run', {}).get('platform'))
        if platform == 'laptop':
            return dict(kind='handback', status='partial', reason='original laptop barrier remains',
                        cooldown_protocols=self.cooldown_protocols(events))
        if step.get('kind') == 'run':
            step = dict(step, run=dict(step['run']))
            step['run']['commands'] = step['run']['commands'][:1]
            step['next_required'] = 'post-staging thermal observation and authorize-inference'
            step['cooldown_protocol'] = PROTOCOL
        if step.get('kind') == 'handback':
            step = dict(step, cooldown_protocols=self.cooldown_protocols(events))
        return step

    def cooldown_protocols(self, events):
        """Label scheduling policy separately from frozen inference protocols."""
        current = None
        assignments = {}
        for event in events:
            if event['action'] == 'begin-work' and event['platform'] == 'phone':
                window = read(event['launch'])
                current = PROTOCOL if window.get('thermal_continuation') else 'allocation-fixed-cooldown-300s-v1'
            elif event['action'] == 'pause-work':
                current = None
            elif event['action'] == 'start' and self.runs[event['run']]['platform'] == 'phone':
                if current is None:
                    raise ValueError('phone attempt lacks cooldown protocol window')
                assignments[event['run']] = current
        return assignments

    def authorize_inference(self, run_id, sample_path, previous_inference_not_started=False):
        with self.locked() as events:
            checkpoint = self.release_checkpoint()
            self.now = self.clock()
            _, active, starts, _, _ = self.owned(events)
            work, _ = self.work(events)
            run = self.runs[run_id]
            if active != run_id or not work or run['platform'] != 'phone' or run['stage'] not in (2, 3):
                raise ValueError('matching active prepared phone reservation required')
            launch = read(work['launch'])
            if identity.sha(work['launch']) != work['launch_sha256'] or launch.get('thermal_continuation') != self.pin():
                raise ValueError('thermal window identity changed')
            self.budget(run, events, launch)
            preflights = [event for event in events if event['action'] == 'note'
                          and event.get('run') == run_id and event.get('thermal_preflight')]
            if not preflights or preflights[-1]['thermal_preflight']['checkpoint_sha256'] != checkpoint:
                raise ValueError('fresh matching full readiness before thermal query required')
            preflight = preflights[-1]
            if (self.now - timestamp(preflight['at'])).total_seconds() > 120:
                raise ValueError('full thermal readiness expired; prepare probe again')
            snapshot = preflight['thermal_preflight']['artifacts']
            if identity.sha(snapshot['path']) != snapshot['sha256']:
                raise ValueError('validated artifact inventory changed')
            artifacts.verify(read(snapshot['path']))
            # Artifact verification itself spends real active work and sample
            # freshness. Recheck against the clock at the release boundary.
            self.now = self.clock()
            self.budget(run, events, launch)
            self.now = self.clock()
            if (self.now - timestamp(preflight['at'])).total_seconds() > 120:
                raise ValueError('full thermal readiness expired; prepare probe again')
            if (self.now - timestamp(starts[run_id]['at'])).total_seconds() >= run['timeout_seconds']:
                raise ValueError('reserved run deadline reached before inference')
            observations = [event for event in events if event['action'] == 'note'
                            and event.get('run') == run_id and event.get('thermal')]
            sample = read(sample_path)
            if timestamp(sample['query_started_at']) < timestamp(preflights[-1]['at']):
                raise ValueError('thermal query precedes full readiness')
            supersedes = None
            if observations:
                last = observations[-1]
                if identity.sha(last['thermal']['path']) != last['thermal']['sha256']:
                    raise ValueError('saved thermal observation changed')
                prior = read(last['thermal']['path'])
                prior_start = timestamp(prior['query_started_at'])
                if last['thermal']['kind'] == 'ready':
                    if self.now <= prior_start + timedelta(seconds=15) or previous_inference_not_started is not True:
                        raise ValueError('expired authorization and explicit no-inference-started attestation required')
                    supersedes = last['sequence']
                elif (timestamp(sample['query_started_at']) - prior_start).total_seconds() < 15:
                    raise ValueError('fifteen-second thermal polling interval incomplete')
            result = sample_readiness(sample, self.now, timestamp(starts[run_id]['at']))
            self.append(events, 'note', reason='adaptive thermal observation', run=run_id,
                        thermal=dict(path=str(Path(sample_path).resolve()), sha256=identity.sha(sample_path),
                                     previous_inference_not_started=previous_inference_not_started,
                                     supersedes_sequence=supersedes, **result))
            if result['kind'] == 'ready':
                result['command'] = run['commands'][1]
                result['must_start_by'] = (timestamp(sample['query_started_at']) + timedelta(seconds=15)).isoformat()
            return result

    def transition(self, action, run_id=None, outcome=None, platform=None, launch_path=None, restoration=None, note=None):
        if action == 'begin-work':
            if str(run_id) not in ('2', '3') or platform != 'phone':
                raise ValueError('only prepared stage-2/3 phone work authorized')
            if read(launch_path).get('thermal_continuation') != self.pin():
                raise ValueError('window must pin reviewed thermal continuation')
        if action in ('start', 'finish', 'retry'):
            run = self.runs[run_id]
            if run['stage'] not in (2, 3) or run['platform'] != 'phone':
                raise ValueError('run outside prepared thermal continuation')
        if action == 'finish' and outcome in ('complete', 'thermal'):
            with self.locked() as events:
                self.owned(events)
                authorized = [event['thermal'] for event in events if event['action'] == 'note'
                              and event.get('run') == run_id and event.get('thermal')]
                if not authorized or authorized[-1]['kind'] != 'ready':
                    raise ValueError('saved ready thermal authorization required')
                if identity.sha(authorized[-1]['path']) != authorized[-1]['sha256']:
                    raise ValueError('saved thermal observation changed')
        if action not in ('next', 'start', 'finish', 'retry', 'begin-work', 'pause-work', 'note', 'release'):
            raise ValueError('action outside thermal continuation')
        return super().transition(action, run_id, outcome, platform, launch_path, restoration, note)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('next', 'start', 'finish', 'retry', 'begin-work', 'pause-work', 'note', 'release', 'prepare-probe', 'authorize-inference'))
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
    parser.add_argument('--sample', type=Path)
    parser.add_argument('--previous-inference-not-started', action='store_true')
    args = parser.parse_args()
    try:
        executor = Execution(args.manifest, args.owner, args.continuation, args.continuation_sha256)
        if args.action == 'authorize-inference':
            result = executor.authorize_inference(args.run, args.sample, args.previous_inference_not_started)
        elif args.action == 'prepare-probe':
            result = executor.prepare_probe(args.run)
        else:
            result = executor.transition(args.action, args.run, args.outcome, args.platform, args.window,
                                        read(args.restoration) if args.restoration else None, args.note)
    except (KeyError, TypeError, ValueError, OSError, StopIteration, subprocess.CalledProcessError) as error:
        parser.exit(1, 'scheduling refused: ' + str(error) + '\n')
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
