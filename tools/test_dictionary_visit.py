import copy
from datetime import datetime, timedelta
import unittest
import json
import tempfile
from pathlib import Path

import dictionary_visit
from test_experiment import fixture as replay_fixture


def visit_fixture():
    old_plan, old_reports = replay_fixture()
    base = old_plan["runs"][0]["expected"]
    cases = {f"take{i}": copy.deepcopy(base["endpoint_references"]["de" if i < 5 else "en"])
             for i in range(10)}
    setups = {arm: dict(provenance=copy.deepcopy(base["provenance"]), artifacts=base["artifacts"],
                        variant={"runtime": {"profile": False}}, model_directory="model",
                        execution={"encoder": "onnx-cpu", "fallback": False})
              for arm in ("default", "candidate-a")}
    plan = dict(protocol=dictionary_visit.PROTOCOL, status="frozen", sha256="f" * 64,
                cases=cases, setups=setups, device=base["device"], dictionary=["Zurich"], stage_inventory={})
    receipts = {}
    for i, run in enumerate(dictionary_visit.schedule(list(cases))):
        measured = copy.deepcopy(old_reports["A-0"])
        wanted = dictionary_visit.expected(plan, run)
        measured["provenance"] = dict(wanted["provenance"], run_id=run["id"], process_start_id=run["id"])
        report = measured["report"]
        report.update(device=wanted["device"], verifiedArtifacts=wanted["artifacts"],
                      verifiedInputs=wanted["inputs"], execution=wanted["execution"])
        row = copy.deepcopy(report["endpoints"][0 if cases[run["case"]]["lang"] == "de" else 1])
        row["id"] = run["case"]
        row["session"]["eventsNs"]["commitAccepted"] = row["session"]["eventsNs"]["finalReady"]
        row["session"]["eventsNs"]["returnRequested"] = row["session"]["eventsNs"]["takeEnded"]
        if run["arm"] != "candidate-a":
            row["text"] += " extra"
        report["endpoints"] = [row]
        phone = dict(verb="run", classes="ch.og.dictation.LabTest",
                     ts=(datetime(2026, 10, 26) + timedelta(seconds=i * 5)).isoformat(),
                     app_sha256=wanted["provenance"]["build_sha256"],
                     test_sha256=wanted["provenance"]["test_build_sha256"],
                     lab_sha256=dictionary_visit.lab_hash(plan, run))
        receipts[run["id"]] = dict(measurement=measured, phone=phone, measurement_sha256="a" * 64,
                                   wrapper={"outcome": "passed", "lab": {"result.json": "a" * 64}})
    return plan, receipts


