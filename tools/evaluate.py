#!/usr/bin/env python3
"""Frozen evaluator of the experiment loop: scores a pipeline report against the references.

One code path for all ten takes: corpus/takes.json maps take files to script ids; references are
corpus/scripts/<id>.txt for de-*/en-* and docs/baseline.md for D6/E6 (via tools/score.py);
corpus/scripts/<id>.terms lists the names and numbers that must come out right.
Normalisation and word error counting are tools/score.py's, unchanged.

Usage:
  tools/evaluate.py takes REPORT.json                  per-take table and totals
  tools/evaluate.py compare BASELINE.json CANDIDATE.json [HOTWORDS.txt]  stage-1 keep rule; with a hotwords
                                                    file the dictionary rule applies (docs/autoresearch.md)
  tools/evaluate.py phone RESULT.json [LAPTOP_REPORT.json]  phone budget and text parity
Prints a summary block of "key: value" lines last; tools/lab.py reads it. Do not edit during a
loop: checksummed by tools/lab.py check. Quirks of the normaliser are logged, not fixed.
"""
import json, os, re, statistics, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, "tools"))
import score  # noqa: E402

# Stage-1 keep rule (adversarial review of 2026-10-10, finding 1): paired per take, a margin above
# the sampling floor, no language, term or punctuation regression beyond the tolerances.
MIN_TOTAL_GAIN = 8        # words fewer in total over the ten takes
MAX_LANG_LOSS = 1         # a language may get worse by at most this many words
MAX_TERMS_LOSS = 1        # terms missed may grow by at most this many
MAX_PUNCT_LOSS = 2        # punctuation/case issues may grow by at most this many
MAX_GUARD_LOSS = 1.0      # guard word error rate per language may grow by at most this many points
MAX_SILENCE_CHARS = 0     # letters or digits produced on 5 s of silence or faint noise

# Dictionary variants (ADR-005, schema 2): judged on the mechanism, not on general accuracy.
DICT_MIN_TERMS_GAIN = 5   # dictionary terms recovered, out of the baseline's misses
DICT_MAX_OTHER_LOSS = 3   # errors outside dictionary words may grow by at most this many
DICT_MAX_GUARD_INSERTS = 1  # dictionary words appearing in public clips where none is spoken

# Phone budget (agreed 2026-10-10). Memory is a flag, not a gate (review finding 9).
MAX_RTF = 0.30
MAX_STOP_MS = 2000
MEMORY_FLAG_KB = 2_000_000
MAX_THERMAL = 2           # PowerManager: 0 none, 1 light, 2 moderate, 3 severe


def references():
    """script id -> (lang, reference text, [terms])"""
    refs, _ = score.baseline()
    out = {}
    scripts = os.path.join(ROOT, "corpus", "scripts")
    for sid in sorted(json.load(open(os.path.join(ROOT, "corpus", "takes.json"))).values()):
        if sid in out:
            continue
        if "-" in sid:
            lang = sid[:2]
            text = open(os.path.join(scripts, sid + ".txt"), encoding="utf-8").read().strip()
        else:
            lang, text = refs[sid]
        terms_path = os.path.join(scripts, sid + ".terms")
        terms = [t.strip() for t in open(terms_path, encoding="utf-8")] if os.path.exists(terms_path) else []
        out[sid] = (lang, text, [t for t in terms if t])
    return out


def punct_case_issues(ref, hyp):
    """Structural count on the raw text (R5 asks for capitalisation and punctuation):
    difference in sentence-final marks, plus words that start a sentence in lower case."""
    marks = lambda t: len(re.findall(r"[.!?](?=\s|$)", t))
    lower_starts = len(re.findall(r"[.!?]\s+[a-zäöü]", hyp)) + (1 if re.match(r"^\s*[a-zäöü]", hyp) else 0)
    return abs(marks(ref) - marks(hyp)) + lower_starts


def secs(chunk):
    """Decode time of a chunk: the laptop writes decodeSeconds, LabTest writes decodeMs."""
    return chunk["decodeSeconds"] if "decodeSeconds" in chunk else chunk.get("decodeMs", 0) / 1000.0


