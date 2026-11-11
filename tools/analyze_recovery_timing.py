#!/usr/bin/env python3
"""Summarize metadata-only recovery receipts; never reads audio or transcript content."""
from __future__ import annotations
import argparse
import json
import statistics
from pathlib import Path

SAMPLE_RATE = 16_000
FORBIDDEN = {"text", "transcript", "audio", "pcm", "editor", "editorinfo", "content"}


def _validate(row: object) -> dict:
    if not isinstance(row, dict):
        raise ValueError("receipt must be an object")
    if any(str(key).lower() in FORBIDDEN for key in row):
        raise ValueError("receipt contains a forbidden content field")
    if row.get("protocol") != "recovery-timing-v1" or row.get("clock") != "CLOCK_MONOTONIC":
        raise ValueError("unsupported timing receipt protocol or clock")
    for key in ("takeId", "processId", "phase"):
        if not isinstance(row.get(key), str):
            raise ValueError(f"missing {key}")
    ns = row.get("ns")
    if not isinstance(ns, int) or isinstance(ns, bool) or ns < 0:
        raise ValueError("invalid monotonic timestamp")
    for key in ("sampleStart", "sampleEnd", "queued", "revision"):
        if key in row and (not isinstance(row[key], int) or isinstance(row[key], bool) or row[key] < 0):
            raise ValueError(f"invalid {key}")
    if "fixtureId" in row and not isinstance(row["fixtureId"], str):
        raise ValueError("invalid fixtureId")
    return row


def _range(values: list[float]) -> dict:
    if not values:
        return {"count": 0, "min": None, "median": None, "max": None}
    return {"count": len(values), "min": round(min(values), 2),
            "median": round(statistics.median(values), 2), "max": round(max(values), 2)}


def _sample_time(offset: int, anchors: list[dict]) -> int | None:
    if not anchors:
        return None
    anchors = sorted(anchors, key=lambda e: e["sampleEnd"])
    if offset <= anchors[0]["sampleEnd"]:
        anchor = anchors[0]
        return anchor["ns"] - (anchor["sampleEnd"] - offset) * 1_000_000_000 // SAMPLE_RATE
    for left, right in zip(anchors, anchors[1:]):
        a, b = left["sampleEnd"], right["sampleEnd"]
        if a <= offset <= b and b > a:
            return left["ns"] + (right["ns"] - left["ns"]) * (offset - a) // (b - a)
    anchor = anchors[-1]
    return anchor["ns"] + (offset - anchor["sampleEnd"]) * 1_000_000_000 // SAMPLE_RATE


