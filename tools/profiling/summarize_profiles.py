#!/usr/bin/env python3
"""Summarize ORT sessions without treating overlapping kernel times as Stop latency.

Parakeet graph labels use node names in the pinned export; unrecognized graphs
remain unassigned. Native search and host/device transfers are not separately
exposed by this CPU adapter, and are never inferred by subtracting kernel sums.
"""
import argparse
import json
import math
from pathlib import Path


def interval_union(intervals):
    """Length of a half-open interval union, in the caller's units."""
    total = 0
    end = None
    for start, stop in sorted(intervals):
        total += max(0, stop - max(start, end if end is not None else start))
        end = max(stop, end if end is not None else stop)
    return total


def valid_interval(start, duration):
    """ORT complete events need finite, nonnegative timestamps and durations."""
    if any(isinstance(v, bool) or not isinstance(v, (int, float)) for v in (start, duration)):
        return False
    try:
        return all(math.isfinite(v) and v >= 0 for v in (start, duration, start + duration))
    except OverflowError:
        return False


def summarize(directory):
    rows = []
    for path in sorted(Path(directory).glob('*.json')):
        errors = []
        try:
            events = json.loads(path.read_text())
            if not isinstance(events, list) or any(not isinstance(e, dict) for e in events):
                raise ValueError('expected an event array')
        except (ValueError, UnicodeError) as error:
            rows.append(dict(file=str(path), graph='unassigned', errors=['malformed or truncated profile: ' + str(error)],
                             valid_for_additive_wall=False, clock_aligned=False))
            continue
        intervals = []
        for event in events:
            if event.get('name') != 'model_run':
                continue
            start, duration = event.get('ts'), event.get('dur')
            if not valid_interval(start, duration):
                errors.append('model_run has missing or invalid interval')
            else:
                intervals.append((start, start + duration))
        wall_sum = sum(stop - start for start, stop in intervals)
        wall_union = interval_union(intervals)
        overlap = wall_sum - wall_union
        if overlap:
            errors.append('overlapping model_run intervals; additive wall rejected')
        nodes = []
        for index, event in enumerate(events):
            if event.get('cat') != 'Node':
                continue
            if (not isinstance(event.get('name'), str)
                    or not valid_interval(event.get('ts'), event.get('dur'))
                    or not isinstance(event.get('args', {}), dict)):
                errors.append(f'Node event {index} has invalid name, interval or args; excluded from node totals')
                continue
            provider = event.get('args', {}).get('provider')
            if provider is not None and not isinstance(provider, str):
                errors.append(f'Node event {index} has invalid provider; excluded from node totals')
                continue
            nodes.append(event)
        names = [e.get('name', '') for e in nodes]
        role = 'unassigned'
        if path.name.startswith('lid'):
            role = 'language-identification'
        elif any('/pre_encode/' in name for name in names):
            role = 'parakeet-encoder'
        elif any('/dec_rnn/' in name for name in names):
            role = 'parakeet-decoder'
        elif any('/pred/MatMul' in name for name in names) and any('/enc/MatMul' in name for name in names):
            role = 'parakeet-joiner'
        rows.append(dict(file=str(path), graph=role,
                         node_time_us=sum(e.get('dur', 0) for e in nodes),
                         model_run_time_us=wall_sum,
                         model_run_count=len(intervals), model_run_union_us=wall_union,
                         model_run_overlap_us=overlap,
                         valid_for_additive_wall=not errors and role != 'unassigned' and bool(intervals),
                         clock_aligned=False,
                         completeness='unverified; ORT JSON has no dropped-event counter',
                         errors=errors,
                         providers=sorted({e.get('args', {}).get('provider') for e in nodes if e.get('args', {}).get('provider')})))
    return dict(sessions=rows, native_search='not separately exposed', transfers='not separately exposed; CPU provider',
                timing_warning='ORT timestamps are session-relative and completeness is unverified. Node durations can overlap. Use harness wall time for load, LID, queue and Stop.')

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory'); parser.add_argument('output')
    args = parser.parse_args()
    with open(args.output, 'x') as target:
        json.dump(summarize(args.directory), target, indent=2)
