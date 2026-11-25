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


def checkout(directory, name='checkout "quoted"'):
    """A disposable main checkout with the gate sources; never the user's installed gate or Git index."""
    root = Path(directory) / name
    shutil.copytree(ROOT / 'tools/gate', root / 'tools/gate', ignore=shutil.ignore_patterns('__pycache__'))
    for source in ('phone', 'test_phone.py', 'test_agent_guard.py'):
        shutil.copy2(ROOT / 'tools' / source, root / 'tools' / source)
    environment = {k: v for k, v in os.environ.items() if not k.startswith('GIT_')}
    environment['XDG_DATA_HOME'] = str(Path(directory) / 'installed')
    subprocess.run(['git', 'init', '-q', str(root)], env=environment, check=True)
    return root, environment


def apply(root, environment):
    return subprocess.run(['bash', 'tools/gate/apply.sh'], cwd=root, env=environment, text=True, capture_output=True)


class InstallGuardTests(unittest.TestCase):
    """ADR-028: the wrapper is installed only from the main checkout and never without the marker guard."""

    def assert_nothing_installed(self, directory, root):
        self.assertFalse((Path(directory) / 'installed').exists())
        self.assertFalse((root / '.claude').exists())
        self.assertFalse((root / '.codex').exists())

    def test_apply_refuses_in_a_worktree_on_any_branch(self):
        with tempfile.TemporaryDirectory() as directory:
            root, environment = checkout(directory, 'main-checkout')
            git = lambda *arguments: subprocess.run(
                ['git', '-c', 'user.name=t', '-c', 'user.email=t@example.invalid', *arguments], cwd=root,
                env=environment, check=True, capture_output=True)
            git('add', '-A')
            git('commit', '-q', '-m', 'gate sources')
            worktree = Path(directory) / 'worktree'
            git('worktree', 'add', '-q', '-b', 'other', str(worktree))
            result = apply(worktree, environment)
            self.assertNotEqual(result.returncode, 0, result.stdout)
            self.assertIn('main checkout', result.stderr)
            self.assert_nothing_installed(directory, worktree)
            # The main checkout installs, also on a branch other than main.
            git('checkout', '-q', '-b', 'wrapper-change')
            self.assertEqual(apply(root, environment).returncode, 0)

    def test_apply_refuses_a_wrapper_without_the_marker_guard(self):
        with tempfile.TemporaryDirectory() as directory:
            root, environment = checkout(directory)
            wrapper = root / 'tools/phone'
            lines = wrapper.read_text().splitlines(keepends=True)
            kept = [line for line in lines if not line.startswith('MARKER_GUARD = ')]
            self.assertEqual(len(lines) - len(kept), 1)
            wrapper.write_text(''.join(kept))
            result = apply(root, environment)
            self.assertNotEqual(result.returncode, 0, result.stdout)
            self.assertIn('marker guard', result.stderr)
            self.assert_nothing_installed(directory, root)

    def test_apply_runs_the_wrapper_tests_before_installing_anything(self):
        with tempfile.TemporaryDirectory() as directory:
            root, environment = checkout(directory)
            with (root / 'tools/test_phone.py').open('a') as tests:
                tests.write('\nraise SystemExit("planted failure")\n')
            result = apply(root, environment)
            self.assertNotEqual(result.returncode, 0, result.stdout)
            self.assert_nothing_installed(directory, root)

    def test_the_shim_passes_the_callers_directory_first_and_runs_in_its_checkout(self):
        with tempfile.TemporaryDirectory() as directory:
            root, environment = checkout(directory)
            (root / '.claude/hooks').mkdir(parents=True)
            shutil.copy2(ROOT / 'tools/gate/phone', root / '.claude/hooks/phone')
            (root / '.claude/hooks/phone').chmod(0o755)
            installed = Path(directory) / 'installed/pixel-phone/phone'
            installed.parent.mkdir(parents=True)
            installed.write_text('import json, os, sys\nprint(json.dumps([os.getcwd(), sys.argv[1:]]))\n')
            caller = root / 'tools/gate'
            result = subprocess.run([str(root / '.claude/hooks/phone'), '--caller', '/spoofed', 'app'], cwd=caller,
                                    env=environment, text=True, capture_output=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(json.loads(result.stdout),
                             [str(root.resolve()), ['--caller', str(caller.resolve()), '--caller', '/spoofed', 'app']])

    def test_the_claude_allow_list_leaves_out_every_verb_that_asks(self):
        settings = json.loads((ROOT / 'tools/gate/settings.json').read_text())
        shim = str(ROOT / '.claude/hooks/phone') + ' '
        allowed = {rule[len('Bash(' + shim):].rstrip(')').split(':')[0]
                   for rule in settings['permissions']['allow'] if rule.startswith('Bash(' + shim)}
        self.assertEqual(allowed, {'help', 'check', 'status', 'device', 'app', 'crash', 'logs', 'pull', 'push', 'run',
                                   'measure', 'snapshot'})
        self.assertIn('Bash(' + shim + 'measure:*)', settings['permissions']['allow'], 'like run: any class, any extras')
        self.assertEqual(allowed & {'install', 'restore', 'stop', 'forget', 'uninstall'}, set())
        self.assertEqual([rule for rule in settings['permissions']['allow'] if '.claude/hooks/phone' not in rule], [],
                         'nothing but named shim verbs is allowed')


class GateInstallationTests(unittest.TestCase):
    def test_install_migrates_instruction_symlink_without_writing_through_it(self):
        with tempfile.TemporaryDirectory() as directory:
            root, environment = checkout(directory)
            target = root / 'former-target.md'
            target.write_text('must not be overwritten')
            (root / 'CLAUDE.md').symlink_to(target.name)
            source = (root / 'tools/gate/AGENTS.md').read_bytes()
            for _ in range(2):
                result = apply(root, environment)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                for name in ('AGENTS.md', 'CLAUDE.md'):
                    self.assertFalse((root / name).is_symlink(), name)
                    self.assertEqual((root / name).read_bytes(), source)
                self.assertEqual(target.read_text(), 'must not be overwritten')
                codex = tomllib.loads((root / '.codex/config.toml').read_text())
                filesystem = codex['permissions']['dictation']['filesystem']
                self.assertNotIn(':workspace_roots', filesystem)
                self.assertEqual(set(filesystem), {str(root.resolve()), "~/.gradle"})
                self.assertEqual(filesystem["~/.gradle"], "write")
                self.assertEqual(codex['permissions']['dictation']['extends'], ':workspace')
                paths = filesystem[str(root.resolve())]
                for name in ('.claude/hooks', '.claude/settings.json', '.codex',
                             'tools/git-hooks', 'AGENTS.md', 'CLAUDE.md'):
                    self.assertEqual(paths[name], 'read', name)
                claude = json.loads((root / '.claude/settings.json').read_text())
                self.assertEqual(claude['sandbox']['filesystem']['allowWrite'], ['~/.gradle'])
                for name in ('./AGENTS.md', './CLAUDE.md'):
                    self.assertIn(name, claude['sandbox']['filesystem']['denyWrite'])
            # Copied absolute configuration must fail readiness in another checkout.
            other = Path(directory) / 'other-checkout'
            shutil.copytree(root, other, ignore=shutil.ignore_patterns('.git', '__pycache__'))
            check = subprocess.run([sys.executable, 'tools/gate/check_installation.py'],
                                   cwd=other, env=environment, text=True, capture_output=True)
            self.assertEqual(check.returncode, 1, check.stdout + check.stderr)
            self.assertIn('not rendered for this checkout', check.stderr)
            rendered = subprocess.check_output([sys.executable, 'tools/gate/check_installation.py', '--render-codex'],
                                               cwd=other, env=environment, text=True)
            other_config = tomllib.loads(rendered)['permissions']['dictation']['filesystem']
            self.assertEqual(set(other_config), {str(other.resolve()), "~/.gradle"})
            self.assertEqual(other_config["~/.gradle"], "write")
            self.assertEqual(other_config[str(other.resolve())], paths)
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
