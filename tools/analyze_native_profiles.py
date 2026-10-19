#!/usr/bin/env python3
"""Finite offline analysis of schema-1 native wall spans and MeasurementRun timelines.

Usage: timeout -s KILL 60s python3 tools/analyze_native_profiles.py MANIFEST --output corpus/…/analysis.json
No corpus search, audio reads, model execution, or transcript printing. Input manifest:
{schema:1, stages:[{id, encoderRole, requiredRoles, cases:[{id, language,
samples, inputSha256, reference?, terms?}], runs:[{id, result, profile,
measurement, configuration}]}]}. Each file descriptor is {path, sha256, bytes};
paths are relative to the manifest. Exactly two stages, U1/P1/P2/U2 per stage.
The four case orders are forward/forward/reverse/reverse. Cases are fixed before
execution; references and terms, if supplied, stay in the private output only.

Numbers ending Ns are wall nanoseconds, never CPU time. Counterfactuals hold
arrivals, queue order, nonencoder work, Stop/flush dependencies fixed; no GPU
prediction. Synthetic tests may import functions without running actual analysis.
"""
import argparse
import bisect
import copy
import hashlib
import json
import math
from pathlib import Path
import re
import signal
import sys
import unicodedata

ROLES = {"parakeet.encoder", "parakeet.decoder", "parakeet.joiner",
         "cohere.encoder", "cohere.decoder", "lid.encoder", "lid.decoder"}
LIMIT = 64 * 1024 * 1024


class Invalid(ValueError):
    """Fixed diagnostics only: never interpolate private source content."""


def require(ok, code):
    if not ok:
        raise Invalid(code)


def integer(value):
    return type(value) is int and value >= 0


def pinned(base, descriptor, limit=LIMIT):
    require(isinstance(descriptor, dict), "missing-file-descriptor")
    size = descriptor.get("bytes")
    require(integer(size) and size <= limit, "file-size-limit")
    path = (base / descriptor["path"]).resolve()
    require(path.is_file() and path.stat().st_size == size, "file-size-mismatch")
    raw = path.read_bytes()
    require(len(raw) == size and hashlib.sha256(raw).hexdigest() == descriptor.get("sha256"),
            "file-hash-mismatch")
    return json.loads(raw)


def union(intervals):
    total, end = 0, -1
    for a, b in sorted(intervals):
        require(integer(a) and integer(b) and b >= a, "invalid-interval")
        total += max(0, b - max(a, end))
        end = max(end, b)
    return total


def normalize(text):
    return re.findall(r"\w+", unicodedata.normalize("NFKC", text).casefold())


def edit_distance(a, b):
    require(len(a) <= 20000 and len(b) <= 20000 and len(a) * len(b) <= 20000000,
            "scoring-work-limit")
    row = list(range(len(b) + 1))
    for i, x in enumerate(a, 1):
        out = [i]
        for j, y in enumerate(b, 1):
            out.append(min(out[-1] + 1, row[j] + 1, row[j - 1] + (x != y)))
        row = out
    return row[-1]


def spans_summary(spans):
    out = {}
    for role in sorted({s["role"] for s in spans}):
        rows = [s for s in spans if s["role"] == role]
        intervals = [(s["startNs"], s["endNs"]) for s in rows]
        summed = sum(b-a for a, b in intervals)
        united = union(intervals)
        out[role] = {"count": len(rows), "sumWallNs": summed, "unionWallNs": united,
                     "overlapNs": summed - united}
    return out


