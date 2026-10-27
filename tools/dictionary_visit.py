#!/usr/bin/env python3
"""Prepare and assess the bounded candidate-A paired visit. Never runs phone commands.

All plans, staging and receipts belong in corpus. Prepare is deliberately unfrozen;
freeze binds the final APK/source/device identities before any measured attempt.
"""
import argparse
import csv
from datetime import datetime
import hashlib
import json
import os
from pathlib import Path
import shutil
import statistics
import shlex
import zipfile

import evaluate_v4
import experiment
import measurement_identity as identity
import variant

ROOT = Path(__file__).resolve().parents[1]
PROTOCOL = "dictionary-visit-a-v1"


def schedule(cases):
    if len(cases) != 10 or len(set(cases)) != 10:
        raise ValueError("exactly ten distinct development takes required")
    runs = []
    for phase in ("control", "candidate"):
        for round_index in range(3):
            for index, case in enumerate(cases):
                arms = ["default", "default-repeat" if phase == "control" else "candidate-a"]
                if (index + round_index) % 2:
                    arms.reverse()
                for arm in arms:
                    runs.append(dict(id=f"{phase}-r{round_index + 1}-t{index + 1}-{arm}",
                                     phase=phase, round=round_index + 1, case=case, arm=arm))
    return runs


def expected(plan, run):
    setup = plan["setups"]["default" if run["arm"] == "default-repeat" else run["arm"]]
    ref = plan["cases"][run["case"]]
    return dict(provenance=dict(setup["provenance"], experiment_run=run["id"]),
                takes=[], endpoints=[run["case"]], endpoint_references={run["case"]: ref},
                inputs={ref["path"]: ref["sha256"]}, artifacts=setup["artifacts"],
                device=plan["device"], execution=setup["execution"])


def manifest(plan, run):
    setup = plan["setups"]["default" if run["arm"] == "default-repeat" else run["arm"]]
    wanted = expected(plan, run)
    return dict(mode="paced-only", experiment_run=run["id"], modelDirectory=setup["model_directory"],
                provenance=wanted["provenance"], artifacts=wanted["artifacts"],
                inputs=[dict(plan["cases"][run["case"]], id=run["case"])])


def lab_hash(plan, run):
    setup = plan["setups"]["default" if run["arm"] == "default-repeat" else run["arm"]]
    inventory = dict(plan["stage_inventory"], **{
        "variant.json": hashlib.sha256(_variant_bytes(setup["variant"])).hexdigest(),
        "measurement.json": hashlib.sha256(_variant_bytes(manifest(plan, run))).hexdigest()})
    return hashlib.sha256(json.dumps(inventory, sort_keys=True).encode()).hexdigest()


def live_config(plan):
    setup = plan["setups"]["candidate-a"]
    value = {k: setup["provenance"][k] for k in
             ("build_sha256", "configuration_sha256", "model_sha256", "dictionary_sha256")}
    return dict(value, schema=1, plan_sha256=plan["sha256"], modelDirectory=setup["model_directory"], artifacts=setup["artifacts"])


def live_config_hash(plan):
    return hashlib.sha256(_variant_bytes(live_config(plan))).hexdigest()


