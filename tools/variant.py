#!/usr/bin/env python3
"""The closed variant schema of the experiment loop (docs/autoresearch.md).

A variant is a JSON file that fully describes one decode pipeline. The same file is read by the
laptop runner (tools/pipeline.py) and by the phone (app: Variant.kt), so every key here has a
Kotlin twin. The loop may change values, never add keys: unknown keys are rejected. Changing
this file is a setup-session change; freeze its identity before measurements.

Schema 4 adds independent model/LID CPU arena, memory pattern and profiling controls.
Schema 3 adds explicit Qwen context/generation budgets and bounded chunks. Schema 1/2 files
still load; their unbounded Qwen chunks migrate to a 40 s cap. Other defaults stay unchanged.
"""
import json
import math

SCHEMA = 4
FAMILIES = ("parakeet", "whisper", "canary", "qwen3", "cohere")

DEFAULT = {
    "schema": SCHEMA,
    "runtime": {"model_arena": True, "model_pattern": True, "lid_arena": True, "lid_pattern": True, "profile": False},
    "name": "baseline",
    "description": "app 0.2.2 as shipped: Parakeet v3 MatMul-only INT8, greedy, chunks of 20 s at pauses",
    "family": "parakeet",
    # Directory name under models/ on the laptop; pushed to files/lab/model on the phone.
    "model": "parakeet-v3-matmul8",
    "threads": 4,
    # Transducer decoding (parakeet only). Whisper, Canary, Qwen3 and Cohere in this runtime are greedy-only.
    "decoding": {"method": "greedy_search", "max_active_paths": 4, "blank_penalty": 0.0},
    # Whisper only. language "" = the model detects the language of each chunk itself.
    "whisper": {"language": "", "tail_paddings": -1},
    "qwen3": {"max_total_len": 1024, "max_new_tokens": 256},
    # Cohere only. language "" = decided per take by lid (required then); or "de" / "en" fixed.
    "cohere": {"language": "", "use_punct": True, "use_itn": True},
    # Canary and Cohere: spoken-language identification on the first chunk of a take decides the language
    # for the whole take. probe_seconds 0 = use the whole first chunk. Languages outside de/en fall back.
    "lid": {"model": "", "probe_seconds": 0.0, "fallback": "en"},
    # Dictionary (ADR-005): a file with one name or term per line, read from corpus/lab/ on the laptop and
    # files/lab/ on the phone. parakeet needs modified_beam_search for it; qwen3 takes it as a prompt
    # (score ignored). Judged by the dictionary metrics, not the general keep rule.
    "hotwords": {"file": "", "score": 1.5},
    # Cutting = app's PauseChunker + silero VAD. A chunk ends pad_s after speech ended at a pause
    # (>= min_silence_s of silence), once it is at least min_chunk_s long. The VAD declares a speech
    # end after vad_max_speech_s of continuous speech. max_chunk_s > 0 adds a hard cut when a chunk
    # reaches that length without a pause (Whisper needs <= 28 because this runtime drops audio
    # beyond 30 s).
    "cutting": {"min_chunk_s": 20.0, "pad_s": 0.4, "min_silence_s": 0.8, "min_speech_s": 0.25,
                "vad_max_speech_s": 20.0, "max_chunk_s": 0.0},
    # Closed set of structural text operations. No lexical substitution exists on purpose.
    # skip_silent_chunks: a chunk in which the VAD found no speech is not decoded at all (no-speech gate).
    "post": {"swiss_ss": True, "seam_capitalize": False, "skip_silent_chunks": False},
}

