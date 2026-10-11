#!/usr/bin/env python3
"""Runner of the experiment loop (docs/autoresearch.md). Frozen: checksummed by `check`.

  tools/lab.py check                      freeze, hold-out, leak and asset checks; run before every experiment
  tools/lab.py run VARIANT.json [--guard] [--note TEXT] [--reference NAME]
                                          stage 1 on the laptop: decode the ten takes, score, compare with
                                          the baseline report, append a ledger row; --guard adds the public clips
  tools/lab.py phone-prepare VARIANT.json stage the model and variant under corpus/lab/push/<name>/ for the wrapper
  tools/lab.py phone-score RESULT.json VARIANT.json
                                          stage 2: budget on the phone result, parity with the laptop report, ledger
  tools/lab.py adopt-baseline REPORT.json freeze a stage-1 report of variants/baseline.json as the comparison point
  tools/lab.py ledger [--all]             ranking of kept and discarded experiments
  tools/lab.py dictionary                 write corpus/lab/dictionary.txt (names of the ten scripts) for hotword variants
  tools/lab.py range NAME...              span of total errors over ledger variants: is a knob family worth searching?

Paths are fixed: reports in corpus/lab/runs/<name>-<commit>.json, the ledger in corpus/lab/results.tsv,
the frozen baseline report in corpus/lab/baseline.json (written by the setup session from
variants/baseline.json). Everything under corpus/ is gitignored and holds transcripts; keep it there.
"""
import argparse, datetime, hashlib, json, os, re, shutil, subprocess, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, "tools"))
import evaluate, score, variant as variant_mod  # noqa: E402

PY = os.path.join(ROOT, "models", "venv", "bin", "python")
LAB = os.path.join(ROOT, "corpus", "lab")
RUNS = os.path.join(LAB, "runs")
LEDGER = os.path.join(LAB, "results.tsv")
BASELINE = os.path.join(LAB, "baseline.json")
FROZEN = os.path.join(ROOT, "tools", "frozen.sha256")
HOLDOUT = ("de-4", "de-5", "en-4", "en-5")
COLUMNS = ["when", "commit", "variant", "stage1", "de_errors", "en_errors", "total_errors", "gain", "wins", "losses",
           "terms_missed", "punct_case", "guard_de_wer", "guard_en_wer", "silence_chars", "laptop_rtf", "laptop_last_s",
           "stage2", "phone_rtf", "phone_stop_ms", "phone_vmhwm_mb", "phone_thermal", "phone_parity", "status", "note"]


