import copy
import unittest
import json
from pathlib import Path
import tempfile
from unittest.mock import patch
import evaluate_v4 as ev


class SyntheticReportTests(unittest.TestCase):
    """Real scoring against a temporary reference tree, never personal fixtures."""
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        root = Path(self.temp.name)
        (root / 'corpus/scripts').mkdir(parents=True)
        (root / 'models/guard').mkdir(parents=True)
        (root / 'docs').mkdir()
        (root / 'docs/baseline.md').write_text('German: D1 Hallo Welt.\nEnglish: E1 Hello world.\n')
        (root / 'corpus/takes.json').write_text(json.dumps({'de.wav': 'de-1', 'en.wav': 'en-1'}))
        for lang, text in [('de', 'Hallo Welt.'), ('en', 'Hello world.')]:
            (root / f'corpus/scripts/{lang}-1.txt').write_text(text)
            (root / f'corpus/scripts/{lang}-1.terms').write_text(text.split()[0])
        (root / 'models/guard/refs.tsv').write_text('id\tlang\tsecs\tref\ng-de\tde\t1\tHallo Welt.\ng-en\ten\t1\tHello world.\n')
        self.dictionary = root / 'dictionary.txt'
        self.dictionary.write_text('Hallo\nHello\n')
        for module in (ev.historical, ev.historical.score):
            patcher = patch.object(module, 'ROOT', str(root))
            patcher.start()
            self.addCleanup(patcher.stop)

    def fixture(self, mode):
        provenance = {key: 'a' * 64 if key.endswith('_sha256') else 'fixed'
                      for key in ev.IDENTITY + ('model_sha256', 'configuration_sha256')}
        provenance.update(mode=mode, platform='phone' if mode == 'historical-full' else 'laptop', feed=640)
        takes = [{'file': lang + '.wav', 'text': text, 'audioSeconds': seconds,
                  'chunks': [{'rtf': 0.1, 'decodeSeconds': 0.1}], 'stopToFinalMs': 100,
                  'inputSamples': 16000, 'coverageSamples': 16000, 'residualSamples': 0,
                  'stopState': {'active': False, 'queued': 0}}
                 for lang, text, seconds in [('de', 'Hallo Welt.', 1), ('en', 'Hello world.', 2)]]
        endpoints = [dict(takes[0], id='prefix')]
        report = {'takes': takes, 'endpoints': endpoints, 'coldLoadMs': 100,
                  'memory': [{'phase': phase, 'rssKb': 100, 'highWaterKb': 200, 'pssKb': 100,
                              'javaUsedBytes': 10, 'nativeAllocatedBytes': 10} for phase in
                             ('before-load', 'after-load', 'after-warmup', 'buffer-loaded', 'buffer-released', 'recognizer-released')]}
        expected = {'provenance': copy.deepcopy(provenance), 'takes': ['de.wav', 'en.wav'],
                    'endpoints': ['prefix'], 'take_languages': {'de.wav': 'de', 'en.wav': 'en'},
                    'endpoint_references': {'prefix': {'reference': 'Hallo Welt.', 'lang': 'de', 'cut_word': False}},
                    'scoring_dictionary': {'path': str(self.dictionary), 'sha256': ev.identity.sha(self.dictionary)}}
        if mode == 'quality-full':
            report['endpoints'] = []
            expected['endpoints'] = []
            report['guard'] = [{'id': 'g-' + lang, 'lang': lang, 'text': text, 'audioSeconds': 1, 'decodeSeconds': 0.1}
                               for lang, text in [('de', 'Hallo Welt.'), ('en', 'Hello world.')]]
            expected['guard'] = ['g-de', 'g-en']
            report['silence'] = {'silent': '', 'noise': ''}
            for take in takes:
                take['jitter'] = [{'feed': feed, 'text': take['text']} for feed in (512, 800)]
        if mode == 'historical-full':
            report.update(paced=copy.deepcopy(takes), before={'thermalStatus': 0}, after={'thermalStatus': 0},
                          vmHwmKb=1000, vmHwmAfterPacedKb=1000)
            for row in report['paced']:
                row['thermalAfter'] = 0
            expected['paced'] = ['de.wav', 'en.wav']
            report['endpoints'] = []
            expected['endpoints'] = []
        if mode == 'persistent':
            report['takes'] = [dict(t, repetition=r) for r in (0, 1) for t in takes]
            report['endpoints'] = [dict(t, repetition=r) for r in (0, 1) for t in endpoints]
            expected['takes'] *= 2
            expected['endpoints'] *= 2
            expected['repetitions'] = 2
        candidate = {'schema': 4, 'outcome': 'complete', 'provenance': dict(provenance, run_id='one', process_start_id='one'), 'report': report}
        control = copy.deepcopy(candidate)
        control['provenance'].update(run_id='two', process_start_id='two')
        return candidate, control, expected

    def test_valid_modes_score_real_text(self):
        for mode in ('quality-full', 'historical-full', 'unpaced-only', 'paced-only', 'persistent'):
            with self.subTest(mode=mode):
                a, b, expected = self.fixture(mode)
                result = ev.compare(a, b, expected, expected, {'normalized_text_mismatches_allowed': 0})
                self.assertEqual(result['evidence'], 'complete', result['evidence_errors'])
                self.assertEqual(result['quality']['repetitions'][0]['candidate']['en_errors'], 0)
                a['report']['takes'][0]['text'] = 'Wrong words.'
                changed = ev.compare(a, b, expected, expected)
                self.assertGreater(changed['quality']['repetitions'][0]['candidate']['de_errors'], 0)
                self.assertEqual(ev.compare(a, b, expected, expected, {'normalized_text_mismatches_allowed': 0})['evidence'], 'incomplete')

    def test_missing_and_corrupt_evidence_at_compare_boundary(self):
        for mode in ('quality-full', 'historical-full', 'unpaced-only', 'paced-only', 'persistent'):
            a, b, expected = self.fixture(mode)
            mutations = []
            for key in a['provenance']:
                mutations += [(('provenance', key), None), (('provenance', key), {})]
            mutations += [(('schema',), None), (('outcome',), 'failed')]
            for section in ('takes', 'guard', 'endpoints', 'paced'):
                if a['report'].get(section):
                    mutations += [(('report', section), []), (('report', section), None),
                                  (('report', section, 0, 'text'), None), (('report', section, 0, 'text'), {})]
            if mode == 'quality-full':
                mutations += [(('report', 'silence'), {}), (('report', 'takes', 0, 'jitter'), [])]
            elif mode != 'historical-full':
                mutations += [(('report', 'memory'), []), (('report', 'coldLoadMs'), None),
                              (('report', 'takes', 0, 'coverageSamples'), 1),
                              (('report', 'takes', 0, 'stopState'), {}), (('report', 'takes', 0, 'stopToFinalMs'), float('nan'))]
            else:
                mutations += [(('report', 'vmHwmKb'), None), (('report', 'before'), {})]
            for path, value in mutations:
                with self.subTest(mode=mode, path=path, value=value):
                    broken = copy.deepcopy(a)
                    target = broken
                    for key in path[:-1]:
                        target = target[key]
                    target[path[-1]] = value
                    self.assertEqual(ev.compare(broken, b, expected, expected)['evidence'], 'incomplete')

    def test_persistent_duplicate_pass_and_unknown_mode_fail(self):
        a, b, expected = self.fixture('persistent')
        for row in a['report']['takes'] + a['report']['endpoints']:
            row['repetition'] = 0
        self.assertEqual(ev.compare(a, b, expected, expected)['evidence'], 'incomplete')

    def test_required_fields_deleted_or_corrupted_on_either_side(self):
        for mode in ('quality-full', 'historical-full', 'unpaced-only', 'paced-only', 'persistent'):
            a, b, expected = self.fixture(mode)
            paths = [('provenance', key) for key in a['provenance']]
            paths += [('schema',), ('outcome',), ('report',)]
            for section in ('takes', 'guard', 'endpoints', 'paced'):
                if a['report'].get(section):
                    paths += [('report', section, 0, 'text'), ('report', section, 0, 'id' if section in ('guard', 'endpoints') else 'file')]
            if mode == 'quality-full':
                paths += [('report', 'silence', key) for key in ('silent', 'noise')]
                paths += [('report', 'takes', 0, 'jitter', j, key) for j in (0, 1) for key in ('feed', 'text')]
            elif mode == 'historical-full':
                paths += [('report', key) for key in ('vmHwmKb', 'vmHwmAfterPacedKb')]
                paths += [('report', key, 'thermalStatus') for key in ('before', 'after')]
                paths += [('report', 'paced', 0, key) for key in ('thermalAfter', 'stopToFinalMs')]
                paths += [('report', 'takes', 0, 'chunks', 0, 'rtf')]
            else:
                paths += [('report', 'coldLoadMs')]
                paths += [('report', 'memory', i, key) for i in range(6) for key in ('phase', 'rssKb', 'highWaterKb', 'pssKb')]
                for section in ('takes', 'endpoints'):
                    paths += [('report', section, 0, key) for key in ('stopToFinalMs', 'inputSamples', 'coverageSamples', 'residualSamples', 'stopState')]
                    paths += [('report', section, 0, 'stopState', key) for key in ('active', 'queued')]
            for side in ('candidate', 'control'):
                for path in paths:
                    for delete in (True, False):
                        with self.subTest(mode=mode, side=side, path=path, delete=delete):
                            candidate, control = copy.deepcopy(a), copy.deepcopy(b)
                            target = candidate if side == 'candidate' else control
                            for key in path[:-1]:
                                target = target[key]
                            if delete:
                                del target[path[-1]]
                            else:
                                target[path[-1]] = {'corrupt': True}
                            self.assertEqual(ev.compare(candidate, control, expected, expected)['evidence'], 'incomplete')

    def test_reference_hash_repeat_and_prefix_contract_mutations(self):
        for mode in ('quality-full', 'historical-full', 'unpaced-only', 'paced-only', 'persistent'):
            a, b, expected = self.fixture(mode)
            for key in ('run_id', 'process_start_id'):
                broken = copy.deepcopy(b)
                broken['provenance'][key] = a['provenance'][key]
                self.assertEqual(ev.compare(a, broken, expected, expected, {'normalized_text_mismatches_allowed': 0})['evidence'], 'incomplete')
            wrong_hash = copy.deepcopy(expected)
            wrong_hash['scoring_dictionary']['sha256'] = 'b' * 64
            self.assertEqual(ev.compare(a, b, wrong_hash, expected)['evidence'], 'incomplete')
            if a['report']['endpoints']:
                for key in ('reference', 'lang', 'cut_word'):
                    changed = copy.deepcopy(expected)
                    del changed['endpoint_references']['prefix'][key]
                    self.assertEqual(ev.compare(a, b, changed, expected)['evidence'], 'incomplete')

    def test_phone_diagnostic_requires_android_allocation_counters(self):
        for mode in ('unpaced-only', 'paced-only', 'persistent'):
            a, b, expected = self.fixture(mode)
            for report in (a, b):
                report['provenance']['platform'] = 'phone'
            expected['provenance']['platform'] = 'phone'
            self.assertEqual(ev.compare(a, b, expected, expected)['evidence'], 'complete')
            for key in ('javaUsedBytes', 'nativeAllocatedBytes'):
                for index in range(6):
                    for side in ('candidate', 'control'):
                        with self.subTest(mode=mode, key=key, index=index, side=side):
                            candidate, control = copy.deepcopy(a), copy.deepcopy(b)
                            selected = candidate if side == 'candidate' else control
                            del selected['report']['memory'][index][key]
                            self.assertEqual(ev.compare(candidate, control, expected, expected)['evidence'], 'incomplete')
        a, b, expected = self.fixture('unpaced-only')
        a['provenance']['mode'] = b['provenance']['mode'] = expected['provenance']['mode'] = 'unknown'
        self.assertEqual(ev.compare(a, b, expected, expected)['evidence'], 'incomplete')


