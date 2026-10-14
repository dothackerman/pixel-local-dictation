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
from runtime_config import provider
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
    def __init__(self, v, model_dir, hotwords_path=""):
        d = v["decoding"]
        extra = {}
        if hotwords_path:
            # Hotwords need the BPE vocabulary of the model (bpe.vocab next to the ONNX files).
            vocab = glob.glob(os.path.join(model_dir, "*.vocab"))
            assert len(vocab) == 1, f"hotwords need exactly one *.vocab in {model_dir}"
            extra = dict(hotwords_file=hotwords_path, hotwords_score=v["hotwords"]["score"], modeling_unit="bpe", bpe_vocab=vocab[0])
        self.rec = sherpa_onnx.OfflineRecognizer.from_transducer(
            encoder=one(model_dir, "encoder"), decoder=one(model_dir, "decoder"), joiner=one(model_dir, "joiner"),
            tokens=tokens(model_dir), num_threads=v["threads"], provider=provider(v), model_type="nemo_transducer",
            decoding_method=d["method"], max_active_paths=d["max_active_paths"], blank_penalty=d["blank_penalty"], **extra)

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
            language=w["language"], task="transcribe", num_threads=v["threads"], provider=provider(v), tail_paddings=w["tail_paddings"])

    def begin_take(self, first_chunk):
        return ""

    def transcribe(self, samples):
        s = self.rec.create_stream(); s.accept_waveform(RATE, samples); self.rec.decode_stream(s)
        return s.result.text.strip()


class LanguageSwitched:
    """A model that needs the language as input; spoken-language identification on the first chunk
    picks the language per take. Cohere: one recognizer, the language is set on each stream
    (verified 2026-10-12: the runtime reads it per stream). Canary: one recognizer per language."""

    def __init__(self, v, model_dir, lid_dir, build, per_stream=False):
        self.per_stream = per_stream
        self.recs = {"de": build("de")} if per_stream else {lang: build(lang) for lang in ("de", "en")}
        self.recs = {"de": self.recs["de"], "en": self.recs["de"]} if per_stream else self.recs
        cfg = sherpa_onnx.SpokenLanguageIdentificationConfig(
            whisper=sherpa_onnx.SpokenLanguageIdentificationWhisperConfig(
                encoder=one(lid_dir, "encoder"), decoder=one(lid_dir, "decoder")),
            num_threads=v["threads"], provider=provider(v, "lid"))
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
        s = rec.create_stream()
        if self.per_stream:
            s.set_option("language", self.lang)
        s.accept_waveform(RATE, samples); rec.decode_stream(s)
        return s.result.text.strip()


class Qwen3:
    """Qwen3-ASR: conv frontend + encoder + LLM decoder; hotwords go in as a comma-separated prompt."""

    def __init__(self, v, model_dir, hotwords_path=""):
        words = ""
        if hotwords_path:
            words = ",".join(l.strip() for l in open(hotwords_path, encoding="utf-8") if l.strip())
        self.qwen3 = v["qwen3"]
        self.max_chunk_s = v["cutting"]["max_chunk_s"]
        variant_mod.qwen3_check_budget(self.qwen3, self.max_chunk_s, words)
        self.rec = sherpa_onnx.OfflineRecognizer.from_qwen3_asr(
            conv_frontend=one(model_dir, "conv_frontend"), encoder=one(model_dir, "encoder"), decoder=one(model_dir, "decoder"),
            tokenizer=os.path.join(model_dir, "tokenizer") if os.path.isdir(os.path.join(model_dir, "tokenizer")) else model_dir,
            num_threads=v["threads"], provider=provider(v), hotwords=words,
            max_total_len=self.qwen3["max_total_len"], max_new_tokens=self.qwen3["max_new_tokens"])

    def begin_take(self, first_chunk):
        return ""

    def transcribe(self, samples):
        if len(samples) > round(self.max_chunk_s * RATE):
            raise ValueError("qwen3: input exceeds validated chunk duration")
        s = self.rec.create_stream(); s.accept_waveform(RATE, samples); self.rec.decode_stream(s)
        # sherpa strips up to 16 language/scaffold tokens from the reported tokens. Refuse a
        # near-limit result rather than returning a possibly truncated transcript as success.
        if len(s.result.tokens) >= self.qwen3["max_new_tokens"] - 16:
            raise ValueError("qwen3: result approaches generation limit; increase budget or shorten chunks")
        return s.result.text.strip()


class Cohere:
    def __init__(self, v, model_dir, language=None):
        c = v["cohere"]
        self.rec = sherpa_onnx.OfflineRecognizer.from_cohere_transcribe(
            encoder=one(model_dir, "encoder"), decoder=one(model_dir, "decoder"), tokens=tokens(model_dir),
            num_threads=v["threads"], provider=provider(v), language=c["language"] if language is None else language,
            use_punct=c["use_punct"], use_itn=c["use_itn"])

    def begin_take(self, first_chunk):
        return ""

    def transcribe(self, samples):
        s = self.rec.create_stream(); s.accept_waveform(RATE, samples); self.rec.decode_stream(s)
        return s.result.text.strip()


