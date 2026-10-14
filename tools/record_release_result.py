#!/usr/bin/env python3
"""Preserve one returned device result under its frozen release run ID."""
import argparse
import json
from pathlib import Path
import shutil

from wrap_historical_measurement import wrap

ROOT = Path(__file__).resolve().parents[1]
LAB = ROOT / "corpus/lab/release-1a"


def record(manifest, run_id, source):
    all_runs = manifest['runs'] + manifest.get('retry_options', [])
    run = next(r for r in all_runs if r["id"] == run_id and r["platform"] == "phone")
    if 'retry_slot' in run and any(Path(r['raw_output']).exists() for r in all_runs
            if r['platform'] == 'phone' and r.get('retry_slot') == run['retry_slot']):
        raise ValueError('device retry slot already consumed')
    source = Path(source).resolve()
    if not source.is_relative_to(ROOT / "corpus/lab/phone"):
        raise ValueError("result must be the JSON returned into the private device-results directory")
    raw_path, output = Path(run["raw_output"]), Path(run["output"])
    if raw_path.exists() or output.exists():
        raise FileExistsError("run already has preserved evidence; use its predeclared retry slot")
    # Preserve bytes even if parsing or provenance validation fails.
    with source.open("rb") as origin, raw_path.open("xb") as destination:
        shutil.copyfileobj(origin, destination)
    raw = json.loads(raw_path.read_text())
    if run["mode"] == "historical-full":
        stage = json.loads((ROOT / run["staging"] / "historical-manifest.json").read_text())
        envelope = wrap(raw, stage)
    else:
        envelope = raw
    run_uuid = envelope.get("provenance", {}).get("run_id")
    process = envelope.get("provenance", {}).get("process_start_id")
    if not run_uuid or not process:
        raise ValueError("missing measured freshness identity; raw evidence retained")
    for previous in all_runs:
        path = Path(previous["output"])
        if previous["platform"] == "phone" and path.exists():
            provenance = json.loads(path.read_text()).get("provenance", {})
            if provenance.get("run_id") == run_uuid or provenance.get("process_start_id") == process:
                raise ValueError("reused device result or process; raw evidence retained")
    with output.open("x") as destination:
        json.dump(envelope, destination, indent=2)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("run_id"); parser.add_argument("returned_json")
    args = parser.parse_args()
    record(json.loads((LAB / "manifest.json").read_text()), args.run_id, args.returned_json)