def sha(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def commit():
    try:
        return subprocess.run(["git", "rev-parse", "--short=7", "HEAD"], cwd=ROOT, capture_output=True, text=True, check=True).stdout.strip()
    except Exception:
        return "nogit"


def fail(msg):
    print("check FAILED: " + msg, file=sys.stderr)
    sys.exit(1)


def check(quiet=False):
    # 1. Frozen files unchanged.
    if not os.path.exists(FROZEN):
        fail("tools/frozen.sha256 missing; the setup session writes it")
    for line in open(FROZEN, encoding="utf-8"):
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        want, rel = line.split(maxsplit=1)
        path = os.path.join(ROOT, rel)
        if not os.path.exists(path):
            fail(f"frozen file missing: {rel}")
        if sha(path) != want:
            fail(f"frozen file changed: {rel}. The loop may not edit the evaluator, the pipeline, the schema, "
                 "the take index or the guard manifest. Restore it (git checkout) and log the quirk instead.")
    # 2. Hold-out scripts out of reach.
    for sid in HOLDOUT:
        if os.path.exists(os.path.join(ROOT, "corpus", "scripts", sid + ".txt")):
            fail(f"hold-out script {sid} is still in corpus/scripts; Oriol moves it out (see docs/autoresearch.md)")
    # 3. Variants carry no reference text (leak check), and validate.
    refs = evaluate.references()
    for name in sorted(os.listdir(os.path.join(ROOT, "variants"))):
        if not name.endswith(".json"):
            continue
        path = os.path.join(ROOT, "variants", name)
        try:
            v = variant_mod.load(path)
        except variant_mod.VariantError as e:
            fail(f"{name}: {e}")
        joined = " ".join(variant_mod.strings(v)).lower()
        for _, text, terms in refs.values():
            for term in terms:
                t = term.lower()
                if len(t) >= 4 and t in joined:
                    fail(f"{name}: contains the reference term '{term}'; variants may not carry reference text")
        if v["name"] != name[:-5]:
            fail(f"{name}: variant name '{v['name']}' must equal the file name")
    # 4. Tooling present.
    if not os.path.exists(PY):
        fail("models/venv missing")
    for rel in ("models/silero_vad.onnx", "models/guard/refs.tsv", "corpus/takes.json"):
        if not os.path.exists(os.path.join(ROOT, rel)):
            fail(f"missing {rel}")
    if not quiet:
        print("check ok")


def ledger_rows():
    if not os.path.exists(LEDGER):
        return []
    with open(LEDGER, encoding="utf-8") as f:
        header = f.readline().rstrip("\n").split("\t")
        return [dict(zip(header, line.rstrip("\n").split("\t"))) for line in f if line.strip()]


def ledger_write(rows):
    os.makedirs(LAB, exist_ok=True)
    with open(LEDGER, "w", encoding="utf-8") as f:
        f.write("\t".join(COLUMNS) + "\n")
        for r in rows:
            f.write("\t".join(str(r.get(c, "")) for c in COLUMNS) + "\n")


def fmt(x):
    return "" if x is None else (f"{x:.2f}" if isinstance(x, float) else str(x))


def run(args):
    check(quiet=True)
    v = variant_mod.load(args.variant)
    os.makedirs(RUNS, exist_ok=True)
    c = commit()
    out = os.path.join(RUNS, f"{v['name']}-{c}.json")
    cmd = [PY, os.path.join(ROOT, "tools", "pipeline.py"), args.variant, out, "--takes", os.path.join(ROOT, "corpus", "files", "benchmark"),
           "--models", os.path.join(ROOT, "models")]
    if args.guard:
        cmd += ["--guard", os.path.join(ROOT, "models", "guard")]
    cmd += ["--hotwords-dir", LAB]
    if variant_mod.is_dictionary(v) and not os.path.isfile(os.path.join(LAB, v["hotwords"]["file"])):
        fail(f"hotwords file corpus/lab/{v['hotwords']['file']} missing; tools/lab.py dictionary writes dictionary.txt")
    print("decoding:", " ".join(os.path.relpath(x, ROOT) if x.startswith(ROOT) else x for x in cmd), file=sys.stderr)
    r = subprocess.run(cmd, cwd=ROOT)
    row = {"when": datetime.datetime.now().strftime("%Y-%m-%d %H:%M"), "commit": c, "variant": v["name"], "note": args.note or v["description"]}
    if r.returncode != 0:
        row.update({"stage1": "crash", "status": "crash"})
        ledger_write(ledger_rows() + [row])
        print("stage1: crash"); sys.exit(1)
    report = json.load(open(out, encoding="utf-8"))
    s = evaluate.print_takes(report)
    row.update({"de_errors": fmt(s["de_errors"]), "en_errors": fmt(s["en_errors"]), "total_errors": fmt(s["total_errors"]),
                "terms_missed": s["terms_missed"], "punct_case": s["punct_case"], "guard_de_wer": fmt(s["guard_de_wer"]),
                "guard_en_wer": fmt(s["guard_en_wer"]), "silence_chars": s["silence_chars"],
                "laptop_rtf": fmt(s["laptop_rtf_median"]), "laptop_last_s": fmt(s["laptop_last_chunk_max_s"])})
    if os.path.exists(BASELINE) and os.path.abspath(out) != os.path.abspath(BASELINE):
        base = json.load(open(BASELINE, encoding="utf-8"))
        hot = os.path.join(LAB, v["hotwords"]["file"]) if variant_mod.is_dictionary(v) else None
        ref = None
        if hot:
            if not args.reference:
                fail("a dictionary variant needs --reference NAME: the same pipeline without hotwords, already run with --guard")
            rrows = [r for r in ledger_rows() if r["variant"] == args.reference and r.get("stage1") in ("pass", "fail", "baseline")]
            if not rrows:
                fail(f"no ledger row for reference variant '{args.reference}'")
            rpath = os.path.join(RUNS, f"{args.reference}-{rrows[-1]['commit']}.json")
            if not os.path.exists(rpath):
                fail(f"reference report missing: {os.path.relpath(rpath, ROOT)}")
            ref = json.load(open(rpath, encoding="utf-8"))
        verdict, reasons, cmp = evaluate.compare(base, report, hot, ref)
        print()
        print("per-take error delta (candidate minus baseline):")
        for f, d in cmp.get("per_take_delta", {}).items():
            print(f"  {f}: {d:+.2f}")
        for k in ("rule", "reference", "gain", "terms_gain", "other_errors_base", "other_errors", "guard_inserts", "wins", "losses", "guard_checked"):
            if k in cmp:
                print(f"{k}: {cmp.get(k)}")
        print(f"stage1: {verdict}")
        print("reasons: " + ("; ".join(reasons) if reasons else "-"))
        row.update({"stage1": verdict, "gain": fmt(cmp.get("gain")), "wins": cmp.get("wins"), "losses": cmp.get("losses"),
                    "status": "pending-phone" if verdict == "pass" else "discard"})
        if cmp.get("rule") == "dictionary":
            row["note"] = f"[dictionary vs {cmp['reference']}: terms +{cmp['terms_gain']}, other {cmp['other_errors_base']}->{cmp['other_errors']}, inserts {cmp['guard_inserts']}] " + row["note"]
    else:
        print("stage1: baseline (no comparison)")
        row.update({"stage1": "baseline", "status": "baseline"})
    print(f"report: {os.path.relpath(out, ROOT)}")
    ledger_write(ledger_rows() + [row])


def phone_prepare(args):
    check(quiet=True)
    v = variant_mod.load(args.variant)
    dest = os.path.join(LAB, "push", v["name"])
    if os.path.exists(dest):
        shutil.rmtree(dest)
    os.makedirs(os.path.join(dest, "model"))
    src = os.path.join(ROOT, "models", v["model"])
    for f in os.listdir(src):
        if f.endswith((".onnx", ".txt", ".weights", ".vocab", ".json", ".data")) and os.path.isfile(os.path.join(src, f)):
            os.link(os.path.join(src, f), os.path.join(dest, "model", f))
    tok = os.path.join(src, "tokenizer")   # Qwen3 keeps its tokenizer files in a subdirectory
    if os.path.isdir(tok):
        for f in os.listdir(tok):
            os.link(os.path.join(tok, f), os.path.join(dest, "model", f))
    if variant_mod.is_dictionary(v):
        shutil.copyfile(os.path.join(LAB, v["hotwords"]["file"]), os.path.join(dest, v["hotwords"]["file"]))
    if v["lid"]["model"]:
        os.makedirs(os.path.join(dest, "lid"))
        lsrc = os.path.join(ROOT, "models", v["lid"]["model"])
        for f in os.listdir(lsrc):
            if f.endswith(".onnx") or f.endswith(".txt"):
                os.link(os.path.join(lsrc, f), os.path.join(dest, "lid", f))
    os.link(os.path.join(ROOT, "models", "silero_vad.onnx"), os.path.join(dest, "silero_vad.onnx"))
    with open(os.path.join(dest, "variant.json"), "w", encoding="utf-8") as f:
        json.dump(v, f, indent=1)
    size = sum(os.path.getsize(os.path.join(d, f)) for d, _, fs in os.walk(dest) for f in fs)
    rel = os.path.relpath(dest, ROOT)
    print(f"staged {rel} ({size / 1e6:.0f} MB). Next, one command per Bash call, in this order:")
    print(f"  tools/phone push-variant {rel}")
    print("  tools/phone lab            (run in the background; several minutes)")
    print("  tools/phone pull-results   (prints the path of the result file under corpus/lab/phone/)")
    print(f"  tools/lab.py phone-score corpus/lab/phone/<file>.json {os.path.relpath(args.variant, ROOT)}")


def phone_score(args):
    check(quiet=True)
    v = variant_mod.load(args.variant)
    result = json.load(open(args.result, encoding="utf-8"))
    if result.get("variant", {}).get("name") != v["name"]:
        fail(f"result is for variant '{result.get('variant', {}).get('name')}', not '{v['name']}'")
    rows = ledger_rows()
    mine = [r for r in rows if r["variant"] == v["name"] and r.get("stage1") in ("pass", "baseline")]
    if not mine:
        fail("no stage-1 pass or baseline row for this variant in the ledger; run stage 1 first")
    row = mine[-1]
    lap_path = os.path.join(RUNS, f"{v['name']}-{row['commit']}.json")
    lap = json.load(open(lap_path, encoding="utf-8")) if os.path.exists(lap_path) else None
    verdict, reasons, flags, s = evaluate.phone_check(result, lap)
    # Accuracy of the phone's own text, for the record and for the divergence check.
    ps = evaluate.summary(result, evaluate.score_takes(result))
    print(f"phone text: de {ps['de_errors']} en {ps['en_errors']} total {ps['total_errors']} (laptop row: {row['total_errors']})")
    if lap is not None:
        ls = evaluate.summary(lap, evaluate.score_takes(lap))
        if abs(ps["total_errors"] - ls["total_errors"]) > 3 + ls["errors_spread"]:
            flags.append(f"divergence: phone total {ps['total_errors']} vs laptop {ls['total_errors']} (spread {ls['errors_spread']}); "
                         "judge this variant on the phone text")
    for k, val in s.items():
        print(f"{k}: {val}")
    print(f"stage2: {verdict}")
    print("reasons: " + ("; ".join(reasons) if reasons else "-"))
    print("flags: " + ("; ".join(flags) if flags else "-"))
    row.update({"stage2": verdict, "phone_rtf": fmt(s["phone_rtf_median"]), "phone_stop_ms": s["phone_stop_max_ms"],
                "phone_vmhwm_mb": s["phone_vmhwm_mb"], "phone_thermal": s["phone_thermal_max"],
                "phone_parity": (s["phone_parity"] or "").replace("\t", " "),
                "status": ("keep" if verdict == "pass" else "discard") if row["status"] != "baseline" else "baseline"})
    if flags:
        row["note"] = (row.get("note", "") + " | " + "; ".join(flags)).strip(" |")
    ledger_write(rows)
    print(f"ledger updated: {row['status']}")


def dictionary(args):
    """Writes corpus/lab/dictionary.txt: the names and terms of the ten scripts without digits, one per line.
    This is the mechanism test of ADR-005: are names recognised when they are in the user's list."""
    words = []
    for _, _, terms in evaluate.references().values():
        for t in terms:
            # Names only: a term whose normalised form contains a digit is a number, date or amount.
            normalised = " ".join(score.normalise(t, "de") + score.normalise(t, "en"))
            if not any(ch.isdigit() for ch in normalised) and t not in words:
                words.append(t)
    os.makedirs(LAB, exist_ok=True)
    path = os.path.join(LAB, "dictionary.txt")
    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(words) + "\n")
    print(f"{len(words)} terms -> {os.path.relpath(path, ROOT)}")


