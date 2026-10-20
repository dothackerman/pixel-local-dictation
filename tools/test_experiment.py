"""Experiment decisions use synthetic evidence, never personal recordings."""
import copy
import json
from pathlib import Path
import tempfile
import unittest

import experiment
import evaluate_v4


def fixture():
    plan = {
        "schema": 1, "id": "synthetic", "kind": "gpu-wait",
        "policy_status": "approved", "control": "A", "candidate": "C",
        "allowed_differences": ["build_sha256", "model_sha256", "configuration_sha256"],
        "thresholds": {"relative_gain": .2, "absolute_gain_ms": 500, "peak_kb": 3500000,
                       "max_thermal": 0},
        "runs": [],
    }
    reports = {}
    for pair in range(2):
        for setup, wait in (("A", 2000), ("C", 1000)):
            run = f"{setup}-{pair}"
            provenance = {key: "a" * 64 if key.endswith("_sha256") else "fixed"
                          for key in evaluate_v4.IDENTITY + ("model_sha256", "configuration_sha256",
                                                             "dictionary_sha256", "test_build_sha256")}
            provenance.update(platform="phone", feed=640, mode="paced-only",
                              timing_protocol=experiment.PROTOCOL, experiment_run=run)
            for field in plan["allowed_differences"]:
                provenance[field] = ("b" if setup == "C" else "a") * 64
            references = {lang: {"reference": text, "lang": lang, "cut_word": False, "terms": []}
                          for lang, text in (("de", "Hallo Welt."), ("en", "Hello world."))}
            expected = {"provenance": provenance, "takes": [], "endpoints": ["de", "en"],
                        "endpoint_references": references,
                        "artifacts": {"model/encoder.onnx": "a"*64},
                        "inputs": {"../benchmark/input.wav": "b"*64},
                        "device": {"model": "synthetic", "osBuild": "synthetic-os"},
                        "execution": {"encoder": "onnx-cpu" if setup == "A" else "litert-fp32-gpu", "fallback": False}}
            for ref in references.values():
                ref.update(path="../benchmark/input.wav", sha256="b"*64, samples=16000)
            if setup == "C":
                expected["execution"]["gpu_only"] = True
            plan["runs"].append({"id": run, "setup": setup, "pair": pair, "expected": copy.deepcopy(expected)})
            rows = []
            for lang, ref in references.items():
                events = {"captureStart": 1, "previewReady": 500000001, "stopRequested": 1000000001,
                          "captureStopped": 1000000001, "finalReady": 1000000001 + wait * 1000000,
                          "takeEnded": 1000000002 + wait * 1000000}
                rows.append({"id": lang, "text": ref["reference"], "stopToFinalMs": wait,
                             "inputPath": ref["path"], "inputSha256": ref["sha256"],
                             "inputSamples": 16000, "coverageSamples": 16000, "residualSamples": 16000,
                             "stopState": {"active": False, "queued": 0},
                             "session": {"outcome": "COMPLETED", "capture": "replay",
                                         "editor": "simulated", "keyboard": "simulated", "eventsNs": events}})
            report = {"endpoints": rows, "takes": [], "coldLoadMs": 100,
                      "sessionProtocol": experiment.PROTOCOL, "device": expected["device"],
                      "execution": expected["execution"],
                      "verifiedArtifacts": expected["artifacts"], "verifiedInputs": expected["inputs"],
                      "variant": {"runtime": {"profile": False}},
                      "memory": [{"phase": phase, "rssKb": 100, "highWaterKb": 200, "pssKb": 100,
                                  "javaUsedBytes": 10, "nativeAllocatedBytes": 10, "thermalStatus": 0}
                                 for phase in ("before-load", "after-load", "after-warmup", "buffer-loaded",
                                               "buffer-released", "recognizer-released")]}
            reports[run] = {"schema": 4, "outcome": "complete", "report": report,
                            "provenance": dict(provenance, run_id=run, process_start_id=run)}
    return plan, reports