def native_valid(profile, timeline, descriptor, enabled):
    require(profile.get("schema") == 1 and profile.get("clock") == "CLOCK_MONOTONIC",
            "native-schema-clock")
    require(profile.get("enabled") is enabled and profile.get("complete") is True
            and profile.get("valid") is True, "native-incomplete")
    for key in ("dropped", "runFailures", "clockFailures", "sessionDropped", "activeAtFlush"):
        require(type(profile.get(key)) is int and profile[key] == 0, "native-error-drop-inflight")
    spans, sessions = profile.get("spans"), profile.get("sessions")
    require(isinstance(spans, list) and isinstance(sessions, list), "native-missing-records")
    require(profile.get("spanLimit") == 262144 and profile.get("sessionLimit") == 128,
            "native-recorder-limits")
    require(len(spans) <= 262144 and len(sessions) <= 128
            and profile.get("retained") == len(spans) and profile.get("attempted") == len(spans),
            "native-count-mismatch")
    require(enabled or not spans, "control-has-spans")
    mapping = {}
    for row in sessions:
        sid, role = row.get("sessionId"), row.get("role")
        require(integer(sid) and sid > 0 and sid not in mapping and role in ROLES,
                "native-session-map")
        mapping[sid] = role
    require(sorted(mapping) == list(range(1, len(mapping) + 1)), "native-session-sequence")
    clocks = timeline.get("nativeClockSamples", [])
    require([c.get("phase") for c in clocks] ==
            ["beforeModelConstruction", "beforeNativeFlush", "afterNativeFlush"], "clock-samples-missing")
    for row in clocks:
        require(all(integer(row.get(k)) for k in ("beforeNs", "nativeNs", "afterNs"))
                and row["beforeNs"] <= row["nativeNs"] <= row["afterNs"]
                and row.get("valid") is True, "clock-bracket-invalid")
    require(clocks[0]["afterNs"] <= clocks[1]["beforeNs"] <= clocks[2]["beforeNs"],
            "clock-sample-order")
    require(timeline.get("nativeClockAlignment") == "verified-brackets"
            and timeline.get("nativeRoleProfilerRequested") is True
            and timeline.get("nativeAttribution") ==
            ("actual-ort-session-run" if enabled else "disabled-control"), "native-attribution")
    meta = timeline.get("nativeProfile", {})
    require(meta.get("enabled") is enabled and meta.get("complete") is True
            and meta.get("bytes") == descriptor["bytes"]
            and meta.get("sha256") == descriptor["sha256"] and meta.get("byteLimit") == LIMIT,
            "native-file-receipt")
    for row in spans:
        require(row.get("role") in ROLES and mapping.get(row.get("sessionId")) == row["role"],
                "native-role-session-mismatch")
        require(type(row.get("status")) is int and row["status"] == 0
                and integer(row.get("startNs")) and integer(row.get("endNs"))
                and clocks[0]["beforeNs"] <= row["startNs"] <= row["endNs"] <= clocks[1]["afterNs"],
                "native-span-clock-status")
    return spans


def one(events, phase, optional=False):
    rows = [e for e in events if e["phase"] == phase]
    require(len(rows) <= 1 and (optional or len(rows) == 1), "timeline-phase-cardinality")
    return rows[0] if rows else None


def duration(events, start, end, optional=False):
    a, b = one(events, start, optional), one(events, end, optional)
    require((a is None) == (b is None), "timeline-unmatched-phase")
    if a is None:
        return None
    require(b["ns"] >= a["ns"], "timeline-phase-order")
    return b["ns"] - a["ns"]


def fifo(jobs, stop, final, encoder_role):
    """Replay whole observed FIFO incl. pre-Stop encoder and final callback work."""
    require(jobs, "missing-worker-jobs")
    previous_end = -1
    dispatch_gaps = []
    for job in jobs:
        require(job["releaseNs"] <= job["startNs"] <= job["endNs"]
                and job["startNs"] >= previous_end, "worker-serialization")
        dispatch_gaps.append(job["startNs"] - max(job["releaseNs"], previous_end))
        previous_end = job["endNs"]
        require(0 <= job["encoderNs"] <= job["endNs"] - job["startNs"], "encoder-service-overlap")
    service = sum(j["endNs"] - j["startNs"] for j in jobs)
    encoder = sum(j["encoderNs"] for j in jobs)
    require(jobs[-1]["startNs"] <= final <= jobs[-1]["endNs"], "final-outside-finish-worker")
    # Gaps include OS dispatch and observer/executor work outside worker spans.
    # Hold each observed gap fixed after its release/prior-job dependency; this
    # reproduces the observed baseline without assuming scheduling accelerates.
    # finish's callback is synchronous in MeasurementRun; use its actual offset,
    # retaining the trailing worker cleanup in service accounting only.
    def replay(a):
        end = jobs[0]["releaseNs"]
        for i, job in enumerate(jobs):
            work = ((final if i == len(jobs)-1 else job["endNs"]) - job["startNs"])
            scaled_encoder = job["encoderNs"] if a == 1 else job["encoderNs"] / a
            end = max(job["releaseNs"], end) + dispatch_gaps[i] + work - job["encoderNs"] + scaled_encoder
        return end
    baseline = replay(1)
    require(baseline == final, "fifo-baseline-mismatch")
    scenarios = {}
    for a, label in ((2, "2x"), (math.inf, "infinite")):
        end = replay(a)
        wait = max(0, end-stop)
        savings = max(0, baseline-stop) - wait
        require(savings <= min(max(0, baseline-stop), encoder) + 1, "fifo-savings-bound")
        scenarios[label] = {"finalNs": end, "stopWaitNs": wait, "savingsVsReplay1xNs": savings,
                            "serviceSpeedup": service/(service-encoder+encoder/a) if service > encoder or a != math.inf else None}
    return {"encoderRole": encoder_role, "serviceNs": service, "encoderNs": encoder,
            "encoderFraction": encoder/service if service else 0,
            "observedStopWaitNs": max(0, final-stop), "replayed1xFinalNs": baseline,
            "baselineDispatchGapNs": final-baseline,
            "fixedDispatchGapNs": sum(dispatch_gaps), "jobDispatchGapsNs": dispatch_gaps,
            "estimateValid": True, "scenarios": scenarios,
            "limitation": "Observed dispatch/executor gaps held fixed per job; scheduling under acceleration is unknown. No GPU forecast.",
            "scope": "ASR encoder only; LID and all other worker work fixed; wall service excludes capture idle"}