def recognizer(v, models_root, hotwords_path=""):
    model_dir = os.path.join(models_root, v["model"])
    if v["family"] == "parakeet":
        return Transducer(v, model_dir, hotwords_path)
    if v["family"] == "whisper":
        return Whisper(v, model_dir)
    if v["family"] == "qwen3":
        return Qwen3(v, model_dir, hotwords_path)
    lid_dir = os.path.join(models_root, v["lid"]["model"]) if v["lid"]["model"] else ""
    if v["family"] == "cohere":
        if not lid_dir:
            return Cohere(v, model_dir)
        return LanguageSwitched(v, model_dir, lid_dir, lambda lang: Cohere(v, model_dir, lang).rec, per_stream=True)
    return LanguageSwitched(v, model_dir, lid_dir, lambda lang: sherpa_onnx.OfflineRecognizer.from_nemo_canary(
        encoder=one(model_dir, "encoder"), decoder=one(model_dir, "decoder"), tokens=tokens(model_dir),
        src_lang=lang, tgt_lang=lang, num_threads=v["threads"], provider=provider(v)))


# ---------------------------------------------------------------- cutting (twin of VadSegmenter + PauseChunker)

class Chunker:
    """PauseChunker: contiguous chunks; a chunk ends pad after a speech end once it is at least
    min_samples long; max_samples > 0 forces a cut when the pending audio reaches that length.
    Each chunk carries whether the VAD found speech in it: a speech end was reported inside it, or
    speech was in progress at the cut (no-speech gate, post.skip_silent_chunks)."""

    def __init__(self, min_samples, pad_samples, max_samples):
        self.min, self.pad, self.max = min_samples, pad_samples, max_samples
        self.pending = np.zeros(0, dtype=np.float32)
        self.cut_at = 0
        self.speech_seen = False

    def append(self, samples, speech_now):
        self.pending = np.concatenate([self.pending, samples])
        if self.max and len(self.pending) >= self.max:
            return self._take(self.max, speech_now)
        return None

    def on_speech_end(self, speech_end):
        self.speech_seen = True
        n = min(speech_end + self.pad, self.cut_at + len(self.pending)) - self.cut_at
        if n < self.min:
            return None
        return self._take(n, False)

    def rest(self, speech_now):
        if len(self.pending) == 0:
            return None
        return self._take(len(self.pending), speech_now)

    def _take(self, n, speech_now):
        chunk, self.pending = self.pending[:n], self.pending[n:]
        start = self.cut_at
        self.cut_at += n
        speech = self.speech_seen or speech_now
        self.speech_seen = False
        return start, chunk, speech


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
        vad.accept_waveform(piece)
        forced = ch.append(piece, vad.is_speech_detected())
        if forced:
            out.append(forced)
        while not vad.empty():
            seg = vad.front; vad.pop()
            got = ch.on_speech_end(seg.start + len(seg.samples))
            if got:
                out.append(got)
    rest = ch.rest(vad.is_speech_detected())
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

def run(v, models_root, take_files, guard_dir, vad_model, hotwords_path=""):
    t0 = time.perf_counter()
    rec = recognizer(v, models_root, hotwords_path)
    gate = v["post"]["skip_silent_chunks"]
    report = {"variant": v, "side": "laptop", "loadSeconds": time.perf_counter() - t0,
              "runtime": "sherpa-onnx " + sherpa_onnx.__version__, "takes": [], "guard": []}
    rec.transcribe(np.zeros(RATE, dtype=np.float32))  # warm-up, as the app's first decode would
    for path in take_files:
        x = read_wav(path)
        runs = []
        for feed in FEEDS:
            parts, texts, lid, begun = [], [], "", False
            for start, seg, speech in chunks(v, vad_model, x, feed):
                if gate and not speech:
                    parts.append({"startSeconds": start / RATE, "audioSeconds": len(seg) / RATE, "decodeSeconds": 0.0, "text": "", "skipped": True})
                    continue
                if not begun:
                    lid = rec.begin_take(seg); begun = True
                t = time.perf_counter()
                text = rec.transcribe(seg)
                secs = time.perf_counter() - t
                parts.append({"startSeconds": start / RATE, "audioSeconds": len(seg) / RATE, "decodeSeconds": secs, "text": text, "speech": speech})
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
    silent, noise = np.zeros(5 * RATE, dtype=np.float32), (rng.standard_normal(5 * RATE) * 0.003).astype(np.float32)
    if gate:
        # The gate decides from the VAD, exactly as the app would for a chunk of this audio.
        texts = []
        for x in (silent, noise):
            got = chunks(v, vad_model, x, FEEDS[0])
            texts.append(" ".join(rec.transcribe(seg) for _, seg, speech in got if speech))
        report["silence"] = {"silent": texts[0], "noise": texts[1], "gated": True}
    else:
        report["silence"] = {"silent": rec.transcribe(silent), "noise": rec.transcribe(noise)}
    return report


def main():
    p = argparse.ArgumentParser()
    p.add_argument("variant"); p.add_argument("out")
    p.add_argument("--takes", default="corpus/files/benchmark", help="directory of 16 kHz mono WAV takes")
    p.add_argument("--guard", default=None, help="models/guard to also decode the public clips")
    p.add_argument("--models", default="models")
    p.add_argument("--only", default=None, help="substring of take file names to decode (smoke tests)")
    p.add_argument("--hotwords-dir", default="corpus/lab", help="where hotwords.file is read from")
    a = p.parse_args()
    v = variant_mod.load(a.variant)
    from runtime_config import set_profile_directory
    set_profile_directory(os.path.abspath(a.out) + ".profiles")
    hot = os.path.join(a.hotwords_dir, v["hotwords"]["file"]) if v["hotwords"]["file"] else ""
    if hot:
        assert os.path.isfile(hot), hot
    takes = sorted(f for f in glob.glob(os.path.join(a.takes, "*.wav")) if not a.only or a.only in os.path.basename(f))
    assert takes, a.takes
    report = run(v, a.models, takes, a.guard, os.path.join(a.models, "silero_vad.onnx"), hot)
    with open(a.out, "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=1)


if __name__ == "__main__":
    main()