def score_takes(report):
    takes_map = json.load(open(os.path.join(ROOT, "corpus", "takes.json")))
    refs = references()
    rows = []
    for t in report["takes"]:
        sid = takes_map.get(t["file"])
        if sid is None:
            continue
        lang, ref, terms = refs[sid]
        hyp = t.get("text") or ""
        r, h = score.normalise(ref, lang), score.normalise(hyp, lang)
        missed = [x for x in terms if "".join(score.normalise(x, lang)) not in "".join(h)]
        chunks = t.get("chunks", [])
        # Error count averaged over the feed sizes the pipeline tried (see tools/pipeline.py FEEDS);
        # the primary text is what the other columns and the parity check use.
        counts = [score.errors(r, h)] + [score.errors(r, score.normalise(j.get("text") or "", lang)) for j in t.get("jitter", [])]
        rows.append({"file": t["file"], "script": sid, "lang": lang, "words": len(r), "errors": sum(counts) / len(counts),
                     "errorsPrimary": counts[0], "errorsSpread": max(counts) - min(counts),
                     "termsMissed": missed, "termsTotal": len(terms), "punctCase": punct_case_issues(ref, hyp),
                     "chunks": len(chunks), "lid": t.get("lid", ""),
                     "rtf": (sum(secs(c) for c in chunks) / t["audioSeconds"]) if chunks and t.get("audioSeconds") else None,
                     "lastChunkSeconds": secs(chunks[-1]) if chunks else None})
    return rows


def score_guard(report):
    out = {}
    for g in report.get("guard", []):
        refs = out.setdefault(g["lang"], {"errors": 0, "words": 0, "clips": 0, "empty": 0, "decodeSeconds": 0.0, "audioSeconds": 0.0})
    if not report.get("guard"):
        return out
    refs_tsv = os.path.join(ROOT, "models", "guard", "refs.tsv")
    ref_by_id = {}
    with open(refs_tsv, encoding="utf-8") as f:
        next(f)
        for line in f:
            cid, lang, secs, ref = line.rstrip("\n").split("\t")
            ref_by_id[cid] = ref
    for g in report["guard"]:
        lang = g["lang"]
        r, h = score.normalise(ref_by_id[g["id"]], lang), score.normalise(g["text"], lang)
        a = out[lang]
        a["errors"] += score.errors(r, h); a["words"] += len(r); a["clips"] += 1
        a["empty"] += 1 if not h else 0
        a["decodeSeconds"] += g["decodeSeconds"]; a["audioSeconds"] += g["audioSeconds"]
    for a in out.values():
        a["wer"] = 100.0 * a["errors"] / max(a["words"], 1)
    return out


def dictionary_words(path):
    """Normalised words of a hotwords file, for the non-dictionary error count and the guard insert check."""
    words = set()
    for line in open(path, encoding="utf-8"):
        term = line.split(" :")[0].strip()
        if term:
            for lang in ("de", "en"):
                words.update(score.normalise(term, lang))
    return {w for w in words if not any(ch.isdigit() for ch in w)}


def other_errors(report, words):
    """Word errors on the ten takes with the dictionary words removed from reference and hypothesis."""
    takes_map = json.load(open(os.path.join(ROOT, "corpus", "takes.json")))
    refs = references()
    total = 0.0
    for t in report["takes"]:
        sid = takes_map.get(t["file"])
        if sid is None:
            continue
        lang, ref, _ = refs[sid]
        r = [w for w in score.normalise(ref, lang) if w not in words]
        hyps = [t.get("text") or ""] + [j.get("text") or "" for j in t.get("jitter", [])]
        total += sum(score.errors(r, [w for w in score.normalise(h, lang) if w not in words]) for h in hyps) / len(hyps)
    return round(total, 2)


def guard_inserts(report, words):
    """Dictionary words in guard hypotheses that are not in the clip's reference."""
    if not report.get("guard"):
        return None
    ref_by_id = {}
    with open(os.path.join(ROOT, "models", "guard", "refs.tsv"), encoding="utf-8") as f:
        next(f)
        for line in f:
            cid, lang, secs, ref = line.rstrip("\n").split("\t")
            ref_by_id[cid] = (lang, ref)
    n = 0
    for g in report["guard"]:
        lang, ref = ref_by_id[g["id"]]
        r = set(score.normalise(ref, lang))
        n += sum(1 for w in score.normalise(g["text"], lang) if w in words and w not in r)
    return n


