#!/usr/bin/env python3
"""Freeze concrete local commands and comparison contracts; never launch a run."""
import csv
import copy
import json
from pathlib import Path
import subprocess

import measurement_identity as identity
import variant

ROOT = Path(__file__).resolve().parents[1]
LAB = ROOT / "corpus/lab/release-1a"
PYTHON = str(ROOT / "models/venv/bin/python")


def save(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + "\n")


def main():
    manifest = json.loads((LAB / "manifest.json").read_text())
    manifest["source_commit"] = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    manifest["source_files"] = identity.source_files(ROOT)
    manifest["test_apk"]["sha256"] = identity.sha(ROOT / manifest["test_apk"]["path"])
    manifest["apk"]["sha256"] = identity.sha(ROOT / manifest["apk"]["path"])
    environment = json.loads((LAB / "environment.json").read_text())
    environment["source"] = manifest["source_files"]
    save(LAB / "environment.json", environment)
    suites = {mode: json.loads((LAB / name).read_text()) for mode, name in (
        ("quality-full", "quality-suite.json"), ("diagnostic", "diagnostic-suite.json"))}
    suites['quality-full']['quality_inputs_sha256'] = identity.quality_inputs(ROOT, suites['quality-full']['inputs'])
    save(LAB / 'quality-suite.json', suites['quality-full'])
    takes_map = json.loads((ROOT / 'corpus/takes.json').read_text())
    languages = {name: 'de' if script.lower().startswith('d') else 'en' for name, script in takes_map.items()}
    with (ROOT / "models/guard/refs.tsv").open() as source:
        guards = list(csv.DictReader(source, delimiter="\t"))
    contracts = {}
    runs = []
    families = [("baseline-stock", "quality-full"), ("dict-beam8-fp32-s20-stock", "quality-full")]
    families += [("dict-beam8-fp32-s20-stock", mode) for mode in ("unpaced-only", "paced-only", "persistent")]
    for name, mode in families:
        path = ROOT / "variants" / (name + ".json")
        config = variant.load(path)
        suite = suites["quality-full" if mode == "quality-full" else "diagnostic"]
        repetitions = 2 if mode == "persistent" else 1
        provenance = dict(suite["provenance"], platform="laptop", mode=mode,
            feed=[640, 512, 800] if mode == "quality-full" else 640,
            build_sha256=identity.digest(manifest["source_files"]),
            runtime_sha256=identity.digest(identity.runtime_files()),
            evaluator_sha256=identity.digest(identity.evaluator_files(ROOT)),
            model_sha256=identity.digest(identity.model_files(ROOT, config)),
            configuration_sha256=identity.sha(path), inputs_sha256=identity.digest(suite["inputs"]),
            references_sha256=identity.digest(identity.reference_files(ROOT)),
            dictionary_sha256=identity.sha(ROOT / "corpus/lab" / config["hotwords"]["file"])
                if config["hotwords"]["file"] else identity.digest([]))
        takes = [Path(i["path"]).name for i in suite["inputs"] if "reference" not in i]
        endpoints = [i for i in suite["inputs"] if "reference" in i]
        expected = dict(provenance=provenance, takes=takes * repetitions,
            endpoints=[i["id"] for i in endpoints] * repetitions,
            endpoint_references={i["id"]: {k: i.get(k) for k in ("reference", "lang", "cut_word")} for i in endpoints})
        if mode == "quality-full":
            expected["guard"] = [g["id"] for g in guards]
            provenance['inputs_sha256'] = suites['quality-full']['quality_inputs_sha256']
        expected['take_languages'] = {name: languages[name] for name in takes}
        for repeat in (1, 2):
            run_id = "laptop-" + name + "-" + mode + "-" + str(repeat)
            output = LAB / "runs" / (run_id + ".json")
            adapter = "quality_measurement.py" if mode == "quality-full" else "measurement.py"
            suite_path = LAB / ("quality-suite.json" if mode == "quality-full" else "diagnostic-suite.json")
            command = [PYTHON, str(ROOT / "tools" / adapter), str(path), str(suite_path), str(output)]
            if mode != "quality-full":
                command += ["--mode", mode]
                if mode == "persistent":
                    command += ["--repetitions", "2"]
            audio_seconds = sum(i.get("stop_sample", i.get("samples", 0)) / 16000 for i in suite["inputs"])
            decode_bound = manifest["decode_upper_bound_per_full_suite"]
            if mode == "quality-full":
                audio_seconds = audio_seconds * 3 + sum(float(g["seconds"]) for g in guards) + 11
                decode_bound = decode_bound * 3 + len(guards) + 3
            else:
                audio_seconds = audio_seconds * repetitions + 1
                decode_bound = next(r["max_decodes"] for r in manifest["phone_runs"] if r["mode"] == mode)
            runs.append(dict(id=run_id, platform="laptop", command=command, output=str(output),
                timeout_seconds=3600, audio_seconds=audio_seconds, max_decodes=decode_bound))
            contracts[run_id] = expected
    wrapper = str(ROOT / "tools/phone")
    for index, mode in enumerate(manifest["phone_order"], 1):
        stage = LAB / "push" / mode
        filename = "historical-manifest.json" if mode == "historical-full" else "measurement.json"
        spec = json.loads((stage / filename).read_text())
        spec["provenance"].update(build_sha256=manifest["apk"]["sha256"],
            test_build_sha256=manifest["test_apk"]["sha256"],
            evaluator_sha256=identity.digest(identity.evaluator_files(ROOT)))
        save(stage / filename, spec)
        repeat = spec["repetitions"]
        run_id = "phone-" + str(index).zfill(2) + "-" + mode
        output = LAB / "runs" / (run_id + ".json")
        endpoints = [] if mode == "historical-full" else [i for i in spec["inputs"] if "reference" in i]
        expected = dict(provenance=spec["provenance"],
            takes=[Path(i["path"]).name for i in spec["inputs"] if "reference" not in i] * repeat,
            endpoints=[i["id"] for i in endpoints] * repeat,
            endpoint_references={i["id"]: {k: i.get(k) for k in ("reference", "lang", "cut_word")} for i in endpoints})
        contracts[run_id] = expected
        expected['take_languages'] = {name: languages[name] for name in expected['takes']}
        if mode == 'historical-full':
            ordered = sorted((i for i in spec['inputs'] if 'reference' not in i), key=lambda i: i['samples'])
            expected['paced'] = [Path(i['path']).name for i in (ordered[0], ordered[-1])]
        run = dict(next(r for r in manifest["phone_runs"] if r["mode"] == mode), id=run_id, platform="phone",
            commands=[[wrapper, "push-variant", str(stage)], [wrapper, "lab"], [wrapper, "pull-results"]],
            output=str(output), raw_output=str(output.with_suffix(".raw.json")))
        if mode == "historical-full":
            run["wrap_command"] = [PYTHON, str(ROOT / "tools/wrap_historical_measurement.py"),
                run["raw_output"], str(stage / filename), str(output)]
        run["record_command"] = [PYTHON, str(ROOT / "tools/record_release_result.py"), run_id,
            "RETURNED_JSON_PATH"]
        runs.append(run)
    comparisons = []
    pairs = []
    for name, mode in families:
        stem = "laptop-" + name + "-" + mode + "-"
        pairs.append((stem + "2", stem + "1", "repeat"))
    for mode in ("unpaced-only", "paced-only", "persistent", "historical-full"):
        selected = [r["id"] for r in runs if r["platform"] == "phone" and r["mode"] == mode]
        pairs.append((selected[1], selected[0], "repeat"))
    for repeat in (1, 2):
        pairs.append(("laptop-dict-beam8-fp32-s20-stock-quality-full-" + str(repeat),
                      "laptop-baseline-stock-quality-full-" + str(repeat), "quality-delta"))
    by_id = {r["id"]: r for r in runs}
    for candidate, control, purpose in pairs:
        name = candidate + "-vs-" + control
        path = LAB / "comparisons" / (name + ".json")
        save(path, dict(candidate=contracts[candidate], control=contracts[control],
            repeat_rule={"normalized_text_mismatches_allowed": 0} if purpose == "repeat" else None))
        output = LAB / "reports" / (name + ".json")
        comparisons.append(dict(purpose=purpose, candidate=candidate, control=control, manifest=str(path),
            output=str(output), command=[PYTHON, str(ROOT / "tools/evaluate_v4.py"),
                by_id[candidate]["output"], by_id[control]["output"], str(path), str(output)]))
    for directory in ("runs", "reports"):
        (LAB / directory).mkdir(exist_ok=True)
    retry_options = []
    for run in runs:
        for slot in (1, 2):
            retry = copy.deepcopy(run)
            retry.update(id=run['id'] + '-retry-' + str(slot), retry_of=run['id'], retry_slot=slot)
            retry['output'] = str(Path(run['output']).with_stem(retry['id']))
            if run['platform'] == 'laptop':
                retry['command'] = [retry['output'] if arg == run['output'] else arg for arg in run['command']]
            else:
                retry['raw_output'] = str(Path(retry['output']).with_suffix('.raw.json'))
                retry['record_command'][2] = retry['id']
                if 'wrap_command' in retry:
                    retry['wrap_command'][2] = retry['raw_output']
                    retry['wrap_command'][-1] = retry['output']
            retry_options.append(retry)
    manifest.update(runs=runs, comparisons=comparisons,
        retry_options=retry_options,
        repeat_rule=dict(normalized_text_mismatches_allowed=0, resource_failures_allowed_as_diagnostics=True),
        retry_rule=dict(laptop_processes=2, phone_processes=2, thermal_retries_per_configuration=1,
                        output_suffixes=["-retry-1", "-retry-2"], reuse_completed_output=False),
        restoration=manifest.get('restoration', dict(owner=None, procedure=None, artifact_sha256=None)),
        brief="docs/batch-1a-v1.md", brief_sha256=identity.sha(ROOT / "docs/batch-1a-v1.md"))
    contract_paths = [Path(c['manifest']) for c in comparisons]
    contract_paths += [LAB / name for name in ('quality-suite.json', 'diagnostic-suite.json')]
    contract_paths += [LAB / 'push' / mode / ('historical-manifest.json' if mode == 'historical-full' else 'measurement.json')
                       for mode in set(manifest['phone_order'])]
    manifest['frozen_contracts'] = identity.files(contract_paths)
    save(LAB / "manifest.json", manifest)
    print("Frozen", len(runs), "planned processes and", len(comparisons), "comparison contracts; no runs launched.")


if __name__ == "__main__":
    main()