def range_cmd(args):
    """Range-finding: the span of total errors over the named ledger variants. A family whose span
    stays inside the keep margin is not an experiment."""
    rows = {r["variant"]: r for r in ledger_rows()}
    vals = []
    for name in args.variants:
        r = rows.get(name)
        if not r or not r.get("total_errors"):
            print(f"{name}: no stage-1 row"); continue
        vals.append((float(r["total_errors"]), name))
    if not vals:
        return
    lo, hi = min(vals), max(vals)
    base = float(rows["baseline"]["total_errors"]) if "baseline" in rows else None
    print(f"span {lo[0]:.2f} ({lo[1]}) .. {hi[0]:.2f} ({hi[1]}) = {hi[0] - lo[0]:.2f} words; keep margin {evaluate.MIN_TOTAL_GAIN}"
          + (f"; best gain against baseline {base - lo[0]:.2f}" if base is not None else ""))
    print("verdict: " + ("worth searching" if base is not None and base - lo[0] >= evaluate.MIN_TOTAL_GAIN else "inside the margin: not an experiment"))


def adopt_baseline(args):
    report = json.load(open(args.report, encoding="utf-8"))
    if report.get("variant", {}).get("name") != "baseline":
        fail("adopt-baseline takes a report of variants/baseline.json")
    if os.path.exists(BASELINE):
        fail(f"{os.path.relpath(BASELINE, ROOT)} exists; the comparison point is frozen for the campaign")
    shutil.copyfile(args.report, BASELINE)
    print(f"baseline frozen: {os.path.relpath(BASELINE, ROOT)}")