_TYPES = {
    "runtime.model_arena": bool, "runtime.model_pattern": bool, "runtime.lid_arena": bool, "runtime.lid_pattern": bool, "runtime.profile": bool,
    # Optional, never defaulted, so resolved historical variants stay identical. "webgpu" puts only the
    # Parakeet encoder on WebGPU in the patched ORT 1.31 phone build (tools/ort131-sherpa).
    "runtime.encoder_provider": str,
    "schema": int, "name": str, "description": str, "family": str, "model": str, "threads": int,
    "decoding.method": str, "decoding.max_active_paths": int, "decoding.blank_penalty": float,
    "whisper.language": str, "whisper.tail_paddings": int,
    "qwen3.max_total_len": int, "qwen3.max_new_tokens": int,
    "cohere.language": str, "cohere.use_punct": bool, "cohere.use_itn": bool,
    "lid.model": str, "lid.probe_seconds": float, "lid.fallback": str,
    "hotwords.file": str, "hotwords.score": float,
    "cutting.min_chunk_s": float, "cutting.pad_s": float, "cutting.min_silence_s": float,
    "cutting.min_speech_s": float, "cutting.vad_max_speech_s": float, "cutting.max_chunk_s": float,
    "post.swiss_ss": bool, "post.seam_capitalize": bool, "post.skip_silent_chunks": bool,
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
    for section in ("runtime", "decoding", "whisper", "qwen3", "cohere", "lid", "hotwords", "cutting", "post"):
        if section in v and not isinstance(v[section], dict):
            raise VariantError(f"{section} must be an object")
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
    if out["schema"] not in (1, 2, 3, SCHEMA):
        raise VariantError(f"schema {out['schema']} not supported, this runner knows {SCHEMA}")
    if out["schema"] < 3 and out["family"] == "qwen3" and out["cutting"]["max_chunk_s"] == 0:
        out["cutting"]["max_chunk_s"] = 40.0
    out["schema"] = SCHEMA
    if out["family"] not in FAMILIES:
        raise VariantError(f"family must be one of {FAMILIES}")
    if not out["name"] or any(c in out["name"] for c in " /\\\t\n"):
        raise VariantError("name must be a single token without spaces or slashes")
    if not 1 <= out["threads"] <= 8:
        raise VariantError("threads must be 1..8")
    encoder = out["runtime"].get("encoder_provider", "cpu")
    if encoder not in ("cpu", "webgpu"):
        raise VariantError("runtime.encoder_provider must be cpu or webgpu")
    if encoder == "webgpu" and out["family"] != "parakeet":
        raise VariantError("runtime.encoder_provider webgpu is parakeet only")
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
    fam = out["family"]
    if fam == "qwen3":
        q = out["qwen3"]
        if not 512 <= q["max_total_len"] <= 8192 or not 256 <= q["max_new_tokens"] <= 2048:
            raise VariantError("qwen3 budgets: max_total_len must be 512..8192, max_new_tokens 256..2048")
        if not (0 < c["max_chunk_s"] <= 40):
            raise VariantError("qwen3: cutting.max_chunk_s must be set and at most 40")
        qwen3_check_budget(q, c["max_chunk_s"])
    if fam == "whisper":
        if not (0 < c["max_chunk_s"] <= 28):
            raise VariantError("whisper: cutting.max_chunk_s must be set and at most 28 (runtime drops audio beyond 30 s)")
        if out["whisper"]["language"] not in ("", "de", "en"):
            raise VariantError("whisper.language must be '', 'de' or 'en'")
    if fam == "canary":
        if not out["lid"]["model"]:
            raise VariantError("canary: lid.model is required (the model needs the language as input)")
        if out["lid"]["fallback"] not in ("de", "en"):
            raise VariantError("lid.fallback must be de or en")
        if c["max_chunk_s"] and c["max_chunk_s"] > 40:
            raise VariantError("canary: keep chunks at most 40 s")
    if fam == "cohere":
        if out["cohere"]["language"] not in ("", "de", "en"):
            raise VariantError("cohere.language must be '', 'de' or 'en'")
        if out["cohere"]["language"] == "" and not out["lid"]["model"]:
            raise VariantError("cohere needs the language: set cohere.language or lid.model (the model decodes nothing without one)")
        if out["lid"]["fallback"] not in ("de", "en"):
            raise VariantError("lid.fallback must be de or en")
    if fam not in ("canary", "cohere") and out["lid"]["model"]:
        raise VariantError("lid is only used with canary and cohere")
    h = out["hotwords"]
    if h["file"]:
        if any(ch in h["file"] for ch in "/\\") or not h["file"].endswith(".txt"):
            raise VariantError("hotwords.file is a plain file name ending in .txt, read from corpus/lab/ and files/lab/")
        if fam == "parakeet" and d["method"] != "modified_beam_search":
            raise VariantError("parakeet hotwords need decoding.method modified_beam_search")
        if fam not in ("parakeet", "qwen3"):
            raise VariantError("hotwords are supported for parakeet and qwen3 only")
        if not 0 < h["score"] <= 10:
            raise VariantError("hotwords.score must be in (0, 10]")
    return out


def qwen3_check_budget(q, seconds, hotwords=""):
    """Conservative bound for the pinned export: <=13 audio tokens/s, 64 scaffold tokens.

    UTF-8 byte length bounds the byte-BPE hotword tokens. Called again with the actual prompt
    when loading, because a duration cap alone cannot make arbitrary dictionaries safe.
    The dynamic KV export must be initialized with max_total_len, not overridden per stream.
    """
    required = math.ceil(seconds * 13) + 64 + len(hotwords.encode("utf-8")) + q["max_new_tokens"]
    if required > q["max_total_len"]:
        raise VariantError("qwen3: audio, hotword prompt and generation reserve exceed max_total_len; "
                           "increase the constructor budget, shorten chunks or reduce hotwords")


def strings(v):
    """All string values of a variant; used by the leak check against the reference texts."""
    return [val for _, val in _flat(v) if isinstance(val, str)]


def is_dictionary(v):
    return bool(v["hotwords"]["file"])


if __name__ == "__main__":
    import sys
    for p in sys.argv[1:]:
        try:
            load(p); print("ok", p)
        except VariantError as e:
            print("INVALID", p, e); sys.exit(1)
