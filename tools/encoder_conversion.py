#!/usr/bin/env python3
"""Bounded synthetic encoder conversion diagnosis; never reads audio or transcripts.

freeze writes immutable input identities before inference. attempt preserves converter
failure/log and checks ONNX CPU outputs; no downstream eligibility on missing tensors.
"""
import argparse
import collections
import hashlib
import importlib.metadata
import json
from pathlib import Path
import platform
import os
import subprocess
import sys
import time

import numpy as np


def digest(path):
    h = hashlib.sha256()
    with Path(path).open("rb") as f:
        for block in iter(lambda: f.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def tensor_verdict(reference, candidate):
    results = []
    for index, (a, b) in enumerate(zip(reference, candidate)):
        entry = {"index": index, "shape": list(b.shape)}
        expected_dtype = np.float32 if index == 0 else np.int64
        if a.dtype != expected_dtype or b.dtype != expected_dtype:
            entry["failure"] = "dtype"
        elif a.shape != b.shape:
            entry["failure"] = "shape"
        elif not np.isfinite(a).all() or not np.isfinite(b).all():
            entry["failure"] = "nonfinite"
        elif np.issubdtype(a.dtype, np.integer):
            if a.dtype != b.dtype or not np.array_equal(a, b):
                entry["failure"] = "length"
        elif a.dtype != np.float32 or b.dtype != np.float32 or not np.allclose(b, a, atol=1e-4, rtol=1e-3):
            entry["failure"] = "precision"
        if a.shape == b.shape and a.size and np.isfinite(a).all() and np.isfinite(b).all():
            entry["max_absolute_error"] = float(np.max(np.abs(a.astype(float) - b)))
        results.append(entry)
    return {"passed": len(reference) == len(candidate) == 2 and all("failure" not in r for r in results),
            "outputs": results}


def environment_identity():
    return {"python": sys.version, "executable": str(Path(sys.executable).resolve()),
            "dependencies": sorted(f"{d.metadata['Name']}=={d.version}" for d in importlib.metadata.distributions())}


def require_frozen_environment(expected):
    if expected != environment_identity():
        raise ValueError("environment changed after freeze")


def coverage_failure(report):
    custom = report.get("custom_lowered_nodes", [])
    if custom:
        return f"unsupported custom lowering: {len(custom)} nodes; first={custom[0]}"
    return None


def write(path, value):
    Path(path).write_text(json.dumps(value, indent=2, allow_nan=False) + "\n")


def freeze(model, out, timeout=900, infer_shapes=False):
    import onnx
    out.mkdir(parents=True, exist_ok=False)
    graph = onnx.load(model / "encoder.onnx", load_external_data=False)
    conversion_input = model / "encoder.onnx"
    if infer_shapes:
        staged = out / "input"
        staged.mkdir()
        inferred = onnx.shape_inference.infer_shapes(graph, strict_mode=False, data_prop=False)
        if [n.SerializeToString() for n in inferred.graph.node] != [n.SerializeToString() for n in graph.graph.node]:
            raise ValueError("shape inference changed graph nodes")
        conversion_input = staged / "encoder.onnx"
        onnx.save(inferred, conversion_input)
        (staged / "encoder.weights").symlink_to((model / "encoder.weights").resolve())
    fixtures = []
    # No production hard maximum exists. These are synthetic stress probes only;
    # actual shortest/median/longest development chunks remain unestablished.
    for name, frames, length, seed in [("silence", 17, 17, None), ("short", 17, 17, 731),
                                        ("twenty_seconds", 2000, 1961, 732),
                                        ("sixty_seconds", 6000, 6000, 733)]:
        values = (np.zeros((1, 128, frames), np.float32) if seed is None else
                  np.random.Generator(np.random.PCG64(seed)).standard_normal((1, 128, frames), dtype=np.float32))
        np.savez(out / (name + ".npz"), audio_signal=values, length=np.array([length], np.int64))
        fixtures.append({"id": name, "frames": frames, "true_length": length, "seed": seed,
                         "mask": "internal ONNX length-derived mask; input tail unchanged",
                         "sha256": digest(out / (name + ".npz"))})
    environment = environment_identity()
    versions = environment["dependencies"]
    (out / "requirements.txt").write_text("\n".join(versions) + "\n")
    contract = {"schema": 1, "model_directory": str(model.resolve()), "conversion_input": str(conversion_input.resolve()),
                "conversion_input_sha256": digest(conversion_input), "metadata_shape_inference": infer_shapes,
                "converter_memory_limit_bytes": 24 * 1024**3,
                "model": {f: digest(model / f) for f in ["encoder.onnx", "encoder.weights"]},
                "harness_sha256": digest(__file__), "environment": environment, "python": sys.version, "converter_timeout_seconds": timeout,
                "platform": platform.platform(), "requirements_sha256": digest(out / "requirements.txt"),
                "opsets": [{"domain": x.domain, "version": x.version} for x in graph.opset_import],
                "operators": dict(collections.Counter(n.op_type for n in graph.graph.node)),
                "fixtures": fixtures, "atol": 1e-4, "rtol": 1e-3,
                "envelope_complete": False,
                "envelope_limitation": "Production chunks unbounded; development chunk quantiles not established. No inference eligibility from these stress probes alone.",
                "converter_options": ["--tflite_backend", "flatbuffer_direct", "--not_use_onnxsim", "--not_use_opname_auto_generate", "--report_op_coverage", "--keep_shape_absolutely_input_names", "audio_signal", "length", "--verbosity", "info"],
                "candidate_adapter": None,
                "adapter_limitation": "No app encoder adapter accepted until conversion succeeds; Android binary/runtime not tested."}
    (out / "executed-harness.py").write_bytes(Path(__file__).read_bytes())
    write(out / "freeze.json", contract)


def attempt(out):
    contract = json.loads((out / "freeze.json").read_text())
    require_frozen_environment(contract["environment"])
    if digest(out / "requirements.txt") != contract["requirements_sha256"]:
        raise ValueError("requirements changed after freeze")
    model = Path(contract["model_directory"])
    if contract["harness_sha256"] != digest(__file__):
        raise ValueError("harness changed after freeze")
    for name, expected in contract["model"].items():
        if digest(model / name) != expected:
            raise ValueError("original model changed after freeze")
    for fixture in contract["fixtures"]:
        if digest(out / (fixture["id"] + ".npz")) != fixture["sha256"]:
            raise ValueError("fixture changed after freeze")
    if digest(contract["conversion_input"]) != contract["conversion_input_sha256"]:
        raise ValueError("conversion input changed after freeze")
    if (out / "conversion.log").exists():
        raise ValueError("attempt already started; preserve it and freeze a new directory")
    command = [str(Path(sys.executable).with_name("onnx2tf")), "-i", contract["conversion_input"],
               "-o", str(out / "converted"), *contract["converter_options"]]
    result = {"freeze_sha256": digest(out / "freeze.json"), "command": command,
              "converted_cpu_verdict": "unproven", "eligible": False, "reference_fixtures": []}
    start = time.monotonic()
    import psutil
    with (out / "conversion.log").open("w") as log:
        process = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT,
                                   env={**os.environ, "PYTHONUNBUFFERED": "1"})
        peak = 0
        while process.poll() is None:
            failure = None
            coverage = out / "converted" / "encoder_op_coverage_report.json"
            if coverage.exists():
                try:
                    failure = coverage_failure(json.loads(coverage.read_text()))
                except json.JSONDecodeError:
                    pass  # report being written; inspect again on the next poll
            try:
                memory = psutil.Process(process.pid).memory_full_info()
                peak = max(peak, memory.rss + getattr(memory, "swap", 0))
                if peak > contract["converter_memory_limit_bytes"]:
                    failure = "host converter memory bound exceeded (RSS + swap)"
            except psutil.NoSuchProcess:
                pass
            if time.monotonic() - start > contract["converter_timeout_seconds"]:
                failure = f"{contract['converter_timeout_seconds']} second bounded conversion timeout"
            if failure:
                result["converter_failure"] = failure
                process.terminate()
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    process.kill()
                break
            time.sleep(.5)
        result["converter_exit"] = process.wait()
        result["converter_peak_rss_plus_swap_bytes"] = peak
    result["converter_seconds"] = time.monotonic() - start
    result["conversion_log_sha256"] = digest(out / "conversion.log")
    result["converted_artifacts"] = {str(p.relative_to(out)): digest(p) for p in (out / "converted").rglob("*") if p.is_file()}
    write(out / "result.json", result)
    # Original reference execution is real ONNX CPU, synthetic feature tensors only.
    import onnxruntime as ort
    options = ort.SessionOptions()
    options.intra_op_num_threads = 4
    options.inter_op_num_threads = 1
    session = ort.InferenceSession(str(model / "encoder.onnx"), sess_options=options, providers=["CPUExecutionProvider"])
    for fixture in contract["fixtures"]:
        data = np.load(out / (fixture["id"] + ".npz"))
        start = time.monotonic()
        try:
            tensors = session.run(None, dict(data))
            np.savez(out / (fixture["id"] + "-onnx.npz"), outputs=tensors[0], encoded_lengths=tensors[1])
            item = {"id": fixture["id"], "seconds": time.monotonic() - start,
                    "shapes": [list(t.shape) for t in tensors],
                    "encoded_lengths": tensors[1].tolist(),
                    "finite": all(bool(np.isfinite(t).all()) for t in tensors),
                    "output_sha256": digest(out / (fixture["id"] + "-onnx.npz")),
                    "candidate": "not executed; no accepted adapter/artifact"}
        except Exception as error:
            item = {"id": fixture["id"], "reference_failure": str(error)}
        result["reference_fixtures"].append(item)
        write(out / "result.json", result)
    print(json.dumps(result, indent=2))


def main():
    p = argparse.ArgumentParser(description=__doc__)
    commands = p.add_subparsers(dest="command", required=True)
    f = commands.add_parser("freeze")
    f.add_argument("--model", type=Path, required=True)
    f.add_argument("--out", type=Path, required=True)
    f.add_argument("--timeout", type=int, default=900)
    f.add_argument("--infer-shapes", action="store_true")
    a = commands.add_parser("attempt")
    a.add_argument("--out", type=Path, required=True)
    args = p.parse_args()
    if args.command == "freeze":
        if args.timeout <= 0:
            p.error("--timeout must be positive")
        freeze(args.model, args.out, args.timeout, args.infer_shapes)
    else:
        attempt(args.out)

if __name__ == "__main__":
    main()