def live_receipts_valid(plan, rows):
    if not isinstance(rows, list) or len(rows) < 2:
        return False
    expected_identity = live_config(plan)
    seen, completed, cancelled = set(), False, False
    for row in rows:
        actual = row.get("identity", {})
        if (row.get("protocol") != "ime-candidate-live-v1" or actual.get("verified") is not True or
                actual.get("live_config_sha256") != live_config_hash(plan) or not row.get("processId") or
                not row.get("takeId") or row["takeId"] in seen):
            return False
        seen.add(row["takeId"])
        if any(actual.get(k) != expected_identity[k] for k in
               ("plan_sha256", "build_sha256", "configuration_sha256", "model_sha256", "dictionary_sha256", "artifacts")):
            return False
        events = row.get("eventsNs", {})
        if row.get("outcome") == "COMPLETED":
            ordered = [events.get(k) for k in ("captureStart", "stopRequested", "captureStopped", "finalReady", "commitAccepted", "returnRequested", "takeEnded")]
            if (row.get("commitAccepted") is not True or row.get("returnAccepted") is not True or
                    not all(type(t) is int and t >= 0 for t in ordered) or ordered != sorted(ordered)):
                return False
            completed = True
        elif row.get("outcome") in ("CANCELLED", "INPUT_CHANGED"):
            if row.get("commitAccepted") is not None or "commitAccepted" in events:
                return False
            cancelled = True
        else:
            return False
    return completed and cancelled


def _write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("x") as sink:
        json.dump(value, sink, indent=2, ensure_ascii=False, allow_nan=False)
        sink.write("\n")


def _private(path, root):
    path = Path(path).resolve()
    if not path.is_relative_to(Path(root).resolve() / "corpus"):
        raise ValueError("visit files must remain in corpus")
    return path


def _link(source, target):
    source = Path(source).resolve()
    target.parent.mkdir(parents=True, exist_ok=True)
    try:
        os.link(source, target)
    except OSError:
        shutil.copyfile(source, target)


def _variant_bytes(value):
    return (json.dumps(value, indent=2, ensure_ascii=False) + "\n").encode()


def prepare(folder, root=ROOT):
    """Stage the fixed development suite once; no APK or device readiness is inferred."""
    import wave
    root = Path(root).resolve()
    folder = _private(folder, root)
    folder.mkdir(parents=True, exist_ok=False)
    stage = folder / "stage"
    stage.mkdir()
    refs = json.loads((root / "corpus/lab/issue11/references.json").read_text())
    cases = {}
    for index, (name, (lang, text)) in enumerate(refs.items()):
        source = root / "corpus/files/benchmark" / name
        target = stage / "inputs" / name
        _link(source, target)
        with wave.open(str(source)) as wav:
            if (wav.getnchannels(), wav.getsampwidth(), wav.getframerate()) != (1, 2, 16000):
                raise ValueError("development takes must be mono PCM16 16kHz")
            samples = wav.getnframes()
        cases[f"take-{index + 1}"] = dict(path=f"inputs/{name}", sha256=identity.sha(source),
            samples=samples, lang=lang, reference=text, cut_word=False)
    schedule(list(cases))
    if {r["lang"] for r in cases.values()} != {"de", "en"}:
        raise ValueError("both languages required")
    dictionary = root / "corpus/lab/issue11-dictionary.txt"
    _link(dictionary, stage / dictionary.name)
    _link(root / "models/silero_vad.onnx", stage / "silero_vad.onnx")
    setups = {}
    variants = {"default": variant.validate(json.loads((root / "variants/baseline.json").read_text())),
                "candidate-a": variant.validate(json.loads((root / "corpus/lab/issue11/candidate-a.json").read_text()))}
    for arm, value in variants.items():
        directory = "models/" + value["model"]
        files = list((root / "models" / value["model"]).iterdir())
        for file in files:
            if file.is_file():
                _link(file, stage / directory / file.name)
        artifacts = {str(p.relative_to(stage)): identity.sha(p) for p in (stage / directory).rglob("*") if p.is_file()}
        model_hash = identity.digest(artifacts)
        artifacts["silero_vad.onnx"] = identity.sha(stage / "silero_vad.onnx")
        if value["hotwords"]["file"]:
            if value["hotwords"]["file"] != dictionary.name:
                raise ValueError("candidate dictionary differs from prepared dictionary")
            artifacts[dictionary.name] = identity.sha(dictionary)
        artifacts["variant.json"] = hashlib.sha256(_variant_bytes(value)).hexdigest()
        setups[arm] = dict(variant=value, model_directory=directory, artifacts=artifacts,
            execution={"encoder": "onnx-cpu", "fallback": False, "runtime_core_sha256": experiment.RUNTIME_CORES[0]},
            provenance=dict(platform="phone", feed=640, mode="paced-only", timing_protocol=experiment.PROTOCOL,
                model_sha256=model_hash, configuration_sha256=artifacts["variant.json"],
                dictionary_sha256=identity.sha(dictionary) if value["hotwords"]["file"] else identity.digest([]),
                inputs_sha256=identity.digest({k: (r["sha256"], r["samples"]) for k, r in cases.items()}),
                references_sha256=identity.digest(cases)))
    plan = dict(protocol=PROTOCOL, status="draft", cases=cases, setups=setups, device=None,
                stage_inventory={str(p.relative_to(stage)): identity.sha(p) for p in stage.rglob("*") if p.is_file()},
                dictionary=dictionary.read_text().splitlines(), dictionary_file_sha256=identity.sha(dictionary),
                audio_minutes=sum(r["samples"] for r in cases.values()) / 16000 / 60 * 12)
    _write(folder / "draft.json", plan)
    return folder / "draft.json"


