"""Host-only phone continuation regressions; no device or inference calls."""
from datetime import timedelta
import json
import unittest
from unittest.mock import patch

import allocation_phone_continuation as phone
import test_allocation


class PhoneTests(unittest.TestCase):
    fixture = test_allocation.ContinuationTests.fixture
    save = test_allocation.ContinuationTests.save

    def setUp(self):
        test_allocation.ContinuationTests.setUp(self)
        a, b, expected = self.fixture('persistent')
        expected['provenance']['platform'] = 'phone'
        expected['input_samples'] = {'de.wav': 16000, 'en.wav': 16000, 'prefix': 16000}
        self.save(self.root / 'expected.json', expected)
        self.envelopes = {'one': a, 'two': b}
        for envelope in self.envelopes.values():
            envelope['provenance']['platform'] = 'phone'
            for row in envelope['report']['memory']:
                row['thermalStatus'] = 0
        for run in self.manifest['runs'] + self.manifest['retry_options']:
            run.update(stage=2, platform='phone', mode='persistent')
        self.manifest['execution_steps'] = [dict(step, stage=2) for step in self.manifest['execution_steps']]
        self.manifest['stages']['1'].update(runs=[], comparisons=[])
        self.manifest['stages']['2'].update(runs=['one', 'two'], comparisons=['pair'])
        self.save(self.path, self.manifest)
        self.record = self.root / 'continuation.json'
        self.save(self.record, {'fixture': True})
        self.pin = phone.identity.sha(self.record)
        self.window = self.root / 'window.json'
        window = json.loads((self.root / 'launch.json').read_text())
        window['phone_continuation'] = dict(path=str(self.record.resolve()), sha256=self.pin)
        self.save(self.window, window)
        checker = patch.object(phone.Execution, 'ready', side_effect=lambda: json.loads(self.window.read_text()))
        checker.start(); self.addCleanup(checker.stop)
        gate = patch('allocation_report.stage_gate', side_effect=lambda manifest, stage, replacements:
                     dict(stage=stage, evidence='complete', errors=[], rows=[], comparisons=[]))
        gate.start(); self.addCleanup(gate.stop)

    def act(self, action, run=None, outcome=None, platform=None, window=None, restoration=None):
        return phone.Execution(self.path, 'owner', self.record, self.pin, self.now).transition(
            action, run, outcome, platform, window, restoration)

    def begin(self):
        test_allocation.execution.Execution(self.path, 'owner', self.now).transition('acquire')
        self.act('begin-work', '2', platform='phone', window=self.window)

    def test_phone_reservation_validation_cooldown_and_restoration(self):
        self.begin()
        self.act('start', 'one')
        self.save(self.root / 'one.json', self.envelopes['one'])
        self.now += timedelta(seconds=1)
        self.act('finish', 'one', 'complete')
        self.assertEqual(self.act('next')['kind'], 'validate')
        test_allocation.ContinuationTests.validate(self, 'one')
        self.assertEqual(self.act('next')['kind'], 'cooldown')
        self.now += timedelta(seconds=300)
        self.assertEqual(self.act('next')['id'], 'two')
        with self.assertRaisesRegex(ValueError, 'restoration'):
            self.act('pause-work')
        self.act('pause-work', restoration=dict(completed=True, owner='Oriol', final_state='synthetic baseline retained'))
        with self.assertRaisesRegex(ValueError, 'new authorization'):
            self.act('begin-work', '2', platform='phone', window=self.window)

    def test_phone_authorization_does_not_renew_consumed_budget(self):
        self.begin()
        self.now += timedelta(seconds=941)
        self.assertEqual(self.act('next')['reason'], 'active-work budget cannot fit full timeout')
        self.act('pause-work', restoration=dict(completed=True, owner='Oriol', final_state='synthetic restored'))
        window = json.loads(self.window.read_text())
        self.now += timedelta(days=1)
        for key in ('start', 'end'):
            window['phone_window'][key] = (phone.allocation_execution.timestamp(window['phone_window'][key]) + timedelta(days=1)).isoformat()
        window['checked_at'] = self.now.astimezone(phone.allocation_execution.readiness.ZoneInfo('Europe/Zurich')).isoformat()
        second = self.root / 'second-window.json'; self.save(second, window)
        self.act('begin-work', '2', platform='phone', window=second)
        self.assertEqual(self.act('next')['reason'], 'active-work budget cannot fit full timeout')

    def test_missing_stage_gate_stops_phone_begin(self):
        test_allocation.execution.Execution(self.path, 'owner', self.now).transition('acquire')
        with patch('allocation_report.stage_gate', return_value={'evidence': 'incomplete'}):
            with self.assertRaisesRegex(ValueError, 'previous stage'):
                self.act('begin-work', '2', platform='phone', window=self.window)

    def test_window_pin_and_laptop_boundary(self):
        for stage, platform in [('1', 'phone'), ('2', 'laptop')]:
            with self.assertRaisesRegex(ValueError, 'only prepared'):
                self.act('begin-work', stage, platform=platform, window=self.window)
        with self.assertRaisesRegex(ValueError, 'pin reviewed'):
            self.act('begin-work', '2', platform='phone', window=self.root / 'launch.json')
        self.manifest['runs'][0]['platform'] = 'laptop'; self.save(self.path, self.manifest)
        with self.assertRaisesRegex(ValueError, 'outside prepared phone'):
            self.act('start', 'one')

    def test_original_laptop_barrier_is_not_skipped(self):
        with patch.object(phone.allocation_execution.Execution, 'next_step', return_value=dict(kind='begin-work', stage=3, platform='laptop')):
            executor = phone.Execution(self.path, 'owner', self.record, self.pin, self.now)
            self.assertEqual(executor.next_step([])['kind'], 'handback')

    def test_full_timeout_and_restoration_margin_must_fit(self):
        window = json.loads(self.window.read_text())
        window['phone_window']['end'] = (self.now + timedelta(seconds=950)).astimezone(
            phone.allocation_execution.readiness.ZoneInfo('Europe/Zurich')).isoformat()
        self.save(self.window, window)
        self.begin()
        self.assertEqual(self.act('next')['reason'], 'outside authorized window or restoration margin')
        with self.assertRaisesRegex(ValueError, 'not the permitted'):
            self.act('start', 'one')

    def test_continuation_record_pins_both_new_sources_and_original_amendment(self):
        sources = {}
        for name in phone.PHONE_FILES:
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b'synthetic administrative source')
            sources[name] = phone.identity.sha(path)
        record = dict(authorized_by='Oriol', session_id='owner',
                      scope='prepared-stage-2-3-phone-continuation', authorized_stages=[2, 3],
                      manifest=str(self.path), manifest_sha256=phone.identity.sha(self.path),
                      administrative_files=sources,
                      laptop_amendment=dict(path=str(self.record), sha256=self.pin))
        self.assertEqual(phone.verify_record(self.path, record, 'owner', self.root), {'fixture': True})
        source = self.root / next(iter(sources))
        original = source.read_bytes(); source.write_bytes(b'changed')
        with self.assertRaisesRegex(ValueError, 'source changed'):
            phone.verify_record(self.path, record, 'owner', self.root)
        source.write_bytes(original)
        self.record.write_text('{}')
        with self.assertRaisesRegex(ValueError, 'laptop amendment changed'):
            phone.verify_record(self.path, record, 'owner', self.root)


if __name__ == '__main__':
    unittest.main()