class ProvenanceTests(unittest.TestCase):
    def envelope(self):
        return {"schema": 4, "outcome": "complete", "provenance": {key: "a" * 64 if key.endswith("_sha256") else "fixed" for key in ev.IDENTITY +
                ("model_sha256", "configuration_sha256", "run_id", "process_start_id")},
                "report": {"takes": [{"file": "a.wav", "text": "hello"}], "vmHwmKb": 4_000_000}}

    def test_phone_cannot_use_laptop_control(self):
        candidate, control = self.envelope(), self.envelope()
        candidate["provenance"]["platform"] = "phone"
        control["provenance"]["platform"] = "laptop"
        result = ev.compare(candidate, control, {"takes": ["a.wav"]}, {"takes": ["a.wav"]})
        self.assertEqual(result["evidence"], "incomplete")
        self.assertIn("unmatched control: platform", result["evidence_errors"])

    def test_historical_rows_and_missing_coverage_are_incomplete(self):
        candidate = self.envelope()
        candidate.pop("schema")
        self.assertTrue(ev.evidence_errors(candidate, {"takes": ["a.wav", "b.wav"]}))

    def test_resource_failure_does_not_suppress_quality(self):
        candidate = self.envelope()
        candidate["provenance"]["mode"] = "historical-full"
        with patch.object(ev.historical, "score_takes", return_value=[]), patch.object(ev.historical, "summary", return_value={"de_errors": 2, "en_errors": 1}):
            expected = {"takes": ["a.wav"], "provenance": candidate["provenance"]}
            result = ev.compare(candidate, copy.deepcopy(candidate), expected, expected)
        self.assertEqual(result["resources"]["status"], "fail")
        self.assertEqual(result["quality"]["status"], "scored")
        self.assertEqual(result["acceptance"], "diagnostic-only")