def freeze(draft, app, test, device, snapshot, root=ROOT):
    root = Path(root).resolve()
    draft = _private(draft, root)
    plan = json.loads(draft.read_text())
    if plan.get("status") != "draft" or not all(device.get(k) for k in ("model", "osBuild")) or not snapshot:
        raise ValueError("freeze requires observed device and pre-visit snapshot identity")
    if plan["setups"]["default"]["variant"] != variant.validate(json.loads((root / "variants/baseline.json").read_text())):
        raise ValueError("default differs from shipped baseline")
    if plan["setups"]["candidate-a"]["variant"] != variant.validate(json.loads((root / "corpus/lab/issue11/candidate-a.json").read_text())):
        raise ValueError("candidate differs from screened candidate A")
    if identity.sha(root / "corpus/lab/issue11-dictionary.txt") != plan["dictionary_file_sha256"]:
        raise ValueError("dictionary changed since staging")
    for relative, digest in plan["stage_inventory"].items():
        if identity.sha(draft.parent / "stage" / relative) != digest:
            raise ValueError("staged input changed: " + relative)
    with zipfile.ZipFile(app) as apk:
        core = hashlib.sha256(apk.read("lib/arm64-v8a/libonnxruntime.so")).hexdigest()
        runtime = identity.digest({name: hashlib.sha256(apk.read(name)).hexdigest()
                                   for name in apk.namelist() if name.startswith("lib/") and name.endswith(".so")})
        default = plan["setups"]["default"]
        for name in ("encoder.int8.onnx", "decoder.onnx", "joiner.onnx", "tokens.txt"):
            if hashlib.sha256(apk.read("assets/parakeet-v3/" + name)).hexdigest() != default["artifacts"][default["model_directory"] + "/" + name]:
                raise ValueError("default staged model differs from shipped APK: " + name)
        if hashlib.sha256(apk.read("assets/parakeet-v3/silero_vad.onnx")).hexdigest() != default["artifacts"]["silero_vad.onnx"]:
            raise ValueError("default VAD differs from shipped APK")
    if core != experiment.RUNTIME_CORES[0]:
        raise ValueError("candidate A must use shipped ORT 1.28.2")
    snapshot_path = _private(snapshot, root)
    snapshot_value = json.loads(snapshot_path.read_text())
    if not all(snapshot_value.get(k) for k in ("app_sha256", "data_sha256", "ts")):
        raise ValueError("snapshot receipt is incomplete")
    sources = experiment.source_identity(root)
    evaluator = {name: identity.sha(root / "tools" / name) for name in
                 ("dictionary_visit.py", "dictionary_score.py", "experiment.py", "evaluate_v4.py", "measurement_identity.py", "variant.py", "score.py")}
    plan.update(status="frozen", device=device, snapshot={"path": str(snapshot_path), "sha256": identity.sha(snapshot_path)},
                sources=sources, evaluator=evaluator)
    for setup in plan["setups"].values():
        setup["provenance"].update(build_sha256=identity.sha(app), test_build_sha256=identity.sha(test),
            runtime_sha256=runtime, source_sha256=identity.digest(sources), evaluator_sha256=identity.digest(evaluator))
    plan["sha256"] = identity.digest(plan)
    path = draft.parent / "plan.json"
    _write(path, plan)
    return path


