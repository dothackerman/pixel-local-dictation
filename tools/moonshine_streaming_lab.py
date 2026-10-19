#!/usr/bin/env python3
"""Bounded oracle-language Moonshine Small quality screen; personal output stays in corpus.

prepare MANIFEST freezes local inputs/assets without inference. run MANIFEST LANGUAGE OUTPUT
loads once, creates one persistent native stream per input and feeds 640 samples unpaced.
This is laptop batch replay, not live latency, automatic language routing or phone evidence.
"""
import argparse
import hashlib
import importlib.metadata
import json
from pathlib import Path
import time

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
RATE, FEED = 16000, 640
PACKAGE_SHA = "1ed9e0ccf94be4845d69e7fa862dcf4c8b5801a758796966494c694f48184496"


def sha(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def private_path(path):
    path = Path(path).resolve()
    if not path.is_relative_to(ROOT / "corpus"):
        raise ValueError("manifest and output must stay inside corpus/")
    return path


def write_new(path, value):
    path = private_path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("x", encoding="utf-8") as f:
        json.dump(value, f, ensure_ascii=False, indent=2)
        f.write("\n")


def prepare(path):
    import evaluate
    import moonshine_voice
    from pipeline import read_wav

    if importlib.metadata.version("moonshine-voice") != "0.1.5":
        raise ValueError("requires pinned moonshine-voice==0.1.5")
    staging = ROOT / "models/moonshine-packages"
    wheel = staging / "moonshine_voice-0.1.5-py3-none-manylinux_2_34_x86_64.whl"
    if sha(wheel) != PACKAGE_SHA:
        raise ValueError("pinned wheel hash mismatch")
    assets = json.loads((staging / "staged-assets.json").read_text())
    refs = evaluate.references()
    takes_map = json.loads((ROOT / "corpus/takes.json").read_text())
    inputs = []
    for name, sid in sorted(takes_map.items()):
        wav = ROOT / "corpus/files/benchmark" / name
        lang, _, _ = refs[sid]
        inputs.append(dict(kind="take", file=name, script=sid, lang=lang,
                           path=str(wav), samples=len(read_wav(str(wav)))))
    guard_refs = ROOT / "models/guard/refs.tsv"
    for line in guard_refs.read_text().splitlines()[1:]:
        cid, lang, _, _ = line.split("\t")
        wav = ROOT / "models/guard" / lang / (cid + ".wav")
        inputs.append(dict(kind="guard", id=cid, lang=lang, path=str(wav),
                           samples=len(read_wav(str(wav)))))
    files = {Path(i["path"]) for i in inputs}
    files.update([ROOT / "corpus/takes.json", ROOT / "corpus/lab/dictionary.txt",
                  guard_refs, ROOT / "docs/evaluation/baseline.md", wheel])
    files.update((ROOT / "corpus/scripts").glob("*.txt"))
    files.update((ROOT / "corpus/scripts").glob("*.terms"))
    files.update(ROOT / "tools" / f for f in ("moonshine_streaming_lab.py", "pipeline.py", "runtime_config.py", "variant.py", "evaluate.py", "score.py"))
    files.update(staging / f for f in ("native-catalog.json", "small-model-manifests.json", "staged-assets.json"))
    for model in assets["models"].values():
        files.update(f for f in Path(model["path"]).rglob("*") if f.is_file())
    files.update(f for f in Path(moonshine_voice.__file__).parent.rglob("*") if f.is_file() and f.suffix in (".py", ".so"))
    files.update(Path(moonshine_voice.__file__).parent.parent.glob("moonshine_voice.libs/*.so*"))
    manifest = dict(schema=1, package="moonshine-voice==0.1.5", wheelSha256=PACKAGE_SHA,
                    modelArch=4, models=assets["models"], feedSamples=FEED, sampleRate=RATE,
                    updateIntervalSeconds=0.5, repetitions=1, timeoutSeconds=3600,
                    mode="oracle-language-native-streaming-unpaced-laptop-quality", inputs=inputs,
                    vocabularyCaveat="All are existing campaign takes. Dictionary derivation may include D6/E6; no unseen or held-out subgroup is established.",
                    synthetic=dict(seconds=5, noiseSeed=0, noiseStddev=0.003),
                    runtime={p: importlib.metadata.version(p) for p in ("moonshine-voice", "numpy", "sherpa-onnx")},
                    files=[dict(path=str(f.resolve()), sha256=sha(f)) for f in sorted(files)])
    write_new(path, manifest)


class ReplayFailure(RuntimeError):
    """Native failure with the partial stream evidence retained for the run report."""
    def __init__(self, evidence):
        super().__init__(evidence["message"])
        self.evidence = evidence


def replay(transcriber, audio, clock=time.perf_counter):
    """Public test seam: each sample once/in order, one stream, final native transcript."""
    started = clock()
    submitted = 0
    events = []

    def listener(event):
        line = getattr(event, "line", None)
        events.append(dict(type=type(event).__name__, batchWallSeconds=clock() - started,
                           submittedSamples=submitted, lineId=getattr(line, "line_id", None),
                           audioStartSeconds=getattr(line, "start_time", None),
                           text=getattr(line, "text", None), error=str(event.error) if hasattr(event, "error") else None))

    stream = failure = close_error = None
    try:
        stream = transcriber.create_stream(update_interval=0.5)
        stream.add_listener(listener)
        stream.start()
        for offset in range(0, len(audio), FEED):
            packet = audio[offset:offset + FEED]
            # Callbacks run synchronously inside add_audio; this packet is already submitted.
            submitted += len(packet)
            stream.add_audio(packet, RATE)
        stop_at = clock()
        transcript = stream.stop()
        finished = clock()
        if transcript is None or any(e["error"] is not None for e in events):
            raise RuntimeError("native stream failed; preserve failed attempt, no automatic retry")
        lines = sorted(transcript.lines, key=lambda line: (line.start_time, line.line_id))
        raw = " ".join(line.text.strip() for line in lines if line.text.strip())
        result = dict(audioSeconds=len(audio) / RATE, text=raw.replace("ß", "ss").replace("ẞ", "SS"),
                    nativeText=raw, decodeSeconds=finished - started,
                    batchStopToFinalSeconds=finished - stop_at,
                    submittedSamples=submitted, feedPackets=(len(audio) + FEED - 1) // FEED,
                    events=events,
                    nativeLines=[dict(lineId=l.line_id, audioStartSeconds=l.start_time,
                                      complete=l.is_complete, text=l.text) for l in lines])
    except Exception as error:
        failure = error
    finally:
        if stream is not None:
            try:
                stream.close()
            except Exception as error:
                if failure is None:
                    failure = error
                else:
                    close_error = dict(errorType=type(error).__name__, message=str(error))
    if failure is not None:
        evidence = dict(errorType=type(failure).__name__, message=str(failure),
                        audioSeconds=len(audio) / RATE, submittedSamples=submitted, events=events)
        if close_error is not None:
            evidence["closeError"] = close_error
        raise ReplayFailure(evidence) from failure
    return result


def run(manifest_path, language, output):
    # Verification precedes all model loading; no implicit downloads/network APIs.
    manifest_path, output = private_path(manifest_path), private_path(output)
    if output.exists():
        raise FileExistsError(output)
    manifest = json.loads(manifest_path.read_text())
    for item in manifest["files"]:
        if sha(item["path"]) != item["sha256"]:
            raise ValueError("frozen file changed: " + item["path"])
    for package, version in manifest["runtime"].items():
        if importlib.metadata.version(package) != version:
            raise ValueError("frozen runtime changed: " + package)
    if (manifest["schema"], manifest["modelArch"], manifest["feedSamples"], manifest["repetitions"]) != (1, 4, FEED, 1):
        raise ValueError("unsupported screen configuration")
    import evaluate
    from pipeline import read_wav
    from moonshine_voice import Transcriber
    from moonshine_voice.moonshine_api import ModelArch

    report = dict(schema=1, model="Moonshine Small Streaming", language=language,
                  manifestSha256=sha(manifest_path), mode=manifest["mode"], runtime=manifest["runtime"],
                  modelAssets=manifest["models"][language], takes=[], guard=[], silence={},
                  caveat="Unpaced laptop native-stream quality only; no LID, live latency or phone evidence. Quantized publisher assets.")
    started = time.perf_counter()
    transcriber = failure = current = None
    try:
        transcriber = Transcriber(model_path=manifest["models"][language]["path"],
                                  model_arch=ModelArch.SMALL_STREAMING, update_interval=0.5)
        report["modelLoadSeconds"] = time.perf_counter() - started
        for item in manifest["inputs"]:
            if item["lang"] != language:
                continue
            current = item
            audio = read_wav(item["path"])
            if len(audio) != item["samples"]:
                raise ValueError("sample count changed")
            result = dict(item, **replay(transcriber, audio))
            report["takes" if item["kind"] == "take" else "guard"].append(result)
        rng = np.random.default_rng(0)
        for name, audio in (("silent", np.zeros(5 * RATE, dtype=np.float32)),
                            ("noise", (rng.standard_normal(5 * RATE) * 0.003).astype(np.float32))):
            current = dict(kind="synthetic", id=name)
            result = replay(transcriber, audio)
            report["silence"][name] = result["text"]
            report.setdefault("syntheticDetails", {})[name] = result
        current = dict(kind="scoring")
        report["scores"] = evaluate.score_takes(report)
        report["guardScores"] = evaluate.score_guard(report)
        report["processBatchSeconds"] = time.perf_counter() - started
    except Exception as error:
        failure = error
    finally:
        if transcriber is not None:
            try:
                transcriber.close()
            except Exception as error:
                if failure is None:
                    failure = error
                else:
                    report["closeError"] = dict(errorType=type(error).__name__, message=str(error))
    if failure is not None:
        report["status"] = "failed"
        report["failure"] = dict(input=current, errorType=type(failure).__name__, message=str(failure))
        if isinstance(failure, ReplayFailure):
            report["failure"]["stream"] = failure.evidence
        write_new(output, report)
        raise failure
    write_new(output, report)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    p = sub.add_parser("prepare")
    p.add_argument("manifest")
    p = sub.add_parser("run")
    p.add_argument("manifest")
    p.add_argument("language", choices=("de", "en"))
    p.add_argument("output")
    args = parser.parse_args()
    if args.command == "prepare":
        prepare(args.manifest)
    else:
        run(args.manifest, args.language, args.output)


if __name__ == "__main__":
    main()
