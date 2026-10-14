#!/usr/bin/env python3
"""Version 4 diagnostic evaluation. Historical evaluator and thresholds stay intact.

Reports are envelopes: schema=4, provenance, report. Expected provenance and suite
coverage come from the frozen manifest, never inferred from a report filename.
This release cannot grant mechanism/product acceptance or open the recording trigger.
"""
import argparse
import json
import statistics
import math
import re
import evaluate as historical

REVISION = 4

def finite(value):
    return type(value) in (int, float) and math.isfinite(value) and value >= 0

IDENTITY = ("platform", "build_sha256", "runtime_sha256", "evaluator_sha256",
            "inputs_sha256", "references_sha256", "feed", "mode", "timing_protocol")


def evidence_errors(envelope, expected):
    errors = []
    if envelope.get("schema") != REVISION:
        errors.append("report schema is not 4")
    if envelope.get("outcome") != "complete":
        errors.append("run did not complete")
    if envelope.get("failure") or envelope.get("cleanupFailure"):
        errors.append("run failed: " + str(envelope.get("failure") or envelope.get("cleanupFailure")))
    provenance = envelope.get("provenance", {})
    for key in IDENTITY + ("model_sha256", "configuration_sha256", "run_id", "process_start_id"):
        if not provenance.get(key):
            errors.append("missing provenance: " + key)
    for key, value in provenance.items():
        if key.endswith("_sha256") and (not isinstance(value, str) or not re.fullmatch("[0-9a-f]{64}", value)):
            errors.append("invalid hash: " + key)
    for key in IDENTITY + ("model_sha256", "configuration_sha256"):
        if key not in expected.get("provenance", {}):
            errors.append("unfrozen expected provenance: " + key)
    for key, value in expected.get("provenance", {}).items():
        if provenance.get(key) != value:
            errors.append("unexpected provenance: " + key)
    report = envelope.get("report", {})
    for section, id_key in (("takes", "file"), ("guard", "id"), ("endpoints", "id")):
        wanted = expected.get(section)
        if wanted is None:
            continue
        rows = report.get(section, [])
        ids = [r.get(id_key) for r in rows]
        if sorted(ids, key=str) != sorted(wanted):
            errors.append("incomplete or duplicate " + section)
        if any(not isinstance(r.get("text"), str) for r in rows):
            errors.append("missing text: " + section)
    if envelope.get("provenance", {}).get("mode") == "quality-full":
        errors += historical.evidence_errors(report, "quality-full", guard=True)
        for take in report.get("takes", []):
            if any(not isinstance(row.get("text"), str) for row in take.get("jitter", [])):
                errors.append("missing jitter text")
            if [row.get("feed") for row in take.get("jitter", [])] != [512, 800]:
                errors.append("missing jitter feed coverage")
    for row in report.get("endpoints", []):
        ref = expected.get("endpoint_references", {}).get(row.get("id"))
        if not isinstance(ref, dict) or not isinstance(ref.get("reference"), str) or ref.get("lang") not in ("de", "en") or "cut_word" not in ref:
            errors.append("missing or malformed prefix reference: " + str(row.get("id")))
    return errors


def compare(candidate, control, expected_candidate, expected_control):
    errors = evidence_errors(candidate, expected_candidate) + evidence_errors(control, expected_control)
    cp, bp = candidate.get("provenance", {}), control.get("provenance", {})
    errors += ["unmatched control: " + k for k in IDENTITY if cp.get(k) != bp.get(k)]
    measurement_issues = measurement_errors(candidate) + measurement_errors(control)
    result = {"evaluator_revision": REVISION, "evidence": "incomplete" if errors or measurement_issues else "complete",
              "evidence_errors": errors + measurement_issues, "quality": {"status": "incomplete"},
              "resources": resources(candidate), "acceptance": "diagnostic-only",
              "recording_trigger": "unchanged; not evaluated"}
    if errors:
        return result
    cr, br = candidate["report"], control["report"]
    # Resource failures cannot suppress quality scoring. Prefixes are never full takes.
    if cr.get("takes") and br.get("takes"):
        repetitions = sorted({row.get("repetition", 0) for row in cr["takes"]})
        scores = []
        for repetition in repetitions:
            candidate_take_report = dict(cr, takes=[r for r in cr["takes"] if r.get("repetition", 0) == repetition])
            control_take_report = dict(br, takes=[r for r in br["takes"] if r.get("repetition", 0) == repetition])
            cs = historical.summary(candidate_take_report, historical.score_takes(candidate_take_report))
            bs = historical.summary(control_take_report, historical.score_takes(control_take_report))
            scores.append({"repetition": repetition, "candidate": cs, "control": bs,
                "delta_errors": {lang: cs[lang + "_errors"] - bs[lang + "_errors"] for lang in ("de", "en")}})
        result["quality"] = {"status": "scored", "repetitions": scores}
    result["endpoints"] = endpoint_checks(cr.get("endpoints", []), expected_candidate.get("endpoint_references", {}))
    return result


