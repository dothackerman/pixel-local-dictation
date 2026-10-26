"""Exercise the real gate installer in a disposable checkout; no phone access."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import tomllib
import unittest


ROOT = Path(__file__).resolve().parents[1]


class GateInstallationTests(unittest.TestCase):
    def test_install_migrates_instruction_symlink_without_writing_through_it(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / 'checkout'
            shutil.copytree(ROOT / 'tools/gate', root / 'tools/gate')
            for name in ('phone', 'test_phone.py', 'test_agent_guard.py'):
                shutil.copy2(ROOT / 'tools' / name, root / 'tools' / name)
            # Test the checkout migration, never the user's installed gate or Git index.
            environment = {k: v for k, v in os.environ.items() if not k.startswith('GIT_')}
            environment['XDG_DATA_HOME'] = str(Path(directory) / 'installed')
            subprocess.run(['git', 'init', '-q', str(root)], env=environment, check=True)
            target = root / 'former-target.md'
            target.write_text('must not be overwritten')
            (root / 'CLAUDE.md').symlink_to(target.name)
            source = (root / 'tools/gate/AGENTS.md').read_bytes()
            for _ in range(2):
                result = subprocess.run(['bash', 'tools/gate/apply.sh'], cwd=root,
                                        env=environment, text=True, capture_output=True)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                for name in ('AGENTS.md', 'CLAUDE.md'):
                    self.assertFalse((root / name).is_symlink(), name)
                    self.assertEqual((root / name).read_bytes(), source)
                self.assertEqual(target.read_text(), 'must not be overwritten')
                codex = tomllib.loads((root / '.codex/config.toml').read_text())
                paths = codex['permissions']['dictation']['filesystem'][':workspace_roots']
                for name in ('.claude/hooks', '.claude/settings.json', '.codex',
                             'tools/git-hooks', 'AGENTS.md', 'CLAUDE.md'):
                    self.assertEqual(paths[name], 'read', name)
                claude = json.loads((root / '.claude/settings.json').read_text())
                for name in ('./AGENTS.md', './CLAUDE.md'):
                    self.assertIn(name, claude['sandbox']['filesystem']['denyWrite'])
            # A source/installation mismatch and the old symlink both fail the check.
            for broken in ('different instructions', None):
                (root / 'CLAUDE.md').unlink()
                if broken is None:
                    (root / 'CLAUDE.md').symlink_to('AGENTS.md')
                else:
                    (root / 'CLAUDE.md').write_text(broken)
                check = subprocess.run([sys.executable, 'tools/gate/check_installation.py'],
                                       cwd=root, env=environment, text=True, capture_output=True)
                self.assertEqual(check.returncode, 1, check.stdout + check.stderr)
                self.assertIn('CLAUDE.md', check.stderr)


if __name__ == '__main__':
    unittest.main()
