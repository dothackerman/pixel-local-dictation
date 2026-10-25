#!/usr/bin/env python3
"""Prepare and compare bounded experiments. Never runs inference or device commands.

prepare SPEC.json             snapshot host artifacts into private corpus/lab/<id>/
check PREPARATION.json        report missing or changed host artifacts
freeze PRIVATE_PLAN.json     validate and freeze one complete comparison plan
reserve PLAN.json RUN        charge one planned attempt before its external execution
record PLAN.json RUN RESULT  preserve that attempt's result, including failures
compare PLAN.json            validate all receipts and save a private decision report

Stage variants with `python3 tools/variant.py normalize variants/X.json LAB/variant.json`
so the phone echoes every setting explicitly (the evaluator reads the echoed variant).

Old run/phone-prepare/phone-score/ledger commands are retired. Historical evaluator
modules remain readable; historical execution source is retained in Git.
"""
import argparse
import json
from pathlib import Path
import sys

import experiment


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest="command", required=True)
    for name, args in (
        ("prepare", ("spec",)), ("check", ("preparation",)), ("freeze", ("plan",)),
        ("reserve", ("plan", "run")), ("record", ("plan", "run", "result")), ("compare", ("plan",)),
    ):
        sub = commands.add_parser(name)
        for arg in args:
            sub.add_argument(arg)
    args = parser.parse_args()
    try:
        def read(path, private=True):
            return json.loads((experiment.private_path(path) if private else Path(path)).read_text())
        if args.command == "prepare":
            output = {"path": str(experiment.prepare(read(args.spec, private=False)))}
        elif args.command == "check":
            output = experiment.check(args.preparation)
        elif args.command == "freeze":
            output = {"path": str(experiment.freeze(read(args.plan)))}
        elif args.command == "reserve":
            output = {"path": str(experiment.reserve(args.plan, args.run))}
        elif args.command == "record":
            output = {"path": str(experiment.record(args.plan, args.run, read(args.result)))}
        else:
            path = experiment.private_path(args.plan)
            result = experiment.report(path)
            saved = experiment.save_report(path, result)
            output = dict(result, path=str(saved))
        print(json.dumps(output, indent=2, ensure_ascii=False, allow_nan=False))
        return 2 if output.get("decision") == "inconclusive" or output.get("missing") or output.get("changed") else 0
    except (OSError, ValueError, KeyError, TypeError) as error:
        print("lab: " + str(error), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
