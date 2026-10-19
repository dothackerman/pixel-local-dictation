"""Synthetic data only; never opens corpus or invokes recognition."""
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("analyzer", Path(__file__).with_name("analyze_native_profiles.py"))
a = importlib.util.module_from_spec(spec)
spec.loader.exec_module(a)


def native_fixture(enabled=True):
    profile = {"schema": 1, "clock": "CLOCK_MONOTONIC", "enabled": enabled,
        "spanLimit": 262144, "sessionLimit": 128, "attempted": int(enabled), "retained": int(enabled),
        "dropped": 0, "runFailures": 0, "clockFailures": 0, "sessionDropped": 0,
        "activeAtFlush": 0, "complete": True, "valid": True,
        "sessions": [{"sessionId": 1, "role": "parakeet.encoder"}],
        "spans": [{"sessionId": 1, "role": "parakeet.encoder", "startNs": 20, "endNs": 30,
                   "status": 0}] if enabled else []}
    descriptor = {"sha256": "a"*64, "bytes": 100}
    timeline = {"nativeRoleProfilerRequested": True, "nativeClockAlignment": "verified-brackets",
        "nativeAttribution": "actual-ort-session-run" if enabled else "disabled-control",
        "nativeProfile": {"enabled": enabled, "complete": True, "sha256": "a"*64,
                          "bytes": 100, "byteLimit": a.LIMIT},
        "nativeClockSamples": [{"phase": phase, "beforeNs": n, "nativeNs": n+1,
                                "afterNs": n+2, "valid": True} for phase, n in
             zip(("beforeModelConstruction", "beforeNativeFlush", "afterNativeFlush"), (1, 90, 95))]}
    return profile, timeline, descriptor


class ReplayTests(unittest.TestCase):
    def test_pre_stop_encoder_moves_queued_decoder(self):
        # Encoder finishes exactly at Stop. The queued decoder can move entirely
        # before Stop under ideal acceleration: after-Stop encoder bound is zero.
        jobs = [{"releaseNs": 0, "startNs": 0, "endNs": 10, "encoderNs": 10},
                {"releaseNs": 1, "startNs": 10, "endNs": 18, "encoderNs": 0},
                {"releaseNs": 10, "startNs": 18, "endNs": 20, "encoderNs": 0}]
        out = a.fifo(jobs, 10, 20, "parakeet.encoder")
        self.assertEqual(out["observedStopWaitNs"], 10)
        self.assertEqual(out["scenarios"]["2x"]["stopWaitNs"], 5)
        self.assertEqual(out["scenarios"]["infinite"]["stopWaitNs"], 2)
        self.assertEqual(out["encoderFraction"], .5)

    def test_capture_idle_is_not_service_and_release_remains_fixed(self):
        jobs = [{"releaseNs": 0, "startNs": 0, "endNs": 10, "encoderNs": 8},
                {"releaseNs": 100, "startNs": 100, "endNs": 110, "encoderNs": 8},
                {"releaseNs": 120, "startNs": 120, "endNs": 122, "encoderNs": 0}]
        out = a.fifo(jobs, 120, 121, "cohere.encoder")
        self.assertEqual(out["serviceNs"], 22)
        self.assertEqual(out["scenarios"]["infinite"]["stopWaitNs"], 1)
        self.assertEqual(out["baselineDispatchGapNs"], 0)

    def test_fixed_dispatch_gaps_reconstruct_observed_baseline(self):
        jobs = [{"releaseNs":0,"startNs":2000000,"endNs":12000000,"encoderNs":10000000},
                {"releaseNs":1,"startNs":15000000,"endNs":23000000,"encoderNs":0},
                {"releaseNs":12000000,"startNs":27000000,"endNs":29000000,"encoderNs":0}]
        out = a.fifo(jobs,12000000,28000000,"parakeet.encoder")
        self.assertEqual(out["replayed1xFinalNs"],28000000)
        self.assertEqual(out["observedStopWaitNs"],16000000)
        self.assertEqual(out["jobDispatchGapsNs"],[2000000,3000000,4000000])
        self.assertEqual(out["fixedDispatchGapNs"],9000000)
        self.assertEqual(out["scenarios"]["2x"]["stopWaitNs"],11000000)
        self.assertEqual(out["scenarios"]["infinite"]["stopWaitNs"],6000000)

    def test_overlap_and_encoder_double_count_rejected(self):
        jobs = [{"releaseNs": 0, "startNs": 0, "endNs": 10, "encoderNs": 11}]
        with self.assertRaises(a.Invalid): a.fifo(jobs, 0, 10, "cohere.encoder")
        jobs[0]["encoderNs"] = 0
        jobs.append({"releaseNs": 1, "startNs": 9, "endNs": 15, "encoderNs": 0})
        with self.assertRaises(a.Invalid): a.fifo(jobs, 0, 15, "cohere.encoder")