def totals(rows):
    t = {}
    for r in rows:
        a = t.setdefault(r["lang"], {"errors": 0, "words": 0, "termsMissed": 0, "termsTotal": 0, "punctCase": 0})
        a["errors"] += r["errors"]; a["words"] += r["words"]
        a["termsMissed"] += len(r["termsMissed"]); a["termsTotal"] += r["termsTotal"]; a["punctCase"] += r["punctCase"]
    return t


def summary(report, rows):
    t = totals(rows)
    g = score_guard(report)
    s = {}
    for lang in ("de", "en"):
        a = t.get(lang, {"errors": 0, "words": 0, "termsMissed": 0, "termsTotal": 0, "punctCase": 0})
        s[f"{lang}_errors"] = round(a["errors"], 2); s[f"{lang}_words"] = a["words"]
        s[f"{lang}_wer"] = round(100.0 * a["errors"] / max(a["words"], 1), 2)
    s["total_errors"] = round(s["de_errors"] + s["en_errors"], 2)
    s["errors_spread"] = round(sum(r["errorsSpread"] for r in rows), 1)
    s["terms_missed"] = sum(a["termsMissed"] for a in t.values())
    s["terms_total"] = sum(a["termsTotal"] for a in t.values())
    s["punct_case"] = sum(a["punctCase"] for a in t.values())
    rtfs = [r["rtf"] for r in rows if r["rtf"] is not None]
    s["laptop_rtf_median"] = round(statistics.median(rtfs), 4) if rtfs else None
    s["laptop_last_chunk_max_s"] = round(max(r["lastChunkSeconds"] for r in rows if r["lastChunkSeconds"] is not None), 2) if rtfs else None
    for lang in ("de", "en"):
        s[f"guard_{lang}_wer"] = round(g[lang]["wer"], 2) if lang in g else None
        s[f"guard_{lang}_empty"] = g[lang]["empty"] if lang in g else None
    sil = report.get("silence", {})
    s["silence_chars"] = sum(1 for ch in (sil.get("silent") or "") + (sil.get("noise") or "") if ch.isalnum())
    return s


def print_takes(report):
    rows = score_takes(report)
    print(f"{'take':24} {'script':6} {'lang':4} {'errors':>11} {'wer':>6} {'terms':>7} {'punct':>5} {'chunks':>6} {'rtf':>6} lid   (errors = mean over feeds, ±spread)")
    for r in rows:
        print(f"{r['file']:24} {r['script']:6} {r['lang']:4} {r['errors']:5.1f}±{r['errorsSpread']:<1}/{r['words']:<4} {100 * r['errors'] / r['words']:5.1f}% "
              f"{len(r['termsMissed']):3}/{r['termsTotal']:<3} {r['punctCase']:5} {r['chunks']:6} "
              f"{(r['rtf'] if r['rtf'] is not None else 0):6.3f} {r['lid']}")
        if r["termsMissed"]:
            print(f"{'':24} missed: {', '.join(r['termsMissed'])}")
    s = summary(report, rows)
    print()
    for k, v in s.items():
        print(f"{k}: {v}")
    return s


