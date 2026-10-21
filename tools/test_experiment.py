"""Experiment decisions use synthetic evidence, never personal recordings."""
import copy
import json
from pathlib import Path
import tempfile
import subprocess
import shutil
import sys
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


def webgpu_fixture():
    """One ort131-cpu vs ort131-webgpu pair: same build and model, variant differs only by encoder provider."""
    plan, reports = fixture()
    plan.update(kind="webgpu-parity", allowed_differences=["configuration_sha256"])
    plan["runs"] = plan["runs"][:2]
    reports = {name: value for name, value in reports.items() if name.endswith("-0")}
    for run in plan["runs"]:
        candidate = run["setup"] == plan["candidate"]
        provenance = run["expected"]["provenance"]
        provenance.update(build_sha256="a" * 64, model_sha256="a" * 64)
        reports[run["id"]]["provenance"].update(build_sha256="a" * 64, model_sha256="a" * 64)
        run["expected"]["execution"] = {"encoder": "onnx-webgpu" if candidate else "onnx-cpu", "fallback": False,
                                        "runtime_core_sha256": experiment.RUNTIME_CORES[1]}
        report = reports[run["id"]]["report"]
        report["execution"] = run["expected"]["execution"]
        report["variant"] = {"family": "parakeet", "runtime": {"profile": False}}
        if candidate:
            report["variant"]["runtime"]["encoder_provider"] = "webgpu"
    return plan, reports