def measurement_errors(envelope):
    report = envelope.get("report", {})
    mode = envelope.get("provenance", {}).get("mode")
    if mode == "quality-full":
        return []
    if mode == "historical-full":
        _, reasons, _, _ = historical.phone_check(report)
        return [reason for reason in reasons if any(word in reason for word in ("missing", "invalid", "None"))]
    errors = []
    memory = report.get("memory", [])
    phases = {row.get("phase") for row in memory}
    if not {"before-load", "after-load", "after-warmup", "buffer-loaded", "buffer-released", "recognizer-released"} <= phases:
        errors.append("incomplete memory phases")
    for row in memory:
        if any(not finite(row.get(key)) or row.get(key) == 0 for key in ("rssKb", "highWaterKb", "pssKb")):
            errors.append("invalid memory reading")
        if envelope.get("provenance", {}).get("platform") == "phone" and any(not finite(row.get(key)) for key in ("javaUsedBytes", "nativeAllocatedBytes")):
            errors.append("missing Android allocation counters")
    if not finite(report.get("coldLoadMs")):
        errors.append("missing cold load")
    for row in report.get("takes", []) + report.get("endpoints", []):
        if not finite(row.get("stopToFinalMs")):
            errors.append("missing or invalid final wait")
        if type(row.get("inputSamples")) is not int or row["inputSamples"] <= 0 or row.get("coverageSamples") != row["inputSamples"]:
            errors.append("incomplete audio coverage")
        state = row.get("stopState", {})
        if type(state.get("active")) is not bool or type(state.get("queued")) is not int or state["queued"] < 0:
            errors.append("missing Stop decoder state")
        if type(row.get("residualSamples")) is not int or row["residualSamples"] < 0:
            errors.append("missing residual length")
    return errors


def endpoint_checks(rows, references):
    checked = []
    for row in rows:
        reference = references.get(row.get("id"))
        item = dict(row)
        if reference is None:
            item["qualityEvidence"] = "incomplete: missing prefix reference"
        else:
            ref = historical.score.normalise(reference["reference"], reference["lang"])
            hyp = historical.score.normalise(row["text"], reference["lang"])
            item.update(qualityEvidence="scored", prefixErrors=historical.score.errors(ref, hyp),
                        missingFinalOutput=bool(ref) and not hyp, tailMatches=hyp[-3:] == ref[-3:],
                        adjacentDuplicateWords=sum(a == b for a, b in zip(hyp, hyp[1:])),
                        cutWord=reference.get("cut_word"))
        checked.append(item)
    return checked


def resources(envelope):
    report = envelope.get("report", {})
    mode = envelope.get("provenance", {}).get("mode")
    if mode == "historical-full":
        verdict, reasons, flags, values = historical.phone_check(report)
        return {"status": verdict, "historical_gate": True, "reasons": reasons, "flags": flags, **values}
    waits = [r.get("stopToFinalMs") for r in report.get("endpoints", [])]
    valid = [w for w in waits if finite(w)]
    memory = report.get("memory", [])
    return {"status": "diagnostic", "historical_gate": False,
            "memory": memory, "waits_ms": waits,
            "wait_median_ms": statistics.median(valid) if valid else None,
            "wait_max_ms": max(valid) if valid else None,
            "complete_waits": len(valid) == len(waits) and bool(waits)}


def dictionary_twin_errors(candidate, twin):
    """A future acceptance caller must also require complete suite evidence."""
    cp, tp = candidate.get("provenance", {}), twin.get("provenance", {})
    errors = ["unmatched twin: " + k for k in IDENTITY + ("model_sha256",)
              if not cp.get(k) or cp.get(k) != tp.get(k)]
    if not historical.same_pipeline(candidate.get("report", {}).get("variant"),
                                    twin.get("report", {}).get("variant"), dictionary_twin=True):
        errors.append("dictionary is not the sole pipeline difference")
    return errors


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("candidate", "control", "manifest", "output"):
        parser.add_argument(name)
    args = parser.parse_args()
    def read(path):
        with open(path) as source:
            return json.load(source)
    manifest = read(args.manifest)
    output = compare(read(args.candidate), read(args.control), manifest["candidate"], manifest["control"])
    with open(args.output, "x") as destination:
        json.dump(output, destination, indent=2)

if __name__ == "__main__":
    main()