def analyze(path: Path) -> dict:
    groups: dict[tuple[str, str], list[dict]] = {}
    for line_number, line in enumerate(path.read_text().splitlines(), 1):
        if not line.strip():
            continue
        try:
            row = _validate(json.loads(line))
        except (json.JSONDecodeError, ValueError) as exc:
            raise ValueError(f"invalid receipt at line {line_number}: {exc}") from exc
        groups.setdefault((row["processId"], row["takeId"]), []).append(row)

    takes = []
    for (process_id, take_id), events in sorted(groups.items()):
        events.sort(key=lambda e: e["ns"])
        phase: dict[str, list[dict]] = {}
        for event in events:
            phase.setdefault(event["phase"], []).append(event)
        stops = phase.get("stopRequested", [])
        finals = phase.get("finalReady", [])
        terminals = phase.get("takeEnded", [])
        anchors = [e for e in phase.get("captureSampleAnchor", []) if "sampleEnd" in e]
        stop_anchors = [e for e in phase.get("captureStopAnchor", []) if "sampleEnd" in e]
        source_anchors = anchors  # callback timestamps anchor sample time; stop ack is a count boundary only
        source_ends = [e for e in phase.get("chunkReady", []) if "sampleEnd" in e]
        queued = {e.get("revision"): e for e in phase.get("checkpointQueued", [])}
        durable = phase.get("checkpointDurable", [])
        failed = phase.get("checkpointFailed", [])
        # Reconstruct the frontier in receipt order; preloading the final offset would
        # falsely attribute future captured audio to earlier backlog measurements.
        captured_end = 0
        latest_frontier = 0
        newly_oldest_lags = []
        newly_newest_lags = []
        checkpoint_queue_lags = []
        chunk_to_durable_lags = []
        max_risk_samples = 0
        for event in events:
            if event["phase"] in ("captureSampleAnchor", "captureStopAnchor") and "sampleEnd" in event:
                captured_end = max(captured_end, event["sampleEnd"])
                max_risk_samples = max(max_risk_samples, captured_end - latest_frontier)
            elif event["phase"] == "checkpointDurable" and "sampleEnd" in event:
                offset = event["sampleEnd"]
                oldest_time = _sample_time(latest_frontier, source_anchors)
                newest_time = _sample_time(offset, source_anchors)
                if oldest_time is not None:
                    newly_oldest_lags.append((event["ns"] - oldest_time) / 1e6)
                if newest_time is not None:
                    newly_newest_lags.append((event["ns"] - newest_time) / 1e6)
                request = queued.get(event.get("revision"))
                if request is not None:
                    checkpoint_queue_lags.append((event["ns"] - request["ns"]) / 1e6)
                earlier = [c for c in source_ends if c["sampleEnd"] <= offset]
                if earlier:
                    latest_chunk = max(earlier, key=lambda c: c["sampleEnd"])
                    chunk_to_durable_lags.append((event["ns"] - latest_chunk["ns"]) / 1e6)
                latest_frontier = max(latest_frontier, offset)
                max_risk_samples = max(max_risk_samples, captured_end - latest_frontier)
        stop_state = phase.get("producerStop", [{}])[0]
        stop_ns = stops[0]["ns"] if stops else None
        final_ns = finals[0]["ns"] if finals else None
        terminal_ns = terminals[-1]["ns"] if terminals else None
        complete = bool(anchors and stop_anchors and stops and finals and terminals)
        fixture_ids = [e["fixtureId"] for e in phase.get("scenario", []) if e.get("fixtureId")]
        first_durable = min(durable, key=lambda e: e.get("sampleEnd", 0), default=None)
        audio_start_ns = _sample_time(0, source_anchors)
        takes.append({
            "take": len(takes) + 1,
            "fixtureId": fixture_ids[0] if fixture_ids else "unspecified",
            "status": "complete" if complete else "incomplete_receipts",
            "firstSampleAnchorNs": anchors[0]["ns"] if anchors else None,
            "firstChunkMsFromFirstSample": round((source_ends[0]["ns"] - anchors[0]["ns"]) / 1e6, 2) if source_ends and anchors else None,
            "initialZeroCheckpointIntervalMs": round((first_durable["ns"] - audio_start_ns) / 1e6, 2) if first_durable and audio_start_ns is not None else None,
            "zeroCheckpointObservedThroughMs": round((terminal_ns - audio_start_ns) / 1e6, 2) if complete and not durable and audio_start_ns is not None else None,
            "firstDurableSourceSec": round(min(durable, key=lambda e: e.get("sampleEnd", 0))["sampleEnd"] / SAMPLE_RATE, 3) if durable else None,
            "oldestNewlyDurableAudioLagMs": _range(newly_oldest_lags),
            "newestNewlyDurableAudioLagMs": _range(newly_newest_lags),
            "chunkToDurableLagMs": _range(chunk_to_durable_lags),
            "checkpointQueueLagMs": _range(checkpoint_queue_lags),
            "durableCheckpointCount": len(durable),
            "failedCheckpointCount": len(failed),
            "zeroCheckpoint": not durable if complete else None,
            "durableSourceFrontierSec": round(latest_frontier / SAMPLE_RATE, 3),
            "atRiskBacklogMaxSec": round(max_risk_samples / SAMPLE_RATE, 3) if complete else None,
            "atRiskBacklogAtStopSec": round(max(0, (captured_end - latest_frontier) / SAMPLE_RATE), 3) if complete else None,
            "decodeStopWaitMs": round((final_ns - stop_ns) / 1e6, 2) if final_ns is not None and stop_ns is not None else None,
            "terminalStopWaitMs": round((terminal_ns - stop_ns) / 1e6, 2) if terminal_ns is not None and stop_ns is not None else None,
            "maxDecodeQueue": max((e.get("queued", 0) for e in events if e["phase"] in ("chunkEnqueue", "finishEnqueue")), default=0),
            "activeAtStop": stop_state.get("active"),
            "queuedAtStop": stop_state.get("queued"),
            "limitations": ["Capture callback anchors bound/interpolate audio sample time; they are not word timestamps.",
                            "Source frontiers include pauses and chunk padding.",
                            "A missing terminal receipt is incomplete, not a measured zero-checkpoint run."],
        })
    return {"protocol": "recovery-timing-summary-v1", "sampleRate": SAMPLE_RATE,
            "takeCount": len(takes), "takes": takes,
            "limitations": ["Metadata only; transcript/audio content is never loaded.",
                            "Controlled producer death/store reopen is separate from actual live system interruption."]}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("receipts", type=Path, help="metadata-only recovery-timing.jsonl")
    args = parser.parse_args()
    print(json.dumps(analyze(args.receipts), indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