class CommandComparisonTests(unittest.TestCase):
    """Exercise the operator boundary with explicitly fabricated, nonpersonal receipts."""

    def command(self, *args, ok=True):
        completed = subprocess.run([sys.executable, str(experiment.ROOT / "tools/lab.py"), *map(str, args)],
                                   capture_output=True, text=True)
        if ok:
            self.assertEqual(0, completed.returncode, completed.stderr + completed.stdout)
        else:
            self.assertEqual(2, completed.returncode, completed.stderr + completed.stdout)
        return json.loads(completed.stdout) if completed.stdout else completed.stderr

    def rehearsal(self, kind, breaches, mutate=None):
        corpus = experiment.ROOT / "corpus"
        corpus.mkdir(exist_ok=True)
        temp = tempfile.TemporaryDirectory(prefix="fabricated-control-", dir=corpus)
        self.addCleanup(temp.cleanup)
        folder = Path(temp.name)
        plan, reports = webgpu_fixture() if kind == "webgpu-parity" else fixture()
        plan.update(id=folder.name.replace("_", "-"), kind=kind)
        if kind in ("conversion-parity", "runtime-parity"):
            plan["runs"] = plan["runs"][:2]
            reports = {name: value for name, value in reports.items() if name.endswith("-0")}
            for run in plan["runs"]:
                if kind == "runtime-parity":
                    side = 0 if run["setup"] == plan["control"] else 1
                    run["expected"]["execution"] = {"encoder": "onnx-cpu", "fallback": False,
                                                    "runtime_core_sha256": experiment.RUNTIME_CORES[side]}
                elif run["setup"] == "C":
                    run["expected"]["execution"] = {"encoder": "litert-fp32-cpu", "fallback": False}
                reports[run["id"]]["report"]["execution"] = run["expected"]["execution"]
        elif kind == "gpu-wait":
            plan["runs"] = [plan["runs"][i] for i in (0, 1, 3, 2)]
        for name in breaches:
            reports[name]["report"]["memory"][0].update(highWaterKb=4000000, thermalStatus=1)
        if mutate:
            mutate(reports)
        spec = folder / "spec.json"
        spec.write_text(json.dumps({"schema": 1, "id": plan["id"],
                                    "artifacts": {"fabricated-source": "tools/lab.py"}}))
        prepared = self.command("prepare", spec)
        self.assertEqual("host-artifacts-present", self.command("check", prepared["path"])["status"])
        raw = folder / "input-plan.json"
        raw.write_text(json.dumps(plan))
        frozen = self.command("freeze", raw)["path"]
        self.addCleanup(shutil.rmtree, Path(frozen).parent)
        for run, receipt in reports.items():
            self.command("reserve", frozen, run)
            source = folder / (run + ".json")
            source.write_text(json.dumps(receipt))
            self.command("record", frozen, run, source)
        return frozen

    def test_control_breaches_are_inconclusive_and_candidates_require_valid_controls(self):
        for kind in ("conversion-parity", "gpu-wait", "runtime-parity", "webgpu-parity"):
            for breaches, decision in ((["A-0"], "inconclusive"), (["C-0"], "reject"),
                                       (["A-0", "C-0"], "inconclusive")):
                with self.subTest(kind=kind, breaches=breaches):
                    frozen = self.rehearsal(kind, breaches)
                    result = self.command("compare", frozen, ok=decision != "inconclusive")
                    self.assertEqual(decision, result["decision"], result)
                    self.assertEqual(breaches, [b["run"] for b in result["resource_breaches"]])
                    self.assertEqual(["highWaterKb", "thermalStatus"], result["resource_breaches"][0]["exceeded"])
                    self.assertEqual(0, result["languages"]["de"]["errors"]["C"])
                    self.assertEqual(100, result["observations"]["A-0"]["cold_load_ms"])
                    self.assertEqual(2000, result["observations"]["A-0"]["cases"][0]["stop_wait_ms"])
                    self.assertEqual(500, result["observations"]["A-0"]["cases"][0]["first_preview_ms"])
                    self.assertEqual(len(json.loads(Path(frozen).read_text())["plan"]["runs"]), len(result["receipt_sha256"]))


    def test_later_control_failure_retains_every_breach_and_candidate_quality(self):
        def observations(reports):
            reports["A-1"]["report"]["memory"][1].update(highWaterKb=4500000)
            reports["C-0"]["report"]["endpoints"][0]["text"] = "Hallo."
            reports["C-0"]["report"]["endpoints"][0]["encoderMs"] = 123
        frozen = self.rehearsal("gpu-wait", ["C-0", "A-1"], observations)
        result = self.command("compare", frozen, ok=False)
        self.assertEqual("inconclusive", result["decision"])
        self.assertEqual(["C-0", "A-1", "A-1"], [b["run"] for b in result["resource_breaches"]])
        self.assertEqual(2, len(result["control_blockers"]))
        self.assertEqual(1, result["languages"]["de"]["errors"]["C"])
        self.assertEqual(4, result["languages"]["de"]["reference_words"]["C"])
        self.assertEqual(.25, result["languages"]["de"]["word_error_rate"]["C"])
        self.assertEqual(123, result["observations"]["C-0"]["cases"][0]["encoder_ms"])


    def test_missing_receipt_remains_unresolved_without_authorizing_retry(self):
        frozen = self.rehearsal("gpu-wait", [], lambda reports: reports.pop("C-1"))
        self.command("reserve", frozen, "C-1")
        result = self.command("compare", frozen, ok=False)
        self.assertEqual("unresolved-execution", result["attempt_states"]["C-1"])
        self.assertEqual(4, result["attempts_reserved"])
        self.assertEqual(3, len(result["receipt_sha256"]))
        self.assertEqual(2000, result["observations"]["A-0"]["cases"][0]["stop_wait_ms"])
        self.assertEqual(0, result["observed_language_scores"]["de"]["C"]["errors"])
        self.assertFalse(result["observations_validated"])
        self.command("reserve", frozen, "C-1", ok=False)
        self.command("record", frozen, "A-0", Path(frozen).parent / "receipts/A-0.json", ok=False)

    def test_failed_duplicate_changed_and_incompatible_evidence_cannot_advance(self):
        mutations = [
            lambda r: r["C-1"].update(outcome="failed"),
            lambda r: r["C-1"]["provenance"].update(process_start_id="C-0"),
            lambda r: r["C-1"]["provenance"].update(build_sha256="c" * 64),
            lambda r: r["C-1"]["report"]["device"].update(osBuild="incompatible"),
        ]
        for mutate in mutations:
            with self.subTest(mutate=mutate):
                frozen = self.rehearsal("gpu-wait", ["A-1"], mutate)
                result = self.command("compare", frozen, ok=False)
                self.assertEqual("inconclusive", result["decision"])
                self.assertTrue(result["errors"])
                self.assertEqual("A-1", result["resource_breaches"][0]["run"])
                self.assertEqual(4, len(result["observations"]))
                self.assertEqual(0, result["observed_language_scores"]["en"]["C"]["errors"])
        receipt = Path(frozen).parent / "receipts/C-1.json"
        value = json.loads(receipt.read_text())
        value["measurement"]["outcome"] = "changed"
        receipt.write_text(json.dumps(value))
        self.assertIn("receipt changed", self.command("compare", frozen, ok=False))


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


