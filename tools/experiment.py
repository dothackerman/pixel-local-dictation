"""Bounded phone session comparisons. No inference, scheduling or phone execution.

Each report must match its own frozen identity. Only explicitly planned differences
may separate setups. Historical v4 evaluation rules remain unchanged.
"""
from collections import Counter, defaultdict
from datetime import datetime
import json
import os
from pathlib import Path
import re
import statistics
import uuid
from zoneinfo import ZoneInfo

import evaluate_v4 as evidence
import score
import measurement_identity as identity

PROTOCOL = "dictation-session-replay-v1"
DIFFERENCES = {"build_sha256", "test_build_sha256", "runtime_sha256",
               "model_sha256", "configuration_sha256"}
IDENTITIES = set(evidence.IDENTITY) | DIFFERENCES | {"dictionary_sha256"}
ROOT = Path(__file__).resolve().parents[1]


def private_path(path, root=ROOT):
    path, root = Path(path).resolve(), Path(root).resolve()
    if not path.is_relative_to(root / "corpus"):
        raise ValueError("experiment evidence must stay inside this checkout's corpus/")
    return path


def _directory(name, root):
    if not isinstance(name, str) or not re.fullmatch(r"[a-z0-9][a-z0-9-]{0,63}", name):
        raise ValueError("invalid experiment identity")
    return private_path(Path(root) / "corpus/lab" / name, root)


def _write(path, value, root):
    path = private_path(path, root)
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("x", encoding="utf-8") as output:
        json.dump(value, output, indent=2, ensure_ascii=False, allow_nan=False)
        output.write("\n")
        output.flush()
        os.fsync(output.fileno())
    return path


def _now():
    return datetime.now(ZoneInfo("Europe/Zurich")).isoformat()


def _artifact(relative, root):
    path = Path(root) / relative
    if Path(relative).is_absolute() or not path.resolve().is_relative_to(Path(root).resolve()):
        raise ValueError("artifact outside checkout")
    if not path.exists():
        return None
    paths = sorted(p for p in path.rglob("*") if p.is_file()) if path.is_dir() else [path]
    if not paths:
        return None
    for item in paths:
        if not item.resolve().is_relative_to(Path(root).resolve()):
            raise ValueError("artifact link outside checkout")
    return {"sha256": identity.digest({str(p.relative_to(path)) if path.is_dir() else p.name: identity.sha(p)
                                       for p in paths}),
            "files": len(paths), "bytes": sum(p.stat().st_size for p in paths)}


def prepare(spec, root=ROOT):
    """Snapshot host readiness without loading models or touching a device."""
    if spec.get("schema") != 1 or not spec.get("artifacts"):
        raise ValueError("preparation requires a schema-1 artifact specification")
    folder = _directory(spec["id"], root)
    artifacts = {role: {"path": path, "observed": _artifact(path, root)}
                 for role, path in spec["artifacts"].items()}
    value = {"kind": "preparation", "spec": spec, "spec_sha256": identity.digest(spec),
             "sources": source_identity(root),
             "created": _now(), "artifacts": artifacts}
    return _write(folder / ("preparation-" + uuid.uuid4().hex + ".json"), value, root)


def check(path, root=ROOT):
    """Present artifacts are not proof of conversion correctness or launch authorization."""
    value = json.loads(private_path(path, root).read_text())
    if value.get("kind") != "preparation" or identity.digest(value["spec"]) != value["spec_sha256"]:
        raise ValueError("invalid preparation snapshot")
    missing, changed = [], []
    if {role: entry["path"] for role, entry in value["artifacts"].items()} != value["spec"]["artifacts"]:
        raise ValueError("artifact inventory differs from specification")
    for role, entry in value["artifacts"].items():
        observed = _artifact(entry["path"], root)
        if observed is None:
            missing.append(role)
        if observed != entry["observed"]:
            changed.append(role)
    if value["sources"] != source_identity(root):
        changed.append("source")
    return {"missing": missing, "changed": changed, "measurement_ready": False,
            "status": "preparation-required" if missing or changed else "host-artifacts-present",
            "next": "verify conversion, backend, parity, device recovery and approved protocol before freezing measurements"}


def source_identity(root=ROOT):
    root = Path(root)
    paths = []
    for folder in ("tools", "app/src"):
        paths += [p for p in (root / folder).rglob("*")
                  if p.is_file() and p.suffix in (".py", ".kt", ".java", ".xml", ".cc", ".h")]
    paths += [p for p in (root / "app/build.gradle.kts", root / "build.gradle.kts",
                         root / "assets.lock", root / "tools/phone") if p.is_file()]
    return {str(p.relative_to(root)): identity.sha(p) for p in sorted(paths)}


