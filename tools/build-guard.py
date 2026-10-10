#!/usr/bin/env python3
"""Build the public regression guard once, on the laptop (setup step, not part of the loop).

Picks a fixed sample of read-speech clips, 5-20 s long, from two public test sets that were
downloaded and pinned by tools/fetch-experiment-assets.sh:
  German:  Multilingual LibriSpeech, test split (CC BY 4.0), models/guard-src/mls-german-test.parquet
  English: LibriSpeech test-clean (CC BY 4.0), models/guard-src/test-clean.tar.gz
Writes models/guard/<lang>/<id>.wav (16 kHz mono PCM) and models/guard/refs.tsv
(id, lang, seconds, reference). The sample is deterministic (seed below), so the manifest
checksum in experiments.lock identifies the guard exactly.

Usage: models/venv/bin/python tools/build-guard.py [--per-lang 150]
"""
import argparse, io, os, random, subprocess, sys, tarfile, wave
import numpy as np
import soundfile as sf

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "models", "guard-src")
OUT = os.path.join(ROOT, "models", "guard")
SEED = 20261010
MIN_S, MAX_S = 5.0, 20.0


def write_wav(path, samples):
    pcm = np.clip(samples * 32768, -32768, 32767).astype("<i2")
    with wave.open(path, "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(16000); w.writeframes(pcm.tobytes())


def resample_to_16k(samples, rate):
    if rate == 16000:
        return samples.astype(np.float32)
    # Linear interpolation is enough for a regression guard; the sets are 16 kHz anyway.
    n = int(round(len(samples) * 16000 / rate))
    return np.interp(np.linspace(0, len(samples) - 1, n), np.arange(len(samples)), samples).astype(np.float32)


def german(per_lang, rng):
    import pyarrow.parquet as pq
    t = pq.read_table(os.path.join(SRC, "mls-german-test.parquet"),
                      columns=["id", "speaker_id", "audio_duration", "transcript", "audio"])
    rows = [r for r in t.to_pylist() if MIN_S <= r["audio_duration"] <= MAX_S]
    rows.sort(key=lambda r: r["id"])
    # At most three clips per speaker, so one voice cannot dominate.
    rng.shuffle(rows)
    picked, per_speaker = [], {}
    for r in rows:
        if per_speaker.get(r["speaker_id"], 0) >= 3:
            continue
        per_speaker[r["speaker_id"]] = per_speaker.get(r["speaker_id"], 0) + 1
        picked.append(r)
        if len(picked) == per_lang:
            break
    out = []
    for r in picked:
        # Opus bytes; ffmpeg decodes to 16 kHz mono PCM on stdout.
        pcm = subprocess.run(["ffmpeg", "-v", "error", "-i", "pipe:0", "-f", "s16le", "-ac", "1", "-ar", "16000", "pipe:1"],
                             input=r["audio"]["bytes"], stdout=subprocess.PIPE, check=True).stdout
        x = np.frombuffer(pcm, dtype="<i2").astype(np.float32) / 32768
        out.append((r["id"], x, r["transcript"]))
    return out


def english(per_lang, rng):
    tf = tarfile.open(os.path.join(SRC, "test-clean.tar.gz"))
    members = {m.name: m for m in tf.getmembers() if m.isfile()}
    refs = {}
    for name, m in members.items():
        if name.endswith(".trans.txt"):
            for line in tf.extractfile(m).read().decode().splitlines():
                k, _, v = line.partition(" ")
                refs[k] = v
    ids = sorted(refs)
    rng.shuffle(ids)
    out, per_speaker = [], {}
    for k in ids:
        spk = k.split("-")[0]
        if per_speaker.get(spk, 0) >= 3:
            continue
        path = next(n for n in members if n.endswith("/" + k + ".flac"))
        x, rate = sf.read(io.BytesIO(tf.extractfile(members[path]).read()), dtype="float32")
        x = resample_to_16k(x if x.ndim == 1 else x.mean(axis=1), rate)
        if not MIN_S <= len(x) / 16000 <= MAX_S:
            continue
        per_speaker[spk] = per_speaker.get(spk, 0) + 1
        out.append((k, x, refs[k]))
        if len(out) == per_lang:
            break
    return out


def main():
    p = argparse.ArgumentParser(); p.add_argument("--per-lang", type=int, default=150)
    a = p.parse_args()
    rng = random.Random(SEED)
    rows = []
    for lang, fn in (("de", german), ("en", english)):
        os.makedirs(os.path.join(OUT, lang), exist_ok=True)
        clips = fn(a.per_lang, random.Random(SEED + (0 if lang == "de" else 1)))
        for cid, x, ref in clips:
            write_wav(os.path.join(OUT, lang, cid + ".wav"), x)
            rows.append((cid, lang, "%.2f" % (len(x) / 16000), " ".join(ref.split())))
        print(lang, len(clips), "clips, %.1f min" % (sum(len(x) for _, x, _ in clips) / 16000 / 60))
    rows.sort()
    with open(os.path.join(OUT, "refs.tsv"), "w", encoding="utf-8") as f:
        f.write("id\tlang\tseconds\treference\n")
        for r in rows:
            f.write("\t".join(r) + "\n")
    print("written", os.path.join(OUT, "refs.tsv"))


if __name__ == "__main__":
    main()
