"""Exercise the shared PreToolUse guard without executing any phone command.

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

SHIM = str(ROOT / '.claude/hooks/phone')


def quiet(event):
    with contextlib.redirect_stderr(io.StringIO()):
        return guard.check(event)


def bash(command, tool='Bash'):
    key = 'cmd' if tool == 'exec_command' else 'command'
    return quiet({'tool_name': tool, 'tool_input': {key: command}, 'cwd': str(ROOT)})


class ShellRuleTests(unittest.TestCase):
    def test_mentioning_protected_words_in_read_only_commands_passes(self):
        for command in ['cat AGENTS.md', 'grep -n adb tools/phone', 'git log -- tools/phone .claude/hooks',
                        'sed -n 1,40p .claude/settings.json', 'git show HEAD:tools/phone | head',
                        "python3 - <<'PY'\nprint('adb')\nPY", 'cat > docs.md <<EOF\nuse adb never\nEOF',
                        'rg fastboot tools', 'wc -l tools/phone; ls .codex', 'echo "the adb word"',
                        './gradlew :app:assembleBenchmark :app:lintBenchmark', 'python3 tools/test_agent_guard.py',
                        "rg -n 'pull-results|adb |profile-install' tools -g '!.gradle'",
                        "python3 - <<'PY'\nimport subprocess\nprint(len('adb'))\nPY",
                        "cat <<EOF > notes.md\nrun: adb devices (never do this)\nEOF\necho done",
                        'git log --grep="adb devices" --oneline', 'ls | grep adb', 'echo $(ls) | grep fastboot',
                        'python3 -c "import re; re.compile(\'adb\')"', 'gradle --version', './gradlew tasks --all']:
            for tool in ('Bash', 'exec_command'):
                with self.subTest(command=command, tool=tool):
                    self.assertEqual(bash(command, tool), 0)

    def test_invoking_adb_or_fastboot_in_any_position_is_denied(self):
        for command in ['adb devices', 'fastboot flash boot x.img', 'ls; adb shell', 'true && adb shell id',
                        'echo x | adb shell cat', '$(adb shell pidof x)', '`adb devices`', 'sh -c "adb devices"',
                        "bash -c 'adb shell'", 'env ANDROID_SERIAL=x adb shell', 'timeout 30 adb logcat',
                        '/home/oriol/Android/Sdk/platform-tools/adb devices', '~/Android/Sdk/platform-tools/fastboot devices',
                        'echo serial | xargs adb -s', '(adb devices)', 'nice -n 5 adb shell',
                        'ls\nadb devices', 'FOO=1 BAR=2 adb shell id', 'exec adb shell', 'command adb devices',
                        'sh -c "ls; adb shell id"', 'bash -c \'echo x && adb devices\'',
                        'xargs -0 -n1 adb install < list']:
            with self.subTest(command=command):
                self.assertEqual(bash(command), 2)

    def test_gradle_device_tasks_are_denied(self):
        for command in ['./gradlew installBenchmark', './gradlew :app:connectedBenchmarkAndroidTest',
                        './gradlew uninstallAll', 'gradle :app:installDebug', './gradlew assemble connectedCheck']:
            with self.subTest(command=command):
                self.assertEqual(bash(command), 2)

    def test_the_shim_itself_passes_including_redirects_and_pipes(self):
        for command in [SHIM + ' status', SHIM + ' app > corpus/lab/app.json', SHIM + ' logs | head -50',
                        SHIM + ' run ch.og.dictation.LabTest provider=webgpu && echo done']:
            with self.subTest(command=command):
                self.assertEqual(bash(command), 0)


class EditRuleTests(unittest.TestCase):
    def edit(self, path, tool='Edit'):
        return quiet({'tool_name': tool, 'tool_input': {'file_path': path}, 'cwd': str(ROOT)})

    def test_harness_files_are_read_only_and_the_wrapper_is_not(self):
        for path in ['.claude/hooks/phone', '.claude/hooks/phone_guard.py', '.claude/settings.json', '.codex/config.toml',
                     '.codex/hooks/phone-guard.sh', 'tools/git-hooks/pre-commit', 'AGENTS.md', 'CLAUDE.md', '.git/config']:
            with self.subTest(path=path):
                self.assertEqual(self.edit(path), 2)
                self.assertEqual(self.edit(str(ROOT / path), 'Write'), 2)
        for path in ['tools/phone', 'tools/test_phone.py', 'tools/gate/phone_guard.py', 'README.md',
                     '.claude/skills/retro/SKILL.md', 'app/build.gradle.kts']:
            with self.subTest(path=path):
                self.assertEqual(self.edit(path), 0)

    def test_apply_patch_follows_the_same_paths(self):
        patch = '*** Begin Patch\n*** Update File: AGENTS.md\n@@\n-x\n+y\n*** End Patch'
        self.assertEqual(quiet({'tool_name': 'apply_patch', 'tool_input': {'patch': patch}, 'cwd': str(ROOT)}), 2)
        patch = patch.replace('AGENTS.md', 'tools/phone')
        self.assertEqual(quiet({'tool_name': 'apply_patch', 'tool_input': {'patch': patch}, 'cwd': str(ROOT)}), 0)

    def test_malformed_input_is_denied(self):
        self.assertEqual(quiet({'tool_name': 'Bash', 'tool_input': 'nope'}), 2)
        self.assertEqual(quiet({'tool_name': 'Bash', 'tool_input': {}}), 2)
        self.assertEqual(quiet({'tool_name': 'Read', 'tool_input': {'file_path': '.claude/hooks/phone'}}), 0)


if __name__ == '__main__':
    unittest.main()