class EndpointTests(unittest.TestCase):
    def test_missing_silence_is_unavailable_and_dictionary_diagnostics_are_scored(self):
        report = {'takes': [{'file': 'a.wav', 'text': 'hello'}]}
        expected = {'take_languages': {'a.wav': 'en'}, 'scoring_dictionary': {'path': 'fixture', 'sha256': 'hash'}}
        with patch.object(ev.historical, 'score_takes', return_value=[]), \
             patch.object(ev.historical, 'summary', return_value={'silence_chars': 0}), \
             patch.object(ev.historical, 'dictionary_words', return_value={'hello'}), \
             patch.object(ev.historical, 'other_errors', return_value=2), \
             patch.object(ev.historical, 'guard_inserts', return_value=None):
            result = ev.quality_summary(report, expected)
        self.assertIsNone(result['silence_chars'])
        self.assertIsNone(result['guard_dictionary_insertions'])
        self.assertEqual(result['non_term_errors'], {'de': None, 'en': 2})

    def test_repeat_requires_fresh_process_and_matching_normalized_outputs(self):
        a = {'provenance': {'run_id': 'one', 'process_start_id': 'one'},
             'report': {'endpoints': [{'id': 'early', 'text': 'Hello world!'}]}}
        b = copy.deepcopy(a)
        expected = {'endpoint_references': {'early': {'lang': 'en'}}}
        rule = {'normalized_text_mismatches_allowed': 0}
        self.assertEqual(len(ev.repeat_errors(a,b,expected,rule)),2)
        b['provenance'] = {'run_id':'two','process_start_id':'two'}
        b['report']['endpoints'][0]['text'] = 'hello world'
        self.assertEqual(ev.repeat_errors(a,b,expected,rule),[])
        b['report']['endpoints'][0]['text'] = 'hello'
        self.assertIn('repeat normalized output mismatch',ev.repeat_errors(a,b,expected,rule))
        b['report']['endpoints'][0].pop('text')
        self.assertIn('incomplete repeat output evidence',ev.repeat_errors(a,b,expected,rule))

    def test_historical_paced_output_cannot_bypass_repeat_check(self):
        a = {'provenance': {'run_id': 'one', 'process_start_id': 'one'},
             'report': {'paced': [{'file': 'short.wav', 'text': 'hello world'}]}}
        b = copy.deepcopy(a)
        b['provenance'] = {'run_id': 'two', 'process_start_id': 'two'}
        expected = {'take_languages': {'short.wav': 'en'}}
        rule = {'normalized_text_mismatches_allowed': 0}
        self.assertEqual(ev.repeat_errors(a,b,expected,rule), [])
        b['report']['paced'][0]['text'] = 'hello'
        self.assertIn('repeat normalized output mismatch', ev.repeat_errors(a,b,expected,rule))
        b['report']['paced'][0].pop('text')
        self.assertIn('missing text: paced', ev.evidence_errors(b, {'paced': ['short.wav']}))

    def test_waits_and_tail_checks_are_reported_together(self):
        rows = [{"id": "early", "text": "hello", "stopToFinalMs": 120},
                {"id": "late", "text": "hello world world", "stopToFinalMs": 360}]
        result = ev.resources({"provenance": {"mode": "paced-only"}, "report": {"endpoints": rows}})
        self.assertEqual(result["wait_median_ms"], 240)
        self.assertEqual(result["wait_max_ms"], 360)
        refs = {name: {"lang": "en", "reference": "hello world"} for name in ("early", "late")}
        checked = ev.endpoint_checks(rows, refs)
        self.assertFalse(checked[0]["tailMatches"])
        self.assertEqual(checked[1]["adjacentDuplicateWords"], 1)
        failed = {"schema": 4, "failure": "decoder timeout"}
        self.assertTrue(any("run failed" in e for e in ev.evidence_errors(failed, {})))

if __name__ == "__main__":
    unittest.main()