class WebGpuParityTests(unittest.TestCase):
    def test_matched_ort131_cpu_and_webgpu_with_identical_text_advances_to_full_flow_diagnosis(self):
        plan, reports = webgpu_fixture()
        result = experiment.compare(plan, reports)
        self.assertEqual("advance", result["decision"], result)
        self.assertEqual("development-phone-replay", result["claim"])
        self.assertFalse(result["product_winner"])
        self.assertIn("diagnostic", result["next"])

    def test_changed_text_rejects(self):
        plan, reports = webgpu_fixture()
        reports["C-0"]["report"]["endpoints"][1]["text"] = "Hello world"
        result = experiment.compare(plan, reports)
        self.assertEqual("reject", result["decision"], result)
        self.assertEqual(1, result["languages"]["en"]["changed_outputs"])

    def test_plan_must_be_one_runtime_core_and_differ_only_by_configuration(self):
        def legacy_control(plan):
            plan["runs"][0]["expected"]["execution"]["runtime_core_sha256"] = experiment.RUNTIME_CORES[0]

        def cpu_candidate(plan):
            plan["runs"][1]["expected"]["execution"]["encoder"] = "onnx-cpu"

        def other_build(plan):
            plan["allowed_differences"].append("build_sha256")
            plan["runs"][1]["expected"]["provenance"]["build_sha256"] = "c" * 64

        def no_configuration_difference(plan):
            plan["allowed_differences"] = []

        for mutate in (legacy_control, cpu_candidate, other_build, no_configuration_difference):
            with self.subTest(mutate=mutate.__name__):
                plan, _ = webgpu_fixture()
                mutate(plan)
                self.assertTrue(experiment.plan_errors(plan))

    def test_variant_may_differ_only_by_encoder_provider(self):
        mutations = [
            lambda r: r["C-0"]["report"]["variant"]["runtime"].pop("encoder_provider"),
            lambda r: r["C-0"]["report"]["variant"].update(beam=8),
            lambda r: r["A-0"]["report"]["variant"]["runtime"].update(encoder_provider="webgpu"),
        ]
        for mutate in mutations:
            plan, reports = webgpu_fixture()
            mutate(reports)
            result = experiment.compare(plan, reports)
            self.assertEqual("inconclusive", result["decision"], result)
            self.assertTrue(result["errors"])

    def test_explicit_cpu_provider_on_control_is_accepted(self):
        plan, reports = webgpu_fixture()
        reports["A-0"]["report"]["variant"]["runtime"]["encoder_provider"] = "cpu"
        self.assertEqual("advance", experiment.compare(plan, reports)["decision"])

    def test_single_language_suite_is_still_refused(self):
        plan, _ = webgpu_fixture()
        for run in plan["runs"]:
            run["expected"]["endpoints"] = ["de"]
            run["expected"]["endpoint_references"].pop("en")
        self.assertIn("freeze one complete endpoint suite covering both languages", experiment.plan_errors(plan))


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