def frozen(path, root=ROOT):
    path = _private(path, root)
    plan = json.loads(path.read_text())
    binding = {k: v for k, v in plan.items() if k != "sha256"}
    if plan.get("status") != "frozen" or identity.digest(binding) != plan.get("sha256"):
        raise ValueError("plan is not frozen or was changed")
    if plan["sources"] != experiment.source_identity(root):
        raise ValueError("source changed after freeze; use frozen revision")
    if identity.sha(_private(plan["snapshot"]["path"], root)) != plan["snapshot"]["sha256"]:
        raise ValueError("snapshot receipt changed")
    return plan


def stage_run(path, run_id, root=ROOT):
    plan = frozen(path, root)
    folder = Path(path).parent
    runs = schedule(list(plan["cases"]))
    index = next((i for i, r in enumerate(runs) if r["id"] == run_id), None)
    if index is None:
        raise ValueError("unknown run")
    if any(not (folder / "receipts" / (r["id"] + ".json")).is_file() for r in runs[:index]):
        raise ValueError("record all preceding attempts before advancing")
    prior = {r["id"]: read_receipt(folder / "receipts" / (r["id"] + ".json"), plan, root)
             for r in runs[:index]}
    errors = receipt_errors(plan, runs[:index], prior)
    if errors:
        raise ValueError("prior attempt invalid; diagnose before continuing: " + "; ".join(errors))
    if index >= 60:
        for a, b in zip(runs[:60:2], runs[1:60:2]):
            texts = [json.loads((folder / "receipts" / (r["id"] + ".json")).read_text())["measurement"]["report"]["endpoints"][0]["text"]
                     for r in (a, b)]
            if texts[0] != texts[1]:
                raise ValueError("default/default text differs; diagnose before candidate measurements")
    run = runs[index]
    _write(folder / "attempts" / (run_id + ".json"), dict(plan_sha256=plan["sha256"], run=run_id))
    setup = plan["setups"]["default" if run["arm"] == "default-repeat" else run["arm"]]
    target = folder / "stage"
    (target / "variant.json").write_bytes(_variant_bytes(setup["variant"]))
    (target / "measurement.json").write_bytes(_variant_bytes(manifest(plan, run)))
    for relative, digest in setup["artifacts"].items():
        if identity.sha(target / relative) != digest:
            raise ValueError("staged artifact changed: " + relative)
    for ref in plan["cases"].values():
        if identity.sha(target / ref["path"]) != ref["sha256"]:
            raise ValueError("staged take changed")
    return target


def record(path, run_id, measurement, wrapper_path, root=ROOT):
    plan = frozen(path, root)
    folder = Path(path).parent
    attempt = folder / "attempts" / (run_id + ".json")
    if run_id not in {r["id"] for r in schedule(list(plan["cases"]))} or not attempt.is_file():
        raise ValueError("run must be staged/reserved before recording")
    # Keep failures and unknown outcomes too. A failed receipt cannot be replaced by a retry.
    measurement, wrapper_path = _private(measurement, root), _private(wrapper_path, root)
    wrapper = json.loads(wrapper_path.read_text())
    phone_meta = _private(Path(wrapper["path"]) / "meta.json", root)
    value = dict(plan_sha256=plan["sha256"], measurement=json.loads(measurement.read_text()),
                 phone=json.loads(phone_meta.read_text()), measurement_path=str(measurement),
                 measurement_sha256=identity.sha(measurement), phone_meta_path=str(phone_meta),
                 phone_meta_sha256=identity.sha(phone_meta), wrapper=wrapper,
                 wrapper_path=str(wrapper_path), wrapper_sha256=identity.sha(wrapper_path))
    _write(folder / "receipts" / (run_id + ".json"), value)


