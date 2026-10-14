"""Exercise both harness contracts without executing a phone command."""
import json
from pathlib import Path
import subprocess
import tempfile
import tomllib
import unittest


ROOT = Path(__file__).resolve().parents[1]
GUARD = ROOT / '.claude/hooks/phone-guard.sh'
WRAPPER = str(ROOT / 'tools/phone')


def call(tool, args, cwd=ROOT):
    return subprocess.run(['bash', str(GUARD)], input=json.dumps({
        'tool_name': tool, 'tool_input': args, 'cwd': str(cwd),
    }), text=True, capture_output=True)


class GuardTests(unittest.TestCase):
    def test_single_wrapper_and_normal_build_commands_pass_both_contracts(self):
        for tool, key in [('Bash', 'command'), ('exec_command', 'cmd')]:
            for command in [WRAPPER+' status', WRAPPER+' install build/app.apk',
                            WRAPPER+' push-variant corpus/lab/push/baseline',
                            WRAPPER+' lab', WRAPPER+' pull-results',
                            './gradlew test', 'git status --short']:
                with self.subTest(tool=tool, command=command):
                    self.assertEqual(call(tool, {key: command}).returncode, 0)

    def test_chaining_substitution_direct_access_and_gate_changes_block(self):
        commands = ['tools/phone status; true', 'tools/phone status\ntrue',
                    'tools/phone status > /tmp/out', 'tools/phone $(echo status)',
                    'adb devices', '/opt/custom/adb devices', 'fastboot devices',
                    'sudo true', 'echo adb', 'cp x .codex/config.toml',
                    'rm .claude/hooks/phone-guard.sh', 'chmod +w tools/git-hooks/pre-commit',
                    'git config core.hooksPath /tmp', 'python3', 'bash',
                    'cat > AGENTS.md', 'tools/phone status --anything']
        for tool in ['Bash', 'exec_command']:
            for command in commands:
                with self.subTest(tool=tool, command=command):
                    self.assertEqual(call(tool, {'command': command}).returncode, 2)

    def test_wrapper_identity_is_independent_of_execution_directory(self):
        for cwd in [ROOT, ROOT/'app', Path('/tmp')]:
            for command in ['tools/phone status', './tools/phone status', '/tmp/tools/phone status']:
                self.assertEqual(call('Bash', {'command': command}, cwd).returncode, 2)
            self.assertEqual(call('Bash', {'command': WRAPPER+' status', 'workdir': '/tmp'}, cwd).returncode, 0)

    def test_native_edits_and_patch_moves_protect_the_same_files(self):
        gates = ['.claude/settings.json', '.codex/config.toml', '.agents/skills/new/SKILL.md',
                 '.git/config', '.aws/credentials', 'tools/phone',
                 'tools/git-hooks/pre-commit', 'AGENTS.md', 'CLAUDE.md']
        for path in gates:
            for tool in ['Edit', 'Write', 'MultiEdit', 'NotebookEdit']:
                with self.subTest(tool=tool, path=path):
                    self.assertEqual(call(tool, {'file_path': path}).returncode, 2)
            patch = '*** Begin Patch\n*** Update File: app/example\n*** Move to: '+path+'\n@@\n-a\n+b\n*** End Patch'
            self.assertEqual(call('apply_patch', {'command': patch}).returncode, 2)
        for tool in ['Edit', 'Write']:
            self.assertEqual(call(tool, {'file_path': 'app/example'}).returncode, 0)
        patch = '*** Begin Patch\n*** Add File: app/example\n+ok\n*** End Patch'
        self.assertEqual(call('apply_patch', {'command': patch}).returncode, 0)

    def test_absolute_subdirectory_and_symlink_edits_block(self):
        self.assertEqual(call('Edit', {'file_path': str(ROOT/'tools/phone')}).returncode, 2)
        self.assertEqual(call('Edit', {'file_path': '../.codex/config.toml'}, ROOT/'app').returncode, 2)
        with tempfile.TemporaryDirectory() as directory:
            alias = Path(directory)/'alias'
            alias.symlink_to(ROOT/'tools/phone')
            self.assertEqual(call('Write', {'file_path': str(alias)}).returncode, 2)

    def test_malformed_input_blocks_instead_of_silently_passing(self):
        for raw in ['{', '[]', '{}', '{"tool_name":"Bash","tool_input":{}}']:
            result = subprocess.run(['bash', str(GUARD)], input=raw, text=True, capture_output=True)
            self.assertEqual(result.returncode, 2)

    def test_codex_reuses_the_same_guard(self):
        self.assertEqual((ROOT/'.codex/hooks').resolve(), (ROOT/'.claude/hooks').resolve())

    def test_configuration_protects_both_harnesses(self):
        claude = json.loads((ROOT/'.claude/settings.json').read_text())
        codex = tomllib.loads((ROOT/'.codex/config.toml').read_text())
        paths = codex['permissions']['dictation']['filesystem'][':workspace_roots']
        for path, access in paths.items():
            self.assertEqual(access, 'read')
            self.assertIn('Edit(/'+path+')', claude['permissions']['deny'])
            self.assertIn('./'+path, claude['sandbox']['filesystem']['denyWrite'])
        self.assertTrue(claude['sandbox']['enabled'])
        self.assertEqual(claude['sandbox']['excludedCommands'], [WRAPPER+' *'])
        for settings in [claude, json.loads((ROOT/'.codex/hooks.json').read_text())]:
            registration = settings['hooks']['PreToolUse'][0]
            for tool in ['Bash', 'Edit', 'Write', 'apply_patch']:
                self.assertIn(tool, registration['matcher'])
            self.assertIn('git rev-parse --show-toplevel', registration['hooks'][0]['command'])

    def test_launcher_and_registration_failures_block(self):
        with tempfile.TemporaryDirectory() as directory:
            launcher = Path(directory)/'guard.sh'
            launcher.write_text(GUARD.read_text())
            # No policy module next to this launcher: Python startup must block.
            result = subprocess.run(['bash', str(launcher)], input='{}', text=True, capture_output=True)
            self.assertEqual(result.returncode, 2)
            for file in ['.claude/settings.json', '.codex/hooks.json']:
                config = json.loads((ROOT/file).read_text())
                command = config['hooks']['PreToolUse'][0]['hooks'][0]['command']
                result = subprocess.run(['bash', '-c', command], cwd=directory,
                                        input='{}', text=True, capture_output=True)
                self.assertEqual(result.returncode, 2)


if __name__ == '__main__':
    unittest.main()
