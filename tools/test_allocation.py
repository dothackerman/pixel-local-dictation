"""Host synthetic regressions at scheduler and evidence-barrier public seams."""
import copy
from datetime import timedelta
import json
from pathlib import Path
import unittest
from unittest.mock import patch

import allocation_execution as execution
import allocation_report as reports
import check_allocation
import test_release_execution as legacy_tests
import test_evaluate_v4 as fixtures


class ContinuationTests(unittest.TestCase):
    fixture = fixtures.SyntheticReportTests.fixture
    save = legacy_tests.ExecutionTests.save
    complete = legacy_tests.ExecutionTests.complete
    validate = legacy_tests.ExecutionTests.validate

    def setUp(self):
        legacy_tests.ExecutionTests.setUp(self)
        launch=json.loads((self.root/'launch.json').read_text())
        launch.update(experiment='allocation-v1',authorized_stages=[1,2,3])
        self.save(self.root/'launch.json',launch)
        expected=json.loads((self.root/'expected.json').read_text())
        expected['input_samples']={'de.wav':16000,'en.wav':16000,'prefix':16000}
        self.save(self.root/'expected.json',expected)
        for run in self.manifest['runs'] + self.manifest['retry_options']:
            run.update(stage=1, configuration='synthetic')
        self.manifest.update(ranking={},allocation_device_validation={'synthetic':True}, stages={str(s):dict(
            runs=[r['id'] for r in self.manifest['runs']] if s==1 else [], comparisons=['pair'] if s==1 else [],
            limits=copy.deepcopy(self.manifest['limits'])) for s in (1,2,3)})
        self.save(self.path, self.manifest)
        checker = patch('check_allocation.launch_readiness', return_value=[])
        checker.start()
        self.addCleanup(checker.stop)

    def act(self, action, run=None, outcome=None, owner='owner', platform=None, window=None, restoration=None):
        return execution.Execution(self.path, owner, self.now).transition(action, run, outcome, platform, window, restoration)

    def begin(self):
        self.act('acquire')
        self.assertEqual(self.act('next')['kind'],'begin-work')
        self.act('begin-work','1',platform='laptop')

    def test_pause_resume_retains_spent_count_storage_and_ownership(self):
        self.begin()
        self.complete('one')
        self.validate('one')
        self.now += timedelta(seconds=600)
        self.act('pause-work')
        self.now += timedelta(days=1)
        self.act('begin-work','1',platform='laptop')
        self.assertEqual(self.act('next')['id'],'two')
        self.now += timedelta(seconds=340)
        self.assertEqual(self.act('next')['status'],'partial')
        with self.assertRaisesRegex(ValueError,'not the permitted'):
            self.act('start','two')
        with self.assertRaisesRegex(ValueError,'different or missing owner'):
            self.act('next',owner='other')
        self.act('pause-work')
        self.act('release')
        self.act('acquire')
        self.act('begin-work','1',platform='laptop')
        self.assertIn('active-work budget',self.act('next')['reason'])

    def test_timeout_interruption_never_releases_active_process(self):
        self.begin()
        self.act('start','one')
        self.now += timedelta(seconds=61)
        for action in ('pause-work','release','next'):
            with self.subTest(action=action), self.assertRaises(ValueError):
                self.act(action)
        self.assertEqual(self.act('finish','one','complete')['outcome'],'timeout')
        with self.assertRaises(ValueError):
            self.act('retry','retry')
        self.act('pause-work')
        self.now += timedelta(days=1)
        self.act('begin-work','1',platform='laptop')
        with self.assertRaisesRegex(ValueError,'failed run'):
            self.act('next')

    def test_transient_retry_uses_unique_output_and_counts_process(self):
        self.begin()
        self.act('start','one')
        self.act('finish','one','transient')
        self.act('retry','retry')
        self.envelopes['retry']=copy.deepcopy(self.envelopes['one'])
        self.complete('retry')
        self.validate('retry')
        self.assertEqual(self.act('next')['id'],'two')
        events=[json.loads(line) for line in (self.root/'execution-events.jsonl').read_text().splitlines()]
        self.assertEqual(sum(e['action']=='start' for e in events),2)
        self.assertFalse((self.root/'one.json').exists())

    def test_storage_budget_survives_overnight_and_reacquire(self):
        self.manifest['limits']['storage_bytes']=10000
        self.save(self.path,self.manifest)
        self.begin()
        self.act('pause-work')
        self.act('release')
        (self.root/'retained-evidence.bin').write_bytes(b'x'*10001)
        self.now+=timedelta(days=1)
        self.act('acquire')
        self.act('begin-work','1',platform='laptop')
        self.assertIn('storage budget',self.act('next')['reason'])

    def test_phone_pause_requires_named_restoration_and_new_window(self):
        self.act('acquire')
        self.act('begin-work','1',platform='phone',window=self.root/'launch.json')
        for proof in (None,{'completed':True,'owner':'other','final_state':'restored'}):
            with self.subTest(proof=proof),self.assertRaisesRegex(ValueError,'restoration'):
                self.act('pause-work',restoration=proof)
        self.act('pause-work',restoration={'completed':True,'owner':'Oriol','final_state':'synthetic restored build'})
        with self.assertRaisesRegex(ValueError,'new authorization'):
            self.act('begin-work','1',platform='phone',window=self.root/'launch.json')

    def test_changed_contract_or_ledger_cannot_resume(self):
        self.begin()
        self.act('pause-work')
        self.manifest['limits']['laptop_processes']=99
        self.save(self.path,self.manifest)
        with self.assertRaisesRegex(ValueError,'manifest changed'):
            self.act('begin-work','1',platform='laptop')

    def test_stage_storage_limit_remains_stricter_than_combined(self):
        self.manifest['stages']['1']['limits']['storage_bytes']=10000
        self.save(self.path,self.manifest)
        self.begin()
        (self.root/'stage-output.bin').write_bytes(b'x'*10001)
        self.act('pause-work')
        self.now+=timedelta(days=1)
        self.act('begin-work','1',platform='laptop')
        self.assertIn('stage storage budget',self.act('next')['reason'])

    def test_three_valid_stages_advance_and_preserve_aggregate_accounting(self):
        runs=[]
        a,b,expected=self.fixture('persistent')
        expected['provenance']['platform']='phone'
        expected['input_samples']={'de.wav':16000,'en.wav':16000,'prefix':16000}
        contract=self.root/'persistent-expected.json';self.save(contract,expected)
        for stage in (1,2,3):
            for repetition in (1,2):
                name=f's{stage}-{repetition}'
                run=dict(self.manifest['runs'][0],id=name,stage=stage,platform='phone',mode='persistent',configuration='stock',
                         output=str(self.root/(name+'.json')),validation_output=str(self.root/(name+'-validation.json')),
                         expected_manifest=str(contract))
                envelope=copy.deepcopy(a)
                envelope['provenance'].update(platform='phone',run_id=name,process_start_id=name)
                for row in envelope['report']['memory']:row['thermalStatus']=0
                self.envelopes[name]=envelope;runs.append(run)
        self.manifest.update(runs=runs,retry_options=[],comparisons=[],execution_steps=[{'run':r['id'],'stage':r['stage']} for r in runs],ranking={})
        self.manifest['limits'].update(phone_processes=6,phone_seconds=5000)
        for stage in (1,2,3):
            self.manifest['stages'][str(stage)].update(runs=[r['id'] for r in runs if r['stage']==stage],comparisons=[])
        self.save(self.path,self.manifest)
        self.act('acquire')
        for stage in (1,2,3):
            if stage>1:
                self.assertEqual(self.act('next')['kind'],'pause-work')
                self.act('pause-work',restoration={'completed':True,'owner':'Oriol','final_state':'synthetic restored'})
            self.assertEqual(self.act('next')['kind'],'begin-work')
            window=self.root/f'window-{stage}.json'
            authorization=json.loads((self.root/'launch.json').read_text())
            authorization['checked_at']=self.now.astimezone(execution.readiness.ZoneInfo('Europe/Zurich')).isoformat()
            self.save(window,authorization)
            self.act('begin-work',str(stage),platform='phone',window=window)
            for repetition in (1,2):
                name=f's{stage}-{repetition}'
                if stage>1 or repetition>1:self.now+=timedelta(seconds=300)
                self.complete(name);self.validate(name)
        result=self.act('next')
        self.assertEqual(result['kind'],'complete')
        self.assertTrue(result['restoration_required'])
        self.assertEqual(result['report']['accounting']['started_processes']['phone'],6)
        self.assertEqual(len(result['report']['accounting']['attempts']),6)
        self.assertEqual(result['report']['status'],'completed')

    def test_comparison_barrier_rechecks_matched_resource_report(self):
        self.begin()
        self.complete('one');self.validate('one')
        self.complete('two');self.validate('two')
        self.assertEqual(self.act('next')['kind'],'compare')
        comparison=self.manifest['comparisons'][0]
        result=reports.compare(self.manifest,comparison)
        self.assertEqual(result['matched_resources']['delta']['cold_load_ms'],0)
        self.save(comparison['output'],result)
        self.assertEqual(self.act('next')['kind'],'handback')  # this fixture lacks the required phone mode
        result['matched_resources']['delta']['cold_load_ms']=999
        self.save(comparison['output'],result)
        with self.assertRaisesRegex(ValueError,'comparison barrier'):
            self.act('next')

    def test_stage_one_authorization_cannot_expand_to_whole_loop(self):
        launch=json.loads((self.root/'launch.json').read_text())
        launch['authorized_stages']=[1]
        self.save(self.root/'launch.json',launch)
        with self.assertRaisesRegex(ValueError,'three-stage experiment'):
            self.act('acquire')

    def test_deviation_note_is_append_only_and_retained_in_handback(self):
        self.begin()
        execution.Execution(self.path,'owner',self.now).transition('note',note='Awaiting separately authorized window')
        self.act('pause-work')
        events=[json.loads(line) for line in (self.root/'execution-events.jsonl').read_text().splitlines()]
        result=reports.aggregate(self.manifest,events=events,now=self.now)
        self.assertEqual(result['accounting']['deviations'][0]['reason'],'Awaiting separately authorized window')
        self.assertEqual(result['accounting']['started_processes']['laptop'],0)


