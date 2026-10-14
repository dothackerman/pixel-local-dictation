#!/usr/bin/env python3
"""Shared Claude/Codex PreToolUse policy; never invokes a phone command."""

import json
from pathlib import Path
import re
import sys


ROOT = Path(__file__).resolve().parents[2]
PROTECTED = (".claude", ".codex", ".agents", ".git", ".aws",
             "tools/phone", "tools/git-hooks", "AGENTS.md", "CLAUDE.md")
WRAPPER = re.compile(re.escape(str(ROOT / 'tools/phone')) +
                     r"[ \t]+[a-z-]+(?:[ \t]+[A-Za-z0-9_./][A-Za-z0-9_./-]*)*[ \t]*")
FORBIDDEN = re.compile(
    r"(^|[^A-Za-z0-9_-])(adb|fastboot|sudo)([^A-Za-z0-9_-]|$)"
    r"|platform-tools|tools/phone|\.claude/(hooks|settings)"
    r"|\.codex/(hooks|config|rules)|tools/git-hooks|core\.hooksPath"
    r"|(?:^|[ /])(?:AGENTS|CLAUDE)\.md", re.IGNORECASE)


def deny(reason):
    print("phone-guard: " + reason, file=sys.stderr)
    return 2


def protected(path, cwd):
    candidate = Path(path)
    if not candidate.is_absolute():
        candidate = cwd / candidate
    # Check spelling and target: replacing a symlink is also a gate change.
    for target in (candidate.absolute(), candidate.resolve()):
        for relative in PROTECTED:
            gate = ROOT / relative
            if target == gate or gate in target.parents:
                return True
    return False


def check(event):
    tool = event.get("tool_name")
    args = event.get("tool_input")
    if not isinstance(args, dict):
        return deny("invalid hook input")
    cwd = Path(event.get("cwd") or ROOT).resolve()
    if tool in ("Bash", "exec_command"):
        command = args.get("command", args.get("cmd"))
        if not isinstance(command, str) or not command.strip():
            return deny("missing shell command")
        if WRAPPER.fullmatch(command):
            return 0
        if re.match(r"(?:\./)?tools/phone(?:\s|$)", command):
            return deny("use the exact wrapper path: " + str(ROOT / 'tools/phone'))
        if FORBIDDEN.search(command):
            return deny("use one wrapper invocation; gate files are read-only")
        if command.strip() in ("bash", "sh", "zsh", "fish", "python", "python3"):
            return deny("interactive interpreters bypass command inspection; use a script")
        return 0
    if tool in ("Edit", "Write", "MultiEdit", "NotebookEdit"):
        path = args.get("file_path", args.get("notebook_path"))
        if not isinstance(path, str) or not path:
            return deny("missing edit path")
        return deny("gate files are read-only") if protected(path, cwd) else 0
    if tool == "apply_patch":
        patch = args.get("command", args.get("patch"))
        if not isinstance(patch, str):
            return deny("missing patch")
        paths = re.findall(r"^\*\*\* (?:Add File|Update File|Delete File|Move to): (.+)$", patch, re.MULTILINE)
        if not paths:
            return deny("patch has no file paths")
        if any(protected(path, cwd) for path in paths):
            return deny("gate files are read-only")
        return 0
    return 0


def main():
    try:
        event = json.load(sys.stdin)
        if not isinstance(event, dict):
            return deny("invalid hook input")
        return check(event)
    except (ValueError, OSError, TypeError, RuntimeError):
        return deny("cannot validate hook input")


if __name__ == "__main__":
    sys.exit(main())
