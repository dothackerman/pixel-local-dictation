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
