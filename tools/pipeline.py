#!/usr/bin/env python3
"""Laptop twin of the app's decode path, driven by a variant file (tools/variant.py).

Decodes WAV takes exactly as the app would: the take is fed in recorder-sized pieces, silero VAD
finds pauses, the chunker cuts contiguous chunks (PauseChunker in Engine.kt), each chunk is decoded
and the chunk texts are joined with one space. Guard clips (short public clips) are decoded whole.

Usage:
  models/venv/bin/python tools/pipeline.py VARIANT.json OUT.json [--takes corpus/files/benchmark]
                                            [--guard models/guard] [--models models]
Timings are laptop numbers. Frozen: checksummed by tools/lab.py check.
"""
import argparse, glob, json, os, sys, time, wave
import numpy as np
import sherpa_onnx

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import variant as variant_mod  # noqa: E402

RATE = 16000
# Samples per recorder read. The live recorder's read size is not fixed, and the chunk boundary moves
# by a few tens of milliseconds with it; measured on 2026-10-10, that alone moves the ten-take error
# count by several words. Every take is therefore decoded with three feed sizes and the evaluator
# averages them. The first is the primary one (PipelineTest feeds 640).
FEEDS = (640, 512, 800)


def read_wav(path):
    with wave.open(path) as w:
        assert w.getframerate() == RATE and w.getnchannels() == 1 and w.getsampwidth() == 2, path
        return np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32) / 32768


def one(model_dir, *stems):
    """The single file in model_dir whose name contains one of the stems and ends with .onnx."""
    for stem in stems:
        hits = sorted(f for f in glob.glob(os.path.join(model_dir, "*.onnx")) if stem in os.path.basename(f))
        if len(hits) == 1:
            return hits[0]
        if len(hits) > 1:
            # Prefer the int8 file when both precisions are present (whisper archives ship both).
            int8 = [h for h in hits if ".int8." in h]
            if len(int8) == 1:
                return int8[0]
    raise FileNotFoundError(f"no unique *.onnx for {stems} in {model_dir}")


def tokens(model_dir):
    hits = glob.glob(os.path.join(model_dir, "*tokens.txt"))
    assert len(hits) == 1, hits
    return hits[0]


# ---------------------------------------------------------------- recognizers

class Transducer:
    def __init__(self, v, model_dir):
        d = v["decoding"]
        self.rec = sherpa_onnx.OfflineRecognizer.from_transducer(
            encoder=one(model_dir, "encoder"), decoder=one(model_dir, "decoder"), joiner=one(model_dir, "joiner"),
            tokens=tokens(model_dir), num_threads=v["threads"], model_type="nemo_transducer",
            decoding_method=d["method"], max_active_paths=d["max_active_paths"], blank_penalty=d["blank_penalty"])

    def begin_take(self, first_chunk):
        return ""

    def transcribe(self, samples):
        s = self.rec.create_stream(); s.accept_waveform(RATE, samples); self.rec.decode_stream(s)
        return s.result.text.strip()


class Whisper:
    def __init__(self, v, model_dir):
        w = v["whisper"]
        self.rec = sherpa_onnx.OfflineRecognizer.from_whisper(
            encoder=one(model_dir, "encoder"), decoder=one(model_dir, "decoder"), tokens=tokens(model_dir),
            language=w["language"], task="transcribe", num_threads=v["threads"], tail_paddings=w["tail_paddings"])

    def begin_take(self, first_chunk):
        return ""

    def transcribe(self, samples):
        s = self.rec.create_stream(); s.accept_waveform(RATE, samples); self.rec.decode_stream(s)
        return s.result.text.strip()


