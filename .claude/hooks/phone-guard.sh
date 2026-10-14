#!/usr/bin/env bash
# Shared Claude/Codex guard. Any execution failure blocks the pending call.
python3 "$(dirname -- "${BASH_SOURCE[0]}")/phone_guard.py"
status=$?
if [ "$status" -ne 0 ]; then
  echo "phone-guard: validation failed; blocking the tool call" >&2
  exit 2
fi
