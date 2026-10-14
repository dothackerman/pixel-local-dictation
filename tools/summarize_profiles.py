#!/usr/bin/env python3
"""Summarize ORT sessions without treating overlapping kernel times as Stop latency.

Parakeet graph labels use node names in the pinned export; unrecognized graphs
remain unassigned. Native search and host/device transfers are not separately
exposed by this CPU adapter, and are never inferred by subtracting kernel sums.
"""
import argparse
import json
from pathlib import Path


def summarize(directory):
    rows = []
    for path in sorted(Path(directory).glob('*.json')):
        events = json.loads(path.read_text())
        nodes = [e for e in events if e.get('cat') == 'Node']
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
                         model_run_time_us=sum(e.get('dur', 0) for e in events if e.get('name') == 'model_run'),
                         providers=sorted({e.get('args', {}).get('provider') for e in nodes if e.get('args', {}).get('provider')})))
    return dict(sessions=rows, native_search='not separately exposed', transfers='not separately exposed; CPU provider',
                timing_warning='Node durations can overlap. Use harness wall time for load, LID, queue and Stop.')

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory'); parser.add_argument('output')
    args = parser.parse_args()
    with open(args.output, 'x') as target:
        json.dump(summarize(args.directory), target, indent=2)