class NativeValidationTests(unittest.TestCase):
    def test_complete_native_and_disabled_control(self):
        for enabled in (True, False):
            p, t, d = native_fixture(enabled)
            self.assertEqual(len(a.native_valid(p, t, d, enabled)), int(enabled))

    def test_corrupt_complete_drop_inflight_role_and_clock_rejected(self):
        for key, value in (("complete", False), ("dropped", 1), ("activeAtFlush", 1),
                           ("attempted", 2), ("clockFailures", 1), ("runFailures", 1)):
            with self.subTest(key=key):
                p, t, d = native_fixture(); p[key] = value
                with self.assertRaises(a.Invalid): a.native_valid(p,t,d,True)
        p,t,d = native_fixture(); p["spans"][0]["role"] = "cohere.encoder"
        with self.assertRaises(a.Invalid): a.native_valid(p,t,d,True)
        p,t,d = native_fixture(); t["nativeClockSamples"][0]["nativeNs"] = 50
        with self.assertRaises(a.Invalid): a.native_valid(p,t,d,True)

    def test_hash_and_size_fail_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory); raw = b'{"synthetic":true}'
            (base/"x.json").write_bytes(raw)
            descriptor = {"path": "x.json", "bytes": len(raw), "sha256": hashlib.sha256(raw).hexdigest()}
            self.assertEqual(a.pinned(base, descriptor), {"synthetic": True})
            descriptor["sha256"] = "0"*64
            with self.assertRaises(a.Invalid): a.pinned(base, descriptor)
            descriptor["bytes"] += 1
            with self.assertRaises(a.Invalid): a.pinned(base, descriptor)

    def test_union_does_not_add_nested_wall(self):
        self.assertEqual(a.union([(0, 20), (5, 10), (18, 25), (30, 35)]), 30)


def event(phase, ns, **kwargs):
    return dict(phase=phase, ns=ns, **kwargs)


def case_events(offset=0):
    rows = [event("captureStart", 100), event("feedStart", 110, samples=640, scheduledNs=110, latenessNs=0),
        event("chunkReady",110,chunkId=0,samples=640),event("chunkEnqueue",110,chunkId=0),
        event("chunkWorkerStart",111,chunkId=0),event("firstClosedChunkGate",111,chunkId=0,samples=640),
        event("recognitionStart",112,chunkId=0,samples=640),event("recognitionEnd",120,chunkId=0,samples=640),
        event("firstUsablePreview",120,chunkId=0),event("chunkWorkerEnd",121,chunkId=0),
        event("producerStop",125,scheduledNs=125,latenessNs=0),event("finishCall",126),
        event("flushStart",127),event("flushEnd",128),event("finishEnqueue",129),
        event("finishWorkerStart",130),event("finishDrain",131),event("finalCallback",132),event("finishWorkerEnd",133)]
    for row in rows:
        row["ns"] += offset
        if "scheduledNs" in row: row["scheduledNs"] += offset
    return rows


class AttributionTests(unittest.TestCase):
    def test_span_within_worker_but_outside_recognition_fails(self):
        case = {"samples":640,"language":"en"}
        span = {"role":"parakeet.encoder","startNs":111,"endNs":113}
        with self.assertRaises(a.Invalid): a.case_analysis(case_events(),[span],case,"parakeet.encoder",True)

    def test_correct_role_fraction_and_first_usable(self):
        span = {"role":"parakeet.encoder","startNs":112,"endNs":118}
        out, _ = a.case_analysis(case_events(),[span],{"samples":640,"language":"en"},"parakeet.encoder",True)
        self.assertEqual(out["serviceNs"],13)
        self.assertEqual(out["fifo"]["encoderNs"],6)
        self.assertEqual(out["captureToFirstUsableNs"],20)
        self.assertEqual(out["finalWaitNs"],7)

    def test_missing_final_and_native_overlap_rejected(self):
        rows = [r for r in case_events() if r["phase"] != "finalCallback"]
        with self.assertRaises(a.Invalid): a.case_analysis(rows,[],{"samples":640,"language":"en"},"parakeet.encoder",False)
        spans = [{"role":r,"startNs":112,"endNs":118} for r in ("parakeet.encoder","parakeet.decoder")]
        with self.assertRaises(a.Invalid): a.case_analysis(case_events(),spans,{"samples":640,"language":"en"},"parakeet.encoder",True)