class DictionaryVisitTests(unittest.TestCase):
    def test_staging_stops_before_a_next_run_when_prior_receipts_are_invalid(self):
        for defect in ('result-digest', 'process', 'insertion'):
            with self.subTest(defect=defect), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                folder = root / 'corpus/visit'
                (folder / 'receipts').mkdir(parents=True)
                plan, receipts = visit_fixture()
                snapshot = folder / 'snapshot.json'
                snapshot.write_text('{}')
                plan['snapshot'] = {'path': str(snapshot), 'sha256': dictionary_visit.identity.sha(snapshot)}
                plan['sources'] = dictionary_visit.experiment.source_identity(root)
                del plan['sha256']
                plan['sha256'] = dictionary_visit.identity.digest(plan)
                path = folder / 'plan.json'
                path.write_text(json.dumps(plan))
                runs = dictionary_visit.schedule(list(plan['cases']))
                first, second = (receipts[r['id']] for r in runs[:2])
                if defect == 'process':
                    second['measurement']['provenance']['process_start_id'] = first['measurement']['provenance']['process_start_id']
                elif defect == 'insertion':
                    del second['measurement']['report']['endpoints'][0]['session']['eventsNs']['commitAccepted']
                for run in runs[:2]:
                    value = receipts[run['id']]
                    value['plan_sha256'] = plan['sha256']
                    for prefix, content in [('measurement', value['measurement']), ('phone_meta', value['phone'])]:
                        original = folder / (run['id'] + '-' + prefix + '.json')
                        original.write_text(json.dumps(content))
                        value[prefix + '_path'] = str(original)
                        value[prefix + '_sha256'] = dictionary_visit.identity.sha(original)
                    value['wrapper']['lab']['result.json'] = value['measurement_sha256']
                    if defect == 'result-digest' and run == runs[1]:
                        value['wrapper']['lab']['result.json'] = '0' * 64
                    wrapper = folder / (run['id'] + '-wrapper.json')
                    wrapper.write_text(json.dumps(value['wrapper']))
                    value.update(wrapper_path=str(wrapper), wrapper_sha256=dictionary_visit.identity.sha(wrapper))
                    (folder / 'receipts' / (run['id'] + '.json')).write_text(json.dumps(value))
                with self.assertRaisesRegex(ValueError, 'prior attempt invalid'):
                    dictionary_visit.stage_run(path, runs[2]['id'], root)
                self.assertFalse((folder / 'attempts').exists())

    def test_missing_receipts_cannot_decide_adoption(self):
        result = dictionary_visit.compare({"cases": {f"take{i}": {} for i in range(10)}}, {})
        self.assertEqual("inconclusive", result["decision"])
        self.assertTrue(result["errors"])

    def test_valid_replay_still_needs_meaning_live_and_recovery_observations(self):
        plan, receipts = visit_fixture()
        result = dictionary_visit.compare(plan, receipts)
        self.assertEqual([], result["errors"])
        self.assertEqual("pending-manual-validation", result["decision"])
        self.assertEqual(30, len(result["pairs"]["control"]))
        self.assertEqual(2000, result["candidate_median_stop_ms"])

    def test_one_unpaced_or_wrong_staging_receipt_invalidates_visit(self):
        for defect in ("pacing", "staging", "process", "order", "wrapper"):
            with self.subTest(defect=defect):
                plan, receipts = visit_fixture()
                first, second = list(receipts.values())[:2]
                if defect == "pacing":
                    second["measurement"]["report"]["endpoints"][0]["session"]["eventsNs"]["captureStart"] = 990000001
                elif defect == "staging":
                    second["phone"]["lab_sha256"] = "0" * 64
                elif defect == "process":
                    second["measurement"]["provenance"]["process_start_id"] = first["measurement"]["provenance"]["process_start_id"]
                elif defect == "order":
                    second["phone"]["ts"] = first["phone"]["ts"]
                else:
                    second["wrapper"]["outcome"] = "failed"
                result = dictionary_visit.compare(plan, receipts)
                self.assertEqual("inconclusive", result["decision"])
                self.assertTrue(result["errors"])

    def test_meaning_changing_false_accept_rejects_otherwise_eligible_candidate(self):
        plan, receipts = visit_fixture()
        manual = dict(plan_sha256=plan["sha256"], meaning_review={name: 0 for name in receipts if name.endswith("candidate-a")},
                      live_check=dict(one_insertion_on_stop=True, cancellation_safe=True, usable_futo_return=True,
                                      build_sha256=plan["setups"]["candidate-a"]["provenance"]["build_sha256"], evidence="owner observation"),
                      restoration_verified=True, restoration_evidence="checked restoration")
        self.assertEqual("pending-manual-validation", dictionary_visit.compare(plan, receipts, manual)["decision"])
        # A matching APK alone cannot prove the IME loaded the measured candidate.
        candidate = plan["setups"]["candidate-a"]
        candidate["provenance"].update(dictionary_sha256="d" * 64)
        identity = {k: candidate["provenance"][k] for k in ("build_sha256", "configuration_sha256", "model_sha256", "dictionary_sha256")}
        identity.update(plan_sha256=plan["sha256"], verified=True, artifacts=candidate["artifacts"],
                        live_config_sha256=dictionary_visit.live_config_hash(plan))
        events = dict(captureStart=1, stopRequested=2, captureStopped=3, finalReady=4, commitAccepted=5, returnRequested=6, takeEnded=7)
        manual["live_check"]["receipts"] = [
            dict(protocol="ime-candidate-live-v1", processId="live", takeId="stop", identity=identity,
                 outcome="COMPLETED", commitAccepted=True, returnAccepted=True, eventsNs=events),
            dict(protocol="ime-candidate-live-v1", processId="live", takeId="cancel", identity=identity,
                 outcome="CANCELLED", commitAccepted=None, returnAccepted=None, eventsNs={"captureStart":1,"takeEnded":2})]
        for run in dictionary_visit.schedule(list(plan["cases"])):
            if run["arm"] == "candidate-a":
                receipts[run["id"]]["measurement"]["provenance"]["dictionary_sha256"] = "d" * 64
                receipts[run["id"]]["phone"]["lab_sha256"] = dictionary_visit.lab_hash(plan, run)
        self.assertEqual("eligible-for-owner-adoption", dictionary_visit.compare(plan, receipts, manual)["decision"])
        identity["verified"] = False
        self.assertEqual("pending-manual-validation", dictionary_visit.compare(plan, receipts, manual)["decision"])
        identity["verified"] = True
        original_configuration = identity["configuration_sha256"]
        identity["configuration_sha256"] = "0" * 64
        self.assertEqual("pending-manual-validation", dictionary_visit.compare(plan, receipts, manual)["decision"])
        identity["configuration_sha256"] = original_configuration
        manual["meaning_review"][next(iter(manual["meaning_review"]))] = 1
        self.assertEqual("reject", dictionary_visit.compare(plan, receipts, manual)["decision"])

    def test_missing_insertion_receipt_cannot_be_called_stop_wait(self):
        plan, receipts = visit_fixture()
        del next(iter(receipts.values()))["measurement"]["report"]["endpoints"][0]["session"]["eventsNs"]["commitAccepted"]
        result = dictionary_visit.compare(plan, receipts)
        self.assertEqual("inconclusive", result["decision"])

    def test_three_rounds_pair_each_take_before_next_and_controls_run_first(self):
        runs = dictionary_visit.schedule([f"take{i}" for i in range(10)])
        self.assertEqual(120, len(runs))
        self.assertEqual({"control"}, {r["phase"] for r in runs[:60]})
        self.assertEqual({"candidate"}, {r["phase"] for r in runs[60:]})
        self.assertEqual(["default", "candidate-a", "candidate-a", "default"],
                         [r["arm"] for r in runs[60:64]])
        for first, second in zip(runs[::2], runs[1::2]):
            self.assertEqual((first["phase"], first["round"], first["case"]),
                             (second["phase"], second["round"], second["case"]))


if __name__ == "__main__":
    unittest.main()
