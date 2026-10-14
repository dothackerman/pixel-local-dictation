#!/usr/bin/env python3
"""Word error rate of replay reports against the reference scripts in docs/evaluation/baseline.md.

Usage: tools/score.py TAKES.json REPORT.json [REPORT.json ...]
       tools/score.py --corpus corpus        (milestone 3: Oriol's scripts, FUTO against the app)
TAKES.json maps a take's file name to its script id, e.g. {"20261010-080909-723.wav": "D6"}.
Reports come from tools/replay.py. Prints one row per take and decode path, and FUTO's
row from the raw output recorded in docs/evaluation/baseline.md.

Normalisation before counting: lower case, punctuation removed, hyphens become spaces,
spoken numbers below 1000 and ordinals up to twelfth become digits, gigabyte(s) becomes gb,
percent becomes %, a few British/American spelling pairs are treated as equal; accents other
than German umlauts are ignored; unit abbreviations, money ("£3.80", "4.80 Franken", "vier Franken
achtzig"), clock times ("14:35 Uhr", "14 Uhr 35", "2.35pm") and spoken years are brought to one form. Names and numbers
are also reported on their own, because one wrong name matters more than its word count.
"""
import json, os, re, sys, unicodedata

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


def _german_numbers():
    ones = ["", "ein", "zwei", "drei", "vier", "fünf", "sechs", "sieben", "acht", "neun"]
    teens = ["zehn", "elf", "zwölf", "dreizehn", "vierzehn", "fünfzehn", "sechzehn", "siebzehn", "achtzehn", "neunzehn"]
    tens = ["", "", "zwanzig", "dreissig", "vierzig", "fünfzig", "sechzig", "siebzig", "achtzig", "neunzig"]
    def below100(n):
        if n < 10: return ones[n]
        if n < 20: return teens[n - 10]
        return tens[n // 10] if n % 10 == 0 else ones[n % 10] + "und" + tens[n // 10]
    out = {}
    for n in range(21, 1000):
        h, r = divmod(n, 100)
        if h:
            out[ones[h] + "hundert" + below100(r)] = n
            if h == 1: out["hundert" + below100(r)] = n
        else:
            out[below100(n)] = n
    return out


GERMAN_COMPOUNDS = _german_numbers()
# Ordinals in dates: "fünfzehnten", "einundzwanzigsten" -> 15, 21
GERMAN_COMPOUNDS.update({w + "ten": n for w, n in list(NUM["de"].items()) if 13 <= n <= 19 and not w.endswith("ten")})
GERMAN_COMPOUNDS.update({w + "sten": n for w, n in list(GERMAN_COMPOUNDS.items()) if 20 < n <= 31 and not w.endswith("ten")})
GERMAN_COMPOUNDS.update({"zwanzigsten": 20, "dreissigsten": 30})
NUM["en"].update(thirteenth=13, fourteenth=14, fifteenth=15, sixteenth=16, seventeenth=17, eighteenth=18,
                 nineteenth=19, twentieth=20, thirtieth=30)
EN_ORDINAL_UNITS = dict(first=1, second=2, third=3, fourth=4, fifth=5, sixth=6, seventh=7, eighth=8, ninth=9)
EN_TENS = dict(twenty=20, thirty=30, forty=40, fifty=50, sixty=60, seventy=70, eighty=80, ninety=90)
EN_UNITS = dict(one=1, two=2, three=3, four=4, five=5, six=6, seven=7, eight=8, nine=9)
# Spelling variants that are not recognition errors.
VARIANTS = {"flavour": "flavor", "tyres": "tires", "colour": "color", "grey": "gray", "café": "cafe",
            "gigabyte": "gb", "gigabytes": "gb", "percent": "%", "prozent": "%", "chilli": "chili",
            "franc": "franken", "frank": "franken", "francs": "franken", "pound": "pounds", "euro": "euros"}


def _english_numbers(words):
    """twenty three -> 23, two hundred and fifty -> 250, on the token list."""
    out, i = [], 0
    while i < len(words):
        w, n, k = words[i], None, i
        if w in EN_UNITS and k + 1 < len(words) and words[k + 1] == "hundred":
            n, k = EN_UNITS[w] * 100, k + 2
            if k < len(words) and words[k] == "and" and k + 1 < len(words) and (words[k + 1] in EN_TENS or words[k + 1] in NUM["en"]):
                k += 1
        if k < len(words) and words[k] in EN_TENS:
            n, k = (n or 0) + EN_TENS[words[k]], k + 1
            if k < len(words) and (words[k] in EN_UNITS or words[k] in EN_ORDINAL_UNITS):
                n, k = n + {**EN_UNITS, **EN_ORDINAL_UNITS}[words[k]], k + 1
        elif n is not None and k < len(words) and words[k] in NUM["en"] and not words[k].endswith(("st", "nd", "rd", "th")):
            n, k = n + NUM["en"][words[k]], k + 1
        if n is None:
            out.append(w); i += 1
        else:
            out.append(str(n)); i = k
    return out


UNITS = {"km": "kilometer", "kilometern": "kilometer", "ml": "milliliter", "millilitres": "milliliter",
         "milliliters": "milliliter", "g": "gramm", "fr": "franken", "litern": "liter", "litres": "liters"}
CURRENCY = {"£": "pounds", "€": "euros"}


def _accents(t):
    """Léa = Lea, Neuchâtel = Neuchatel, jalapeño = jalapeno; German umlauts stay."""
    keep = "äöü"
    return "".join(c if c in keep else unicodedata.normalize("NFD", c)[0] for c in t)


def _money_and_times(t):
    t = re.sub(r"([£€])\s?(\d+)(?:[.,](\d\d))?", lambda m: f" {m.group(2)} {CURRENCY[m.group(1)]} {m.group(3) or ''} ", t)
    t = re.sub(r"\b(\d+)[.,](\d\d)\s+(franken|frank\b|franc\b|fr\b\.?|pounds|euros?)", r"\1 \3 \2", t)   # 4.80 Franken = 4 Franken 80
    t = re.sub(r"\b0?(\d{1,2})[:.](\d\d)(?=\s*(uhr|[ap]\.?\s?m\b))", r"\1 \2", t)     # 14:35 Uhr, 9.20 am
    return t


def _years(words, lang):
    out, i = [], 0
    while i < len(words):
        w = words[i]
        nxt = words[i + 1] if i + 1 < len(words) else ""
        nxt2 = words[i + 2] if i + 2 < len(words) else ""
        if w == "2" and nxt in ("thousand", "tausend") and nxt2.isdigit() and len(nxt2) <= 2:
            out.append(str(2000 + int(nxt2))); i += 3                       # two thousand twenty eight
        elif w == "2" and nxt in ("thousand", "tausend") and nxt2 == "and" and i + 3 < len(words) and words[i + 3].isdigit():
            out.append(str(2000 + int(words[i + 3]))); i += 4
        elif w == "20" and nxt.isdigit() and len(nxt) == 2 and 20 <= int(nxt) <= 99:
            out.append("20" + nxt); i += 2                                  # twenty twenty-seven
        else:
            out.append(w); i += 1
    return out


def normalise(text, lang):
    t = _accents(text.lower()).replace("ß", "ss")  # Swiss spelling is not an error (R7)
    t = t.replace("&", " and " if lang == "en" else " und ")
    t = re.sub(r"(\d+)(st|nd|rd|th)\b", r"\1", t)
    t = re.sub(r"(?<=\d)['’](?=\d)", "", t)  # 1'500 -> 1500
    t = re.sub(r"zweitausend(?=\w)", "zwei tausend ", t)
    t = _money_and_times(t)
    t = t.replace("-", " ").replace("%", " % ")
    t = re.sub(r"\b([ap])\.?\s?m\b\.?", r"\1m", t)  # p.m. / p m -> pm
    t = re.sub(r"[^\w\s%]", " ", t)
    t = re.sub(r"(\d)([^\W\d])", r"\1 \2", t)  # "16gb" -> "16 gb"
    words = [VARIANTS.get(w, w) for w in t.split()]
    words = [UNITS.get(w, w) for w in words]
    if lang == "en":
        words = _english_numbers(words)
    else:
        words = [str(GERMAN_COMPOUNDS.get(w, w)) for w in words]
    words = [str(NUM[lang].get(w, w)) for w in words]
    words = _years(words, lang)
    i = 0
    while i < len(words) - 2:   # vierzehn Uhr fünfunddreissig -> 14 35 uhr
        if words[i + 1] == "uhr" and words[i].isdigit() and (i == 0 or not words[i - 1].isdigit()) \
                and words[i + 2].isdigit() and len(words[i + 2]) <= 2 \
                and (i + 3 >= len(words) or words[i + 3] not in ("minuten", "gramm", "kilometer", "liter", "franken")):
            words[i + 1], words[i + 2] = words[i + 2], "uhr"
            i += 3
        else:
            i += 1
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
    text = open(os.path.join(ROOT, "docs", "evaluation", "baseline.md"), encoding="utf-8").read()
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


def corpus(root):
    """Milestone 3 layout, all under the gitignored corpus directory:
    scripts/<id>.txt    reference text as Oriol wants it written; id starts with de or en
    scripts/<id>.terms  optional: one name, number or term per line that must come out right
    futo/<id>.txt       FUTO's output for the same script; futo/times.txt: "<id> <seconds>" per line
    takes.json          {"<take>.wav": "<id>"} for files/benchmark/<take>.json written by the app
    """
    def read(*parts):
        path = os.path.join(root, *parts)
        return open(path, encoding="utf-8").read().strip() if os.path.exists(path) else None
    ids = sorted(f[:-4] for f in os.listdir(os.path.join(root, "scripts")) if f.endswith(".txt"))
    waits = dict(l.split() for l in (read("futo", "times.txt") or "").splitlines() if l.strip())
    ours = {}
    for wav, script in json.load(open(os.path.join(root, "takes.json"))).items():
        if script in ids:
            ours.setdefault(script, []).append(json.loads(read("files", "benchmark", wav[:-4] + ".json")))
    totals = {}
    for script in ids:
        lang, ref = script[:2], normalise(read("scripts", script + ".txt"), script[:2])
        terms = [normalise(t, lang) for t in (read("scripts", script + ".terms") or "").splitlines() if t.strip()]
        rows = [("FUTO", read("futo", script + ".txt"), waits.get(script))]
        rows += [("ours", t.get("text"), "%.1f" % (t["stopToFinalMs"] / 1000)) for t in ours.get(script, [])]
        for engine, hyp, wait in rows:
            if hyp is None:
                print(f"{script:6} {engine:5} missing"); continue
            h = normalise(hyp, lang)
            e = errors(ref, h)
            missed = [" ".join(t) for t in terms if "".join(t) not in "".join(h)]
            print(f"{script:6} {engine:5} {e:3}/{len(ref)} = {100 * e / len(ref):5.1f}%  wait {wait or '?':>5} s  "
                  f"terms missed {len(missed)}/{len(terms)}: {', '.join(missed) or '-'}")
            a = totals.setdefault((lang, engine), [0, 0, 0, 0])
            a[0] += e; a[1] += len(ref); a[2] += len(missed); a[3] += len(terms)
    print()
    for (lang, engine), (e, n, m, t) in sorted(totals.items()):
        print(f"{lang} {engine:5} word errors {e:3}/{n} = {100 * e / n:5.1f}%   terms missed {m}/{t}")


def main():
    if sys.argv[1] == "--corpus":
        return corpus(sys.argv[2])
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
