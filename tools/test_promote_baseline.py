import copy
import unittest
from pathlib import Path
import json
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