class ComparisonTests(unittest.TestCase):
    def test_planned_build_change_can_advance_only_the_replay_claim(self):
        plan, reports = fixture()
        result = experiment.compare(plan, reports)
        self.assertEqual("advance", result["decision"], result)
        self.assertFalse(result["product_winner"])
        self.assertEqual(1000, result["languages"]["de"]["saving_ms"])

    def test_unplanned_build_change_is_inconclusive(self):
        plan, reports = fixture()
        reports["C-0"]["provenance"]["build_sha256"] = "c"*64
        self.assertEqual("inconclusive", experiment.compare(plan, reports)["decision"])

    def test_missing_duplicate_failed_profiled_or_mixed_os_evidence_cannot_advance(self):
        mutations = [
            lambda r: r.pop("C-1"),
            lambda r: r["C-1"]["provenance"].update(process_start_id="C-0"),
            lambda r: r["C-1"].update(outcome="failed"),
            lambda r: r["C-1"]["report"]["device"].update(osBuild="changed"),
            lambda r: r["C-1"]["report"]["variant"]["runtime"].update(profile=True),
            lambda r: r["C-1"]["report"]["endpoints"][0].update(stopToFinalMs=0),
            lambda r: r["C-1"]["report"]["endpoints"][0]["session"].update(editor="live"),
            lambda r: r["C-1"]["report"]["endpoints"].pop(),
            lambda r: r["C-1"]["report"]["endpoints"].reverse(),
            lambda r: r["C-1"]["report"]["endpoints"][0].update(inputSamples=640, coverageSamples=640, residualSamples=640),
            lambda r: r["C-1"]["report"]["endpoints"][0].update(inputPath="../benchmark/another.wav"),
            lambda r: r["C-1"]["report"]["endpoints"][0]["session"]["eventsNs"].update(captureStart=1000000001, previewReady=1000000001),
        ]
        for mutate in mutations:
            plan, reports = fixture()
            mutate(reports)
            self.assertEqual("inconclusive", experiment.compare(plan, reports)["decision"])

    def test_quality_change_or_resource_failure_rejects_without_hiding_scores(self):
        for field in ("quality", "memory", "thermal"):
            plan, reports = fixture()
            if field == "quality":
                reports["C-0"]["report"]["endpoints"][0]["text"] = "Wrong words."
            else:
                reports["C-0"]["report"]["memory"][0]["highWaterKb" if field == "memory" else "thermalStatus"] = 4000000
            result = experiment.compare(plan, reports)
            self.assertEqual("reject", result["decision"], result)
            self.assertIn("de", result["languages"])

    def test_a_plan_cannot_allow_os_or_input_changes(self):
        plan, reports = fixture()
        plan["allowed_differences"].append("inputs_sha256")
        self.assertEqual("inconclusive", experiment.compare(plan, reports)["decision"])


class EvidenceStoreTests(unittest.TestCase):
    def test_attempts_are_reserved_once_and_missing_receipts_are_inconclusive(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            plan, reports = fixture()
            path = experiment.freeze(plan, root)
            experiment.reserve(path, "A-0", root)
            with self.assertRaises(FileExistsError):
                experiment.reserve(path, "A-0", root)
            with self.assertRaises(ValueError):
                experiment.record(path, "C-0", reports["C-0"], root)
            experiment.record(path, "A-0", reports["A-0"], root)
            result = experiment.report(path, root)
            self.assertEqual("inconclusive", result["decision"])
            self.assertEqual(1, result["attempts_reserved"])
            for run, measured in reports.items():
                if run != "A-0":
                    experiment.reserve(path, run, root)
                    experiment.record(path, run, measured, root)
            self.assertEqual("advance", experiment.report(path, root)["decision"])
            with self.assertRaises(FileExistsError):
                experiment.record(path, "A-0", reports["A-0"], root)

    def test_private_evidence_paths_and_plan_digest_are_enforced(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            plan, _ = fixture()
            path = experiment.freeze(plan, root)
            value = json.loads(path.read_text())
            value["plan"]["thresholds"]["absolute_gain_ms"] = 0
            path.write_text(json.dumps(value))
            with self.assertRaises(ValueError):
                experiment.reserve(path, "A-0", root)
            with self.assertRaises(ValueError):
                experiment.private_path(root / "outside.json", root)

    def test_preparation_reports_missing_artifacts_and_detects_drift(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / "tools").mkdir()
            source = root / "tools/example.py"
            source.write_text("version one")
            spec = {"schema": 1, "id": "prepared", "artifacts": {
                "reference": "tools/example.py", "converted": "models/encoder.tflite"}}
            snapshot = experiment.prepare(spec, root)
            status = experiment.check(snapshot, root)
            self.assertEqual(["converted"], status["missing"])
            self.assertFalse(status["measurement_ready"])
            source.write_text("version two")
            self.assertIn("reference", experiment.check(snapshot, root)["changed"])

    def test_frozen_comparison_rejects_changed_evaluator(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / "tools").mkdir()
            source = root / "tools/experiment.py"
            source.write_text("original comparison")
            plan, _ = fixture()
            path = experiment.freeze(plan, root)
            source.write_text("changed comparison")
            with self.assertRaisesRegex(ValueError, "comparison implementation changed"):
                experiment.reserve(path, "A-0", root)


if __name__ == "__main__":
    unittest.main()
