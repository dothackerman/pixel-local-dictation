#!/usr/bin/env python3
"""ADR-026 word scoring. Historical score.py/evaluate.py remain unchanged.

Formatting-only token boundaries have zero cost. Other edits cost one word;
number, listed-term and other counts partition that same alignment.
"""
import re
from collections import Counter
from decimal import Decimal

import score as historical

REVISION = 'dictionary-words-v3'

# Generic words that enter the listed set only through multi-word entries
# ("Frau Keller", "Tensor chip", "Fern & Footpath"). The strict counts (#22)
# ignore them so a stray "and" is neither a listed error nor a false accept.
# The as-scored counts keep the full set so both views stay comparable.
STOP_WORDS = frozenset(('and', 'und', 'frau', 'herr', 'mrs', 'mr', 'station', 'chip', 'bahnhof'))


def normalise(text, language):
    """Keep decimal values intact; unify written and spoken currency amounts."""
    protected = {}

    def decimal(match):
        if re.match(r'\s*(?:uhr\b|[ap]\.?\s?m\b)', text[match.end():], re.I):
            return match[0]  # Historical clock-time rules already cover this.
        # Alphabetic placeholders survive the historical punctuation/number rules.
        key = 'zzdecimal' + 'z' * len(protected) + 'q'
        protected[key] = format(Decimal(match[0].replace(',', '.')).normalize(), 'f')
        return key

    # Product/unit boundaries are formatting, unlike separate numeric values.
    text = re.sub(r'(?<=[^\W\d_])(?=\d)', ' ', text)
    text = re.sub(r'(?<=\d)[\u2019\x27](?=\d)', '', text)
    # Decimal-unit spacing must be split before the decimal's trailing boundary.
    # Leave integer ordinals intact for the historical normalizer (12th -> 12).
    text = re.sub(r'(\b\d+[.,]\d+)(?=[^\W\d_])', r'\1 ', text)
    text = re.sub(r'\b\d+[.,]\d+\b', decimal, text)
    text = re.sub(r'([£€])\s*(zzdecimalz*q)', lambda m: m[2] + ' ' + historical.CURRENCY[m[1]], text)
    words = [protected.get(word, word) for word in historical.normalise(text, language)]
    result, i = [], 0
    while i < len(words):
        if (i + 2 < len(words) and words[i].isdigit()
                and words[i + 1] in ('franken', 'pounds', 'euros')
                and words[i + 2].isdigit() and 0 <= int(words[i + 2]) < 100):
            result.extend((format(Decimal(words[i] + '.' + words[i + 2].zfill(2)).normalize(), 'f'), words[i + 1]))
            i += 3
        else:
            result.append(words[i])
            i += 1
    return result


def _equal_span(reference, hypothesis, i, j):
    """Smallest nonempty spans equal after spacing/hyphen normalisation."""
    if i == len(reference) or j == len(hypothesis):
        return None
    a, b = reference[i], hypothesis[j]
    # Number normalization above handles known written/spoken forms. Joining
    # numeric tokens here would conflate separate values (2 3) with 23.
    if a != b and any(c.isdigit() for c in a + b):
        return None
    end_i, end_j = i + 1, j + 1
    while a != b:
        if len(a) < len(b) and b.startswith(a) and end_i < len(reference):
            if any(c.isdigit() for c in reference[end_i]):
                return None
            a += reference[end_i]; end_i += 1
        elif len(b) < len(a) and a.startswith(b) and end_j < len(hypothesis):
            if any(c.isdigit() for c in hypothesis[end_j]):
                return None
            b += hypothesis[end_j]; end_j += 1
        else:
            return None
    return end_i, end_j


def align(reference, hypothesis):
    """Minimum word edits, with exact matches across formatting boundaries."""
    n, m = len(reference), len(hypothesis)
    costs = [[0] * (m + 1) for _ in range(n + 1)]
    steps = {}
    for i in range(n, -1, -1):
        for j in range(m, -1, -1):
            if i == n and j == m:
                continue
            choices = []
            equal = _equal_span(reference, hypothesis, i, j)
            if equal:
                a, b = equal
                choices.append((costs[a][b], 'equal', a, b))
            if i < n and j < m:
                choices.append((1 + costs[i + 1][j + 1], 'substitution', i + 1, j + 1))
            if i < n:
                choices.append((1 + costs[i + 1][j], 'deletion', i + 1, j))
            if j < m:
                choices.append((1 + costs[i][j + 1], 'insertion', i, j + 1))
            best = min(choices, key=lambda item: item[0])
            costs[i][j], operation, a, b = best
            steps[i, j] = operation, a, b
    result, i, j = [], 0, 0
    while i < n or j < m:
        operation, a, b = steps[i, j]
        result.append({'operation': operation, 'reference': reference[i:a], 'hypothesis': hypothesis[j:b]})
        i, j = a, b
    return result


