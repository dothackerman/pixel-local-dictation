"""Synthetic versioned thermal scheduling checks; no device access."""
from datetime import timedelta
import json
import unittest
from unittest.mock import patch

import allocation_thermal_continuation as thermal
import test_allocation
import test_allocation_phone_continuation as phone_tests


class ThermalContinuationTests(unittest.TestCase):
    fixture = phone_tests.PhoneTests.fixture
    save = phone_tests.PhoneTests.save

    def setUp(self):
        phone_tests.PhoneTests.setUp(self)
        for run in self.manifest['runs'] + self.manifest['retry_options']:
            run['commands'] = [['synthetic-stage'], ['synthetic-inference'], ['synthetic-pull']]
        legacy = self.root / 'legacy.json'
        self.save(legacy, {'source_files': {}})
        self.manifest['legacy_manifest'] = dict(path=str(legacy))
        self.save(self.path, self.manifest)
        window = json.loads(self.window.read_text())
        window['thermal_continuation'] = dict(path=str(self.record.resolve()), sha256=self.pin)
        self.save(self.window, window)
        checker = patch.object(thermal.Execution, 'ready', side_effect=lambda: json.loads(self.window.read_text()))
        checker.start(); self.addCleanup(checker.stop)
        checkpoint = patch.object(thermal.Execution, 'release_checkpoint', return_value='synthetic')
        checkpoint.start(); self.addCleanup(checkpoint.stop)
        inventory = thermal.artifacts.inventory
        collector = patch.object(thermal.artifacts, 'inventory', side_effect=lambda path, root: inventory(path, self.root))
        collector.start(); self.addCleanup(collector.stop)

    def executor(self):
        return thermal.Execution(self.path, 'owner', self.record, self.pin, self.now)

    def act(self, action, run=None, outcome=None, platform=None, window=None, restoration=None):
        return self.executor().transition(action, run, outcome, platform, window, restoration)

    def begin(self):
        test_allocation.execution.Execution(self.path, 'owner', self.now).transition('acquire')
        self.act('begin-work', '2', platform='phone', window=self.window)

    def observation(self, status=0):
        sample = dict(schema='idle-thermal-observation-v1', protocol=thermal.PROTOCOL,
                      staging_completed_at=self.now.isoformat(), query_started_at=self.now.isoformat(),
                      query_completed_at=self.now.isoformat(),
                      probe=dict(schema='idle-thermal-status-v1', source='android-thermalservice', thermalStatus=status))
        path = self.root / ('sample-' + str(status) + '-' + str(self.now.timestamp()) + '.json')
        self.save(path, sample)
        return path

    def test_reservation_returns_only_staging_and_probe_is_post_staging(self):
        self.begin()
        result = self.act('start', 'one')
        self.assertEqual(result['run']['commands'], [['synthetic-stage']])
        events = [json.loads(line) for line in (self.root / 'execution-events.jsonl').read_text().splitlines()]
        self.assertEqual(self.executor().cooldown_protocols(events), {'one': thermal.PROTOCOL})
        self.executor().prepare_probe('one')
        result = self.executor().authorize_inference('one', self.observation(1))
        self.assertEqual(result['command'], ['synthetic-inference'])
        self.assertEqual(result['kind'], 'ready')
        with self.assertRaisesRegex(ValueError, 'no-inference-started'):
            self.executor().authorize_inference('one', self.observation(0))

    def test_two_waits_without_inference_then_one_authorizes(self):
        self.begin(); self.act('start', 'one')
        self.executor().prepare_probe('one')
        result = self.executor().authorize_inference('one', self.observation(2))
        self.assertEqual(result['poll_seconds'], 15)
        self.assertNotIn('command', result)
        with self.assertRaisesRegex(ValueError, 'polling interval'):
            self.executor().authorize_inference('one', self.observation(1))
        self.now += timedelta(seconds=15)
        self.assertEqual(self.executor().authorize_inference('one', self.observation(1))['kind'], 'ready')

    def test_missing_reservation_stale_read_and_reserved_deadline_fail_closed(self):
        self.begin()
        with self.assertRaisesRegex(ValueError, 'active prepared'):
            self.executor().authorize_inference('one', self.observation())
        self.act('start', 'one')
        self.executor().prepare_probe('one')
        sample = self.observation()
        self.now += timedelta(seconds=16)
        with self.assertRaisesRegex(ValueError, 'stale'):
            self.executor().authorize_inference('one', sample)
        self.now += timedelta(seconds=60)
        with self.assertRaisesRegex(ValueError, 'deadline reached'):
            self.executor().authorize_inference('one', self.observation())

    def test_thermal_wait_keeps_original_active_time_budget(self):
        self.begin(); self.act('start', 'one')
        self.executor().prepare_probe('one')
        self.now += timedelta(seconds=941)
        with self.assertRaisesRegex(ValueError, 'full timeout'):
            self.executor().authorize_inference('one', self.observation())

    def test_only_original_fixed_cooldown_is_replaced(self):
        self.begin(); self.act('start', 'one')
        self.executor().prepare_probe('one')
        self.executor().authorize_inference('one', self.observation())
        self.save(self.root / 'one.json', self.envelopes['one'])
        self.now += timedelta(seconds=1)
        self.act('finish', 'one', 'complete')
        test_allocation.ContinuationTests.validate(self, 'one')
        self.assertEqual(self.act('next')['id'], 'two')
        self.now += timedelta(seconds=941)
        self.assertEqual(self.act('next')['reason'], 'active-work budget cannot fit full timeout')

    def test_success_requires_one_saved_ready_authorization(self):
        self.begin(); self.act('start', 'one')
        with self.assertRaisesRegex(ValueError, 'ready thermal authorization'):
            self.act('finish', 'one', 'complete')

    def test_expired_ready_can_refresh_only_with_unused_attestation(self):
        self.begin(); self.act('start', 'one')
        self.executor().prepare_probe('one')
        first = self.observation(0)
        self.executor().authorize_inference('one', first)
        self.now += timedelta(seconds=16)
        with self.assertRaisesRegex(ValueError, 'no-inference-started'):
            self.executor().authorize_inference('one', self.observation(0))
        result = self.executor().authorize_inference('one', self.observation(1), True)
        self.assertEqual(result['kind'], 'ready')
        events = [json.loads(line) for line in (self.root / 'execution-events.jsonl').read_text().splitlines()]
        self.assertIsNotNone(events[-1]['thermal']['supersedes_sequence'])
        self.assertTrue(first.is_file())
        self.assertEqual(sum(event['action'] == 'start' for event in events), 1)

    def test_poll_cadence_uses_queries_not_late_submission(self):
        self.begin(); self.act('start', 'one')
        self.executor().prepare_probe('one')
        sample = self.observation(2)
        self.now += timedelta(seconds=14)
        self.executor().authorize_inference('one', sample)
        sample = self.observation(1)
        self.now += timedelta(seconds=1)
        with self.assertRaisesRegex(ValueError, 'polling interval'):
            self.executor().authorize_inference('one', sample)
        self.assertEqual(self.executor().authorize_inference('one', self.observation(1))['kind'], 'ready')

    def test_readiness_and_release_checkpoint_drift_fail_closed(self):
        self.begin(); self.act('start', 'one')
        with patch.object(thermal.Execution, 'ready', side_effect=ValueError('source checkpoint drift')):
            with self.assertRaisesRegex(ValueError, 'source checkpoint drift'):
                self.executor().prepare_probe('one')
        with self.assertRaisesRegex(ValueError, 'full readiness'):
            self.executor().authorize_inference('one', self.observation())
        self.executor().prepare_probe('one')
        with patch.object(thermal.Execution, 'release_checkpoint', side_effect=ValueError('source checkpoint drift')):
            with self.assertRaisesRegex(ValueError, 'source checkpoint drift'):
                self.executor().authorize_inference('one', self.observation())

    def test_runtime_model_contract_drift_after_readiness_stops_release(self):
        files = [self.root / name for name in ('runtime.so', 'model.onnx', 'contract.json')]
        for path in files:
            path.write_text('{}')
        self.manifest['synthetic_artifacts'] = list(map(str, files))
        self.save(self.path, self.manifest)
        self.begin(); self.act('start', 'one')
        for path in files:
            self.executor().prepare_probe('one')
            path.write_text('{"changed": true}')
            with self.subTest(path=path.name), self.assertRaisesRegex(ValueError, 'artifact changed'):
                self.executor().authorize_inference('one', self.observation())

    def test_full_readiness_cannot_be_reused_after_120_seconds(self):
        for run in self.manifest['runs']:
            run['timeout_seconds'] = 300
        self.save(self.path, self.manifest)
        self.begin(); self.act('start', 'one')
        self.executor().prepare_probe('one')
        self.now += timedelta(seconds=121)
        with self.assertRaisesRegex(ValueError, 'readiness expired'):
            self.executor().authorize_inference('one', self.observation())

    def test_slow_artifact_verification_consumes_sample_freshness(self):
        self.begin(); self.act('start', 'one')
        self.executor().prepare_probe('one')
        sample = self.observation()
        executor = self.executor()
        executor.clock = lambda: self.now
        original = thermal.artifacts.verify

        def slow_verify(snapshot):
            original(snapshot)
            self.now += timedelta(seconds=16)

        with patch.object(thermal.artifacts, 'verify', side_effect=slow_verify):
            with self.assertRaisesRegex(ValueError, 'stale'):
                executor.authorize_inference('one', sample)

    def test_laptop_barrier_and_window_pin_remain(self):
        with self.assertRaisesRegex(ValueError, 'pin reviewed'):
            self.act('begin-work', '2', platform='phone', window=self.root / 'launch.json')
        with patch.object(thermal.allocation_execution.Execution, 'next_step', return_value=dict(kind='begin-work', stage=3, platform='laptop')):
            self.assertEqual(self.executor().next_step([])['reason'], 'original laptop barrier remains')


if __name__ == '__main__':
    unittest.main()
