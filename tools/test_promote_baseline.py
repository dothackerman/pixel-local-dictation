import copy
import unittest
from pathlib import Path
import json
import tempfile
import zipfile
import hashlib
from test_phone import phone
import measurement_identity as identity
import promote_baseline


class PromotionTests(unittest.TestCase):
    def test_acceptance_is_bound_to_replay_and_cannot_waive_another_configuration(self):
        record = json.loads(promote_baseline.RECORD.read_text())
        provenance = {k: record[k] for k in ('configuration_sha256', 'model_sha256', 'dictionary_sha256', 'runtime_sha256')}
        provenance['build_sha256'] = record['historical_build_sha256']
        candidate = dict(variant=record['variant'], model_directory=record['modelDirectory'],
                         artifacts=dict(record['artifacts'], **{'variant.json': record['configuration_sha256']}),
                         provenance=provenance)
        plan = dict(setups={'candidate-a': candidate})
        plan['sha256'] = identity.digest(plan)
        record['plan_sha256'] = plan['sha256']
        self.assertEqual(candidate, promote_baseline.accepted_configuration(plan, record))
        for key in ('configuration_sha256', 'model_sha256', 'dictionary_sha256', 'runtime_sha256', 'historical_build_sha256', 'plan_sha256'):
            altered = copy.deepcopy(record)
            altered[key] = '0' * 64
            with self.subTest(key=key), self.assertRaises(ValueError):
                promote_baseline.accepted_configuration(plan, altered)
        altered = copy.deepcopy(record)
        altered['variant']['hotwords']['score'] = 2.0
        with self.assertRaises(ValueError):
            promote_baseline.accepted_configuration(plan, altered)

    def test_provisioning_rejects_destination_outside_private_corpus(self):
        with self.assertRaisesRegex(ValueError, 'private corpus'):
            promote_baseline.stage(Path('/tmp'), Path('/tmp/outside'), Path('/tmp/no-apk'))

    def test_normal_stage_passes_protected_push_and_cannot_mutate_frozen_evidence(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            visit = root / 'corpus/lab/issue11/visit-a-s15'
            (visit / 'stage').mkdir(parents=True)
            (visit / 'receipts').mkdir()
            record = json.loads(promote_baseline.RECORD.read_text())
            for relative in record['artifacts']:
                source = visit / 'stage' / relative
                source.parent.mkdir(parents=True, exist_ok=True)
                source.write_text('synthetic ' + relative)
                record['artifacts'][relative] = identity.sha(source)
            record['dictionary_sha256'] = record['artifacts']['issue11-dictionary.txt']
            apk = root / 'app.apk'
            with zipfile.ZipFile(apk, 'w') as archive:
                archive.writestr('lib/arm64-v8a/libsynthetic.so', b'synthetic native')
            record['runtime_sha256'] = identity.digest({'lib/arm64-v8a/libsynthetic.so': hashlib.sha256(b'synthetic native').hexdigest()})
            provenance = {k: record[k] for k in ('configuration_sha256', 'model_sha256', 'dictionary_sha256', 'runtime_sha256')}
            provenance['build_sha256'] = record['historical_build_sha256']
            candidate = dict(variant=record['variant'], model_directory=record['modelDirectory'],
                             artifacts=dict(record['artifacts'], **{'variant.json': record['configuration_sha256']}),
                             provenance=provenance)
            plan = dict(setups={'candidate-a': candidate}, cases={})
            for number, lang in enumerate(('de', 'en'), 1):
                source = visit / 'stage' / f'take{number}.wav'
                source.write_bytes(b'synthetic public fixture')
                plan['cases'][f'take-{number}'] = dict(path=source.name, sha256=identity.sha(source), lang=lang)
            plan['sha256'] = identity.digest(plan)
            record['plan_sha256'] = plan['sha256']
            (visit / 'plan.json').write_text(json.dumps(plan))
            for number in (1, 2):
                receipt = dict(plan_sha256=plan['sha256'])
                for prefix, value in [('measurement', {'report': {'endpoints': [{'text': 'synthetic expected output'}]}}), ('phone_meta', {}), ('wrapper', {})]:
                    original = visit / f'{prefix}-{number}.json'
                    original.write_text(json.dumps(value))
                    receipt[prefix + '_path'] = str(original)
                    receipt[prefix + '_sha256'] = identity.sha(original)
                    receipt['phone' if prefix == 'phone_meta' else prefix] = value
                (visit / 'receipts' / f'candidate-r1-t{number}-candidate-a.json').write_text(json.dumps(receipt))
            record_path = root / 'record.json'
            record_path.write_text(json.dumps(record))
            stage = promote_baseline.stage(root, root / 'corpus/normal-stage', apk, record_path)
            self.assertEqual(stage, phone.validate_directory(stage))
            self.assertTrue(all(not p.is_symlink() for p in stage.rglob('*')))
            dictionary = stage / 'issue11-dictionary.txt'
            dictionary.write_text('changed stage')
            self.assertNotEqual(dictionary.read_text(), (visit / 'stage/issue11-dictionary.txt').read_text())
            self.assertFalse((stage / 'live.json').exists())
            self.assertFalse((stage / 'variant.json').exists())