def stage_live(path, root=ROOT):
    plan = frozen(path, root)
    result = report(path, root=root)
    if result["decision"] != "pending-manual-validation" or not result.get("observations_validated"):
        raise ValueError("complete advancing replay required before the supervised candidate check")
    folder = Path(path).parent
    target = folder / "live-stage"
    target.mkdir(exist_ok=False)
    for relative, digest in plan["stage_inventory"].items():
        source = folder / "stage" / relative
        if identity.sha(source) != digest:
            raise ValueError("staged artifact changed: " + relative)
        _link(source, target / relative)
    (target / "variant.json").write_bytes(_variant_bytes(plan["setups"]["candidate-a"]["variant"]))
    (target / "live.json").write_bytes(_variant_bytes(live_config(plan)))
    return target


def read_receipt(receipt, plan, root):
    value = json.loads(receipt.read_text())
    if value["plan_sha256"] != plan["sha256"]:
        raise ValueError("receipt belongs to another frozen plan")
    for prefix in ("measurement", "phone_meta", "wrapper"):
        original = _private(value[prefix + "_path"], root)
        if identity.sha(original) != value[prefix + "_sha256"]:
            raise ValueError("original receipt changed")
    if (value["measurement"] != json.loads(Path(value["measurement_path"]).read_text()) or
            value["phone"] != json.loads(Path(value["phone_meta_path"]).read_text()) or
            value["wrapper"] != json.loads(Path(value["wrapper_path"]).read_text())):
        raise ValueError("copied receipt changed")
    return value


def report(path, manual=None, root=ROOT):
    plan = frozen(path, root)
    folder = Path(path).parent
    receipts = {}
    for receipt in (folder / "receipts").glob("*.json"):
        receipts[receipt.stem] = read_receipt(receipt, plan, root)
    if manual and manual.get("live_check", {}).get("receipt_file"):
        manual = json.loads(json.dumps(manual))
        ref = manual["live_check"]["receipt_file"]
        file = _private(ref["path"], root)
        if identity.sha(file) != ref["sha256"]:
            raise ValueError("live receipt file changed")
        manual["live_check"]["receipts"] = [json.loads(line) for line in file.read_text().splitlines() if line.strip()]
    elif manual and "receipts" in manual.get("live_check", {}):
        raise ValueError("supply the original pulled live receipt file and its hash")
    return compare(plan, receipts, manual)


