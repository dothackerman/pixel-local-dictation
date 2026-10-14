import copy
import unittest
from unittest.mock import patch
import evaluate_v4 as ev


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