def case_analysis(events, spans, case, encoder_role, enabled):
    capture, stop, final = (one(events, p) for p in ("captureStart", "producerStop", "finalCallback"))
    require(capture["ns"] <= stop["ns"] <= final["ns"], "capture-stop-final-order")
    require(integer(stop.get("scheduledNs")) and stop.get("latenessNs") == stop["ns"]-stop["scheduledNs"],
            "stop-schedule-missing")
    duration(events, "flushStart", "flushEnd")
    duration(events, "finishCall", "finishDrain")
    feeds = [e for e in events if e["phase"] == "feedStart"]
    whole, tail = divmod(case["samples"], 640)
    require([e.get("samples") for e in feeds] == [640]*whole + ([tail] if tail else []),
            "feed-sample-coverage")
    require(all(integer(e.get("scheduledNs")) and e.get("latenessNs") == e["ns"]-e["scheduledNs"]
                for e in feeds), "feed-schedule-missing")
    jobs = []
    for event in events:
        phase = event["phase"]
        if phase not in ("chunkEnqueue", "finishEnqueue"):
            continue
        kind = "chunk" if phase == "chunkEnqueue" else "finish"
        scoped = [e for e in events if e.get("chunkId") == event.get("chunkId")]
        start, end = one(scoped, kind+"WorkerStart"), one(scoped, kind+"WorkerEnd")
        jobs.append({"releaseNs": event["ns"], "startNs": start["ns"], "endNs": end["ns"],
                     "encoderNs": 0, "kind": kind})
    require(jobs and jobs[-1]["kind"] == "finish" and sum(j["kind"] == "finish" for j in jobs) == 1,
            "missing-finish-job")
    require([j["startNs"] for j in jobs] == sorted(j["startNs"] for j in jobs), "fifo-order")
    # Validate all worker intervals, even controls, before interval attribution.
    fifo(jobs, stop["ns"], final["ns"], encoder_role)
    starts = [j["startNs"] for j in jobs]
    recognition_intervals, lid_intervals = [], []
    for e in events:
        if e["phase"] == "recognitionStart":
            scoped = [x for x in events if x.get("chunkId") == e.get("chunkId")]
            end = one(scoped, "recognitionEnd")
            require(end["ns"] >= e["ns"], "recognition-interval")
            recognition_intervals.append((e["ns"], end["ns"]))
    lid_a, lid_b = one(events, "lidStart", True), one(events, "lidEnd", True)
    require((lid_a is None) == (lid_b is None), "lid-unmatched")
    if lid_a:
        require(lid_a["ns"] <= lid_b["ns"], "lid-order")
        lid_intervals.append((lid_a["ns"], lid_b["ns"]))
    require(len([e for e in events if e["phase"] == "recognitionEnd"]) == len(recognition_intervals),
            "recognition-unmatched")
    assigned = []
    for span in spans:
        a, b = span["startNs"], span["endNs"]
        if b <= capture["ns"] or a >= jobs[-1]["endNs"]:
            continue
        index = bisect.bisect_right(starts, a)-1
        require(index >= 0 and b <= jobs[index]["endNs"], "native-outside-worker")
        windows = lid_intervals if span["role"].startswith("lid.") else recognition_intervals
        require(any(x <= a <= b <= y for x, y in windows), "native-jvm-role-overlap")
        assigned.append(span)
        if span["role"] == encoder_role:
            jobs[index]["encoderNs"] += b-a
    intervals = [(s["startNs"], s["endNs"]) for s in assigned]
    require(sum(b-a for a, b in intervals) == union(intervals), "overlapping-native-service")
    replay = fifo(jobs, stop["ns"], final["ns"], encoder_role) if enabled else None
    first = one(events, "firstUsablePreview", True)
    decision = one(events, "lidDecision", True)
    missing = [p for p in ("firstUsablePreview", "firstClosedChunkGate") if one(events, p, True) is None]
    if not lid_a:
        missing += ["lidStart/lidEnd/lidDecision (absent or not applicable)"]
    return {"language": case["language"], "roles": spans_summary(assigned),
            "serviceNs": sum(j["endNs"]-j["startNs"] for j in jobs),
            "recognitionWallNs": union(recognition_intervals), "lidComputeNs": duration(events, "lidStart", "lidEnd", True),
            "lidDecision": {k: decision.get(k) for k in ("language", "fallback")} if decision else None,
            "captureToFirstUsableNs": first["ns"]-capture["ns"] if first else None,
            "scheduledStopNs": stop["scheduledNs"], "actualStopNs": stop["ns"],
            "producerStopLatenessNs": stop["latenessNs"],
            "maxFeedLatenessNs": max((e["latenessNs"] for e in feeds), default=0),
            "finalWaitNs": final["ns"]-stop["ns"], "flushNs": duration(events,"flushStart","flushEnd"),
            "fifo": replay, "missingData": missing}, assigned


