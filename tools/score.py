#!/usr/bin/env python3
"""Word error rate of replay reports against the reference scripts in docs/baseline.md.

Usage: tools/score.py TAKES.json REPORT.json [REPORT.json ...]
TAKES.json maps a take's file name to its script id, e.g. {"20261010-080909-723.wav": "D6"}.
Reports come from tools/replay.py. Prints one row per take and decode path, and FUTO's
row from the raw output recorded in docs/baseline.md.

Normalisation before counting: lower case, punctuation removed, hyphens become spaces,
spoken numbers and ordinals become digits, gigabyte(s) becomes gb. Names and numbers
are also reported on their own, because one wrong name matters more than its word count.
"""
import json, os, re, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

NUM = {
    "de": dict(zwei=2, drei=3, vier=4, fünf=5, sechs=6, sieben=7, acht=8, neun=9, zehn=10, elf=11,
               zwölf=12, dreizehn=13, vierzehn=14, fünfzehn=15, sechzehn=16, siebzehn=17, achtzehn=18,
               neunzehn=19, zwanzig=20, ersten=1, zweiten=2, dritten=3, vierten=4, fünften=5,
               sechsten=6, siebten=7, achten=8, neunten=9, zehnten=10, elften=11, zwölften=12),
    "en": dict(two=2, three=3, four=4, five=5, six=6, seven=7, eight=8, nine=9, ten=10, eleven=11,
               twelve=12, thirteen=13, fourteen=14, fifteen=15, sixteen=16, seventeen=17, eighteen=18,
               nineteen=19, twenty=20, first=1, second=2, third=3, fourth=4, fifth=5, sixth=6,
               seventh=7, eighth=8, ninth=9, tenth=10, eleventh=11, twelfth=12),
}
# Checked on the normalised text with spaces removed, so "Tensor-Chip" and "Tensorchip" both count.
NAMES = {"de": ["zürich", "keller", "tensor", "winterthur"], "en": ["zurich", "mrskeller", "tensor", "winterthur"]}
NUMBERS = {"de": ["halb 3", "3 oktober", "12 november", "16 gb", "gleis 4", "vor 9"],
           "en": ["half past 2", "3 of october", "12 of november", "16 gb", "platform 4", "before 9"]}


def normalise(text, lang):
    t = text.lower().replace("-", " ")
    t = re.sub(r"(\d+)(st|nd|rd|th)\b", r"\1", t)
    t = re.sub(r"[^\w\s]", " ", t)
    words = []
    for w in t.split():
        w = {"gigabyte": "gb", "gigabytes": "gb"}.get(w, w)
        words.append(str(NUM[lang].get(w, w)))
    return words


def errors(ref, hyp):
    """Levenshtein distance on words."""
    row = list(range(len(hyp) + 1))
    for i, r in enumerate(ref, 1):
        prev, row[0] = row[0], i
        for j, h in enumerate(hyp, 1):
            prev, row[j] = row[j], min(row[j] + 1, row[j - 1] + 1, prev + (r != h))
    return row[-1]


def baseline():
    text = open(os.path.join(ROOT, "docs", "baseline.md"), encoding="utf-8").read()
    refs, futo = {}, {}
    for lang, label in (("de", "German"), ("en", "English")):
        line = re.search(rf"^{label}: (.+)$", text, re.M).group(1)
        parts = re.split(r"\s*\b([DE]\d) ", line)[1:]
        sentences = dict(zip(parts[0::2], parts[1::2]))
        for k, v in sentences.items():
            refs[k] = (lang, v)
        refs[sorted(sentences)[0][0] + "6"] = (lang, " ".join(sentences[k] for k in sorted(sentences)))
    for m in re.finditer(r'^([DE]6): "(.+)"$', text, re.M):
        futo[m.group(1)] = m.group(2)
    return refs, futo


def row(label, script, refs, hyp):
    lang, ref = refs[script]
    r, h = normalise(ref, lang), normalise(hyp, lang)
    e = errors(r, h)
    squashed, spaced = "".join(h), " ".join(h)
    names = [n for n in NAMES[lang] if n not in squashed]
    numbers = [n for n in NUMBERS[lang] if n not in spaced]
    print(f"{script:3} {label:34} {e:2}/{len(r)} = {100 * e / len(r):5.1f}%   "
          f"names missed: {', '.join(names) or '-':22} numbers missed: {', '.join(numbers) or '-'}")
    return lang, e, len(r)


def main():
    takes = json.load(open(sys.argv[1]))
    refs, futo = baseline()
    totals = {}
    def add(key, res):
        lang, e, n = res
        a = totals.setdefault((key, lang), [0, 0]); a[0] += e; a[1] += n
    for script, hyp in sorted(futo.items()):
        add("FUTO (baseline.md, other take)", row("FUTO (baseline.md, other take)", script, refs, hyp))
    for path in sys.argv[2:]:
        rep = json.load(open(path))
        for t in rep["takes"]:
            script = takes[t["file"]]
            for kind in ("whole", "segmented"):
                if kind in t:
                    key = f"{rep['model'][-12:]} {kind}"
                    add(key, row(f"{key} {t['file'][9:15]}", script, refs, t[kind]["text"]))
    print()
    for (key, lang), (e, n) in sorted(totals.items()):
        print(f"{lang} {key:34} {e:3}/{n} = {100 * e / n:5.1f}%")


if __name__ == "__main__":
    main()