def production_fixture(base):
    """Synthetic files matching MeasurementRun receipt names and path-keyed maps."""
    def write(name, value):
        raw = json.dumps(value).encode()
        (base/name).write_bytes(raw)
        return {"path":name,"bytes":len(raw),"sha256":hashlib.sha256(raw).hexdigest()}
    cases = [{"id":str(i),"samples":640,"language":"de" if i<5 else "en",
              "inputSha256":"0"*64,"reference":"synthetic words"} for i in range(10)]
    runs = []
    for name in ("U1","P1","P2","U2"):
        enabled = name.startswith("P")
        p,t,_ = native_fixture(enabled)
        events = [event("modelConstructionStart",5),event("modelConstructionEnd",10),
                  event("silenceWarmupStart",11),event("silenceWarmupEnd",15)]
        p["spans"] = []
        ordered = cases if name.endswith("1") else list(reversed(cases))
        for i, _case in enumerate(ordered):
            for row in case_events(i*1000):
                row.update(inputIndex=i,repetition=0); events.append(row)
            if enabled: p["spans"].append({"role":"parakeet.encoder","sessionId":1,
                "startNs":112+i*1000,"endNs":118+i*1000,"status":0})
        p["attempted"] = p["retained"] = len(p["spans"])
        for span in p["spans"]:
            for key in ("startNs", "endNs"): span[key] *= 2000000
        profile = write(name+"-profile.json",p)
        t["nativeProfile"].update(bytes=profile["bytes"],sha256=profile["sha256"])
        for row,n in zip(t["nativeClockSamples"],(1,10000,10010)):
            row.update(beforeNs=n,nativeNs=n+1,afterNs=n+2)
        for row in events:
            for key in ("ns", "scheduledNs", "latenessNs"):
                if key in row: row[key] *= 2000000
        for row in t["nativeClockSamples"]:
            for key in ("beforeNs", "nativeNs", "afterNs"): row[key] *= 2000000
        t.update(clock="CLOCK_MONOTONIC",valid=True,droppedEvents=0,eventLimit=65536,events=events)
        config = {"runtime":{"profile":enabled},"lid":{"model":""}}
        configuration = write(name+"-config.json",config)
        provenance = {"build_sha256":"a"*64,"test_build_sha256":"b"*64}
        measurement = {"mode":"paced-only","repetitions":1,"provenance":provenance,
            "artifacts":{"variant.json":configuration["sha256"],"model.onnx":"c"*64},
            "inputs":[{"id":c["id"],"path":"../benchmark/"+str(min(int(c["id"]),5))+".wav","sha256":c["inputSha256"]} for c in ordered]}
        result = {"schema":4,"outcome":"complete","provenance":dict(provenance,
            platform="phone",feed=640,mode="paced-only",configuration_sha256=configuration["sha256"],
            run_id=name,process_start_id="pid-"+name),"report":{"variant":config,"timeline":t,
            "verifiedArtifacts":dict(measurement["artifacts"]),
            "verifiedInputs":{item["path"]:item["sha256"] for item in measurement["inputs"]},
            "buildSha256":provenance["build_sha256"],"testBuildSha256":provenance["test_build_sha256"],
            "runId":name,"processStartId":"pid-"+name,
            "coldLoadMs":1,"memory":[{"phase":phase,"elapsedNs":i,"highWaterKb":100,"thermalStatus":0}
                for i,phase in enumerate(["before-load","after-load","after-warmup"] +
                    [p for _ in cases for p in ("buffer-loaded","after-chunk","buffer-released")] +
                    ["recognizer-released","profile-flushed"])],"endpoints":[],
            "takes":[{"id":c["id"],"repetition":0,"inputSamples":640,"coverageSamples":640,
                      "missingFinalOutput":False,"text":"Synthetic words","stopToFinalMs":14,
                      "chunks":[{"audioSeconds":.04}],"residualSamples":0}
                     for c in ordered]}}
        runs.append({"id":name,"result":write(name+"-result.json",result),"profile":profile,
            "measurement":write(name+"-measurement.json",measurement),"configuration":configuration})
    stage = {"id":"A","encoderRole":"parakeet.encoder","requiredRoles":["parakeet.encoder"],
             "cases":cases,"runs":runs}
    b = {"id":"B","encoderRole":"cohere.encoder","requiredRoles":["cohere.encoder"],
         "cases":cases,"runs":[{"id":n,"status":"not-executed"} for n in ("U1","P1","P2","U2")]}
    manifest = {"schema":1,"stages":[stage,b]}
    return manifest