class Canary:
    """Two recognizers (de, en); spoken-language identification on the first chunk picks one per take."""

    def __init__(self, v, model_dir, lid_dir):
        self.recs = {}
        for lang in ("de", "en"):
            self.recs[lang] = sherpa_onnx.OfflineRecognizer.from_nemo_canary(
                encoder=one(model_dir, "encoder"), decoder=one(model_dir, "decoder"), tokens=tokens(model_dir),
                src_lang=lang, tgt_lang=lang, num_threads=v["threads"])
        cfg = sherpa_onnx.SpokenLanguageIdentificationConfig(
            whisper=sherpa_onnx.SpokenLanguageIdentificationWhisperConfig(
                encoder=one(lid_dir, "encoder"), decoder=one(lid_dir, "decoder")),
            num_threads=v["threads"])
        self.lid = sherpa_onnx.SpokenLanguageIdentification(cfg)
        self.probe = v["lid"]["probe_seconds"]
        self.fallback = v["lid"]["fallback"]
        self.lang = self.fallback

    def begin_take(self, first_chunk):
        x = first_chunk if self.probe <= 0 else first_chunk[: int(self.probe * RATE)]
        s = self.lid.create_stream(); s.accept_waveform(RATE, x)
        detected = self.lid.compute(s)
        self.lang = detected if detected in self.recs else self.fallback
        return detected

    def transcribe(self, samples):
        rec = self.recs[self.lang]
        s = rec.create_stream(); s.accept_waveform(RATE, samples); rec.decode_stream(s)
        return s.result.text.strip()


def recognizer(v, models_root):
    model_dir = os.path.join(models_root, v["model"])
    if v["family"] == "parakeet":
        return Transducer(v, model_dir)
    if v["family"] == "whisper":
        return Whisper(v, model_dir)
    return Canary(v, model_dir, os.path.join(models_root, v["lid"]["model"]))


# ---------------------------------------------------------------- cutting (twin of VadSegmenter + PauseChunker)

class Chunker:
    """PauseChunker: contiguous chunks; a chunk ends pad after a speech end once it is at least
    min_samples long; max_samples > 0 forces a cut when the pending audio reaches that length."""

    def __init__(self, min_samples, pad_samples, max_samples):
        self.min, self.pad, self.max = min_samples, pad_samples, max_samples
        self.pending = np.zeros(0, dtype=np.float32)
        self.cut_at = 0

    def append(self, samples):
        self.pending = np.concatenate([self.pending, samples])
        if self.max and len(self.pending) >= self.max:
            return self._take(self.max)
        return None

    def on_speech_end(self, speech_end):
        n = min(speech_end + self.pad, self.cut_at + len(self.pending)) - self.cut_at
        if n < self.min:
            return None
        return self._take(n)

    def rest(self):
        if len(self.pending) == 0:
            return None
        return self._take(len(self.pending))

    def _take(self, n):
        chunk, self.pending = self.pending[:n], self.pending[n:]
        start = self.cut_at
        self.cut_at += n
        return start, chunk


def make_vad(v, vad_model):
    c = v["cutting"]
    cfg = sherpa_onnx.VadModelConfig()
    cfg.silero_vad.model = vad_model
    cfg.silero_vad.min_silence_duration = c["min_silence_s"]
    cfg.silero_vad.min_speech_duration = c["min_speech_s"]
    cfg.silero_vad.max_speech_duration = c["vad_max_speech_s"]
    cfg.sample_rate = RATE
    return sherpa_onnx.VoiceActivityDetector(cfg, buffer_size_in_seconds=600)


def chunks(v, vad_model, x, feed):
    """Returns [(start_sample, samples)] as the app would cut them, feeding `feed` samples at a time."""
    c = v["cutting"]
    ch = Chunker(int(c["min_chunk_s"] * RATE), int(c["pad_s"] * RATE), int(c["max_chunk_s"] * RATE))
    vad = make_vad(v, vad_model)
    out = []
    for i in range(0, len(x), feed):
        piece = x[i:i + feed]
        forced = ch.append(piece)
        if forced:
            out.append(forced)
        vad.accept_waveform(piece)
        while not vad.empty():
            seg = vad.front; vad.pop()
            got = ch.on_speech_end(seg.start + len(seg.samples))
            if got:
                out.append(got)
    rest = ch.rest()
    if rest:
        out.append(rest)
    return out


