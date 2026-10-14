#!/usr/bin/env python3
"""Cooperative three-stage scheduler. Returns commands; never launches inference.

Work intervals include overhead/cooldown until explicit pause. Overnight pauses
preserve consumed budgets. Exit and restoration are explicit caller attestations;
this host helper neither terminates nor independently observes device processes.
"""
import argparse
from datetime import datetime
import json
from pathlib import Path
import shutil

import allocation_report
import check_release_1a as readiness
import measurement_identity as identity
from release_execution import Execution as Legacy, read, timestamp

LAB = Path(__file__).resolve().parents[1] / 'corpus/lab/allocation-v1'


def work_accounting(events, now):
    active, spent = None, {}
    for event in events:
        if event['action'] == 'begin-work':
            if active is not None:
                raise ValueError('overlapping work intervals')
            active = event
        elif event['action'] == 'pause-work':
            if active is None or event['work_sequence'] != active['sequence']:
                raise ValueError('invalid work closure')
            key = (active['stage'], active['platform'])
            spent[key] = spent.get(key, 0) + (timestamp(event['at']) - timestamp(active['at'])).total_seconds()
            active = None
    if active:
        key = (active['stage'], active['platform'])
        spent[key] = spent.get(key, 0) + (now - timestamp(active['at'])).total_seconds()
    return active, spent


