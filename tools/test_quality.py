from contextlib import redirect_stderr, redirect_stdout
import io
import os
import subprocess
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from quality import gradle, jvm_environment, preparation_problem, staged_errors, test_environment


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
    """An unprepared checkout fails at once with the fix, not later inside Gradle."""
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
            self.assertIn('.gradle/home', preparation_problem(main), 'every checkout has its own Gradle home')
            (main / '.gradle/home').mkdir(parents=True)
            self.assertIsNone(preparation_problem(main))
            (worktree / '.gradle/home').mkdir(parents=True)
            self.assertIn('python3 tools/prepare_checkout.py', preparation_problem(worktree))
            for name in ('local.properties', 'app/libs', 'app/src/main/assets'):
                (worktree / name).parent.mkdir(parents=True, exist_ok=True)
                (worktree / name).symlink_to(main / name)
            self.assertIn('python3 tools/prepare_checkout.py', preparation_problem(worktree), 'jars still missing')
            (worktree / '.gradle/robolectric').mkdir(parents=True)
            self.assertIsNone(preparation_problem(worktree))


    def test_the_shared_home_is_refused(self):
        with tempfile.TemporaryDirectory() as directory:
            main, _ = self.repository(Path(directory))
            (main / '.gradle/home').mkdir(parents=True)
            os.environ['GRADLE_USER_HOME'] = str(Path.home() / '.gradle')
            self.assertIn('shared ~/.gradle', preparation_problem(main))


class OfflineMissTests(unittest.TestCase):
    def run_gradle(self, script):
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            code = gradle(['sh', '-c', script], Path.cwd(), dict(os.environ))
        return code, out.getvalue(), err.getvalue()

    def test_missing_offline_dependency_names_the_fill_command(self):
        code, out, err = self.run_gradle('echo "No cached version available for offline mode"; exit 1')
        self.assertEqual(code, 1)
        self.assertIn('No cached version', out, 'Gradle output still shown')
        self.assertIn('python3 tools/prepare_checkout.py --fill', err)

    def test_other_failures_add_nothing(self):
        self.assertEqual(self.run_gradle('echo compile error; exit 1')[1:], ('compile error\n', ''))
        self.assertEqual(self.run_gradle('echo ok')[0], 0)


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

    def test_git_hook_variables_never_reach_the_tests(self):
        # A pre-commit hook in a worktree exports GIT_DIR; a test's `git init` then re-initialised
        # the real repository (core.bare=true) on 2026-11-25.
        with tempfile.TemporaryDirectory() as directory:
            env = test_environment(Path(directory), {'GIT_DIR': '/repo/.git', 'GIT_INDEX_FILE': 'i', 'HOME': '/h'})
            self.assertEqual(env, {'HOME': '/h'})