def semantic_receipt(events, row, case, config):
    """Observed sample/routing values only; clock/queue timing is deliberately absent."""
    ready = [e for e in events if e["phase"] == "chunkReady"]
    require(ready and all(integer(e.get("chunkId")) and integer(e.get("samples"))
                         and e["samples"] > 0 for e in ready), "chunk-coverage-missing")
    sizes = {e["chunkId"]: e["samples"] for e in ready}
    require(len(sizes) == len(ready) and sum(sizes.values()) == case["samples"], "chunk-coverage-invalid")
    enqueued = [e.get("chunkId") for e in events if e["phase"] == "chunkEnqueue"]
    require(enqueued == [e["chunkId"] for e in ready], "chunk-enqueue-coverage")
    recognition = [e for e in events if e["phase"] == "recognitionStart"]
    require(len({e.get("chunkId") for e in recognition}) == len(recognition), "recognition-coverage-invalid")
    for e in recognition:
        end = one([x for x in events if x.get("chunkId") == e.get("chunkId")], "recognitionEnd")
        require(e.get("samples") == sizes.get(e.get("chunkId")) == end.get("samples"),
                "recognition-sample-mismatch")
    chunks = row.get("chunks")
    require(isinstance(chunks, list) and len(chunks) == len(recognition), "reported-chunks-missing")
    require(all(type(c.get("audioSeconds")) in (int, float)
                and c["audioSeconds"] == e["samples"]/16000 for c, e in zip(chunks, recognition)),
            "reported-chunk-sample-mismatch")
    residual = row.get("residualSamples")
    require(integer(residual) and residual <= case["samples"], "residual-samples-missing")
    stop = one(events, "producerStop")
    require(residual == case["samples"] - sum(e["samples"] for e in ready if e["ns"] <= stop["ns"]),
            "residual-sample-mismatch")
    gate = one(events, "firstClosedChunkGate", True)
    if recognition:
        require(gate is not None and gate.get("chunkId") == recognition[0]["chunkId"]
                and gate.get("samples") == recognition[0]["samples"], "opening-samples-missing")
    route = []
    if config.get("lid", {}).get("model"):
        for phase in ("lidStart", "lidEnd", "lidDecision"):
            e = one(events, phase)
            require(integer(e.get("samples")) and e["samples"] > 0, "routing-samples-missing")
            if config["lid"].get("probe_seconds") == 0:
                require(gate is not None and e["samples"] == gate["samples"], "full-opening-lid-mismatch")
            route.append({k: e[k] for k in ("phase", "samples")})
        require(len({e["samples"] for e in route}) == 1, "routing-sample-mismatch")
        decision = one(events, "lidDecision")
        require(isinstance(decision.get("language"), str) and bool(decision["language"])
                and type(decision.get("fallback")) is bool, "routing-decision-missing")
        route[-1].update(language=decision["language"], fallback=decision["fallback"])
    else:
        require(not any(e["phase"] in ("lidStart", "lidEnd", "lidDecision") for e in events),
                "unexpected-routing-events")
    return {"routing": route, "coverage": {
        "feeds": [e["samples"] for e in events if e["phase"] == "feedStart"],
        "chunks": [[e["chunkId"], e["samples"]] for e in ready],
        "recognized": [[e["chunkId"], e["samples"]] for e in recognition],
        "opening": [gate["chunkId"], gate["samples"]] if gate else None,
        "residualSamples": residual}}


