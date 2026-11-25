"""Exercise the shared PreToolUse git guard without executing any command.

Imports the live hook copy when applied, else the staged copy in tools/gate.
"""
import contextlib
import importlib.machinery
import importlib.util
import io
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
STAGED = ROOT / 'tools/gate/phone_guard.py'
LIVE = ROOT / '.claude/hooks/phone_guard.py'
SOURCE = LIVE if LIVE.exists() and LIVE.read_bytes() == STAGED.read_bytes() else STAGED
loader = importlib.machinery.SourceFileLoader('phone_guard_module', str(SOURCE))
spec = importlib.util.spec_from_loader(loader.name, loader)
guard = importlib.util.module_from_spec(spec)
loader.exec_module(guard)


def quiet(event):
    with contextlib.redirect_stderr(io.StringIO()):
        return guard.check(event)


def bash(command, tool='Bash'):
    key = 'cmd' if tool == 'exec_command' else 'command'
    return quiet({'tool_name': tool, 'tool_input': {key: command}, 'cwd': str(ROOT)})


class ShellRuleTests(unittest.TestCase):
    def test_git_runs_plain_but_cannot_smuggle_programs_out_of_the_sandbox(self):
        for command in ['git commit -q -m "x"', 'git add -A && git commit -m x', 'git log --oneline -5',
                        'git commit --no-verify -m wip', 'git -P status', 'git status --short | head']:
            with self.subTest(command=command):
                self.assertEqual(bash(command), 0)
        for command in ['git -c core.hooksPath=/tmp/h commit -m x', "git -c alias.x='!adb devices' x",
                        'GIT_SSH_COMMAND="adb devices" git commit -m x', 'git --exec-path=/tmp/x commit -m x',
                        'git -C /other commit -m x', 'git --git-dir=/tmp/g commit -m x',
                        'GIT_DIR=/tmp/g git commit -m x', 'ls; git -c x.y=z commit']:
            with self.subTest(command=command):
                self.assertEqual(bash(command), 2)

    def test_git_words_in_other_commands_pass(self):
        for command in ['grep -n "git -c" tools/gate/phone_guard.py', "python3 - <<'PY'\nprint('git -c x')\nPY",
                        'git log --grep="GIT_DIR" --oneline', 'adb devices', './gradlew installBenchmark']:
            for tool in ('Bash', 'exec_command'):
                with self.subTest(command=command, tool=tool):
                    self.assertEqual(bash(command, tool), 0)

    def test_malformed_input_is_denied(self):
        self.assertEqual(quiet({'tool_name': 'Bash', 'tool_input': 'nope'}), 2)
        self.assertEqual(quiet({'tool_name': 'Bash', 'tool_input': {}}), 2)
        self.assertEqual(quiet({'tool_name': 'Edit', 'tool_input': {'file_path': '.claude/hooks/phone'}}), 0)


if __name__ == '__main__':
    unittest.main()