# ---------------------------------------------------------------- post-processing (closed set)

def post_join(v, texts):
    p = v["post"]
    out = []
    for t in texts:
        t = t.strip()
        if not t:
            continue
        if p["swiss_ss"]:
            t = t.replace("ß", "ss").replace("ẞ", "SS")
        if p["seam_capitalize"] and out and out[-1][-1] in ".!?":
            t = t[0].upper() + t[1:]
        out.append(t)
    return " ".join(out)


# ---------------------------------------------------------------- main

def run(v, models_root, take_files, guard_dir, vad_model):
    t0 = time.perf_counter()
    rec = recognizer(v, models_root)
    report = {"variant": v, "side": "laptop", "loadSeconds": time.perf_counter() - t0,
              "runtime": "sherpa-onnx " + sherpa_onnx.__version__, "takes": [], "guard": []}
    rec.transcribe(np.zeros(RATE, dtype=np.float32))  # warm-up, as the app's first decode would
    for path in take_files:
        x = read_wav(path)
        runs = []
        for feed in FEEDS:
            parts, texts, lid = [], [], ""
            for i, (start, seg) in enumerate(chunks(v, vad_model, x, feed)):
                if i == 0:
                    lid = rec.begin_take(seg)
                t = time.perf_counter()
                text = rec.transcribe(seg)
                secs = time.perf_counter() - t
                parts.append({"startSeconds": start / RATE, "audioSeconds": len(seg) / RATE, "decodeSeconds": secs, "text": text})
                texts.append(text)
            runs.append({"feed": feed, "lid": lid, "chunks": parts, "text": post_join(v, texts)})
        primary = runs[0]
        report["takes"].append({"file": os.path.basename(path), "audioSeconds": len(x) / RATE, "lid": primary["lid"],
                                "chunks": primary["chunks"], "text": primary["text"], "jitter": runs[1:]})
        print(f"{os.path.basename(path)}: {len(primary['chunks'])} chunks, "
              f"{sum(p['decodeSeconds'] for p in primary['chunks']):.1f} s decode, {len(runs)} feeds", file=sys.stderr)
    if guard_dir:
        with open(os.path.join(guard_dir, "refs.tsv"), encoding="utf-8") as f:
            next(f)
            for line in f:
                cid, lang, secs, ref = line.rstrip("\n").split("\t")
                x = read_wav(os.path.join(guard_dir, lang, cid + ".wav"))
                lid = rec.begin_take(x)
                t = time.perf_counter()
                text = rec.transcribe(x)
                report["guard"].append({"id": cid, "lang": lang, "audioSeconds": len(x) / RATE, "lid": lid,
                                        "decodeSeconds": time.perf_counter() - t, "text": post_join(v, [text])})
        print(f"guard: {len(report['guard'])} clips", file=sys.stderr)
    # Silence guard: a model that writes text on silence or faint noise would pass the takes and fail the user.
    rng = np.random.default_rng(0)
    report["silence"] = {"silent": rec.transcribe(np.zeros(5 * RATE, dtype=np.float32)),
                         "noise": rec.transcribe((rng.standard_normal(5 * RATE) * 0.003).astype(np.float32))}
    return report


def main():
    p = argparse.ArgumentParser()
    p.add_argument("variant"); p.add_argument("out")
    p.add_argument("--takes", default="corpus/files/benchmark", help="directory of 16 kHz mono WAV takes")
    p.add_argument("--guard", default=None, help="models/guard to also decode the public clips")
    p.add_argument("--models", default="models")
    p.add_argument("--only", default=None, help="substring of take file names to decode (smoke tests)")
    a = p.parse_args()
    v = variant_mod.load(a.variant)
    takes = sorted(f for f in glob.glob(os.path.join(a.takes, "*.wav")) if not a.only or a.only in os.path.basename(f))
    assert takes, a.takes
    report = run(v, a.models, takes, a.guard, os.path.join(a.models, "silero_vad.onnx"))
    with open(a.out, "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=1)


if __name__ == "__main__":
    main()
