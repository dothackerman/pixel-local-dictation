"""Acceptance regressions at the evaluator's public report boundaries (stdlib only)."""
import copy
from contextlib import redirect_stdout
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import evaluate
import variant


def phone_result():
    return {"takes": [{"file": "a.wav", "text": "hello", "audioSeconds": 10,
                       "chunks": [{"rtf": 0.1}]}],
            "paced": [{"file": "a.wav", "text": "hello", "stopToFinalMs": 500,
                       "thermalAfter": 0}],
            "before": {"thermalStatus": 0}, "after": {"thermalStatus": 0},
            "vmHwmKb": 1_800_000, "vmHwmAfterPacedKb": 2_100_000}


class PhoneBudgetTests(unittest.TestCase):
    def test_full_run_memory_peak_is_reported_and_enforced(self):
        result = phone_result()
        verdict, _, flags, summary = evaluate.phone_check(result)
        self.assertEqual(verdict, "pass")
        self.assertEqual(summary["phone_vmhwm_mb"], 2100)
        self.assertTrue(flags)
        result["vmHwmAfterPacedKb"] = 3_500_001
        verdict, reasons, _, _ = evaluate.phone_check(result)
        self.assertEqual(verdict, "fail")
        self.assertTrue(any("memory" in r for r in reasons))

    def test_invalid_budget_measurements_do_not_pass(self):
        for key, value in (("vmHwmAfterPacedKb", None), ("vmHwmAfterPacedKb", float("nan"))):
            result = phone_result(); result[key] = value
            self.assertEqual(evaluate.phone_check(result)[0], "fail")
        result = phone_result(); result["paced"][0]["stopToFinalMs"] = float("nan")
        self.assertEqual(evaluate.phone_check(result)[0], "fail")
        result = phone_result(); result["takes"][0]["chunks"][0]["rtf"] = float("inf")
        self.assertEqual(evaluate.phone_check(result)[0], "fail")
        result = phone_result(); result["vmHwmAfterPacedKb"] = 3_500_000
        self.assertEqual(evaluate.phone_check(result)[0], "pass")

    def test_budget_only_cli_does_not_claim_stage_two_acceptance(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "phone.json"
            path.write_text(json.dumps(phone_result()))
            output = io.StringIO()
            with patch.object(evaluate.sys, "argv", ["evaluate.py", "phone", str(path)]), redirect_stdout(output):
                evaluate.main()
            self.assertIn("budget_only: pass", output.getvalue())
            self.assertNotIn("stage2: pass", output.getvalue())


class ReportAcceptanceTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        root = Path(self.temp.name)
        (root / "corpus/scripts").mkdir(parents=True)
        (root / "models/guard").mkdir(parents=True)
        (root / "corpus/takes.json").write_text(json.dumps({"de.wav": "de-1", "en.wav": "en-1"}))
        names = "Aster Birch Cedar Dahlia Elm"
        for lang in ("de", "en"):
            (root / f"corpus/scripts/{lang}-1.txt").write_text(names + ".")
            (root / f"corpus/scripts/{lang}-1.terms").write_text("\n".join(names.split()))
        (root / "models/guard/refs.tsv").write_text("id\tlang\tsecs\tref\ng-de\tde\t1\tHallo Welt.\ng-en\ten\t1\tHello world.\n")
        self.hotwords = root / "dictionary.txt"
        self.hotwords.write_text("\n".join(names.split()))
        self.addCleanup(patch.stopall)
        patch.object(evaluate, "ROOT", str(root)).start()
        patch.object(evaluate.score, "baseline", return_value=({}, {})).start()
        self.good = {"variant": variant.validate({"name": "candidate"}),
                     "takes": [{"file": f"{lang}.wav", "text": names + ".", "audioSeconds": seconds,
                                "chunks": [{"rtf": 0.1, "decodeSeconds": 0.1}]}
                               for lang, seconds in (("de", 10), ("en", 20))],
                     "guard": [{"id": "g-de", "lang": "de", "text": "Hallo Welt.", "audioSeconds": 1, "decodeSeconds": 0.1},
                               {"id": "g-en", "lang": "en", "text": "Hello world.", "audioSeconds": 1, "decodeSeconds": 0.1}],
                     "silence": {"silent": "", "noise": ""}}
        self.base = copy.deepcopy(self.good)
        self.base["variant"]["name"] = "baseline"
        for take in self.base["takes"]:
            take["text"] = "Wrong wrong wrong wrong wrong."
        self.twin = copy.deepcopy(self.base)
        self.twin["variant"]["name"] = "twin"
        self.dictionary = copy.deepcopy(self.good)
        for report in (self.dictionary, self.twin):
            report["variant"]["decoding"]["method"] = "modified_beam_search"
        self.dictionary["variant"]["hotwords"]["file"] = "dictionary.txt"

    def test_dictionary_requires_complete_takes_and_guard_for_candidate_and_twin(self):
        self.assertEqual(evaluate.compare(self.base, self.dictionary, self.hotwords, self.twin)[0], "pass")
        for report_name, key in (("candidate", "takes"), ("candidate", "guard"), ("twin", "takes"), ("twin", "guard")):
            with self.subTest(report=report_name, missing=key):
                candidate, twin = copy.deepcopy(self.dictionary), copy.deepcopy(self.twin)
                (candidate if report_name == "candidate" else twin)[key].pop()
                self.assertNotEqual(evaluate.compare(self.base, candidate, self.hotwords, twin)[0], "pass")

    def phone(self):
        result = phone_result()
        result.update(copy.deepcopy(self.good))
        result.pop("guard"); result.pop("silence")
        result["paced"] = [{"file": t["file"], "text": t["text"], "stopToFinalMs": 500,
                            "thermalAfter": 0} for t in result["takes"]]
        return result

    def test_phone_acceptance_scores_actual_unpaced_and_paced_text(self):
        self.assertEqual(evaluate.compare(self.base, self.good)[0], "pass")
        result = self.phone()
        self.assertEqual(evaluate.phone_check(result, self.good, self.base)[0], "pass")
        for key in ("takes", "paced"):
            with self.subTest(output=key):
                changed = copy.deepcopy(result)
                changed[key][0]["text"] = "Wrong wrong wrong wrong wrong."
                verdict, reasons, _, _ = evaluate.phone_check(changed, self.good, self.base)
                self.assertEqual(verdict, "fail")
                self.assertTrue(any("accuracy" in r for r in reasons))

    def test_phone_acceptance_requires_complete_paced_and_guard_evidence(self):
        result = self.phone()
        result["paced"].pop()
        self.assertEqual(evaluate.phone_check(result, self.good, self.base)[0], "fail")
        laptop = copy.deepcopy(self.good)
        laptop.pop("guard")
        self.assertEqual(evaluate.compare(self.base, laptop)[0], "pass")  # preliminary stage 1
        self.assertEqual(evaluate.phone_check(self.phone(), laptop, self.base)[0], "fail")

    def test_dictionary_rejects_missing_or_different_twin(self):
        self.assertEqual(evaluate.compare(self.base, self.dictionary, self.hotwords)[0], "invalid")
        twin = copy.deepcopy(self.twin)
        twin["variant"]["decoding"]["max_active_paths"] = 8
        self.assertEqual(evaluate.compare(self.base, self.dictionary, self.hotwords, twin)[0], "invalid")
        candidate = copy.deepcopy(self.dictionary)
        candidate["takes"].append(candidate["takes"][0])
        self.assertEqual(evaluate.compare(self.base, candidate, self.hotwords, self.twin)[0], "invalid")

    def test_dictionary_phone_regression_cannot_be_kept(self):
        result = self.phone()
        result["variant"] = copy.deepcopy(self.dictionary["variant"])
        self.assertEqual(evaluate.phone_check(result, self.dictionary, self.base, self.hotwords, self.twin)[0], "pass")
        for take in result["takes"]:
            take["text"] = "Wrong wrong wrong wrong wrong."
        self.assertEqual(evaluate.phone_check(result, self.dictionary, self.base, self.hotwords, self.twin)[0], "fail")

    def test_phone_rejects_different_pipeline_even_when_text_is_identical(self):
        result = self.phone(); result["variant"]["threads"] = 2
        self.assertEqual(evaluate.phone_check(result, self.good, self.base)[0], "fail")

    def test_baseline_control_exemption_requires_the_frozen_pipeline(self):
        laptop = copy.deepcopy(self.base)
        result = self.phone()
        result["variant"] = copy.deepcopy(laptop["variant"])
        result["takes"] = copy.deepcopy(laptop["takes"])
        for take in result["paced"]:
            take["text"] = "Wrong wrong wrong wrong wrong."
        self.assertEqual(evaluate.phone_check(result, laptop, self.base)[0], "pass")
        laptop["variant"]["model"] = "different-model"
        result["variant"]["model"] = "different-model"
        self.assertEqual(evaluate.phone_check(result, laptop, self.base)[0], "fail")

    def test_each_phone_speech_take_requires_timing_evidence(self):
        for chunks in (None, [], [{"rtf": float("nan")}], [{}]):
            with self.subTest(chunks=chunks):
                result = self.phone()
                if chunks is None:
                    result["takes"][0].pop("chunks")
                else:
                    result["takes"][0]["chunks"] = chunks
                self.assertEqual(evaluate.phone_check(result, self.good, self.base)[0], "fail")

    def test_historical_qwen_report_is_not_corrected_schema_evidence(self):
        laptop = copy.deepcopy(self.good)
        laptop["variant"] = {"schema": 2, "name": "candidate", "family": "qwen3", "model": "qwen"}
        result = self.phone()
        result["variant"] = variant.validate(laptop["variant"])
        self.assertEqual(evaluate.phone_check(result, laptop, self.base)[0], "fail")



if __name__ == "__main__":
    unittest.main()
