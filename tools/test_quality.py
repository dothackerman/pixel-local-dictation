import os
import subprocess
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from quality import jvm_environment, preparation_problem, removed_test_problems, staged_errors


class StagedQualityTests(unittest.TestCase):
    def setUp(self):
        # Commit hooks export repository variables. Every Git call in this test,
        # including staged_errors(), must operate only on its temporary repository.
        environment = {key: value for key, value in os.environ.items()
                       if not key.startswith('GIT_')}
        environment.update(GIT_CONFIG_GLOBAL=os.devnull, GIT_CONFIG_NOSYSTEM='1')
        isolation = patch.dict(os.environ, environment, clear=True)
        isolation.start()
        self.addCleanup(isolation.stop)

    def test_checkout_must_match_staged_source_inputs(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            subprocess.run(['git', 'init', '-q', str(root)], check=True)
            (root / 'tools').mkdir()
            source = root / 'tools/source.py'
            source.write_text('original')
            subprocess.run(['git', 'add', 'tools/source.py'], cwd=root, check=True)
            self.assertEqual(staged_errors(root), [])
            source.write_text('unstaged')
            self.assertTrue(staged_errors(root))
            subprocess.run(['git', 'add', 'tools/source.py'], cwd=root, check=True)
            self.assertEqual(staged_errors(root), [])
            (root / 'notes.md').write_text('unrelated notes')
            self.assertEqual(staged_errors(root), [])
            (root / 'tools/test_new.py').write_text('new test')
            self.assertTrue(staged_errors(root))


class PreparationTests(unittest.TestCase):
    """An unprepared worktree fails at once with the fix, not later inside Gradle."""
    setUp = StagedQualityTests.setUp

    def repository(self, base):
        main = base / 'main'
        subprocess.run(['git', 'init', '-q', str(main)], check=True)
        subprocess.run(['git', '-c', 'user.name=t', '-c', 'user.email=t@t', 'commit', '-q', '--allow-empty', '-m', 'x'],
                       cwd=main, check=True)
        for name in ('local.properties', 'app/libs/a.aar', 'app/src/main/assets/m'):
            (main / name).parent.mkdir(parents=True, exist_ok=True)
            (main / name).write_text('x')
        worktree = base / 'wt'
        subprocess.run(['git', 'worktree', 'add', '-q', '--detach', str(worktree)], cwd=main, check=True)
        return main, worktree

    def test_fresh_worktree_names_the_setup_command(self):
        with tempfile.TemporaryDirectory() as directory:
            main, worktree = self.repository(Path(directory))
            self.assertIsNone(preparation_problem(main))
            self.assertIn('python3 tools/prepare_checkout.py', preparation_problem(worktree))
            for name in ('local.properties', 'app/libs', 'app/src/main/assets'):
                (worktree / name).parent.mkdir(parents=True, exist_ok=True)
                (worktree / name).symlink_to(main / name)
            self.assertIn('python3 tools/prepare_checkout.py', preparation_problem(worktree), 'jars still missing')
            (worktree / '.gradle/robolectric').mkdir(parents=True)
            self.assertIsNone(preparation_problem(worktree))


class JvmEnvironmentTests(unittest.TestCase):
    def test_sandbox_temp_dir_and_prepared_robolectric_jars_reach_every_jvm(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / '.gradle/robolectric').mkdir(parents=True)
            env = jvm_environment(root, {'TMPDIR': '/sandbox/tmp', 'JAVA_TOOL_OPTIONS': '-Down=1'})
            self.assertEqual(env['JAVA_TOOL_OPTIONS'].split(), [
                '-Djava.io.tmpdir=/sandbox/tmp', '-Drobolectric.offline=true',
                '-Drobolectric.dependency.dir=' + str(root / '.gradle/robolectric'), '-Down=1'])

    def test_unprepared_checkout_without_tmpdir_keeps_the_environment(self):
        with tempfile.TemporaryDirectory() as directory:
            self.assertEqual(jvm_environment(Path(directory), {'HOME': '/h'}), {'HOME': '/h'})


class RemovedTestGateTests(unittest.TestCase):
    """The gate compares test names with where the branch left main (#77)."""
    setUp = StagedQualityTests.setUp

    def test_gate_reports_a_test_removed_since_the_branch_left_main(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            git = lambda *arguments: subprocess.run(
                ['git', '-c', 'user.name=t', '-c', 'user.email=t@t', '-c', 'commit.gpgsign=false', *arguments],
                cwd=root, check=True)
            git('init', '-q', '-b', 'main')
            source = root / 'app/src/test/kotlin/a/ATest.kt'
            source.parent.mkdir(parents=True)
            source.write_text('package a\nclass ATest {\n    @Test fun kept() {}\n    @Test fun dropped() {}\n}\n')
            git('add', '.')
            git('commit', '-q', '-m', 'base')
            git('switch', '-q', '-c', 'work')
            self.assertEqual([], removed_test_problems(root))
            source.write_text('package a\nclass ATest {\n    @Test fun kept() {}\n}\n')
            git('commit', '-q', '-a', '-m', 'drop')
            self.assertEqual(['removed test a.ATest.dropped has no entry in tools/removed_tests.json'],
                             removed_test_problems(root))
            git('branch', '-q', '-m', 'main', 'trunk')
            self.assertEqual(['removed-test check: no main or origin/main to compare test names with'],
                             removed_test_problems(root))