def receipt_errors(plan, runs, receipts):
    """Validate an ordered prefix identically before advancement and final comparison."""
    errors_by_run = []
    seen_runs, seen_processes, seen_meta = set(), set(), set()
    previous_ts = None
    for run in runs:
        receipt = receipts[run["id"]]
        measured, phone = receipt["measurement"], receipt["phone"]
        wanted = expected(plan, run)
        errors = evaluate_v4.evidence_errors(measured, wanted)
        wrapper = receipt.get("wrapper", {})
        if (wrapper.get("outcome") != "passed" or not receipt.get("measurement_sha256") or
                wrapper.get("lab", {}).get("result.json") != receipt.get("measurement_sha256")):
            errors.append("instrumentation failed or pulled result differs from device receipt")
        errors += evaluate_v4.measurement_errors(measured)
        errors += experiment._session_errors(measured["report"], wanted)
        report = measured["report"]
        for row in report.get("endpoints", []):
            events = row.get("session", {}).get("eventsNs", {})
            insertion = [events.get(k) for k in ("finalReady", "commitAccepted", "returnRequested", "takeEnded")]
            if not all(type(t) is int and t >= 0 for t in insertion) or insertion != sorted(insertion):
                errors.append("missing or invalid simulated insertion/return receipts")
        for actual, frozen in (("device", "device"), ("verifiedArtifacts", "artifacts"),
                               ("verifiedInputs", "inputs"), ("execution", "execution")):
            if report.get(actual) != wanted[frozen]:
                errors.append("observed " + actual + " differs from freeze")
        for field, seen in (("run_id", seen_runs), ("process_start_id", seen_processes)):
            value = measured["provenance"][field]
            if value in seen:
                errors.append("reused " + field)
            seen.add(value)
        if (phone.get("verb") != "run" or phone.get("classes") != "ch.og.dictation.LabTest" or
                phone.get("app_sha256") != wanted["provenance"]["build_sha256"] or
                phone.get("test_sha256") != wanted["provenance"]["test_build_sha256"] or
                phone.get("lab_sha256") != lab_hash(plan, run)):
            errors.append("wrapper run identity differs from freeze")
        setup = plan["setups"]["default" if run["arm"] == "default-repeat" else run["arm"]]
        if report.get("variant") != setup["variant"]:
            errors.append("reported variant differs from freeze")
        timestamp = datetime.fromisoformat(phone["ts"])
        if previous_ts is not None and timestamp <= previous_ts:
            errors.append("wrapper run order differs from paired order")
        previous_ts = timestamp
        meta_hash = identity.digest(phone)
        if meta_hash in seen_meta:
            errors.append("reused wrapper receipt")
        seen_meta.add(meta_hash)
        for memory in report["memory"]:
            if type(memory.get("thermalStatus")) is not int or memory["thermalStatus"] < 0:
                errors.append("missing thermal evidence")
        errors_by_run.extend(f"{run['id']}: {error}" for error in errors)
    return errors_by_run