def monotonic_drift(values):
    """Frozen zero-tolerance rule: nonconstant monotonic thermal sequence."""
    return len(set(values)) > 1 and (all(a <= b for a, b in zip(values, values[1:]))
                                   or all(a >= b for a, b in zip(values, values[1:])))


def thermal_signature(boundaries):
    """Compare required producer states; retain every observed state transition."""
    required = {"before-load", "after-load", "after-warmup", "buffer-loaded",
                "after-chunk", "buffer-released", "recognizer-released", "profile-flushed"}
    states = [status for i, (_, status) in enumerate(boundaries)
              if i == 0 or status != boundaries[i-1][1]]
    return [row for row in boundaries if row[0] in required], states


def config_without_profile(config):
    value = copy.deepcopy(config)
    require(type(value.get("runtime", {}).get("profile")) is bool, "configuration-profile-missing")
    value["runtime"].pop("profile")
    return value


def analyze_run(base, spec, cases, encoder_role, required_roles):
    enabled = spec["id"].startswith("P")
    result = pinned(base, spec["result"])
    measurement = pinned(base, spec["measurement"], 2*1024*1024)
    config = pinned(base, spec["configuration"], 2*1024*1024)
    profile = pinned(base, spec["profile"])
    require(config["runtime"]["profile"] is enabled, "profile-config-mismatch")
    require(result.get("schema") == 4 and result.get("outcome") == "complete", "result-incomplete")
    require(measurement.get("repetitions", 1) == 1 and measurement.get("mode") == "paced-only", "measurement-mode")
    provenance, report = result["provenance"], result["report"]
    require(report["variant"] == config, "variant-report-mismatch")
    require(provenance.get("configuration_sha256") == spec["configuration"]["sha256"], "configuration-hash-mismatch")
    for key, value in measurement["provenance"].items():
        require(provenance.get(key) == value, "provenance-mismatch")
    for key in ("build_sha256", "test_build_sha256"):
        require(re.fullmatch(r"[0-9a-f]{64}", provenance.get(key, "")) is not None, "build-identity-missing")
    require(provenance.get("platform") == "phone" and provenance.get("feed") == 640
            and provenance.get("mode") == "paced-only", "report-mode")
    require(provenance.get("run_id") and provenance.get("process_start_id"), "process-identity-missing")
    inputs = measurement["inputs"]
    expected = cases if spec["id"].endswith("1") else list(reversed(cases))
    require([x["id"] for x in inputs] == [x["id"] for x in expected], "case-order-mismatch")
    for item, case in zip(inputs, expected):
        require(item["sha256"] == case["inputSha256"], "input-identity-mismatch")
        require(item.get("stop_sample", case["samples"]) == case["samples"], "input-stop-mismatch")
    # Device-observed receipts, not just the intended manifest inventory. These
    # maps are keyed by the exact lab-relative paths used by MeasurementRun.
    require(report.get("verifiedArtifacts") == measurement["artifacts"],
            "observed-artifact-mismatch-or-missing")
    expected_inputs = {}
    for item in inputs:
        require(item["path"] not in expected_inputs or expected_inputs[item["path"]] == item["sha256"],
                "conflicting-input-path-hash")
        expected_inputs[item["path"]] = item["sha256"]
    require(report.get("verifiedInputs") == expected_inputs,
            "observed-input-mismatch-or-missing")
    for observed, envelope in (("buildSha256", "build_sha256"),
                               ("testBuildSha256", "test_build_sha256"),
                               ("runId", "run_id"), ("processStartId", "process_start_id")):
        require(report.get(observed) == provenance[envelope],
                "observed-identity-mismatch-or-missing")
    artifacts = dict(measurement["artifacts"])
    require(artifacts.pop("variant.json", None) == spec["configuration"]["sha256"] and artifacts,
            "artifact-inventory-missing")
    timeline = report["timeline"]
    events = timeline["events"]
    require(timeline.get("clock") == "CLOCK_MONOTONIC" and timeline.get("valid") is True
            and type(timeline.get("droppedEvents")) is int and timeline["droppedEvents"] == 0
            and timeline.get("eventLimit") == 65536 and len(events) <= 65536, "timeline-overflow-invalid")
    require(all(integer(e.get("ns")) and isinstance(e.get("phase"), str) for e in events), "event-invalid")
    # Recorder calls from different threads can append slightly out of timestamp order.
    events = sorted(events, key=lambda e: e["ns"])
    require(all(e.get("repetition", 0) == 0 and ("inputIndex" not in e or
            integer(e["inputIndex"]) and e["inputIndex"] < len(cases)) for e in events), "event-scope-invalid")
    spans = native_valid(profile, timeline, spec["profile"], enabled)
    family = encoder_role.split(".")[0]
    allowed = {role for role in ROLES if role.startswith(family+".")}
    if config.get("lid", {}).get("model"):
        allowed |= {"lid.encoder", "lid.decoder"}
    require(all(s["role"] in allowed for s in profile["sessions"]), "unexpected-configured-role")
    rows = report["takes"] + report["endpoints"]
    require(len(rows) == len(cases) and len({r["id"] for r in rows}) == len(cases), "result-case-coverage")
    rows = {r["id"]: r for r in rows}
    outputs, texts, assigned_all = {}, {}, []
    for i, case in enumerate(expected):
        row = rows[case["id"]]
        require(row.get("repetition") == 0 and row.get("inputSamples") == case["samples"]
                and row.get("coverageSamples") == case["samples"] and row.get("missingFinalOutput") is False
                and isinstance(row.get("text"), str), "sample-output-incomplete")
        scoped = [e for e in events if e.get("inputIndex") == i]
        out, assigned = case_analysis(scoped, spans, case, encoder_role, enabled)
        require(abs(out["finalWaitNs"]/1e6-row["stopToFinalMs"]) <= 5, "stop-report-timeline-mismatch")
        out["semanticReceipt"] = semantic_receipt(scoped, row, case, config)
        texts[case["id"]] = normalize(row["text"])
        if "reference" in case:
            ref = normalize(case["reference"])
            out["quality"] = {"wordErrors": edit_distance(ref, texts[case["id"]]), "referenceWords": len(ref),
                              "terms": [{"present": " ".join(normalize(t)) in " ".join(texts[case["id"]])}
                                        for t in case.get("terms", [])]}
        else:
            out["missingData"].append("reference/term scoring not supplied")
        outputs[case["id"]] = out
        assigned_all.extend(assigned)
    global_events = [e for e in events if "inputIndex" not in e]
    warm_a, warm_b = one(global_events,"silenceWarmupStart"), one(global_events,"silenceWarmupEnd")
    unassigned = [s for s in spans if not (warm_a["ns"] <= s["startNs"] <= s["endNs"] <= warm_b["ns"])]
    require(len(unassigned) == len(assigned_all), "unattributed-native-spans")
    observed = {s["role"] for s in assigned_all}
    require(not enabled or set(required_roles) <= observed, "required-role-missing")
    memory = report.get("memory", [])
    require(memory and all(integer(m.get("highWaterKb")) and integer(m.get("thermalStatus"))
                           and integer(m.get("elapsedNs")) and isinstance(m.get("phase"), str) for m in memory),
            "memory-thermal-missing")
    require([m["elapsedNs"] for m in memory] == sorted(m["elapsedNs"] for m in memory),
            "memory-boundary-order")
    phases = [m["phase"] for m in memory]
    require(phases[:3] == ["before-load", "after-load", "after-warmup"]
            and phases[-2:] == ["recognizer-released", "profile-flushed"]
            and phases.count("buffer-loaded") == len(cases)
            and phases.count("buffer-released") == len(cases)
            and phases.count("after-chunk") == sum(len(c["semanticReceipt"]["coverage"]["recognized"]) for c in outputs.values()),
            "memory-thermal-boundaries-missing")
    result_out = {"cases": outputs, "global": {
        "modelConstructionNs": duration(global_events,"modelConstructionStart","modelConstructionEnd"),
        "silenceWarmupNs": warm_b["ns"]-warm_a["ns"],
        "lidLoadNs": duration(global_events,"lidLoadStart","lidReady",True),
        "asrLoadNs": duration(global_events,"asrLoadStart","asrLoadEnd",True),
        "asrReadyNs": one(global_events,"asrReady",True),
        "coldLoadMs": report.get("coldLoadMs"), "peakHighWaterKb": max(m["highWaterKb"] for m in memory),
        "maxThermalStatus": max(m["thermalStatus"] for m in memory),
        "thermalBoundaries": [[m["phase"], m["thermalStatus"]] for m in memory],
        "thermalDrift": monotonic_drift([m["thermalStatus"] for m in memory])}, "valid": True}
    identity = {"config": config_without_profile(config), "artifacts": artifacts,
                "provenance": measurement["provenance"]}
    return result_out, texts, identity, provenance["run_id"], provenance["process_start_id"]