class Execution(Legacy):
    def state(self, events):
        return super().state([e for e in events if e['action'] not in ('begin-work', 'pause-work', 'note')])

    def work(self, events):
        return work_accounting(events, self.now)

    def ready(self):
        from check_allocation import launch_readiness
        errors = launch_readiness(self.manifest)
        launch = read(self.manifest['launch_record'])
        if launch.get('authorized') is not True or launch.get('authorized_by') != 'Oriol':
            errors.append('missing explicit experiment launch authorization')
        if launch.get('session_id') != self.owner or launch.get('source_commit') != self.manifest['source_commit']:
            errors.append('launch session/source mismatch')
        if launch.get('experiment')!='allocation-v1' or launch.get('authorized_stages')!=[1,2,3]:
            errors.append('explicit authorization for the named three-stage experiment required')
        if errors:
            raise ValueError('; '.join(errors))
        # The current interval owns its separately frozen dated phone authorization.
        events = [json.loads(line) for line in self.log.read_text().splitlines()] if self.log.exists() else []
        for event in events:
            if event['action']=='begin-work' and event['platform']=='phone' and identity.sha(event['launch'])!=event['launch_sha256']:
                raise ValueError('prior window authorization changed; continuation refused')
        active, _ = self.work(events)
        return read(active['launch']) if active and active['platform'] == 'phone' else launch

    def budget(self, run, events, launch):
        active, spent = self.work(events)
        stage, platform = run['stage'], run['platform']
        if not active or (active['stage'], active['platform']) != (stage, platform):
            raise ValueError('begin-work required for next stage/platform')
        self.now = self.clock()
        _, spent = self.work(events)
        _, _, starts, finishes, _ = self.state(events)
        limits = self.manifest['stages'][str(stage)]['limits']
        selected = [e for e in starts.values() if (self.runs[e['run']]['stage'], self.runs[e['run']]['platform']) == (stage, platform)]
        total = [e for e in starts.values() if self.runs[e['run']]['platform'] == platform]
        timeout = run['timeout_seconds']
        if len(selected) >= limits[platform + '_processes'] or len(total) >= self.manifest['limits'][platform + '_processes']:
            raise ValueError('cumulative process count exhausted')
        elapsed = spent.get((stage, platform), 0)
        cumulative = sum(v for (_, p), v in spent.items() if p == platform)
        if timeout <= 0 or elapsed + timeout > limits[platform + '_seconds'] or cumulative + timeout > self.manifest['limits'][platform + '_seconds']:
            raise ValueError('active-work budget cannot fit full timeout')
        baseline = next(e['storage_baseline'] for e in events if e['action'] == 'acquire')
        stage_baseline = next(e['storage_baseline'] for e in events if e['action']=='begin-work' and e['stage']==stage)
        if self.storage() - stage_baseline >= limits['storage_bytes']:
            raise ValueError('stage storage budget exhausted')
        if self.storage() - baseline >= self.manifest['limits']['storage_bytes'] or shutil.disk_usage(self.path.parent).free < 100_000_000:
            raise ValueError('combined storage budget exhausted')
        if platform == 'phone':
            errors = readiness.launch_errors(self.manifest, launch, self.owner, self.now)
            if errors:
                raise ValueError('; '.join(errors))
            if identity.sha(active['launch']) != active['launch_sha256']:
                raise ValueError('window authorization changed')
            if self.now < timestamp(launch['phone_window']['start']) or (timestamp(launch['phone_window']['end']) - self.now).total_seconds() < timeout + self.manifest['limits']['restoration_margin_seconds']:
                raise ValueError('outside authorized window or restoration margin')
            prior = [f for r, f in finishes.items() if self.runs[r]['platform'] == 'phone']
            if prior and (self.now - timestamp(prior[-1]['at'])).total_seconds() < 300:
                raise ValueError('five-minute phone cooldown incomplete')

    def next_step(self, events):
        _,active,starts,finishes,replacements=self.owned(events)
        launch=self.ready()
        if active:
            raise ValueError('active process reservation; confirm process exit and finish before scheduling')
        comparisons={c['id']:c for c in self.manifest['comparisons']}
        for stage in (1, 2, 3):
            for step in (s for s in self.manifest['execution_steps'] if s.get('stage',1)==stage):
                if 'run' in step:
                    run=self.runs[replacements.get(step['run'],step['run'])]
                    finish=finishes.get(run['id'])
                    if finish is None:
                        if run['id'] in starts:
                            raise ValueError('incomplete run')
                        try:
                            self.budget(run,events,launch)
                        except ValueError as error:
                            reason=str(error)
                            if reason=='five-minute phone cooldown incomplete':
                                last=next(e for e in reversed(events) if e['action']=='finish' and self.runs[e['run']]['platform']=='phone')
                                return dict(kind='cooldown',seconds=max(0,300-(self.now-timestamp(last['at'])).total_seconds()))
                            if reason in ('cumulative process count exhausted','active-work budget cannot fit full timeout','stage storage budget exhausted','combined storage budget exhausted','outside authorized window or restoration margin'):
                                growth=self.storage()-next(e['storage_baseline'] for e in events if e['action']=='acquire')
                                return dict(kind='handback',status='partial',reason=reason,
                                    report=allocation_report.aggregate(self.manifest,replacements,events,self.now,growth))
                            if reason!='begin-work required for next stage/platform':
                                raise
                            active_work,_=self.work(events)
                            if active_work:
                                return dict(kind='pause-work',stage=active_work['stage'],platform=active_work['platform'],restoration_required=active_work['platform']=='phone')
                            return dict(kind='begin-work',stage=run['stage'],platform=run['platform'],authorization_required=run['platform']=='phone')
                        if any(Path(run[k]).exists() for k in ('output','validation_output','raw_output') if k in run):
                            raise ValueError('unlogged existing run evidence; return to setup')
                        return dict(kind='run',id=run['id'],run=run)
                    if finish['outcome'] not in ('complete','thermal'):
                        raise ValueError('failed run requires permitted retry or handback')
                    if not Path(run['validation_output']).exists():
                        return dict(kind='validate',id=run['id'],command=run['validate_command'])
                    self.run_evidence(run,finish)
                    if finish['outcome']=='thermal':
                        raise ValueError('thermal evidence requires permitted retry or restoration handback')
                else:
                    comparison=self.comparison(comparisons[step['compare']],replacements)
                    if not Path(comparison['output']).exists():
                        return dict(kind='compare',id=comparison['id'],command=comparison['command'])
                    result=allocation_report.compare(self.manifest,comparison)
                    if result['evidence']!='complete' or read(comparison['output'])!=result:
                        raise ValueError('missing, stale or invalid comparison barrier')
            gate = allocation_report.stage_gate(self.manifest, stage, replacements)
            if gate['evidence'] != 'complete':
                return dict(kind='handback', stage=stage, report=gate)
        active_work, spent = self.work(events)
        owner, active_run, starts, finishes, replacements = self.state(events)
        growth=self.storage()-next(e['storage_baseline'] for e in events if e['action']=='acquire')
        report = allocation_report.aggregate(self.manifest, replacements, events, self.now, growth)
        report['accounting']['active_process']=active_run
        return dict(kind='complete', restoration_required=bool(active_work and active_work['platform']=='phone'), report=report)

    def run_evidence(self, run, finish):
        result = super().run_evidence(run, finish)
        if run['mode'] not in ('quality-full','historical-full') and allocation_report.input_sample_errors(read(run['output']),read(run['expected_manifest'])):
            raise ValueError('invalid exact-suite sample coverage')
        if run['platform']=='phone' and run['mode']!='historical-full':
            statuses=[row.get('thermalStatus') for row in read(run['output'])['report']['memory']]
            if not statuses or any(type(s) is not int or not 0 <= s <= 6 for s in statuses):
                raise ValueError('missing/invalid phone thermal evidence')
        if run['platform'] == 'phone' and run.get('quality_companions'):
            _, _, _, finishes, replacements = self._current_state
            for run_id in run['quality_companions']:
                companion = self.runs[replacements.get(run_id, run_id)]
                super().run_evidence(companion, finishes[companion['id']])
        return result

    def owned(self, events):
        state = super().owned(events)
        self._current_state = state
        return state

    def transition(self, action, run_id=None, outcome=None, platform=None, launch_path=None, restoration=None, note=None):
        if action not in ('begin-work', 'pause-work', 'retry', 'release', 'note'):
            return super().transition(action, run_id, outcome)
        with self.locked() as events:
            _, active_run, starts, finishes, replacements = self.owned(events)
            active_work, _ = self.work(events)
            if action=='note':
                if not isinstance(note,str) or not note.strip():
                    raise ValueError('administrative deviation/reason required; no transcripts')
                self.append(events,'note',reason=note)
                return {'recorded':True}
            if action == 'release':
                if active_run or active_work:
                    raise ValueError('pause work and confirm process exit before ownership handback')
                self.append(events, 'release')
                return {'released': self.owner}
            if action == 'begin-work':
                if active_run or active_work:
                    raise ValueError('active work/process must finish and pause first')
                self.ready()
                stage = int(run_id)
                if stage not in (1, 2, 3) or platform not in ('laptop', 'phone'):
                    raise ValueError('valid stage/platform required')
                if stage > 1 and allocation_report.stage_gate(self.manifest, stage - 1, replacements)['evidence'] != 'complete':
                    raise ValueError('previous stage has invalid/incomplete evidence')
                data = dict(stage=stage, platform=platform, storage_baseline=self.storage())
                if platform == 'phone':
                    launch_path = str(Path(launch_path).resolve())
                    launch = read(launch_path)
                    if launch.get('experiment')!='allocation-v1' or stage not in launch.get('authorized_stages',[]):
                        raise ValueError('window does not authorize this experiment stage')
                    errors = readiness.launch_errors(self.manifest, launch, self.owner, self.now)
                    if errors:
                        raise ValueError('; '.join(errors))
                    if self.now<timestamp(launch['phone_window']['start']):
                        raise ValueError('phone work cannot open before authorized window starts')
                    for previous in events:
                        if previous['action'] == 'begin-work' and previous.get('launch') == launch_path:
                            raise ValueError('new work window requires new authorization record')
                    closed=[e for e in events if e['action']=='pause-work' and e.get('restoration')]
                    if closed and timestamp(launch['checked_at'])<timestamp(closed[-1]['at']):
                        raise ValueError('resume requires fresh checks after previous restoration')
                    data.update(launch=launch_path, launch_sha256=identity.sha(launch_path))
                self.append(events, 'begin-work', **data)
                return data
            if action == 'pause-work':
                if active_run or not active_work:
                    raise ValueError('process exit and active work required before pause')
                if active_work['platform'] == 'phone':
                    if not restoration or restoration.get('completed') is not True or restoration.get('owner') != read(active_work['launch'])['restoration']['owner'] or not restoration.get('final_state'):
                        raise ValueError('named owner restoration completion/final state required')
                self.append(events, 'pause-work', work_sequence=active_work['sequence'], restoration=restoration)
                return {'paused': True, 'spent_seconds': self.work(events)[1].get((active_work['stage'], active_work['platform']), 0)}
            retry = self.runs[run_id]
            original = retry.get('retry_of')
            finish = finishes.get(original, {})
            if active_run or not original or original in replacements or finish.get('outcome') not in ('transient', 'thermal'):
                raise ValueError('no permitted retry of current failed process')
            stage, platform = retry['stage'], retry['platform']
            attempts = [e for e in events if e['action'] == 'retry' and (self.runs[e['run']]['stage'], self.runs[e['run']]['platform']) == (stage, platform)]
            if len(attempts) >= 2 or any(self.runs[e['run']]['retry_slot'] == retry['retry_slot'] for e in attempts):
                raise ValueError('stage retry slots exhausted')
            if finish['outcome'] == 'transient' and sum(e.get('outcome') == 'transient' and self.runs[e['run']]['stage'] == stage for e in finishes.values()) >= 2:
                raise ValueError('second transient laptop failure stops stage')
            if finish['outcome'] == 'thermal':
                self.run_evidence(self.runs[original], finish)
                key='mode' if stage==1 else 'configuration'
                if any(self.runs[e['run']][key] == retry[key] for e in attempts):
                    raise ValueError('thermal retry for configuration already consumed')
            if any(e['sequence'] > starts[original]['sequence'] for r, e in starts.items() if r != original):
                raise ValueError('downstream work already started')
            if any(Path(c['output']).exists() for c in self.manifest['comparisons'] if original in (c['candidate'], c['control'])):
                raise ValueError('comparison already saved before retry')
            self.ready()
            self.append(events, 'retry', run=run_id, original=original)
            return {'retry': run_id}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('acquire', 'next', 'start', 'finish', 'retry', 'release', 'begin-work', 'pause-work', 'note'))
    parser.add_argument('--manifest', type=Path, default=LAB / 'manifest.json')
    parser.add_argument('--owner', required=True)
    parser.add_argument('--run', help='run ID, or stage number for begin-work')
    parser.add_argument('--outcome')
    parser.add_argument('--platform', choices=('laptop', 'phone'))
    parser.add_argument('--window', type=Path)
    parser.add_argument('--restoration', type=Path, help='named owner completion/final-state JSON')
    parser.add_argument('--note',help='administrative deviation/handback reason only; never transcript text')
    args = parser.parse_args()
    try:
        result = Execution(args.manifest, args.owner).transition(args.action, args.run, args.outcome, args.platform,
                                                                args.window, read(args.restoration) if args.restoration else None,args.note)
    except (KeyError, TypeError, ValueError, OSError, StopIteration) as error:
        parser.exit(1, 'scheduling refused: ' + str(error) + '\n')
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