def compare(plan, receipts, manual=None):
    """Incomplete evidence is inconclusive; successful replay never proves live usability."""
    result = dict(decision="inconclusive", errors=[], product_winner=False)
    try:
        runs = schedule(list(plan["cases"]))
        if set(receipts) != {r["id"] for r in runs}:
            raise ValueError("missing or extra run receipts; reconcile all attempts")
        if plan.get("protocol") != PROTOCOL or plan.get("status") != "frozen":
            raise ValueError("final comparison plan is not frozen")
        result["errors"].extend(receipt_errors(plan, runs, receipts))
        if result["errors"]:
            return result
        from dictionary_score import score_text
        rows, pairs = [], {"control": [], "candidate": []}
        for a, b in zip(runs[::2], runs[1::2]):
            if a["phase"] == "control":
                texts = [receipts[r["id"]]["measurement"]["report"]["endpoints"][0]["text"] for r in (a, b)]
                if texts[0] != texts[1]:
                    raise ValueError("default/default text differs; calibration is invalid")
            pair = {}
            for run in (a, b):
                report = receipts[run["id"]]["measurement"]["report"]
                row = report["endpoints"][0]
                ref = plan["cases"][run["case"]]
                counts = score_text(ref["reference"], row["text"], ref["lang"], plan["dictionary"])
                events = row["session"]["eventsNs"]
                observed = dict(run, language=ref["lang"],
                                stop_wait_ms=(events["commitAccepted"] - events["stopRequested"]) / 1e6,
                                stop_to_final_ready_ms=row["stopToFinalMs"],
                                cold_load_ms=report["coldLoadMs"],
                                peak_kb=max(m["highWaterKb"] for m in report["memory"]),
                                max_thermal=max(m["thermalStatus"] for m in report["memory"]))
                provenance = receipts[run["id"]]["measurement"]["provenance"]
                observed.update(measurement_run_id=provenance["run_id"], process_start_id=provenance["process_start_id"],
                                input_sha256=ref["sha256"], configuration_sha256=provenance["configuration_sha256"])
                observed.update({k: counts[k] for k in ("errors", "listed_errors", "number_format_errors",
                                                       "other_errors", "dictionary_insertions", "dictionary_substitutions")})
                rows.append(observed)
                pair[run["arm"]] = observed
            control = pair["default"]
            candidate = pair["default-repeat" if a["phase"] == "control" else "candidate-a"]
            pairs[a["phase"]].append(dict(case=a["case"], round=a["round"],
                stop_difference_ms=candidate["stop_wait_ms"] - control["stop_wait_ms"],
                cold_difference_ms=candidate["cold_load_ms"] - control["cold_load_ms"],
                peak_difference_kb=candidate["peak_kb"] - control["peak_kb"]))
        differences = [r["stop_difference_ms"] for r in pairs["control"]]
        candidates = [r for r in rows if r["arm"] == "candidate-a"]
        waits = [r["stop_wait_ms"] for r in candidates]
        paired = [r["stop_difference_ms"] for r in pairs["candidate"]]
        result.update(rows=rows, pairs=pairs, observations_validated=True,
                      stop_wait_endpoint="simulated editor commitAccepted; real insertion requires supervised live check",
                      detectable_change_ms=max(map(abs, differences)),
                      detectability_rule="largest absolute same-visit default/default paired difference (30 pairs)",
                      candidate_median_stop_ms=statistics.median(waits), candidate_max_stop_ms=max(waits),
                      median_paired_stop_difference_ms=statistics.median(paired), max_paired_stop_difference_ms=max(paired))
        result["resources"] = {arm: {
            "median_cold_load_ms": statistics.median(r["cold_load_ms"] for r in rows if r["arm"] == arm),
            "max_cold_load_ms": max(r["cold_load_ms"] for r in rows if r["arm"] == arm),
            "peak_kb": max(r["peak_kb"] for r in rows if r["arm"] == arm),
            "max_thermal": max(r["max_thermal"] for r in rows if r["arm"] == arm),
        } for arm in ("default", "default-repeat", "candidate-a")}
        candidate_ids = {r["id"] for r in runs if r["arm"] == "candidate-a"}
        manual = manual or {}
        result["manual_observations_sha256"] = identity.digest(manual) if manual else None
        if manual and manual.get("plan_sha256") != plan["sha256"]:
            raise ValueError("manual observations are not bound to this frozen visit")
        reviewed = manual.get("meaning_review", {})
        reviewed_all = set(reviewed) == candidate_ids and all(type(v) is int and v >= 0 for v in reviewed.values())
        harms = sum(reviewed.values()) if reviewed_all else None
        gain = {}
        for lang in ("de", "en"):
            arms = {arm: sum(r["errors"] for r in rows if r["phase"] == "candidate" and
                            r["language"] == lang and r["arm"] == arm) for arm in ("default", "candidate-a")}
            gain[lang] = arms["default"] - arms["candidate-a"]
        result.update(error_reduction_by_language=gain, meaning_changing_false_accepts=harms)
        live = manual.get("live_check", {})
        live_pass = all(live.get(k) is True for k in ("one_insertion_on_stop", "cancellation_safe", "usable_futo_return"))
        live_pass = live_pass and bool(live.get("evidence")) and live.get("build_sha256") == plan["setups"]["candidate-a"]["provenance"]["build_sha256"]
        live_pass = live_pass and live_receipts_valid(plan, live.get("receipts"))
        recovered = manual.get("restoration_verified") is True and bool(manual.get("restoration_evidence"))
        if statistics.median(waits) > 2500 or harms is not None and harms > 0 or any(v <= 0 for v in gain.values()):
            result["decision"] = "reject"
        elif not reviewed_all or not live_pass or not recovered:
            result["decision"] = "pending-manual-validation"
        else:
            result["decision"] = "eligible-for-owner-adoption"
        result["manual_validation"] = dict(meaning_review_complete=reviewed_all, supervised_live_pass=live_pass,
                                           restoration_verified=recovered)
    except (KeyError, ValueError, TypeError, AttributeError, IndexError, OverflowError) as error:
        result["errors"].append(str(error))
    return result