class StageBarrierTests(fixtures.SyntheticReportTests):
    def make_manifest(self,mode='persistent'):
        a,b,expected=self.fixture(mode)
        root=Path(self.temp.name)
        runs=[]
        for index,envelope in enumerate((a,b),1):
            name='phone-'+str(index)
            envelope['provenance']['platform']='phone'
            for row in envelope['report']['memory']:
                row['thermalStatus']=0
            expected['provenance']['platform']='phone'
            expected['input_samples']={'de.wav':16000,'en.wav':16000,'prefix':16000}
            output=root/(name+'.json');output.write_text(json.dumps(envelope))
            contract=root/(name+'-expected.json');contract.write_text(json.dumps(expected))
            validation=root/(name+'-validation.json')
            runs.append(dict(id=name,platform='phone',stage=1,configuration='stock',mode=mode,output=str(output),expected_manifest=str(contract),validation_output=str(validation)))
        manifest=dict(runs=runs,retry_options=[],comparisons=[],stages={'1':dict(runs=[r['id'] for r in runs],comparisons=[])})
        for run in runs:
            result,path=reports.release_report.evaluate(manifest,run['id'])
            Path(path).write_text(json.dumps(result))
        return manifest

    def test_valid_persistent_negative_resources_and_incomplete_counters(self):
        manifest=self.make_manifest()
        self.assertEqual(reports.stage_gate(manifest,1)['action'],'advance')
        run=manifest['runs'][0]
        envelope=json.loads(Path(run['output']).read_text())
        for row in envelope['report']['memory']:row['highWaterKb']=4_000_000
        Path(run['output']).write_text(json.dumps(envelope))
        result,path=reports.release_report.evaluate(manifest,run['id']);Path(path).write_text(json.dumps(result))
        self.assertEqual(reports.stage_gate(manifest,1)['action'],'advance')
        envelope['report']['memory'][0]['rssKb']=None
        Path(run['output']).write_text(json.dumps(envelope))
        self.assertEqual(reports.stage_gate(manifest,1)['action'],'interpretation handback')

    def test_persistent_intra_process_changed_text_stops_advancement(self):
        manifest=self.make_manifest()
        run=manifest['runs'][0];envelope=json.loads(Path(run['output']).read_text())
        envelope['report']['takes'][2]['text']='Changed words'
        Path(run['output']).write_text(json.dumps(envelope))
        result,path=reports.release_report.evaluate(manifest,run['id']);Path(path).write_text(json.dumps(result))
        gate=reports.stage_gate(manifest,1)
        self.assertEqual(gate['action'],'interpretation handback')
        self.assertTrue(any('passes changed' in e for e in gate['errors']))

    def test_equal_but_short_input_and_coverage_cannot_claim_full_suite(self):
        manifest=self.make_manifest()
        run=manifest['runs'][0];envelope=json.loads(Path(run['output']).read_text())
        envelope['report']['takes'][0].update(inputSamples=1,coverageSamples=1)
        Path(run['output']).write_text(json.dumps(envelope))
        result,_=reports.validate_run(manifest,run['id'])
        self.assertEqual(result['evidence'],'incomplete')
        self.assertTrue(any('input length differs' in e for e in result['evidence_errors']))

    def test_allocation_transparency_includes_guard_and_silence(self):
        for section,mutation in (('takes',lambda e:e['report']['takes'][0].update(text='Wrong')),
                                 ('guard',lambda e:e['report']['guard'][0].update(text='Wrong')),
                                 ('silence',lambda e:e['report']['silence'].update(silent='Invented'))):
            with self.subTest(section=section):
                a,b,expected=self.fixture('quality-full');root=Path(self.temp.name)
                a['provenance']['configuration_sha256']='b'*64
                candidate_expected=copy.deepcopy(expected)
                candidate_expected['provenance']['configuration_sha256']='b'*64
                for name,envelope in (('a',a),('b',b)):
                    (root/(name+'.json')).write_text(json.dumps(envelope))
                contract=root/'pair.json';contract.write_text(json.dumps(dict(candidate=candidate_expected,control=expected,repeat_rule=None)))
                manifest=dict(runs=[dict(id=n,output=str(root/(n+'.json'))) for n in ('a','b')],retry_options=[])
                comparison=dict(candidate='a',control='b',manifest=str(contract),purpose='allocation-transparency')
                self.assertEqual(reports.compare(manifest,comparison)['evidence'],'complete')
                mutation(a);(root/'a.json').write_text(json.dumps(a))
                self.assertEqual(reports.compare(manifest,comparison)['evidence'],'incomplete')

    def test_aggregate_complete_and_partial_are_independent_of_acceptance(self):
        manifest=self.make_manifest()
        manifest['stages'].update({'2':copy.deepcopy(manifest['stages']['1']), '3':copy.deepcopy(manifest['stages']['1'])})
        manifest['ranking']={'meaningful_gain_threshold':None}
        complete=reports.aggregate(manifest)
        self.assertEqual(complete['status'],'completed')
        self.assertEqual(complete['acceptance'],'diagnostic-only')
        Path(manifest['runs'][0]['validation_output']).unlink()
        partial=reports.aggregate(manifest)
        self.assertEqual(partial['status'],'blocked')
        self.assertEqual(partial['recording_trigger'],'unchanged; not evaluated')

    def test_valid_started_stage_with_remaining_missing_runs_is_partial(self):
        manifest=self.make_manifest()
        manifest['stages'].update({'2':copy.deepcopy(manifest['stages']['1']), '3':copy.deepcopy(manifest['stages']['1'])})
        manifest['ranking']={'meaningful_gain_threshold':None}
        Path(manifest['runs'][1]['output']).unlink()
        Path(manifest['runs'][1]['validation_output']).unlink()
        self.assertEqual(reports.aggregate(manifest)['status'],'partial')

    def test_readiness_rejects_truthy_but_missing_or_uncovered_device_proof(self):
        root=Path(self.temp.name)
        legacy=root/'legacy.json';legacy.write_text(json.dumps({'apk':{'sha256':'a'*64},'test_apk':{'sha256':'b'*64}}))
        proof=root/'proof.json';proof.write_text(json.dumps({'complete':True,'apk':{'sha256':'a'*64},'test_apk':{'sha256':'b'*64},'runs':[]}))
        manifest={'legacy_manifest':{'path':str(legacy)},'launch_record':str(root/'launch.json'),
                  'allocation_device_validation':{'path':str(proof),'sha256':reports.identity.sha(proof)},
                  'runs':[{'stage':2,'platform':'phone','configuration':'arena-off'}]}
        with patch.object(check_allocation,'setup_errors',return_value=[]):
            self.assertIn('device smoke does not cover every prepared configuration',check_allocation.launch_readiness(manifest))
            proof.write_text('{}')
            self.assertIn('invalid device validation',check_allocation.launch_readiness(manifest))

    def test_malformed_existing_comparison_and_raw_only_failure_are_blocked(self):
        manifest=self.make_manifest()
        manifest['stages'].update({'2':copy.deepcopy(manifest['stages']['1']), '3':copy.deepcopy(manifest['stages']['1'])})
        manifest['ranking']={}
        root=Path(self.temp.name)
        output=root/'broken-comparison.json';output.write_text('malformed')
        manifest['comparisons']=[dict(id='broken',candidate='phone-1',control='phone-2',manifest=str(root/'absent.json'),output=str(output))]
        for stage in manifest['stages'].values():stage['comparisons']=['broken']
        self.assertEqual(reports.aggregate(manifest)['status'],'blocked')
        output.unlink();manifest['comparisons']=[]
        for stage in manifest['stages'].values():stage['comparisons']=[]
        for run in manifest['runs']:
            Path(run['output']).unlink();Path(run['validation_output']).unlink()
        raw=root/'failed.raw.json';raw.write_text('malformed')
        manifest['runs'][0]['raw_output']=str(raw)
        self.assertEqual(reports.aggregate(manifest)['status'],'blocked')

    def test_smoke_cannot_claim_unused_language_annotations_as_coverage(self):
        a,b,expected=self.fixture('persistent');root=Path(self.temp.name)
        a['provenance']['platform']=expected['provenance']['platform']='phone'
        a['report']['takes']=[];expected['takes']=[]
        for row in a['report']['memory']:row['thermalStatus']=0
        for row in a['report']['endpoints']:row.update(id='silence',text='')
        expected['endpoints']=['silence','silence']
        expected['endpoint_references']={'silence':{'reference':'','lang':'en','cut_word':None},
            'unused-de':{'reference':'Hallo Welt','lang':'de','cut_word':None},
            'unused-en':{'reference':'Hello world','lang':'en','cut_word':None}}
        report=root/'smoke.json';report.write_text(json.dumps(a))
        contract=root/'smoke-contract.json';contract.write_text(json.dumps(expected))
        apk={'sha256':'a'*64};test_apk={'sha256':'b'*64}
        legacy=root/'legacy.json';legacy.write_text(json.dumps(dict(apk=apk,test_apk=test_apk)))
        entry=dict(configuration='arena-off',report=str(report),report_sha256=reports.identity.sha(report),
                   expected_manifest=str(contract),expected_sha256=reports.identity.sha(contract))
        proof=root/'proof.json';proof.write_text(json.dumps(dict(complete=True,apk=apk,test_apk=test_apk,runs=[entry])))
        manifest=dict(legacy_manifest={'path':str(legacy)},launch_record=str(root/'launch.json'),
            allocation_device_validation={'path':str(proof),'sha256':reports.identity.sha(proof)},
            runs=[dict(stage=2,platform='phone',configuration='arena-off',expected_manifest=str(contract))])
        with patch.object(check_allocation,'setup_errors',return_value=[]):
            self.assertIn('invalid device smoke: arena-off',check_allocation.launch_readiness(manifest))


if __name__=='__main__':
    unittest.main()
