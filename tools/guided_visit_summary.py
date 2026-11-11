#!/usr/bin/env python3
"""Turns files/lab/guided-visit-<id>.jsonl (+ rotation-probe.jsonl) into the #61 check table
and per-rotation timings for the Signal rotation steps. Reads sanitized metadata only."""
import json
import sys

ROTATION_STEPS = {'A2', 'A3'}


def load(path):
    with open(path, encoding='utf-8') as handle:
        return [json.loads(line) for line in handle if line.strip()]


def checks(rows):
    final = {}
    for row in rows:
        if row['observation'] == 'retry':
            continue
        final[row['step']] = row  # a later attempt replaces an earlier one
    lines = []
    for step in sorted(final, key=lambda s: (s[0], int(''.join(c for c in s[1:] if c.isdigit())), s)):
        row = final[step]
        if row['observation'] != 'done':
            lines.append(f"{step} {row['observation']} - {row.get('title', '')}")
            continue
        for item in row['answers']:
            lines.append(f"{item['id']} {item['answer']} - {row['title']}: {item['text']}")
    return lines


RETENTION = {'ELIGIBLE', 'PASSWORD', 'NO_LEARNING', 'UNCLASSIFIABLE', 'NO_EDITOR'}


def editors(window):
    """Fixed retention classes of the editors that started input in this window (#61)."""
    seen = [e['retention'] for e in window if e['event'] == 'INPUT_START_DONE' and e.get('retention') in RETENTION]
    return ', editors: ' + ' > '.join(seen) if seen else ''


def rotations(rows, trace):
    final = {r['step']: r for r in rows if r['observation'] != 'retry'}
    out = []
    for step in sorted(final):
        row = final[step]
        if step not in ROTATION_STEPS:
            continue
        events = sorted((e for e in trace if e.get('run') == row['run']), key=lambda e: e['ns'])
        if not events:
            out.append(f"{step}: no trace rows (traceComplete {str(row['traceComplete']).lower()})")
            continue
        moments, seen = [], 0
        for i, e in enumerate(events):
            if e.get('rotations', 0) > seen:
                seen = e['rotations']
                moments.append(i)
        for n, start in enumerate(moments, 1):
            end = moments[n] if n < len(moments) else len(events)
            window = events[start:end]
            t0 = window[0]['ns']
            ms = lambda e: round((e['ns'] - t0) / 1_000_000)
            shown = next((e for e in window if e['event'] == 'WINDOW_SHOWN'), None)
            hidden = next((e for e in window if shown and e['event'] == 'WINDOW_HIDDEN' and e['ns'] > shown['ns']), None)
            silenced = any(e['event'] == 'HOLD_SILENCED' for e in window)
            hold = next(((e, name) for e in window for key, name in (
                ('HOLD_SETTLED', 'settled'), ('HOLD_GAP_EXPIRED', 'gap expired'),
                ('HOLD_SILENCED', 'interrupted (silenced)')) if e['event'] == key), None)
            if hold is None:
                hold_text = 'no outcome'
            elif hold[1].startswith('interrupted'):
                hold_text = hold[1]
            else:
                hold_text = f'{hold[1]} +{ms(hold[0])} ms'
            out.append(f"{step} rotation {n}: panel shown " + (f'+{ms(shown)} ms' if shown else 'never') +
                       ', late hide ' + (f'+{round((hidden["ns"] - shown["ns"]) / 1_000_000)} ms after shown' if hidden else 'none') +
                       f", capture silenced: {'yes' if silenced else 'no'}, hold: {hold_text}" + editors(window))
    return out


def summarize(rows, trace):
    return {'checks': checks(rows), 'rotations': rotations(rows, trace)}


def main(argv):
    if not 1 <= len(argv) <= 2:
        print('usage: guided_visit_summary.py guided-visit-<id>.jsonl [rotation-probe.jsonl]', file=sys.stderr)
        return 2
    result = summarize(load(argv[0]), load(argv[1]) if len(argv) == 2 else [])
    print('\n'.join(result['checks']))
    if result['rotations']:
        print('\nSignal rotation (settle/gap evidence)')
        print('\n'.join(result['rotations']))
    return 0


if __name__ == '__main__':
    raise SystemExit(main(sys.argv[1:]))
