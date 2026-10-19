"""Offline scientific allocation barriers; synthetic reports only."""
import copy
import json
from pathlib import Path
import unittest
from unittest.mock import patch

import allocation_report as reports
import test_evaluate_v4 as fixtures


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
            result,path=reports.validate_run(manifest,run['id'])
            Path(path).write_text(json.dumps(result))
        return manifest


    def test_offline_validation_writes_new_output_and_preserves_frozen_verdict(self):
        manifest=self.make_manifest()
        root=Path(self.temp.name)
        path=root/'manifest.json';path.write_text(json.dumps(manifest))
        frozen=Path(manifest['runs'][0]['validation_output'])
        original=frozen.read_bytes()
        output=root/'review.json'
        command=['allocation_report', 'validate', '--manifest', str(path),
                 '--id', 'phone-1', '--output', str(output)]
        with patch('sys.argv',command),self.assertRaises(SystemExit) as exit:
            reports.main()
        self.assertEqual(exit.exception.code,False)
        self.assertEqual(json.loads(output.read_text()),json.loads(frozen.read_text()))
        with patch('sys.argv',command),self.assertRaises(FileExistsError):
            reports.main()
        self.assertEqual(frozen.read_bytes(),original)

    def test_valid_persistent_negative_resources_and_incomplete_counters(self):
        manifest=self.make_manifest()
        self.assertEqual(reports.stage_gate(manifest,1)['action'],'advance')
        run=manifest['runs'][0]
        envelope=json.loads(Path(run['output']).read_text())
        for row in envelope['report']['memory']:row['highWaterKb']=4_000_000
        Path(run['output']).write_text(json.dumps(envelope))
        result,path=reports.validate_run(manifest,run['id']);Path(path).write_text(json.dumps(result))
        self.assertEqual(reports.stage_gate(manifest,1)['action'],'advance')
        envelope['report']['memory'][0]['rssKb']=None
        Path(run['output']).write_text(json.dumps(envelope))
        self.assertEqual(reports.stage_gate(manifest,1)['action'],'interpretation handback')


    def test_persistent_intra_process_changed_text_stops_advancement(self):
        manifest=self.make_manifest()
        run=manifest['runs'][0];envelope=json.loads(Path(run['output']).read_text())
        envelope['report']['takes'][2]['text']='Changed words'
        Path(run['output']).write_text(json.dumps(envelope))
        result,path=reports.validate_run(manifest,run['id']);Path(path).write_text(json.dumps(result))
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


if __name__ == '__main__':
    unittest.main()
