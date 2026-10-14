"""Public synthetic smoke evidence; no device access or personal corpus."""
import copy
import json
from pathlib import Path
import unittest
from unittest.mock import patch

import allocation_smoke as smoke
import measurement_identity as identity
import test_evaluate_v4 as fixtures


class SmokeTests(unittest.TestCase):
    setUp = fixtures.SyntheticReportTests.setUp

    def fixture(self):
        envelope, _, expected = fixtures.SyntheticReportTests.fixture(self, 'persistent')
        expected.pop('scoring_dictionary')
        expected['takes'] = []
        expected['endpoints'] = ['de', 'en', 'silence'] * 2
        expected['endpoint_references'] = {name: dict(reference=text, lang=lang, cut_word=None)
            for name, lang, text in [('de', 'de', 'Hallo Welt.'), ('en', 'en', 'Hello world.'), ('silence', 'en', '')]}
        expected['input_samples'] = dict.fromkeys(('de', 'en', 'silence'), 16000)
        envelope['provenance']['platform'] = expected['provenance']['platform'] = 'phone'
        envelope['report']['takes'] = []
        row = envelope['report']['endpoints'][0]
        envelope['report']['endpoints'] = [dict(row, id=name, text=reference['reference'], repetition=rep)
            for rep in (0, 1) for name, reference in expected['endpoint_references'].items()]
        for memory in envelope['report']['memory']:
            memory['thermalStatus'] = 0
        root = self.dictionary.parent
        output, contract = root / 'report.json', root / 'contract.json'
        output.write_text(json.dumps(envelope)); contract.write_text(json.dumps(expected))
        run = dict(id='public', configuration='public', output=str(output), expected_manifest=str(contract), expected_sha256=identity.sha(contract))
        binary = dict(path=str(self.dictionary), sha256=identity.sha(self.dictionary))
        return envelope, run, dict(runs=[run], apk=binary, test_apk=binary, frozen_files={})

    def test_complete_public_two_pass_smoke(self):
        _, run, manifest = self.fixture()
        self.assertEqual(smoke.validate(manifest, run)['evidence'], 'complete')

    def test_silence_changed_pass_missing_speech_and_coverage_stop(self):
        envelope, run, manifest = self.fixture()
        changes = [('silence', 'text', 'invented'), ('de', 'text', ''), ('en', 'coverageSamples', 1),
                   ('de', 'text', 'changed public sentence')]
        for name, key, value in changes:
            changed = copy.deepcopy(envelope)
            next(r for r in changed['report']['endpoints'] if r['id'] == name)[key] = value
            Path(run['output']).write_text(json.dumps(changed))
            with self.subTest(name=name, key=key):
                self.assertEqual(smoke.validate(manifest, run)['evidence'], 'incomplete')

    def test_thermal_and_changed_binary_are_invalid(self):
        envelope, run, manifest = self.fixture()
        envelope['report']['memory'][0]['thermalStatus'] = 3
        Path(run['output']).write_text(json.dumps(envelope))
        self.assertIn('missing or excessive thermal status', smoke.validate(manifest, run)['errors'])
        manifest['apk']['sha256'] = '0' * 64
        self.assertIn('smoke binary changed: apk', smoke.validate(manifest, run)['errors'])

    def test_timeout_and_nonfinite_recording_reject_before_preservation(self):
        _, _, manifest = self.fixture()
        for elapsed in (-1, 421, float('nan'), float('inf'), None):
            with self.subTest(elapsed=elapsed), patch.object(smoke, 'preserve') as preserve:
                with self.assertRaises(ValueError):
                    smoke.record(manifest, 'public', '/unused', elapsed)
                preserve.assert_not_called()

    def test_existing_experiment_ledger_blocks_setup_pointer_mutation(self):
        root = self.dictionary.parent
        (root / 'execution-events.jsonl').write_text('{}\n')
        with patch.object(smoke, 'LEGACY', root), patch.object(smoke, 'validate') as validate:
            with self.assertRaisesRegex(ValueError, 'execution began'):
                smoke.stage1_proof({})
            validate.assert_not_called()
            with self.assertRaisesRegex(ValueError, 'execution began'):
                smoke.prepare()

    def test_proof_rechecks_all_reports_and_cumulative_time(self):
        _, run, manifest = self.fixture()
        root = Path(run['output']).parent
        manifest.update(limits=dict(inference_seconds=3600), purpose='public test')
        Path(run['output']).with_suffix('.validation.json').write_text(json.dumps(dict(
            evidence='complete', elapsed_seconds=100, report_sha256=identity.sha(run['output']))))
        with patch.object(smoke, 'SMOKE', root), patch.object(smoke, 'NAMES', ['public']):
            self.assertTrue(smoke.proof(manifest)['complete'])
            manifest['limits']['inference_seconds'] = 99
            with self.assertRaisesRegex(ValueError, 'cumulative'):
                smoke.proof(manifest)


if __name__ == '__main__':
    unittest.main()
