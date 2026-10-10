#!/usr/bin/env python3
"""The closed variant schema of the experiment loop (docs/autoresearch.md).

A variant is a JSON file that fully describes one decode pipeline. The same file is read by the
laptop runner (tools/pipeline.py) and by the phone (app: Variant.kt), so every key here has a
Kotlin twin. The loop may change values, never add keys: unknown keys are rejected. Changing
this file is a setup-session change (it is checksummed by tools/lab.py check).

Schema 1. Defaults are the shipped app (0.2.2, ADR-003).
"""
import json

SCHEMA = 1
FAMILIES = ("parakeet", "whisper", "canary")

DEFAULT = {
    "schema": SCHEMA,
    "name": "baseline",
    "description": "app 0.2.2 as shipped: Parakeet v3 MatMul-only INT8, greedy, chunks of 20 s at pauses",
    "family": "parakeet",
    # Directory name under models/ on the laptop; pushed to files/lab/model on the phone.
    "model": "parakeet-v3-matmul8",
    "threads": 4,
    # Transducer decoding (parakeet only). Whisper and Canary in this runtime are greedy-only.
    "decoding": {"method": "greedy_search", "max_active_paths": 4, "blank_penalty": 0.0},
    # Whisper only. language "" = the model detects the language of each chunk itself.
    "whisper": {"language": "", "tail_paddings": -1},
    # Canary only: spoken-language identification on the first chunk of a take decides the language
    # for the whole take. probe_seconds 0 = use the whole first chunk. Languages outside de/en fall back.
    "lid": {"model": "", "probe_seconds": 0.0, "fallback": "en"},
    # Cutting = app's PauseChunker + silero VAD. A chunk ends pad_s after speech ended at a pause
    # (>= min_silence_s of silence), once it is at least min_chunk_s long. The VAD declares a speech
    # end after vad_max_speech_s of continuous speech. max_chunk_s > 0 adds a hard cut when a chunk
    # reaches that length without a pause (Whisper needs <= 28 because this runtime drops audio
    # beyond 30 s).
    "cutting": {"min_chunk_s": 20.0, "pad_s": 0.4, "min_silence_s": 0.8, "min_speech_s": 0.25,
                "vad_max_speech_s": 20.0, "max_chunk_s": 0.0},
    # Closed set of structural text operations. No lexical substitution exists on purpose.
    "post": {"swiss_ss": True, "seam_capitalize": False},
}

_TYPES = {
    "schema": int, "name": str, "description": str, "family": str, "model": str, "threads": int,
    "decoding.method": str, "decoding.max_active_paths": int, "decoding.blank_penalty": float,
    "whisper.language": str, "whisper.tail_paddings": int,
    "lid.model": str, "lid.probe_seconds": float, "lid.fallback": str,
    "cutting.min_chunk_s": float, "cutting.pad_s": float, "cutting.min_silence_s": float,
    "cutting.min_speech_s": float, "cutting.vad_max_speech_s": float, "cutting.max_chunk_s": float,
    "post.swiss_ss": bool, "post.seam_capitalize": bool,
}


class VariantError(ValueError):
    pass


def _flat(d, prefix=""):
    for k, v in d.items():
        key = prefix + k
        if isinstance(v, dict):
            yield from _flat(v, key + ".")
        else:
            yield key, v


def load(path):
    with open(path, encoding="utf-8") as f:
        return validate(json.load(f))


def validate(v):
    """Returns a complete variant (defaults filled in) or raises VariantError."""
    if not isinstance(v, dict):
        raise VariantError("variant must be a JSON object")
    given = dict(_flat(v))
    unknown = sorted(set(given) - set(_TYPES))
    if unknown:
        raise VariantError("unknown keys (the schema is closed): " + ", ".join(unknown))
    out = json.loads(json.dumps(DEFAULT))
    for key, val in given.items():
        want = _TYPES[key]
        if want is float and isinstance(val, int) and not isinstance(val, bool):
            val = float(val)
        if type(val) is not want:
            raise VariantError(f"{key}: expected {want.__name__}, got {type(val).__name__}")
        section, _, leaf = key.rpartition(".")
        (out[section] if section else out)[leaf] = val
    if out["schema"] != SCHEMA:
        raise VariantError(f"schema {out['schema']} not supported, this runner knows {SCHEMA}")
    if out["family"] not in FAMILIES:
        raise VariantError(f"family must be one of {FAMILIES}")
    if not out["name"] or any(c in out["name"] for c in " /\\\t\n"):
        raise VariantError("name must be a single token without spaces or slashes")
    if not 1 <= out["threads"] <= 8:
        raise VariantError("threads must be 1..8")
    d = out["decoding"]
    if d["method"] not in ("greedy_search", "modified_beam_search"):
        raise VariantError("decoding.method must be greedy_search or modified_beam_search")
    if not 1 <= d["max_active_paths"] <= 32:
        raise VariantError("decoding.max_active_paths must be 1..32")
    c = out["cutting"]
    for k in ("min_chunk_s", "pad_s", "min_silence_s", "min_speech_s", "vad_max_speech_s", "max_chunk_s"):
        if c[k] < 0:
            raise VariantError(f"cutting.{k} must not be negative")
    if c["max_chunk_s"] and c["max_chunk_s"] < c["min_chunk_s"]:
        raise VariantError("cutting.max_chunk_s must be 0 or at least min_chunk_s")
    if out["family"] == "whisper":
        if not (0 < c["max_chunk_s"] <= 28):
            raise VariantError("whisper: cutting.max_chunk_s must be set and at most 28 (runtime drops audio beyond 30 s)")
        if out["whisper"]["language"] not in ("", "de", "en"):
            raise VariantError("whisper.language must be '', 'de' or 'en'")
    if out["family"] == "canary":
        if not out["lid"]["model"]:
            raise VariantError("canary: lid.model is required (the model needs the language as input)")
        if out["lid"]["fallback"] not in ("de", "en"):
            raise VariantError("lid.fallback must be de or en")
        if c["max_chunk_s"] and c["max_chunk_s"] > 40:
            raise VariantError("canary: keep chunks at most 40 s")
    if out["family"] != "canary" and out["lid"]["model"]:
        raise VariantError("lid is only used with canary in schema 1")
    return out


def strings(v):
    """All string values of a variant; used by the leak check against the reference texts."""
    return [val for _, val in _flat(v) if isinstance(val, str)]


if __name__ == "__main__":
    import sys
    for p in sys.argv[1:]:
        try:
            load(p); print("ok", p)
        except VariantError as e:
            print("INVALID", p, e); sys.exit(1)
