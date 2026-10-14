"""Host-only scheduling regressions through persistent transition boundary."""
import copy
from datetime import datetime, timedelta, timezone
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import release_execution as execution
import release_report
import test_evaluate_v4 as fixtures


class ExecutionTests(unittest.TestCase):
    fixture = fixtures.SyntheticReportTests.fixture

    def setUp(self):
        fixtures.SyntheticReportTests.setUp(self)
        self.root = Path(self.temp.name)
        self.now = datetime.now(timezone.utc)
        self.path = self.root / 'manifest.json'
        artifact = self.root / 'restore.apk'
        artifact.write_bytes(b'synthetic')
        launch = {'authorized_by': 'Oriol', 'authorized': True, 'source_commit': 'synthetic',
                  'session_id': 'owner', 'hook_confirmed': True, 'guard_tests_passed': True,
                  'device_connection_and_versions_checked': True, 'checked_at': self.now.astimezone(execution.readiness.ZoneInfo('Europe/Zurich')).isoformat(),
                  'phone_window': {'timezone': 'Europe/Zurich',
                                   'start': self.now.astimezone(execution.readiness.ZoneInfo('Europe/Zurich')).isoformat(),
                                   'end': (self.now + timedelta(hours=4)).astimezone(execution.readiness.ZoneInfo('Europe/Zurich')).isoformat()},
                  'restoration': {'owner': 'Oriol', 'procedure': 'synthetic restoration',
                                  'artifact_path': str(artifact), 'artifact_sha256': execution.identity.sha(artifact)}}
        self.save(self.root / 'launch.json', launch)
        a, b, expected = self.fixture('unpaced-only')
        self.envelopes = {'one': a, 'two': b}
        self.save(self.root / 'expected.json', expected)
        runs = [dict(id=name, platform='laptop', mode='unpaced-only', timeout_seconds=60,
                     output=str(self.root / (name + '.json')), expected_manifest=str(self.root / 'expected.json'),
                     validation_output=str(self.root / (name + '-validation.json')), validate_command=['validate', name], command=['synthetic', name])
                for name in ('one', 'two')]
        retry = dict(runs[0], id='retry', retry_of='one', retry_slot=1,
                     output=str(self.root / 'retry.json'), validation_output=str(self.root / 'retry-validation.json'), comparisons=[])
        contract = self.root / 'comparison-contract.json'
        self.save(contract, dict(candidate=expected, control=expected, repeat_rule={'normalized_text_mismatches_allowed': 0}))
        comparison = dict(id='pair', candidate='two', control='one', manifest=str(contract), output=str(self.root / 'pair.json'), command=['compare'])
        retry['comparisons'] = [dict(comparison, output=str(self.root / 'retry-pair.json'))]
        self.manifest = dict(source_commit='synthetic', launch_record=str(self.root / 'launch.json'), runs=runs, retry_options=[retry],
                             comparisons=[comparison], execution_steps=[{'run': 'one'}, {'run': 'two'}, {'compare': 'pair'}],
                             limits=dict(laptop_processes=3, laptop_seconds=1000, phone_processes=2, phone_seconds=1000,
                                         restoration_margin_seconds=900, storage_bytes=1_000_000_000))
        self.save(self.path, self.manifest)
        # Full readiness inventories are a different seam (test_release_setup).
        # Actual authorization/window/restoration checks still execute here.
        checker = patch.object(execution.readiness, 'check', return_value=[])
        checker.start()
        self.addCleanup(checker.stop)

    def save(self, path, value):
        Path(path).write_text(json.dumps(value))

    def act(self, action, run=None, outcome=None, owner='owner'):
        return execution.Execution(self.path, owner, self.now).transition(action, run, outcome)

    def complete(self, name):
        self.act('start', name)
        self.save(self.root / (name + '.json'), self.envelopes[name])
        self.now += timedelta(seconds=1)
        self.act('finish', name, 'complete')

    def validate(self, name):
        result, output = release_report.evaluate(self.manifest, name)
        self.save(output, result)

    def test_lease_active_reservation_and_barriers(self):
        self.act('acquire')
        with self.assertRaisesRegex(ValueError, 'owned'):
            self.act('acquire', owner='another')
        with self.assertRaisesRegex(ValueError, 'not the permitted'):
            self.act('start', 'two')
        self.act('start', 'one')
        for action in ('next', 'release', 'retry'):
            with self.subTest(action=action), self.assertRaises(ValueError):
                self.act(action, 'retry')
        self.save(self.root / 'one.json', self.envelopes['one'])
        self.act('finish', 'one', 'complete')
        self.assertEqual(self.act('next')['kind'], 'validate')
        self.validate('one')
        self.complete('two')
        self.validate('two')
        self.assertEqual(self.act('next')['kind'], 'compare')
        contract = execution.read(self.manifest['comparisons'][0]['manifest'])
        result = execution.evaluator.compare(self.envelopes['two'], self.envelopes['one'], contract['candidate'], contract['control'], contract['repeat_rule'])
        self.save(self.root / 'pair.json', result)
        self.assertEqual(self.act('next')['kind'], 'complete')
        self.envelopes['one']['provenance']['run_id'] = 'replacement'
        self.save(self.root / 'one.json', self.envelopes['one'])
        with self.assertRaisesRegex(ValueError, 'changed since finish'):
            self.act('next')

    def test_failed_process_counts_retry_and_second_failure_stops(self):
        self.act('acquire')
        self.act('start', 'one')
        self.act('finish', 'one', 'transient')
        with self.assertRaisesRegex(ValueError, 'requires permitted retry'):
            self.act('next')
        self.act('retry', 'retry')
        self.assertEqual(self.act('next')['id'], 'retry')
        self.act('start', 'retry')
        self.act('finish', 'retry', 'transient')
        with self.assertRaises(ValueError):
            self.act('retry', 'retry')
        with self.assertRaises(ValueError):
            self.act('next')

    def test_timeout_does_not_release_active_and_cannot_retry(self):
        self.act('acquire')
        self.act('start', 'one')
        self.now += timedelta(seconds=61)
        with self.assertRaisesRegex(ValueError, 'active process'):
            self.act('next')
        self.assertEqual(self.act('finish', 'one', 'transient')['outcome'], 'timeout')
        with self.assertRaises(ValueError):
            self.act('retry', 'retry')

    def test_count_time_storage_and_window_refuse_start(self):
        for field, value in [('laptop_processes', 0), ('laptop_seconds', 59), ('storage_bytes', 0)]:
            with self.subTest(field=field):
                original = copy.deepcopy(self.manifest)
                self.manifest['limits'][field] = value
                self.save(self.path, self.manifest)
                self.act('acquire')
                with self.assertRaises(ValueError):
                    self.act('start', 'one')
                self.act('release')
                (self.root / 'execution-events.jsonl').unlink()
                self.manifest = original
        self.manifest['runs'][0]['platform'] = 'phone'
        self.save(self.path, self.manifest)
        self.act('acquire')
        self.now += timedelta(hours=3, minutes=44, seconds=1)
        with self.assertRaisesRegex(ValueError, 'restoration margin'):
            self.act('start', 'one')

    def test_stale_owner_and_invalid_comparison_never_advance(self):
        self.act('acquire')
        with self.assertRaisesRegex(ValueError, 'different or missing owner'):
            self.act('next', owner='another')
        self.complete('one')
        self.validate('one')
        self.complete('two')
        self.validate('two')
        self.save(self.root / 'pair.json', {'evidence': 'complete'})
        with self.assertRaisesRegex(ValueError, 'comparison barrier'):
            self.act('next')

    def test_existing_staged_storage_is_not_additional_output(self):
        staged = self.root / 'staged-model.onnx'
        staged.write_bytes(b'x' * 200_000)
        (self.root / 'linked-model.onnx').symlink_to(staged)
        self.manifest['limits']['storage_bytes'] = 100_000
        self.save(self.path, self.manifest)
        self.act('acquire')
        self.assertEqual(self.act('next')['id'], 'one')
        (self.root / 'new-output.bin').write_bytes(b'x' * 100_001)
        with self.assertRaisesRegex(ValueError, 'storage budget'):
            self.act('next')
        self.act('release')
        self.act('acquire')
        with self.assertRaisesRegex(ValueError, 'storage budget'):
            self.act('next')

    def test_thermal_retry_and_cooldown_use_measured_status(self):
        run = self.manifest['runs'][0]
        retry = self.manifest['retry_options'][0]
        run['platform'] = retry['platform'] = 'phone'
        expected = execution.read(run['expected_manifest'])
        expected['provenance']['platform'] = 'phone'
        self.save(run['expected_manifest'], expected)
        self.envelopes['one']['provenance']['platform'] = 'phone'
        for row in self.envelopes['one']['report']['memory']:
            row['thermalStatus'] = 3
        self.save(self.path, self.manifest)
        self.act('acquire')
        self.act('start', 'one')
        self.save(self.root / 'one.json', self.envelopes['one'])
        self.now += timedelta(seconds=1)
        self.assertEqual(self.act('finish', 'one', 'complete')['outcome'], 'thermal')
        with self.assertRaises(OSError):
            self.act('retry', 'retry')
        self.validate('one')
        with self.assertRaisesRegex(ValueError, 'thermal evidence requires'):
            self.act('next')
        self.act('retry', 'retry')
        with self.assertRaisesRegex(ValueError, 'cooldown'):
            self.act('start', 'retry')
        self.now += timedelta(seconds=300)
        self.assertEqual(self.act('start', 'retry')['id'], 'retry')

    def test_authorization_restoration_and_tampered_log_refuse(self):
        self.act('acquire')
        launch = execution.read(self.root / 'launch.json')
        launch['authorized'] = False
        self.save(self.root / 'launch.json', launch)
        with self.assertRaisesRegex(ValueError, 'authorization'):
            self.act('next')
        launch['authorized'] = True
        self.save(self.root / 'launch.json', launch)
        (self.root / 'restore.apk').write_bytes(b'changed')
        with self.assertRaisesRegex(ValueError, 'restoration artifact hash'):
            self.act('next')
        log = self.root / 'execution-events.jsonl'
        event = json.loads(log.read_text())
        event['sequence'] = 9
        log.write_text(json.dumps(event) + '\n')
        with self.assertRaisesRegex(ValueError, 'log chain'):
            self.act('next')

    def test_duplicate_freshness_identity_cannot_finish_second_run(self):
        self.act('acquire')
        self.complete('one')
        self.validate('one')
        self.act('start', 'two')
        self.save(self.root / 'two.json', self.envelopes['one'])
        with self.assertRaisesRegex(ValueError, 'reused run or process'):
            self.act('finish', 'two', 'complete')
        with self.assertRaisesRegex(ValueError, 'active process'):
            self.act('next')

    def test_clock_advance_during_checks_cannot_reserve_overdue_run(self):
        self.manifest['runs'][0]['platform'] = 'phone'
        self.save(self.path, self.manifest)
        self.act('acquire')
        runner = execution.Execution(self.path, 'owner', lambda: self.now)
        def slow_readiness(*args, **kwargs):
            self.now += timedelta(hours=3, minutes=45)
            return []
        with patch.object(execution.readiness, 'check', side_effect=slow_readiness):
            with self.assertRaisesRegex(ValueError, 'restoration margin'):
                runner.transition('start', 'one')

    def test_second_transient_failure_after_successful_retry_stops_batch(self):
        retry_two = dict(self.manifest['runs'][1], id='retry-two', retry_of='two', retry_slot=2,
                         output=str(self.root / 'retry-two.json'), validation_output=str(self.root / 'retry-two-validation.json'), comparisons=[])
        self.manifest['retry_options'].append(retry_two)
        self.save(self.path, self.manifest)
        self.act('acquire')
        self.act('start', 'one')
        self.act('finish', 'one', 'transient')
        self.act('retry', 'retry')
        self.envelopes['retry'] = copy.deepcopy(self.envelopes['one'])
        self.complete('retry')
        self.validate('retry')
        self.act('start', 'two')
        self.act('finish', 'two', 'transient')
        with self.assertRaisesRegex(ValueError, 'second transient'):
            self.act('retry', 'retry-two')


if __name__ == '__main__':
    unittest.main()
