"""Host-only thermal readiness boundaries; no device or inference calls."""
from datetime import datetime, timedelta, timezone
import unittest

from allocation_thermal import PROTOCOL, sample_readiness


class ThermalReadinessTests(unittest.TestCase):
    def setUp(self):
        self.now = datetime(2026, 10, 18, 20, tzinfo=timezone.utc)
        self.reserved = self.now - timedelta(seconds=20)
        self.sample = dict(schema='idle-thermal-observation-v1', protocol=PROTOCOL,
                           staging_completed_at=(self.now - timedelta(seconds=10)).isoformat(),
                           query_started_at=(self.now - timedelta(seconds=2)).isoformat(),
                           query_completed_at=(self.now - timedelta(seconds=1)).isoformat(),
                           probe=dict(schema='idle-thermal-status-v1',
                                      source='android-thermalservice', thermalStatus=0))

    def check(self):
        return sample_readiness(self.sample, self.now, self.reserved)

    def test_zero_one_ready_and_two_through_six_wait(self):
        for status in range(7):
            with self.subTest(status=status):
                self.sample['probe']['thermalStatus'] = status
                result = self.check()
                self.assertEqual(result['kind'], 'ready' if status <= 1 else 'cooldown')
                self.assertEqual(result['poll_seconds'], 0 if status <= 1 else 15)

    def test_freshness_uses_query_start_and_includes_fifteen_seconds(self):
        self.sample['staging_completed_at'] = (self.now - timedelta(seconds=19)).isoformat()
        self.sample['query_started_at'] = (self.now - timedelta(seconds=15)).isoformat()
        self.assertEqual(self.check()['age_seconds'], 15)
        self.sample['query_started_at'] = (self.now - timedelta(seconds=15, microseconds=1)).isoformat()
        with self.assertRaisesRegex(ValueError, 'stale'):
            self.check()

    def test_invalid_status_and_identity_fail_closed(self):
        for status in (None, True, False, -1, 7, 0.0, '0'):
            self.sample['probe']['thermalStatus'] = status
            with self.subTest(status=status), self.assertRaises(ValueError):
                self.check()
        self.sample['probe']['thermalStatus'] = 0
        for target, key in ((self.sample, 'schema'), (self.sample, 'protocol'),
                            (self.sample['probe'], 'schema'), (self.sample['probe'], 'source')):
            original = target[key]
            target[key] = 'unknown'
            with self.subTest(key=key), self.assertRaises(ValueError):
                self.check()
            target[key] = original

    def test_order_missing_naive_and_future_timestamps_fail_closed(self):
        cases = [('staging_completed_at', self.reserved - timedelta(seconds=1)),
                 ('query_started_at', self.now - timedelta(seconds=11)),
                 ('query_completed_at', self.now - timedelta(seconds=3)),
                 ('query_completed_at', self.now + timedelta(seconds=1)),
                 ('query_started_at', self.now.replace(tzinfo=None))]
        for key, value in cases:
            original = self.sample[key]
            self.sample[key] = value.isoformat()
            with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                self.check()
            self.sample[key] = original
        del self.sample['query_started_at']
        with self.assertRaises(ValueError):
            self.check()


if __name__ == '__main__':
    unittest.main()
