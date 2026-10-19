"""Synthetic profile evidence checks; never loads models or recordings."""
import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

from summarize_profiles import summarize, interval_union


class ProfileSummaryTests(unittest.TestCase):
    def summary(self, events):
        with TemporaryDirectory() as directory:
            (Path(directory) / 'profile.json').write_text(json.dumps(events))
            return summarize(directory)['sessions'][0]

    def test_union_handles_overlap_touching_and_empty(self):
        self.assertEqual(interval_union([(0, 5), (3, 8), (8, 10), (20, 21)]), 11)
        self.assertEqual(interval_union([]), 0)

    def test_nested_node_events_never_enter_model_wall(self):
        row = self.summary([
            dict(cat='Node', name='/pre_encode/node', ts=0, dur=50),
            dict(cat='Session', name='model_run', ts=5, dur=10),
            dict(cat='Session', name='model_run', ts=15, dur=5)])
        self.assertEqual(row['model_run_count'], 2)
        self.assertEqual(row['model_run_union_us'], 15)
        self.assertEqual(row['graph'], 'parakeet-encoder')
        self.assertFalse(row['clock_aligned'])

    def test_overlapping_runs_reject_additive_wall_claim(self):
        row = self.summary([dict(name='model_run', ts=0, dur=10),
                            dict(name='model_run', ts=5, dur=10)])
        self.assertEqual(row['model_run_union_us'], 15)
        self.assertEqual(row['model_run_overlap_us'], 5)
        self.assertFalse(row['valid_for_additive_wall'])

    def test_unknown_graph_remains_unassigned(self):
        row = self.summary([dict(cat='Node', name='unrecognized', ts=0, dur=1)])
        self.assertEqual(row['graph'], 'unassigned')
        self.assertFalse(row['valid_for_additive_wall'])

    def test_bad_or_truncated_input_retained_as_invalid(self):
        with TemporaryDirectory() as directory:
            (Path(directory) / 'broken.json').write_text('[{"name":')
            rows = summarize(directory)['sessions']
            self.assertEqual(len(rows), 1)
            self.assertFalse(rows[0]['valid_for_additive_wall'])
            self.assertIn('malformed', rows[0]['errors'][0])
        row = self.summary([dict(name='model_run', ts=0, dur=-1)])
        self.assertFalse(row['valid_for_additive_wall'])
        self.assertTrue(row['errors'])

    def test_malformed_nodes_retain_invalid_row_and_valid_neighbor(self):
        valid = [dict(cat='Node', name='/pre_encode/node', ts=0, dur=2,
                      args=dict(provider='CPUExecutionProvider')),
                 dict(name='model_run', ts=0, dur=5)]
        malformed_fields = [dict(name=None), dict(name=[]), dict(dur='bad'),
                            dict(args=None), dict(args=[]), dict(args=dict(provider=[])),
                            dict(ts=None), dict(ts=True), dict(ts=-1),
                            dict(dur=False), dict(dur=-1), dict(dur=float('nan')),
                            dict(ts=float('inf')), dict(dur=10 ** 400),
                            dict(ts=1e308, dur=1e308)]
        for fields in malformed_fields:
            with self.subTest(fields=fields), TemporaryDirectory() as directory:
                malformed = dict(cat='Node', name='/dec_rnn/node', ts=0, dur=1)
                malformed.update(fields)
                (Path(directory) / 'bad.json').write_text(json.dumps(valid + [malformed]))
                (Path(directory) / 'good.json').write_text(json.dumps(valid))
                bad, good = summarize(directory)['sessions']
                self.assertFalse(bad['valid_for_additive_wall'])
                self.assertTrue(bad['errors'])
                self.assertEqual(bad['node_time_us'], 2)
                self.assertEqual(bad['model_run_time_us'], 5)
                self.assertEqual(bad['providers'], ['CPUExecutionProvider'])
                self.assertTrue(good['valid_for_additive_wall'])
                self.assertEqual(good['node_time_us'], 2)
                for row in (bad, good):
                    self.assertFalse(row['clock_aligned'])
                    self.assertTrue(row['completeness'].startswith('unverified;'))

    def test_missing_node_required_fields_are_invalid(self):
        for field in ('name', 'ts', 'dur'):
            with self.subTest(field=field):
                node = dict(cat='Node', name='/pre_encode/node', ts=0, dur=1)
                del node[field]
                row = self.summary([node])
                self.assertFalse(row['valid_for_additive_wall'])
                self.assertTrue(row['errors'])
                self.assertEqual(row['node_time_us'], 0)
                self.assertEqual(row['graph'], 'unassigned')

    def test_invalid_event_containers_retained_with_valid_neighbor(self):
        for events in ({}, None, [None], [[]], ['event']):
            with self.subTest(events=events), TemporaryDirectory() as directory:
                (Path(directory) / 'bad.json').write_text(json.dumps(events))
                (Path(directory) / 'good.json').write_text(json.dumps([
                    dict(cat='Node', name='/pre_encode/node', ts=0, dur=1),
                    dict(name='model_run', ts=0, dur=1)]))
                bad, good = summarize(directory)['sessions']
                self.assertFalse(bad['valid_for_additive_wall'])
                self.assertFalse(bad['clock_aligned'])
                self.assertIn('expected an event array', bad['errors'][0])
                self.assertTrue(good['valid_for_additive_wall'])

    def test_ort_event_ceiling_is_not_complete_evidence(self):
        row = self.summary([dict(name='model_run', ts=0, dur=1)])
        self.assertEqual(row['completeness'], 'unverified; ORT JSON has no dropped-event counter')


if __name__ == '__main__':
    unittest.main()