def compare(base_report, cand_report, hotwords_path=None):
    """Stage-1 keep rule. Returns (verdict, reasons, summary). With hotwords_path the dictionary rule applies."""
    if hotwords_path:
        return compare_dictionary(base_report, cand_report, hotwords_path)
    b_rows, c_rows = score_takes(base_report), score_takes(cand_report)
    b, c = {r["file"]: r for r in b_rows}, {r["file"]: r for r in c_rows}
    reasons = []
    if set(b) != set(c):
        return "invalid", ["candidate and baseline cover different takes"], {}
    wins = sum(1 for f in b if c[f]["errors"] < b[f]["errors"])
    losses = sum(1 for f in b if c[f]["errors"] > b[f]["errors"])
    bs, cs = summary(base_report, b_rows), summary(cand_report, c_rows)
    gain = bs["total_errors"] - cs["total_errors"]
    ok = True
    if gain < MIN_TOTAL_GAIN:
        ok = False; reasons.append(f"total gain {gain} < {MIN_TOTAL_GAIN}")
    if wins <= losses:
        ok = False; reasons.append(f"takes improved {wins} <= takes worse {losses}")
    for lang in ("de", "en"):
        if cs[f"{lang}_errors"] - bs[f"{lang}_errors"] > MAX_LANG_LOSS:
            ok = False; reasons.append(f"{lang} worse by {cs[f'{lang}_errors'] - bs[f'{lang}_errors']} words")
    if cs["terms_missed"] - bs["terms_missed"] > MAX_TERMS_LOSS:
        ok = False; reasons.append(f"terms missed {bs['terms_missed']} -> {cs['terms_missed']}")
    if cs["punct_case"] - bs["punct_case"] > MAX_PUNCT_LOSS:
        ok = False; reasons.append(f"punctuation/case issues {bs['punct_case']} -> {cs['punct_case']}")
    if cs["silence_chars"] > MAX_SILENCE_CHARS:
        ok = False; reasons.append(f"text on silence or faint noise ({cs['silence_chars']} characters)")
    guard_checked = False
    for lang in ("de", "en"):
        if bs[f"guard_{lang}_wer"] is not None and cs[f"guard_{lang}_wer"] is not None:
            guard_checked = True
            if cs[f"guard_{lang}_wer"] - bs[f"guard_{lang}_wer"] > MAX_GUARD_LOSS:
                ok = False; reasons.append(f"guard {lang} {bs[f'guard_{lang}_wer']} -> {cs[f'guard_{lang}_wer']}")
    per_take = {f: round(c[f]["errors"] - b[f]["errors"], 2) for f in sorted(b)}
    out = {"verdict": "pass" if ok else "fail", "gain": gain, "wins": wins, "losses": losses,
           "guard_checked": guard_checked, "per_take_delta": per_take, "reasons": reasons, "candidate": cs, "baseline": bs}
    return out["verdict"], reasons, out


def compare_dictionary(base_report, cand_report, hotwords_path):
    """Dictionary rule: terms recovered, other words not worse, no insertions in the guard, silence clean."""
    b_rows, c_rows = score_takes(base_report), score_takes(cand_report)
    bs, cs = summary(base_report, b_rows), summary(cand_report, c_rows)
    words = dictionary_words(hotwords_path)
    b_other, c_other = other_errors(base_report, words), other_errors(cand_report, words)
    inserts = guard_inserts(cand_report, words)
    reasons, ok = [], True
    terms_gain = bs["terms_missed"] - cs["terms_missed"]
    if terms_gain < DICT_MIN_TERMS_GAIN:
        ok = False; reasons.append(f"terms recovered {terms_gain} < {DICT_MIN_TERMS_GAIN}")
    if c_other - b_other > DICT_MAX_OTHER_LOSS:
        ok = False; reasons.append(f"errors outside dictionary words {b_other} -> {c_other}")
    if inserts is None:
        ok = False; reasons.append("guard not run: a dictionary variant needs --guard for the false-insertion check")
    elif inserts > DICT_MAX_GUARD_INSERTS:
        ok = False; reasons.append(f"dictionary words inserted into public clips: {inserts}")
    if cs["silence_chars"] > MAX_SILENCE_CHARS:
        ok = False; reasons.append(f"text on silence or faint noise ({cs['silence_chars']} characters)")
    for lang in ("de", "en"):
        if bs[f"guard_{lang}_wer"] is not None and cs[f"guard_{lang}_wer"] is not None \
                and cs[f"guard_{lang}_wer"] - bs[f"guard_{lang}_wer"] > MAX_GUARD_LOSS:
            ok = False; reasons.append(f"guard {lang} {bs[f'guard_{lang}_wer']} -> {cs[f'guard_{lang}_wer']}")
    b, c = {r["file"]: r for r in b_rows}, {r["file"]: r for r in c_rows}
    out = {"verdict": "pass" if ok else "fail", "rule": "dictionary", "gain": round(bs["total_errors"] - cs["total_errors"], 2),
           "terms_gain": terms_gain, "other_errors_base": b_other, "other_errors": c_other, "guard_inserts": inserts,
           "wins": sum(1 for f in b if c[f]["errors"] < b[f]["errors"]), "losses": sum(1 for f in b if c[f]["errors"] > b[f]["errors"]),
           "guard_checked": inserts is not None, "per_take_delta": {f: round(c[f]["errors"] - b[f]["errors"], 2) for f in sorted(b)},
           "reasons": reasons, "candidate": cs, "baseline": bs}
    return out["verdict"], reasons, out