def ledger(args):
    rows = ledger_rows()
    if not rows:
        print("no experiments yet"); return
    print(f"{'status':13} {'variant':28} {'commit':7} {'total':>7} {'de':>6} {'en':>6} {'terms':>5} {'gain':>6} {'rtf':>6} {'phone rtf':>9} {'stop ms':>7} note")
    for r in sorted(rows, key=lambda r: float(r["total_errors"] or 1e9)):
        if not args.all and r["status"] not in ("keep", "baseline", "pending-phone"):
            continue
        print(f"{r['status']:13} {r['variant'][:28]:28} {r['commit']:7} {r['total_errors']:>7} {r['de_errors']:>6} {r['en_errors']:>6} "
              f"{r['terms_missed']:>5} {r['gain']:>6} {r['laptop_rtf']:>6} {r['phone_rtf']:>9} {r['phone_stop_ms']:>7} {r['note'][:60]}")


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)
    sub.add_parser("check")
    r = sub.add_parser("run"); r.add_argument("variant"); r.add_argument("--guard", action="store_true"); r.add_argument("--note")
    r.add_argument("--reference", help="dictionary variants: name of the same pipeline without hotwords (run it with --guard first)")
    pp = sub.add_parser("phone-prepare"); pp.add_argument("variant")
    ps = sub.add_parser("phone-score"); ps.add_argument("result"); ps.add_argument("variant")
    ab = sub.add_parser("adopt-baseline"); ab.add_argument("report")
    sub.add_parser("dictionary")
    rg = sub.add_parser("range"); rg.add_argument("variants", nargs="+")
    lg = sub.add_parser("ledger"); lg.add_argument("--all", action="store_true")
    a = p.parse_args()
    {"check": lambda a: check(), "run": run, "phone-prepare": phone_prepare, "phone-score": phone_score,
     "adopt-baseline": adopt_baseline, "ledger": ledger, "dictionary": dictionary, "range": range_cmd}[a.cmd](a)


if __name__ == "__main__":
    main()