def freeze(plan, root=ROOT):
    """Freeze one finite comparison. This does not authorize its execution."""
    errors = plan_errors(plan)
    if errors:
        raise ValueError("; ".join(errors))
    binding = {"plan": plan, "evaluator": evaluator_identity(root)}
    return _write(_directory(plan["id"], root) / "plan.json",
                  dict(binding, kind="frozen-comparison", sha256=identity.digest(binding)), root)


def evaluator_identity(root):
    return {name: identity.sha(Path(root) / "tools" / name)
            for name in ("experiment.py", "evaluate_v4.py", "evaluate.py", "score.py", "measurement_identity.py")
            if (Path(root) / "tools" / name).is_file()}


def _frozen(path, root):
    path = private_path(path, root)
    value = json.loads(path.read_text())
    binding = {"plan": value["plan"], "evaluator": value["evaluator"]}
    if (value.get("kind") != "frozen-comparison" or identity.digest(binding) != value["sha256"] or
            path != _directory(value["plan"]["id"], root) / "plan.json"):
        raise ValueError("frozen plan changed or moved")
    if value["evaluator"] != evaluator_identity(root):
        raise ValueError("comparison implementation changed; use its frozen Git revision")
    return value


def reserve(path, run, root=ROOT):
    frozen = _frozen(path, root)
    if run not in {r["id"] for r in frozen["plan"]["runs"]} or not re.fullmatch(r"[A-Za-z0-9_-]+", run):
        raise ValueError("unknown planned run")
    return _write(Path(path).parent / "attempts" / (run + ".json"),
                  {"run": run, "plan_sha256": frozen["sha256"], "reserved": _now()}, root)


def record(path, run, measured, root=ROOT):
    frozen = _frozen(path, root)
    if run not in {r["id"] for r in frozen["plan"]["runs"]} or not re.fullmatch(r"[A-Za-z0-9_-]+", run):
        raise ValueError("unknown planned run")
    attempt = private_path(Path(path).parent / "attempts" / (run + ".json"), root)
    if not attempt.is_file() or json.loads(attempt.read_text()).get("plan_sha256") != frozen["sha256"]:
        raise ValueError("reserve the attempt before execution; missing receipt means reconcile, not retry")
    # Preserve failed and mismatched evidence too; validation belongs to the comparison.
    return _write(Path(path).parent / "receipts" / (run + ".json"),
                  {"plan_sha256": frozen["sha256"], "recorded": _now(),
                   "sha256": identity.digest(measured), "measurement": measured}, root)


def report(path, root=ROOT):
    frozen = _frozen(path, root)
    reports, hashes, states, reserved = {}, {}, {}, 0
    for run in frozen["plan"]["runs"]:
        name = run["id"]
        attempted = (Path(path).parent / "attempts" / (name + ".json")).exists()
        reserved += attempted
        states[name] = "unresolved-execution" if attempted else "not-reserved"
        receipt = private_path(Path(path).parent / "receipts" / (name + ".json"), root)
        if receipt.exists():
            value = json.loads(receipt.read_text())
            if value["plan_sha256"] != frozen["sha256"] or identity.digest(value["measurement"]) != value["sha256"]:
                raise ValueError("receipt changed")
            reports[name], hashes[name] = value["measurement"], identity.sha(receipt)
            states[name] = "recorded"
    result = compare(frozen["plan"], reports)
    return dict(result, plan_sha256=frozen["sha256"], evaluator=frozen["evaluator"],
                receipt_sha256=hashes, attempts_reserved=reserved, attempt_states=states)


def save_report(path, result, root=ROOT):
    _frozen(path, root)
    return _write(Path(path).parent / ("report-" + uuid.uuid4().hex + ".json"), result, root)


