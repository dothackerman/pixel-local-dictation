#!/usr/bin/env python3
"""Decode WAV takes on the laptop with a sherpa-onnx Parakeet model directory.

Usage: models/venv/bin/python tools/replay.py MODEL_DIR OUT.json take.wav...
Each take is decoded whole and, with --vad, as VAD segments joined by one space
(the phone design in docs/plan.md). Timings are laptop numbers, not phone numbers.
"""
import argparse, glob, json, os, time, wave
import numpy as np
import sherpa_onnx


def read(path):
    with wave.open(path) as w:
        assert w.getframerate() == 16000 and w.getnchannels() == 1 and w.getsampwidth() == 2, path
        return np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32) / 32768


def pick(model_dir, stem):
    hits = sorted(glob.glob(os.path.join(model_dir, stem + "*.onnx")))
    assert len(hits) == 1, (stem, hits)
    return hits[0]


def decode(rec, samples):
    t = time.perf_counter()
    s = rec.create_stream()
    s.accept_waveform(16000, samples)
    rec.decode_stream(s)
    return s.result.text.strip(), getattr(s.result, "lang", ""), time.perf_counter() - t


def segments(vad_model, samples, max_speech):
    cfg = sherpa_onnx.VadModelConfig()
    cfg.silero_vad.model = vad_model
    cfg.silero_vad.min_silence_duration = 0.8
    cfg.silero_vad.min_speech_duration = 0.25
    cfg.silero_vad.max_speech_duration = max_speech
    cfg.sample_rate = 16000
    vad = sherpa_onnx.VoiceActivityDetector(cfg, buffer_size_in_seconds=120)
    out, win = [], cfg.silero_vad.window_size
    for i in range(0, len(samples), win):
        vad.accept_waveform(samples[i:i + win])
        while not vad.empty():
            out.append((vad.front.start, np.array(vad.front.samples, dtype=np.float32))); vad.pop()
    vad.flush()
    while not vad.empty():
        out.append((vad.front.start, np.array(vad.front.samples, dtype=np.float32))); vad.pop()
    # plan: segments under 2 s are merged into the next
    merged, carry = [], None
    for start, seg in out:
        if carry is not None:
            start, seg = carry[0], np.concatenate([carry[1], seg]); carry = None
        if len(seg) < 2 * 16000:
            carry = (start, seg)
        else:
            merged.append((start, seg))
    if carry is not None:
        if merged:
            merged[-1] = (merged[-1][0], np.concatenate([merged[-1][1], carry[1]]))
        else:
            merged.append(carry)
    return merged


def main():
    p = argparse.ArgumentParser()
    p.add_argument("model_dir"); p.add_argument("out"); p.add_argument("wavs", nargs="+")
    p.add_argument("--vad", help="silero_vad.onnx; adds the segmented decode")
    p.add_argument("--max-speech", type=float, default=20.0)
    p.add_argument("--threads", type=int, default=4)
    a = p.parse_args()
    t = time.perf_counter()
    rec = sherpa_onnx.OfflineRecognizer.from_transducer(
        encoder=pick(a.model_dir, "encoder"), decoder=pick(a.model_dir, "decoder"),
        joiner=pick(a.model_dir, "joiner"), tokens=os.path.join(a.model_dir, "tokens.txt"),
        num_threads=a.threads, model_type="nemo_transducer")
    report = {"model": os.path.basename(os.path.normpath(a.model_dir)), "loadSeconds": time.perf_counter() - t,
              "threads": a.threads, "takes": []}
    decode(rec, np.zeros(16000, dtype=np.float32))  # warm-up
    for path in a.wavs:
        x = read(path)
        text, lang, secs = decode(rec, x)
        take = {"file": os.path.basename(path), "audioSeconds": len(x) / 16000,
                "whole": {"text": text, "lang": lang, "decodeSeconds": secs}}
        if a.vad:
            segs = []
            for start, seg in segments(a.vad, x, a.max_speech):
                st, sl, ss = decode(rec, seg)
                segs.append({"startSeconds": start / 16000, "audioSeconds": len(seg) / 16000,
                             "text": st, "lang": sl, "decodeSeconds": ss})
            take["segments"] = segs
            take["segmented"] = {"text": " ".join(s["text"] for s in segs if s["text"])}
        report["takes"].append(take)
    with open(a.out, "w") as f:
        json.dump(report, f, ensure_ascii=False, indent=2)


if __name__ == "__main__":
    main()