def phone_check(result, laptop_report=None):
    """Phone budget on a LabTest result file; text parity with the laptop report of the same variant."""
    reasons, flags = [], []
    rtfs = [c["rtf"] for t in result["takes"] for c in t["chunks"]]
    rtf_med = statistics.median(rtfs) if rtfs else None
    if rtf_med is None or rtf_med > MAX_RTF:
        reasons.append(f"median chunk rtf {rtf_med} > {MAX_RTF}")
    paced = result.get("paced", [])
    stop_max = max((p["stopToFinalMs"] for p in paced), default=None)
    if stop_max is None or stop_max > MAX_STOP_MS:
        reasons.append(f"stop-to-final {stop_max} ms > {MAX_STOP_MS}")
    thermal_max = max([result.get("before", {}).get("thermalStatus", 0), result.get("after", {}).get("thermalStatus", 0)]
                      + [p.get("thermalAfter", 0) for p in paced])
    if thermal_max > MAX_THERMAL:
        reasons.append(f"thermal status reached {thermal_max}")
    vm = result.get("vmHwmKb")
    if vm and vm > MEMORY_FLAG_KB:
        flags.append(f"memory: peak {vm / 1e6:.2f} GB above the {MEMORY_FLAG_KB / 1e6:.0f} GB flag")
    parity = None
    if laptop_report:
        fold = lambda s: " ".join((s or "").replace("ß", "ss").split())
        lap = {t["file"]: fold(t["text"]) for t in laptop_report["takes"]}
        diff = [t["file"] for t in result["takes"] if t["file"] in lap and fold(t["text"]) != lap[t["file"]]]
        parity = "identical" if not diff else f"{len(diff)} of {len(result['takes'])} takes differ: " + ", ".join(diff)
    s = {"phone_rtf_median": round(rtf_med, 4) if rtf_med is not None else None, "phone_stop_max_ms": stop_max,
         "phone_vmhwm_mb": round(vm / 1000) if vm else None, "phone_thermal_max": thermal_max,
         "phone_cold_load_ms": result.get("coldLoadMs"), "phone_parity": parity}
    return ("pass" if not reasons else "fail"), reasons, flags, s


def main():
    cmd = sys.argv[1]
    if cmd == "takes":
        print_takes(json.load(open(sys.argv[2], encoding="utf-8")))
    elif cmd == "compare":
        base = json.load(open(sys.argv[2], encoding="utf-8")); cand = json.load(open(sys.argv[3], encoding="utf-8"))
        verdict, reasons, out = compare(base, cand, sys.argv[4] if len(sys.argv) > 4 else None)
        print("per-take error delta (candidate minus baseline):")
        for f, d in out.get("per_take_delta", {}).items():
            print(f"  {f}: {d:+.2f}")
        print()
        for k in ("rule", "gain", "terms_gain", "other_errors_base", "other_errors", "guard_inserts", "wins", "losses", "guard_checked"):
            if k in out:
                print(f"{k}: {out.get(k)}")
        for k, v in out.get("candidate", {}).items():
            print(f"{k}: {v}")
        print(f"stage1: {verdict}")
        print("reasons: " + ("; ".join(reasons) if reasons else "-"))
    elif cmd == "phone":
        result = json.load(open(sys.argv[2], encoding="utf-8"))
        lap = json.load(open(sys.argv[3], encoding="utf-8")) if len(sys.argv) > 3 else None
        verdict, reasons, flags, s = phone_check(result, lap)
        for k, v in s.items():
            print(f"{k}: {v}")
        print(f"stage2: {verdict}")
        print("reasons: " + ("; ".join(reasons) if reasons else "-"))
        print("flags: " + ("; ".join(flags) if flags else "-"))
    else:
        print(__doc__); sys.exit(2)


if __name__ == "__main__":
    main()