def plan_errors(plan):
    errors = []
    if plan.get("schema") != 1 or plan.get("kind") not in ("conversion-parity", "gpu-wait"):
        errors.append("unsupported comparison plan")
    if plan.get("policy_status") != "approved":
        errors.append("decision thresholds have not been approved")
    differences = plan.get("allowed_differences", [])
    if not isinstance(differences, list) or not set(differences) <= DIFFERENCES:
        errors.append("unsupported intended differences")
        differences = []
    control, candidate = plan.get("control"), plan.get("candidate")
    if not control or not candidate or control == candidate:
        errors.append("two distinct setups required")
    runs = plan.get("runs", [])
    if not runs or len({r["id"] for r in runs}) != len(runs):
        errors.append("missing or duplicate planned run")
    pairs = defaultdict(dict)
    setups = {}
    for run in runs:
        if not re.fullmatch(r"[A-Za-z0-9_-]+", run["id"]) or type(run["pair"]) is not int or run["pair"] < 0:
            errors.append("invalid run or pair identity")
        if run["setup"] not in (control, candidate) or run["setup"] in pairs[run["pair"]]:
            errors.append("each pair needs one run of each setup")
        pairs[run["pair"]][run["setup"]] = run
        expected = run["expected"]
        p = expected["provenance"]
        if not IDENTITIES <= p.keys() or p.get("experiment_run") != run["id"]:
            errors.append("incomplete frozen identities")
        for key in IDENTITIES:
            if key.endswith("_sha256") and not re.fullmatch(r"[a-f0-9]{64}", str(p.get(key, ""))):
                errors.append("invalid frozen hash: " + key)
        stable = dict(expected, provenance={key: value for key, value in p.items() if key != "experiment_run"})
        if run["setup"] in setups and setups[run["setup"]] != stable:
            errors.append("setup or conditions changed between repeats")
        setups[run["setup"]] = stable
        if (p.get("platform"), p.get("mode"), p.get("feed"), p.get("timing_protocol")) != (
                "phone", "paced-only", 640, PROTOCOL):
            errors.append("comparison requires paced phone session replay")
        if (not expected.get("artifacts") or not expected.get("inputs") or
                not all(expected.get("device", {}).get(k) for k in ("model", "osBuild"))):
            errors.append("freeze observed device, artifacts and inputs")
        execution = expected.get("execution", {})
        encoder = ("onnx-cpu" if run["setup"] == control else
                   "litert-fp32-gpu" if plan["kind"] == "gpu-wait" else "litert-fp32-cpu")
        if execution.get("encoder") != encoder or execution.get("fallback") is not False:
            errors.append("freeze the intended encoder backend with fallback disabled")
        if encoder == "litert-fp32-gpu" and execution.get("gpu_only") is not True:
            errors.append("GPU-only execution proof required")
        refs = expected.get("endpoint_references", {})
        if (not refs or expected.get("takes") != [] or
                Counter(expected.get("endpoints", [])) != Counter(refs.keys()) or
                {r["lang"] for r in refs.values()} != {"de", "en"}):
            errors.append("freeze one complete endpoint suite covering both languages")
        for ref in refs.values():
            if (type(ref.get("samples")) is not int or ref["samples"] <= 0 or
                    ref.get("path") not in expected["inputs"] or
                    ref.get("sha256") != expected["inputs"].get(ref.get("path"))):
                errors.append("freeze each case's input path, hash and exact sample count")
    for pair in pairs.values():
        if set(pair) != {control, candidate}:
            errors.append("incomplete planned pair")
            continue
        a, b = (pair[key]["expected"] for key in (control, candidate))
        for key in IDENTITIES - set(differences):
            if a["provenance"].get(key) != b["provenance"].get(key):
                errors.append("unplanned difference: " + key)
        for key in ("inputs", "device", "endpoints", "endpoint_references"):
            if a.get(key) != b.get(key):
                errors.append("unmatched " + key)
    if plan.get("kind") == "gpu-wait" and len(pairs) < 2:
        errors.append("GPU comparison requires repeated pairs")
    thresholds = plan.get("thresholds", {})
    for key in ("relative_gain", "absolute_gain_ms", "peak_kb", "max_thermal"):
        if not evidence.finite(thresholds.get(key)):
            errors.append("missing or invalid threshold: " + key)
    if thresholds.get("relative_gain", 2) >= 1 or thresholds.get("peak_kb", 0) <= 0:
        errors.append("invalid gain or memory limit")
    return errors