def commands(plan_path, root=ROOT):
    """Printable standalone commands, never a device-command executor."""
    root = Path(root).resolve()
    plan_path = _private(plan_path, root)
    plan = json.loads(plan_path.read_text())
    folder = plan_path.parent
    tool = ["python3", str(root / "tools/dictionary_visit.py"), "--root", str(root)]
    phone = str(root / ".claude/hooks/phone")
    lines = ["# Execute each line separately. Inspect each result; stop and reconcile on any failure.",
             "# Freeze final APK/source/device/snapshot identities first. Install/restore require owner approval.",
             "# Preserve startup.jsonl before the first push: push mirrors the entire lab directory."]
    for run in schedule(list(plan["cases"])):
        raw = folder / "raw" / run["id"]
        lines += ["", "# " + run["id"],
                  shlex.join(tool + ["stage", str(folder / "plan.json"), run["id"]]),
                  shlex.join([phone, "push", str(folder / "stage")]),
                  shlex.join([phone, "run", "ch.og.dictation.LabTest"]),
                  shlex.join(tool + ["capture-wrapper", str(raw / "wrapper.json")]),
                  shlex.join([phone, "pull", "files/lab/result.json", "--to", str(raw / "pull")]),
                  shlex.join(tool + ["record", str(folder / "plan.json"), run["id"],
                                     str(raw / "pull/files/lab/result.json"), str(raw / "wrapper.json")])]
    return "\n".join(lines) + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    sub = parser.add_subparsers(dest="command", required=True)
    for name, fields in (("prepare", ("folder",)), ("freeze", ("draft", "app", "test", "device", "snapshot")),
                         ("stage", ("plan", "run")), ("record", ("plan", "run", "measurement", "wrapper")),
                         ("capture-wrapper", ("output",)), ("commands", ("plan",)), ("report", ("plan",)),
                         ("stage-live", ("plan",))):
        command = sub.add_parser(name)
        for field in fields:
            command.add_argument(field)
        if name == "report":
            command.add_argument("--manual")
    args = parser.parse_args()
    root = args.root.resolve()
    if args.command == "prepare":
        print(prepare(args.folder, root))
    elif args.command == "freeze":
        print(freeze(args.draft, args.app, args.test, json.loads(_private(args.device, root).read_text()), args.snapshot, root))
    elif args.command == "stage":
        print(stage_run(args.plan, args.run, root))
    elif args.command == "stage-live":
        print(stage_live(args.plan, root))
    elif args.command == "capture-wrapper":
        value = json.loads((root / "corpus/lab/last.json").read_text())
        _write(_private(args.output, root), value)
    elif args.command == "record":
        wrapper = json.loads(_private(args.wrapper, root).read_text())
        if "path" not in wrapper:
            raise ValueError("wrapper result has no run path; reconcile the attempt")
        record(args.plan, args.run, args.measurement, args.wrapper, root)
    elif args.command == "commands":
        print(commands(args.plan, root), end="")
    else:
        manual = json.loads(_private(args.manual, root).read_text()) if args.manual else None
        value = report(args.plan, manual, root)
        import uuid
        destination = _private(Path(args.plan).parent / ("report-" + uuid.uuid4().hex + ".json"), root)
        _write(destination, value)
        if value.get("rows"):
            with destination.with_suffix(".csv").open("x") as sink:
                writer = csv.DictWriter(sink, fieldnames=list(value["rows"][0]))
                writer.writeheader()
                writer.writerows(value["rows"])
        print(json.dumps({"path": str(destination), "decision": value["decision"], "errors": value["errors"]}))


if __name__ == "__main__":
    main()