def _listed_words(dictionary, language):
    listed = set()
    for entry in dictionary:
        words = normalise(entry, language)
        listed.update(word for word in words if not any(c.isdigit() for c in word))
        if words and not any(c.isdigit() for c in ''.join(words)):
            listed.add(''.join(words))
    return listed


def score_text(reference, hypothesis, language, dictionary=()):
    r, h = normalise(reference, language), normalise(hypothesis, language)
    alignment = align(r, h)
    listed = _listed_words(dictionary, language)
    strict = listed - STOP_WORDS
    result = dict(revision=REVISION, errors=0, words=len(r), listed_errors=0,
                  number_format_errors=0, other_errors=0,
                  dictionary_insertions=0, dictionary_substitutions=0,
                  strict_listed_errors=0, strict_dictionary_insertions=0,
                  strict_dictionary_substitutions=0, alignment=alignment)
    for row in alignment:
        operation = row['operation']
        if operation == 'equal':
            continue
        before, after = row['reference'], row['hypothesis']
        result['errors'] += 1
        # Attribute substitutions/deletions to the spoken reference; otherwise
        # a false listed-word accept would hide harm on unlisted speech.
        attributed = before or after
        if any(word in listed for word in attributed):
            category = 'listed_errors'
        elif any(c.isdigit() for word in attributed for c in word):
            category = 'number_format_errors'
        else:
            category = 'other_errors'
        row['category'] = category
        result[category] += 1
        row['strict_listed'] = any(word in strict for word in attributed)
        result['strict_listed_errors'] += row['strict_listed']
        for words, prefix in ((listed, ''), (strict, 'strict_')):
            if any(word in words for word in after):
                if operation == 'insertion':
                    result[prefix + 'dictionary_insertions'] += 1
                elif operation == 'substitution':
                    result[prefix + 'dictionary_substitutions'] += 1
    return result


def score_guard(reference, hypothesis, control, language, dictionary=()):
    """Score a guard candidate against its matched zero-bonus control.

    A false accept is an inserted/substituted listed word at a normalized
    reference span (or insertion gap). Compare occurrences, including repeated
    words at one gap: correcting an old error cannot cancel a new identity or
    a false accept at another reference location. Strict counts exclude STOP_WORDS.
    """
    dictionary = tuple(dictionary)
    result = score_text(reference, hypothesis, language, dictionary)
    baseline = score_text(reference, control, language, dictionary)
    listed = _listed_words(dictionary, language)

    def occurrences(scored, words):
        found = Counter()
        position = 0
        for row in scored['alignment']:
            end = position + len(row['reference'])
            if row['operation'] in ('insertion', 'substitution'):
                for word in row['hypothesis']:
                    if word in words:
                        found[position, end, word] += 1
            position = end
        return found

    for prefix, words in (('', listed), ('strict_', listed - STOP_WORDS)):
        introduced = occurrences(result, words) - occurrences(baseline, words)
        result[prefix + 'introduced_dictionary_false_accepts'] = sum(introduced.values())
    return result


COUNTS = ('errors', 'words', 'listed_errors', 'number_format_errors', 'other_errors',
          'dictionary_insertions', 'dictionary_substitutions',
          'strict_listed_errors', 'strict_dictionary_insertions', 'strict_dictionary_substitutions')


def _digest(value):
    import hashlib
    import json
    return hashlib.sha256(json.dumps(value, sort_keys=True, ensure_ascii=False,
                                     separators=(',', ':'), allow_nan=False).encode()).hexdigest()


def _runs(report, references):
    rows = {}
    for take in report['takes']:
        name = take['file']
        if name not in references:
            raise ValueError('unknown take: ' + name)
        runs = [dict(take, feed=take.get('feed', 640)), *take.get('jitter', [])]
        for run in runs:
            key = name, run['feed']
            if key in rows:
                raise ValueError('duplicate take/feed')
            if not isinstance(run.get('text'), str):
                raise ValueError('missing hypothesis')
            rows[key] = run['text']
    feeds = {feed for _, feed in rows}
    if not feeds or set(rows) != {(name, feed) for name in references for feed in feeds}:
        raise ValueError('incomplete take/feed coverage')
    return rows