def rewrite_result(base, run, mutate):
    descriptor = run["result"]
    result = json.loads((base/descriptor["path"]).read_text())
    mutate(result["report"])
    raw = json.dumps(result).encode()
    (base/descriptor["path"]).write_bytes(raw)
    descriptor.update(bytes=len(raw), sha256=hashlib.sha256(raw).hexdigest())

class PartialTests(unittest.TestCase):
    def test_unexecuted_stage_is_explicit_not_invented(self):
        stages = [{"id": stage, "encoderRole":role,"requiredRoles":[role],
            "cases":[{"id":str(i),"language":"de" if i<5 else "en","samples":640,"inputSha256":"0"*64} for i in range(10)],
            "runs":[{"id":name,"status":"not-executed"} for name in ("U1","P1","P2","U2")]}
                  for stage,role in (("A","parakeet.encoder"),("B","cohere.encoder"))]
        out = a.analyze({"schema":1,"stages":stages},Path("/nonexistent"),"A")
        self.assertFalse(out["valid"])
        self.assertEqual(out["stages"][0]["runs"],{})
        self.assertEqual(out["stages"][1]["status"],"outside-selected-stage")
        self.assertEqual(out["stages"][0]["missingOrInvalidRuns"]["U1"],"not-executed")

    def test_full_synthetic_A_gate_and_retain_A_when_B_missing(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            manifest = production_fixture(base)
            out = a.analyze(manifest,base,"A")
            self.assertTrue(out["valid"],out)
            self.assertTrue(out["stages"][0]["eligibleForNextStage"])
            self.assertTrue(out["stages"][0]["timingsTransferable"])
            self.assertEqual(out["stages"][0]["runs"]["P1"]["cases"]["0"]["fifo"]["jobDispatchGapsNs"],[2000000,2000000])
            self.assertEqual(out["stages"][0]["languages"]["P1"]["de"]["referenceWords"],10)
            partial = a.analyze(manifest,base)
            self.assertFalse(partial["valid"])
            self.assertEqual(len(partial["stages"][0]["runs"]),4)

    def test_observed_receipts_mismatch_and_missing_retain_other_runs(self):
        for field in ("verifiedArtifacts", "verifiedInputs", "buildSha256", "testBuildSha256", "runId", "processStartId"):
            for missing in (False, True):
                with self.subTest(field=field,missing=missing), tempfile.TemporaryDirectory() as directory:
                    base = Path(directory); manifest = production_fixture(base)
                    def mutate(report):
                        if missing: report.pop(field)
                        elif field == "verifiedArtifacts": report[field]["model.onnx"] = "d"*64
                        elif field == "verifiedInputs": report[field][next(iter(report[field]))] = "d"*64
                        else: report[field] = "d"*64
                    rewrite_result(base,manifest["stages"][0]["runs"][1],mutate)
                    out = a.analyze(manifest,base,"A")["stages"][0]
                    self.assertFalse(out["eligibleForNextStage"])
                    self.assertFalse(out["timingsTransferable"])
                    self.assertEqual(set(out["runs"]),{"U1","P2","U2"})
                    self.assertIn("P1",out["missingOrInvalidRuns"])

    def test_reused_path_conflicting_expected_hash_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory); manifest = production_fixture(base)
            stage = manifest["stages"][0]; run = stage["runs"][0]
            d = run["measurement"]; measurement = json.loads((base/d["path"]).read_text())
            stage["cases"][6]["inputSha256"] = "d"*64
            measurement["inputs"][6]["sha256"] = "d"*64
            raw = json.dumps(measurement).encode(); (base/d["path"]).write_bytes(raw)
            d.update(bytes=len(raw),sha256=hashlib.sha256(raw).hexdigest())
            with self.assertRaisesRegex(a.Invalid,"conflicting-input-path-hash"):
                a.analyze_run(base,run,stage["cases"],stage["encoderRole"],stage["requiredRoles"])


    def test_same_text_changed_route_invalidates_pair(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory); manifest = production_fixture(base)
            # Enable the actual auto-language schema on all four synthetic runs.
            for run in manifest["stages"][0]["runs"]:
                d = run["configuration"]; config = json.loads((base/d["path"]).read_text())
                config["lid"] = {"model":"synthetic-lid", "probe_seconds":0}
                raw = json.dumps(config).encode(); (base/d["path"]).write_bytes(raw)
                d.update(bytes=len(raw),sha256=hashlib.sha256(raw).hexdigest())
                md = run["measurement"]; measurement = json.loads((base/md["path"]).read_text())
                measurement["artifacts"]["variant.json"] = d["sha256"]
                raw = json.dumps(measurement).encode(); (base/md["path"]).write_bytes(raw)
                md.update(bytes=len(raw),sha256=hashlib.sha256(raw).hexdigest())
                def mutate(report):
                    report["variant"] = config
                    report["verifiedArtifacts"] = measurement["artifacts"]
                    for i in range(10):
                        for phase,n in (("lidStart",111),("lidEnd",112),("lidDecision",112)):
                            e = event(phase,(n+i*1000)*2000000,inputIndex=i,repetition=0,samples=640)
                            if phase == "lidDecision": e.update(language="de" if run["id"] == "P1" else "en",fallback=False)
                            report["timeline"]["events"].append(e)
                rewrite_result(base,run,mutate)
                rd = run["result"]; result = json.loads((base/rd["path"]).read_text())
                result["provenance"]["configuration_sha256"] = d["sha256"]
                raw = json.dumps(result).encode(); (base/rd["path"]).write_bytes(raw)
                rd.update(bytes=len(raw),sha256=hashlib.sha256(raw).hexdigest())
            out = a.analyze(manifest,base,"A")["stages"][0]
            self.assertEqual(len(out["runs"]),4,out)
            self.assertIn("paired-routing-coverage-mismatch",out["errors"])
            self.assertFalse(out["pairs"][0]["valid"])
            self.assertFalse(out["eligibleForNextStage"])
            def missing(report):
                report["timeline"]["events"] = [e for e in report["timeline"]["events"]
                    if not (e.get("inputIndex") == 0 and e["phase"] == "lidDecision")]
            rewrite_result(base,manifest["stages"][0]["runs"][3],missing)
            missing_out = a.analyze(manifest,base,"A")["stages"][0]
            self.assertIn("U2",missing_out["missingOrInvalidRuns"])
            self.assertFalse(missing_out["eligibleForNextStage"])

    def test_same_text_changed_chunk_coverage_invalidates_pair(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory); manifest = production_fixture(base)
            def mutate(report):
                # Same complete sample total, different chunk partition; second is silent.
                for e in report["timeline"]["events"]:
                    if e.get("inputIndex") == 0 and "samples" in e and e["phase"] != "feedStart": e["samples"] = 320
                report["takes"][0]["chunks"][0]["audioSeconds"] = .02
                for phase,n,extra in (("chunkReady",121,{"samples":320}),("chunkEnqueue",121,{}),
                                     ("chunkWorkerStart",122,{}),("chunkWorkerEnd",123,{})):
                    report["timeline"]["events"].append(event(phase,n*2000000,inputIndex=0,repetition=0,chunkId=1,**extra))
            rewrite_result(base,manifest["stages"][0]["runs"][1],mutate)
            out = a.analyze(manifest,base,"A")["stages"][0]
            self.assertEqual(len(out["runs"]),4,out)
            self.assertIn("paired-routing-coverage-mismatch",out["errors"])
            self.assertFalse(out["timingsTransferable"])

    def test_extra_identical_thermal_observations_preserve_pair(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory); manifest = production_fixture(base)
            def mutate(report):
                rows = report["memory"]
                rows.insert(4, dict(rows[3], phase="extra-observation"))
                rows.insert(5, dict(rows[3], phase="extra-observation"))
            rewrite_result(base,manifest["stages"][0]["runs"][1],mutate)
            out = a.analyze(manifest,base,"A")["stages"][0]
            self.assertTrue(out["pairs"][0]["valid"],out)
            self.assertTrue(out["eligibleForNextStage"],out)
            self.assertTrue(out["timingsTransferable"],out)

    def test_extra_observation_thermal_excursion_invalidates_pair(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory); manifest = production_fixture(base)
            def mutate(report):
                rows = report["memory"]
                rows.insert(4, dict(rows[3], phase="extra-observation", thermalStatus=1))
            rewrite_result(base,manifest["stages"][0]["runs"][1],mutate)
            out = a.analyze(manifest,base,"A")["stages"][0]
            self.assertFalse(out["runs"]["P1"]["global"]["thermalDrift"])
            self.assertIn("paired-thermal-mismatch-or-drift",out["errors"])
            self.assertFalse(out["pairs"][0]["valid"])
            self.assertFalse(out["eligibleForNextStage"])

    def test_thermal_unequal_monotonic_and_missing_receipts_fail_closed(self):
        for kind in ("unequal","drift","missing", "boundary", "sample"):
            with self.subTest(kind=kind), tempfile.TemporaryDirectory() as directory:
                base = Path(directory); manifest = production_fixture(base)
                def mutate(report):
                    if kind == "unequal":
                        for row in report["memory"]: row["thermalStatus"] = 1
                    elif kind == "drift": report["memory"][-1]["thermalStatus"] = 1
                    elif kind == "missing": report["memory"][0].pop("thermalStatus")
                    elif kind == "boundary": report["memory"].pop(0)
                    else: report["takes"][0].pop("chunks")
                rewrite_result(base,manifest["stages"][0]["runs"][1],mutate)
                out = a.analyze(manifest,base,"A")["stages"][0]
                self.assertFalse(out["eligibleForNextStage"])
                self.assertFalse(out["timingsTransferable"])
                if kind in ("unequal","drift"):
                    self.assertIn("paired-thermal-mismatch-or-drift",out["errors"])
                else: self.assertIn("P1",out["missingOrInvalidRuns"])

    def test_process_order_thermal_drift_with_equal_pairs_invalidates_stage(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory); manifest = production_fixture(base)
            for run in manifest["stages"][0]["runs"][2:]:
                def mutate(report):
                    for row in report["memory"]: row["thermalStatus"] = 1
                rewrite_result(base,run,mutate)
            out = a.analyze(manifest,base,"A")["stages"][0]
            self.assertTrue(all(pair["valid"] for pair in out["pairs"]))
            self.assertIn("process-order-thermal-drift",out["errors"])
            self.assertFalse(out["eligibleForNextStage"])


    def test_perturbation_blocks_next_stage_and_timing_transfer(self):
        for kind in ("recognition", "stop"):
            with self.subTest(kind=kind), tempfile.TemporaryDirectory() as directory:
                base = Path(directory); manifest = production_fixture(base)
                def mutate(report):
                    for row in report["timeline"]["events"]:
                        if row.get("inputIndex") != 0: continue
                        if kind == "recognition" and row["phase"] == "recognitionEnd": row["ns"] -= 2000000
                        if kind == "stop" and row["phase"] in ("finalCallback", "finishWorkerEnd"): row["ns"] += 100000001
                    if kind == "stop": report["takes"][0]["stopToFinalMs"] += 100.000001
                # A control has no native spans, so both altered timelines remain valid.
                rewrite_result(base,manifest["stages"][0]["runs"][0],mutate)
                out = a.analyze(manifest,base,"A")["stages"][0]
                self.assertEqual(out["status"],"complete",out)
                self.assertFalse(out["eligibleForNextStage"])
                self.assertFalse(out["timingsTransferable"])
                self.assertEqual(out["pairs"][0]["timingNontransferableCases"],["0"])


if __name__ == "__main__":
    unittest.main()