def analyze(manifest, base, selected_stage=None):
    require(manifest.get("schema") == 1 and len(manifest.get("stages", [])) == 2, "manifest-stage-count")
    output = {"schema": 1, "valid": True, "stages": [], "limitations": [
        "Ordinary English/German recordings and eight dictionary scripts: known vocabulary, not held out.",
        "Wall spans are not CPU time. No GPU forecast; transfers, CPU contention and scheduling may change.",
        "Fixed-arrival ASR-encoder replay preserves LID, load, warmup, producer lateness, Stop and flush releases.",
        "Role unions must not be added across overlapping scopes. LID is reported separately on the worker critical path.",
        "Profiler perturbation limits transfer to unprofiled execution. Observed per-job dispatch/executor gaps are fixed; their behavior under acceleration is unknown."]}
    run_ids, process_ids = set(), set()
    require([s["id"] for s in manifest["stages"]] == ["A", "B"], "manifest-stage-order")
    for stage in manifest["stages"]:
        if selected_stage and stage["id"] != selected_stage:
            output["stages"].append({"id": stage["id"], "status": "outside-selected-stage",
                                     "eligibleForNextStage": False})
            continue
        require(stage["encoderRole"] in ("parakeet.encoder", "cohere.encoder")
                and set(stage["requiredRoles"]) <= ROLES
                and stage["encoderRole"] in stage["requiredRoles"], "stage-role-invalid")
        cases = stage["cases"]
        require(len(cases) == 10 and len({c["id"] for c in cases}) == 10
                and all(c["language"] in ("de", "en") and integer(c["samples"]) and c["samples"] > 0 for c in cases),
                "manifest-case-count")
        require([r["id"] for r in stage["runs"]] == ["U1", "P1", "P2", "U2"], "run-order-invalid")
        results, texts, identities, run_errors = {}, {}, [], {}
        for run in stage["runs"]:
            status = run.get("status", "complete")
            require(status in ("complete", "failed", "not-executed"), "run-status-invalid")
            if status != "complete":
                run_errors[run["id"]] = status
                continue
            try:
                out, text, identity, rid, pid = analyze_run(base, run, cases, stage["encoderRole"], stage["requiredRoles"])
                require(rid not in run_ids and pid not in process_ids, "duplicate-process-run")
                run_ids.add(rid); process_ids.add(pid)
                results[run["id"]] = out; texts[run["id"]] = text; identities.append(identity)
            except Invalid as error:
                if str(error) == "analysis-deadline":
                    raise
                run_errors[run["id"]] = str(error)
            except (KeyError, TypeError, ValueError, OSError, OverflowError, RecursionError):
                run_errors[run["id"]] = "invalid-or-missing-file-fields"
        stage_errors = []
        if identities and not all(i == identities[0] for i in identities):
            stage_errors.append("paired-identity-mismatch")
        pairs = []
        for u, p in (("U1", "P1"), ("U2", "P2")):
            if u not in results or p not in results:
                pairs.append({"control": u, "profiled": p, "valid": False, "missingData": "incomplete-pair"})
                continue
            warnings = []
            parity = True
            semantic_parity = True
            for case in cases:
                cid = case["id"]
                matches = texts[u][cid] == texts[p][cid]
                parity = parity and matches
                a, b = results[u]["cases"][cid], results[p]["cases"][cid]
                semantic_matches = a["semanticReceipt"] == b["semanticReceipt"]
                semantic_parity = semantic_parity and semantic_matches
                recognition_delta = (b["recognitionWallNs"]-a["recognitionWallNs"])/a["recognitionWallNs"] if a["recognitionWallNs"] else None
                stop_delta = b["finalWaitNs"]-a["finalWaitNs"]
                warn = recognition_delta is None or abs(recognition_delta) > .1 or abs(stop_delta) > 100000000
                b["perturbation"] = {"recognitionRelativeDelta": recognition_delta,
                                     "stopWaitDeltaNs": stop_delta, "transferable": not warn,
                                     "normalizedOutputParity": matches, "routingCoverageParity": semantic_matches}
                if warn: warnings.append(cid)
            ug, pg = results[u]["global"], results[p]["global"]
            memory_delta = (pg["peakHighWaterKb"]-ug["peakHighWaterKb"])*1024
            if not parity: stage_errors.append("paired-output-mismatch")
            if not semantic_parity: stage_errors.append("paired-routing-coverage-mismatch")
            thermal_valid = (thermal_signature(ug["thermalBoundaries"]) == thermal_signature(pg["thermalBoundaries"])
                             and not ug["thermalDrift"] and not pg["thermalDrift"])
            if not thermal_valid: stage_errors.append("paired-thermal-mismatch-or-drift")
            if memory_delta > 256*1024*1024: stage_errors.append("profiling-memory-delta-exceeded")
            pairs.append({"control": u, "profiled": p, "valid": parity and semantic_parity and thermal_valid and memory_delta <= 256*1024*1024,
                          "timingNontransferableCases": warnings,
                          "peakMemoryDeltaBytes": memory_delta,
                          "coldLoadDeltaMs": pg["coldLoadMs"]-ug["coldLoadMs"],
                          "warmupDeltaNs": pg["silenceWarmupNs"]-ug["silenceWarmupNs"],
                          "thermalStatusDelta": pg["maxThermalStatus"]-ug["maxThermalStatus"]})
        languages = {}
        for run_id, run in results.items():
            languages[run_id] = {}
            for language in ("de", "en"):
                rows = [c for c in run["cases"].values() if c["language"] == language]
                roles = {}
                for row in rows:
                    for role, values in row["roles"].items():
                        dst = roles.setdefault(role, {"count": 0, "sumWallNs": 0, "unionWallNs": 0, "overlapNs": 0})
                        for key in dst: dst[key] += values[key]
                service = sum(r["serviceNs"] for r in rows)
                for values in roles.values(): values["serviceWallFraction"] = values["unionWallNs"]/service if service else None
                languages[run_id][language] = {"cases": len(rows), "serviceNs": service, "roles": roles,
                    "recognitionWallNs": sum(r["recognitionWallNs"] for r in rows),
                    "lidComputeNs": sum(r["lidComputeNs"] or 0 for r in rows),
                    "finalWaitNs": sum(r["finalWaitNs"] for r in rows),
                    "wordErrors": sum(r["quality"]["wordErrors"] for r in rows if "quality" in r),
                    "referenceWords": sum(r["quality"]["referenceWords"] for r in rows if "quality" in r),
                    "scoredCases": sum("quality" in r for r in rows)}
        if len(results) == 4 and monotonic_drift([
                boundary[1] for rid in ("U1", "P1", "P2", "U2")
                for boundary in results[rid]["global"]["thermalBoundaries"]]):
            stage_errors.append("process-order-thermal-drift")
        complete = len(results) == 4 and not run_errors and not stage_errors
        transferable = complete and all(not p["timingNontransferableCases"] for p in pairs)
        output["stages"].append({"id": stage["id"], "status": "complete" if complete else "incomplete-or-invalid",
            "eligibleForNextStage": transferable, "timingsTransferable": transferable,
            "runs": results, "pairs": pairs, "languages": languages,
            "missingOrInvalidRuns": run_errors, "errors": stage_errors})
        output["valid"] = output["valid"] and complete
    return output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--stage", choices=("A",), help="Analyze only A; B remains explicit and unexecuted")
    args = parser.parse_args()
    # Internal deadline complements the operator's required external hard KILL.
    signal.signal(signal.SIGALRM, lambda *_: (_ for _ in ()).throw(Invalid("analysis-deadline")))
    signal.alarm(55)
    try:
        require(args.manifest.stat().st_size <= 2*1024*1024, "manifest-size-limit")
        manifest = json.loads(args.manifest.read_text())
        result = analyze(manifest, args.manifest.resolve().parent, args.stage)
        code = 0 if result["valid"] else 1
    except (Invalid, KeyError, TypeError, ValueError, OSError, OverflowError, RecursionError):
        # Detailed source exceptions can contain transcripts or private paths.
        result = {"schema": 1, "valid": False, "error": "invalid-or-missing-input; analysis withheld"}
        code = 1
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2, allow_nan=False)+"\n")
    print("native analysis: valid" if result["valid"] else "native analysis: invalid")
    return code


if __name__ == "__main__":
    sys.exit(main())