def _session_errors(report, expected):
    errors = []
    if report.get("sessionProtocol") != PROTOCOL or report.get("variant", {}).get("runtime", {}).get("profile") is not False:
        errors.append("need unprofiled shared-session evidence")
    if [row.get("id") for row in report.get("endpoints", [])] != expected["endpoints"]:
        errors.append("case order differs from freeze")
    for row in report.get("endpoints", []):
        ref = expected["endpoint_references"].get(row.get("id"), {})
        if (row.get("inputSamples") != ref.get("samples") or
                row.get("inputPath") != ref.get("path") or row.get("inputSha256") != ref.get("sha256")):
            errors.append("case input identity or sample count differs from freeze")
        session = row.get("session", {})
        if any(session.get(key) != value for key, value in
               (("outcome", "COMPLETED"), ("capture", "replay"), ("editor", "simulated"), ("keyboard", "simulated"))):
            errors.append("missing or unsupported session receipts")
        events = session.get("eventsNs", {})
        sequence = [events.get(k) for k in ("captureStart", "stopRequested", "captureStopped", "finalReady", "takeEnded")]
        if not all(type(t) is int and t >= 0 for t in sequence) or sequence != sorted(sequence):
            errors.append("invalid session timeline")
            continue
        # Allow one feed block for timestamp placement; unpaced/truncated input is not a paced take.
        if (sequence[1] - sequence[0]) / 1e9 < (ref.get("samples", 0) - 640) / 16000:
            errors.append("capture duration is shorter than the frozen paced input")
        wait = (events["finalReady"] - events["stopRequested"]) / 1e6
        if not evidence.finite(row.get("stopToFinalMs")) or abs(wait - row["stopToFinalMs"]) > 1:
            errors.append("Stop timing disagrees with session receipts")
        if row.get("text"):
            preview = events.get("previewReady")
            if type(preview) is not int or not sequence[0] <= preview <= sequence[-1]:
                errors.append("missing first preview")
    return errors


def _observations(plan, reports, result):
    """Retain available observations even when evidence validation prevents a verdict.

    Scores here describe supplied rows only; they cannot establish matched coverage.
    No transcript is copied into the aggregate report.
    """
    result.update(observations={}, observed_language_scores={}, resource_breaches=[], observations_validated=False)
    limits = plan["thresholds"]
    for run in plan["runs"]:
        measured = reports.get(run["id"], {})
        if not isinstance(measured, dict) or not isinstance(measured.get("report"), dict):
            continue
        report = measured["report"]
        rows = report.get("endpoints", [])
        memory = report.get("memory", [])
        observed = {"setup": run["setup"], "cold_load_ms": report.get("coldLoadMs"),
                    "memory": memory, "cases": []}
        result["observations"][run["id"]] = observed
        for row in rows if isinstance(rows, list) else []:
            if not isinstance(row, dict):
                continue
            session = row.get("session", {})
            events = session.get("eventsNs", {}) if isinstance(session, dict) else {}
            if not isinstance(events, dict):
                events = {}
            preview, start = events.get("previewReady"), events.get("captureStart")
            observed["cases"].append({"id": row.get("id"), "stop_wait_ms": row.get("stopToFinalMs"),
                "encoder_ms": row.get("encoderMs"),
                "first_preview_ms": (preview - start) / 1e6 if type(preview) is int and type(start) is int else None})
            ref = run["expected"]["endpoint_references"].get(row.get("id"))
            if ref and isinstance(row.get("text"), str):
                lang = ref["lang"]
                totals = result["observed_language_scores"].setdefault(lang, {}).setdefault(
                    run["setup"], {"errors": 0, "reference_words": 0, "cases": 0})
                reference = score.normalise(ref["reference"], lang)
                totals["errors"] += score.errors(reference, score.normalise(row["text"], lang))
                totals["reference_words"] += len(reference)
                totals["cases"] += 1
        for row in memory if isinstance(memory, list) else []:
            if not isinstance(row, dict):
                continue
            exceeded = [key for key, limit in (("highWaterKb", "peak_kb"), ("thermalStatus", "max_thermal"))
                        if evidence.finite(row.get(key)) and row[key] > limits[limit]]
            if exceeded:
                result["resource_breaches"].append({"run": run["id"], "setup": run["setup"],
                    "observation": row, "exceeded": exceeded,
                    "limits": {"highWaterKb": limits["peak_kb"], "thermalStatus": limits["max_thermal"]}})


def compare(plan, reports):
    """Return an inconclusive result for malformed, mismatched or incomplete evidence."""
    result = {"decision": "inconclusive", "claim": "development-phone-replay",
              "product_winner": False, "errors": [], "languages": {}}
    try:
        result["errors"] = plan_errors(plan)
        if result["errors"]:
            return result
        _observations(plan, reports, result)
        runs = plan["runs"]
        if set(reports) != {r["id"] for r in runs}:
            result["errors"].append("missing or extra run receipts")
            return result
        seen_runs, seen_processes = set(), set()
        for run in runs:
            measured, expected = reports[run["id"]], run["expected"]
            result["errors"] += evidence.evidence_errors(measured, expected)
            result["errors"] += evidence.measurement_errors(measured)
            result["errors"] += _session_errors(measured["report"], expected)
            for key, seen in (("run_id", seen_runs), ("process_start_id", seen_processes)):
                value = measured["provenance"].get(key)
                if value in seen:
                    result["errors"].append("reused " + key)
                seen.add(value)
            report = measured["report"]
            for observed, frozen in (("device", "device"), ("verifiedArtifacts", "artifacts"),
                                     ("verifiedInputs", "inputs"), ("execution", "execution")):
                if report.get(observed) != expected[frozen]:
                    result["errors"].append("observed " + observed + " differs from freeze")
            for memory in report["memory"]:
                if type(memory.get("thermalStatus")) is not int or memory["thermalStatus"] < 0:
                    result["errors"].append("missing thermal evidence")
        if result["errors"]:
            return result
        result["observations_validated"] = True
        return _score(plan, reports, result)
    except (KeyError, TypeError, ValueError, AttributeError, OSError, OverflowError) as error:
        result["errors"].append("malformed or unavailable evidence: " + str(error))
        return result


