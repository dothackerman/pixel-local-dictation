"""Build marker: layout, commit and a hash over the build's inputs (ADR-028)."""
import os
import subprocess
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import build_marker

ANDROID = 'xmlns:android="http://schemas.android.com/apk/res/android"'


class IdentityTests(unittest.TestCase):
    def setUp(self):
        # Commit hooks export repository variables; Git here must see only the temporary repository.
        environment = {key: value for key, value in os.environ.items() if not key.startswith('GIT_')}
        environment.update(GIT_CONFIG_GLOBAL=os.devnull, GIT_CONFIG_NOSYSTEM='1')
        isolation = patch.dict(os.environ, environment, clear=True)
        isolation.start()
        self.addCleanup(isolation.stop)
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        subprocess.run(['git', 'init', '-q', str(self.root)], check=True)
        (self.root / 'app/src').mkdir(parents=True)
        (self.root / 'app/src/Main.kt').write_text('fun main() {}')
        (self.root / 'README.md').write_text('notes')
        self.commit('first')

    def commit(self, message):
        subprocess.run(['git', 'add', '-A'], cwd=self.root, check=True)
        subprocess.run(['git', '-c', 'user.name=t', '-c', 'user.email=t@t', 'commit', '-q', '-m', message],
                       cwd=self.root, check=True)
        return subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=self.root, text=True).strip()

    def test_clean_tree_names_its_commit_and_repeats(self):
        head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=self.root, text=True).strip()
        identity = build_marker.identity(self.root)
        self.assertRegex(identity, r'^commit=[0-9a-f]{40};hash=[0-9a-f]{64}$')
        self.assertIn('commit=' + head, identity)
        self.assertEqual(identity, build_marker.identity(self.root))

    def test_dirty_tree_differs_from_the_clean_build_of_the_same_commit(self):
        clean = build_marker.identity(self.root)
        (self.root / 'app/src/Main.kt').write_text('fun main() { println() }')
        edited = build_marker.identity(self.root)
        self.assertNotEqual(clean, edited)
        self.assertEqual(clean.split(';')[0], edited.split(';')[0])
        subprocess.run(['git', 'checkout', '-q', '--', 'app/src/Main.kt'], cwd=self.root, check=True)
        self.assertEqual(clean, build_marker.identity(self.root))
        (self.root / 'app/src/New.kt').write_text('val untracked = 1')
        self.assertNotEqual(clean, build_marker.identity(self.root))
        (self.root / 'app/src/New.kt').unlink()
        (self.root / 'app/src/Main.kt').unlink()
        self.assertNotEqual(clean, build_marker.identity(self.root))

    def test_untracked_binary_inputs_count_and_other_files_do_not(self):
        clean = build_marker.identity(self.root)
        (self.root / 'README.md').write_text('other notes')
        self.assertEqual(clean, build_marker.identity(self.root))
        (self.root / '.gitignore').write_text('app/libs/\n')
        (self.root / 'app/libs').mkdir()
        (self.root / 'app/libs/runtime.aar').write_bytes(b'one')
        with_library = build_marker.identity(self.root)
        self.assertNotEqual(clean, with_library)
        (self.root / 'app/libs/runtime.aar').write_bytes(b'other')
        self.assertNotEqual(with_library, build_marker.identity(self.root))

    def test_a_new_commit_changes_commit_and_hash(self):
        before = build_marker.identity(self.root)
        (self.root / 'app/src/Main.kt').write_text('fun main() = Unit')
        head = self.commit('second')
        after = build_marker.identity(self.root)
        self.assertIn('commit=' + head, after)
        self.assertNotEqual(before.split(';')[1], after.split(';')[1])


class ManifestTests(unittest.TestCase):
    def manifest(self, directory, name, marker):
        entry = f'<meta-data android:name="ch.og.dictation.build" android:value="{marker}" />' if marker else ''
        path = Path(directory) / name
        path.write_text(f'<manifest {ANDROID}><application>{entry}</application></manifest>')
        return path

    def problems(self, release, lab, test):
        with tempfile.TemporaryDirectory() as directory:
            return build_marker.manifest_problems(self.manifest(directory, 'release.xml', release),
                                                  self.manifest(directory, 'lab.xml', lab),
                                                  self.manifest(directory, 'test.xml', test))

    def test_release_is_owner_and_lab_build_and_its_test_apk_are_test_and_equal(self):
        identity = 'commit=' + 'a' * 40 + ';hash=' + 'b' * 64
        self.assertEqual([], self.problems('layout=owner;' + identity, 'layout=test;' + identity, 'layout=test;' + identity))

    def test_each_wrong_marker_is_named(self):
        identity = 'commit=' + 'a' * 40 + ';hash=' + 'b' * 64
        other = 'commit=' + 'a' * 40 + ';hash=' + 'c' * 64
        owner, test = 'layout=owner;' + identity, 'layout=test;' + identity
        self.assertEqual(['release: layout is test, expected owner'], self.problems(test, test, test))
        self.assertEqual(['lab build: layout is owner, expected test', 'test APK: marker differs from the lab build'],
                         self.problems(owner, owner, test))
        self.assertEqual(['test APK: marker differs from the lab build'], self.problems(owner, test, 'layout=test;' + other))
        self.assertEqual(['release: build differs from the lab build'], self.problems('layout=owner;' + other, test, test))
        self.assertEqual(['test APK: no build marker'], self.problems(owner, test, None))
        self.assertEqual(['release: malformed build marker layout=owner'], self.problems('layout=owner', test, test))


class WrapperTests(unittest.TestCase):
    def test_the_phone_wrapper_reads_the_same_entry_and_accepts_the_same_values(self):
        import importlib.machinery
        import importlib.util
        loader = importlib.machinery.SourceFileLoader('phone_for_marker', str(Path(__file__).with_name('phone')))
        wrapper = importlib.util.module_from_spec(importlib.util.spec_from_loader(loader.name, loader))
        loader.exec_module(wrapper)
        self.assertEqual(build_marker.NAME, wrapper.MARKER_NAME)
        build = 'commit=' + 'a' * 40 + ';hash=' + 'b' * 64
        for value, valid in (('layout=test;' + build, True), ('layout=owner;' + build, True),
                             ('layout=lab;' + build, False), ('layout=test;' + build[:-1], False),
                             ('layout=test;' + build + 'c', False), (build, False), ('', False)):
            self.assertEqual(valid, bool(build_marker.FORMAT.fullmatch(value)), value)
            self.assertEqual(valid, bool(wrapper.MARKER.fullmatch(value)), value)


if __name__ == '__main__':
    unittest.main()