def score_report(report, references, dictionary=(), *, run_id, control=None, control_run_id=None):
    """One CSV-compatible row per language/feed; references map files to (language, text).

    Dictionary candidates require an otherwise identical no-dictionary control
    with exactly the same take/feed coverage. Passing a scoring dictionary does
    not imply the decoder used it: that comes from the report's variant.
    """
    import evaluate
    if not run_id:
        raise ValueError('run identity is required')
    candidate = bool(report.get('variant', {}).get('hotwords', {}).get('file'))
    runs = _runs(report, references)
    if candidate:
        if control is None or not control_run_id:
            raise ValueError('dictionary run requires an identified no-dictionary control')
        if not evaluate.same_pipeline(report.get('variant'), control.get('variant'), dictionary_twin=True):
            raise ValueError('control must use the same pipeline without dictionary')
        if set(_runs(control, references)) != set(runs):
            raise ValueError('control take/feed coverage differs')
    dictionary = tuple(dictionary)
    common = dict(revision=REVISION, run_id=run_id, report_sha256=_digest(report),
                  references_sha256=_digest(references), dictionary_sha256=_digest(dictionary),
                  control_run_id=control_run_id or '', control_sha256=_digest(control) if control else '')
    totals = {}
    for (name, feed), text in runs.items():
        language, reference = references[name]
        scored = score_text(reference, text, language, dictionary)
        row = totals.setdefault((language, feed), dict(common, language=language, feed=feed, takes=0,
                                                       old_errors=0, **dict.fromkeys(COUNTS, 0)))
        row['takes'] += 1
        for field in COUNTS:
            row[field] += scored[field]
        row['old_errors'] += historical.errors(historical.normalise(reference, language),
                                               historical.normalise(text, language))
        row['scoring_delta'] = row['errors'] - row['old_errors']
    return [totals[key] for key in sorted(totals)]


def prepare_dictionary(terms, *, names=()):
    """Keep nonnumeric terms, including currency after a price, and named people.

    Personal-name entries are explicit: splitting every phrase would also boost
    unrelated common words. Input spelling is retained, with Swiss ss alongside ß.
    """
    result = []
    for term in [*terms, *(part for name in names for part in name.split())]:
        term = ' '.join(word for word in term.split() if not any(c.isdigit() for c in word))
        if not term or not any(c.isalpha() for c in term):
            continue
        for form in (term, term.replace('ß', 'ss')):
            if form not in result:
                result.append(form)
    return result


def main():
    import argparse
    import csv
    import json
    from pathlib import Path

    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    scoring = commands.add_parser('report', help='Score a pipeline report; write one CSV row per language/feed')
    scoring.add_argument('report')
    scoring.add_argument('references', help='JSON mapping take filenames to [language, reference text]')
    scoring.add_argument('output')
    scoring.add_argument('--dictionary')
    scoring.add_argument('--run-id', required=True)
    scoring.add_argument('--control')
    scoring.add_argument('--control-run-id')
    dictionary = commands.add_parser('dictionary', help='Prepare plain bilingual dictionary entries')
    dictionary.add_argument('terms')
    dictionary.add_argument('output')
    dictionary.add_argument('--names', help='One full personal name per line; components become separate entries')
    args = parser.parse_args()
    if args.command == 'dictionary':
        entries = prepare_dictionary(Path(args.terms).read_text().splitlines(),
                                     names=Path(args.names).read_text().splitlines() if args.names else ())
        Path(args.output).write_text('\n'.join(entries) + '\n')
    else:
        rows = score_report(json.loads(Path(args.report).read_text()),
                            json.loads(Path(args.references).read_text()),
                            Path(args.dictionary).read_text().splitlines() if args.dictionary else (),
                            run_id=args.run_id,
                            control=json.loads(Path(args.control).read_text()) if args.control else None,
                            control_run_id=args.control_run_id)
        with open(args.output, 'w', newline='') as destination:
            writer = csv.DictWriter(destination, fieldnames=list(rows[0]))
            writer.writeheader()
            writer.writerows(rows)


if __name__ == '__main__':
    main()