def _score(plan, reports, result):
    control, candidate = plan["control"], plan["candidate"]
    pairs = defaultdict(dict)
    for run in plan["runs"]:
        pairs[run["pair"]][run["setup"]] = run
    rejected = []
    control_blockers = []
    limits = plan["thresholds"]
    for breach in result["resource_breaches"]:
        reason = "resource limit: " + breach["run"] + " (" + str(breach["observation"].get("phase")) + ")"
        (control_blockers if breach["setup"] == control else rejected).append(reason)
    for lang in ("de", "en"):
        waits = {control: [], candidate: []}
        medians = {control: [], candidate: []}
        error_counts = {control: 0, candidate: 0}
        reference_words = {control: 0, candidate: 0}
        paired_savings = []
        changed_texts = 0
        for pair in pairs.values():
            values = {}
            for setup in (control, candidate):
                run = pair[setup]
                refs = run["expected"]["endpoint_references"]
                rows = {r["id"]: r for r in reports[run["id"]]["report"]["endpoints"] if refs[r["id"]]["lang"] == lang}
                values[setup] = rows
                these_waits = [r["stopToFinalMs"] for r in rows.values()]
                waits[setup] += these_waits
                medians[setup].append(statistics.median(these_waits))
                for key, row in rows.items():
                    reference = score.normalise(refs[key]["reference"], lang)
                    reference_words[setup] += len(reference)
                    error_counts[setup] += score.errors(reference, score.normalise(row["text"], lang))
            paired_savings.append(medians[control][-1] - medians[candidate][-1])
            for key, row in values[control].items():
                a, b = row["text"], values[candidate][key]["text"]
                # Hold quality exactly for this mechanism comparison, including punctuation.
                # Broader quality tradeoffs require their own declared experiment.
                if a != b:
                    changed_texts += 1
        a, b = statistics.median(waits[control]), statistics.median(waits[candidate])
        saving = a - b
        spread = max(max(v) - min(v) for v in medians.values())
        result["languages"][lang] = {
            "errors": error_counts, "reference_words": reference_words,
            "word_error_rate": {setup: error_counts[setup] / reference_words[setup]
                                if reference_words[setup] else None for setup in (control, candidate)},
            "changed_outputs": changed_texts,
            "control_median_ms": a, "candidate_median_ms": b, "saving_ms": saving,
            "relative_gain": saving / a if a else 0, "repeat_spread_ms": spread,
            "paired_savings_ms": paired_savings,
            "control_max_ms": max(waits[control]), "candidate_max_ms": max(waits[candidate]),
        }
        result["languages"][lang]["first_preview_ms"] = {
            setup: [(row["session"]["eventsNs"]["previewReady"] - row["session"]["eventsNs"]["captureStart"]) / 1e6
                    for run in plan["runs"] if run["setup"] == setup
                    for row in reports[run["id"]]["report"]["endpoints"]
                    if run["expected"]["endpoint_references"][row["id"]]["lang"] == lang and row["text"]]
            for setup in (control, candidate)}
        if changed_texts:
            rejected.append(lang + ": output changed; quality parity not established")
        if plan["kind"] == "gpu-wait" and (
                a <= 0 or saving < limits["absolute_gain_ms"] or saving / a < limits["relative_gain"] or
                saving <= spread or min(paired_savings) <= 0):
            rejected.append(lang + ": practical repeatable Stop gain not established")
    result.update(decision="inconclusive" if control_blockers else "reject" if rejected else "advance",
                  control_blockers=control_blockers, reasons=control_blockers + rejected,
                  cold_load_ms={run["id"]: reports[run["id"]]["report"]["coldLoadMs"] for run in plan["runs"]},
                  next="diagnose the invalid control; reconcile before a new matched comparison" if control_blockers else
                  "diagnose the failed gate" if rejected else
                  "GPU wait comparison" if plan["kind"] == "conversion-parity" else "supervised full-flow validation")
    return result
