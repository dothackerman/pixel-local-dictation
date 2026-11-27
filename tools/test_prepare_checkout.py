import io
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
import zipfile

from prepare_checkout import FILL_TASKS, GATE_TASKS, GRADLE_HOME, ROOT, SHARED, fill, prepare, refresh

PASSWORD = 'not-a-real-password-71'
JAR = 'android-all-instrumented-16-robolectric-13921718-i7.jar'


def write(path, text=''):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text)
    return path


class Fixture:
    """A main checkout, a fresh worktree, filled ~/.gradle and Maven caches and its read-only copy."""

    def __init__(self, base):
        self.main, self.root = base / 'main', base / 'main/build/wt'
        self.source, self.shared, self.m2 = base / 'user-gradle', base / 'user-gradle/ro-dep-cache', base / 'm2'
        for checkout in (self.main, self.root):
            write(checkout / 'gradle/wrapper/gradle-wrapper.properties',
                  'distributionUrl=https\\://services.gradle.org/distributions/gradle-9.8.1-bin.zip\n')
            write(checkout / 'app/build.gradle.kts', 'testImplementation("org.robolectric:robolectric:4.17")\n')
        write(self.main / 'local.properties', 'dictation.keystore=/k/release.jks\ndictation.keyAlias=dictation\n'
              'dictation.keystorePassword=' + PASSWORD + '\n')
        write(self.main / 'app/libs/sherpa-onnx-1.13.8.aar', 'aar')
        write(self.main / 'app/src/main/assets/parakeet-v3/tokens.txt', 'tokens')
        write(self.source / 'wrapper/dists/gradle-9.8.1-bin/abc/gradle-9.8.1/lib/gradle.jar', 'gradle')
        write(self.source / 'wrapper/dists/gradle-9.8.1-bin/abc/gradle-9.8.1-bin.zip.ok')
        write(self.source / 'wrapper/dists/gradle-9.8.1-bin/abc/gradle-9.8.1-bin.zip.lck')
        write(self.source / 'caches/modules-2/modules-2.lock')
        write(self.source / 'caches/modules-2/gc.properties')
        write(self.source / 'caches/modules-2/metadata-2.107/module-metadata.bin', 'index')
        robolectric = self.source / 'caches/modules-2/files-2.1/org.robolectric/robolectric/4.17/31a1/robolectric-4.17.jar'
        robolectric.parent.mkdir(parents=True)
        with zipfile.ZipFile(robolectric, 'w') as jar:
            jar.writestr('org/robolectric/plugins/DefaultSdkProvider.class', b'\xca\xfe 10818077 13921718 \x00')
        write(self.m2 / 'org/robolectric/android-all-instrumented/16-robolectric-13921718-i7' / JAR, 'android')
        write(self.m2 / 'org/robolectric/android-all-instrumented/16-robolectric-11111111-i7/'
              'android-all-instrumented-16-robolectric-11111111-i7.jar', 'stale android')
        assert refresh(self.source, self.shared, io.StringIO()) == 0

    @property
    def gradle_home(self):
        return self.root / GRADLE_HOME

    def prepare(self):
        out = io.StringIO()
        code = prepare(self.root, self.main, self.gradle_home, self.shared, self.m2, out)
        return code, out.getvalue()

    def tree(self):
        return sorted((str(p.relative_to(self.root)), str(p.readlink()) if p.is_symlink() else None)
                      for p in self.root.rglob('*'))


class PrepareCheckoutTests(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.fixture = Fixture(Path(directory.name))

    def test_fresh_worktree_links_main_inputs_and_the_offline_robolectric_jar(self):
        code, output = self.fixture.prepare()
        self.assertEqual(code, 0, output)
        root, main = self.fixture.root, self.fixture.main
        for name in ('local.properties', 'app/libs', 'app/src/main/assets'):
            self.assertTrue((root / name).is_symlink(), name)
            self.assertEqual((root / name).resolve(), (main / name).resolve())
        self.assertEqual((root / '.gradle/robolectric' / JAR).read_text(), 'android')
        self.assertEqual([p.name for p in (root / '.gradle/robolectric').iterdir()], [JAR])
        self.assertNotIn(PASSWORD, output)

    def test_second_run_changes_nothing(self):
        self.fixture.prepare()
        before = self.fixture.tree()
        code, output = self.fixture.prepare()
        self.assertEqual((code, output.strip()), (0, 'nothing to do'))
        self.assertEqual(self.fixture.tree(), before)

    def assert_refused(self, *expected):
        before = self.fixture.tree()
        code, output = self.fixture.prepare()
        self.assertEqual(code, 1, output)
        for text in expected:
            self.assertIn(text, output)
        self.assertNotIn(PASSWORD, output)
        self.assertEqual(self.fixture.tree(), before, 'nothing half-written')

    def test_missing_main_assets_name_the_fetch_script(self):
        for path in (self.fixture.main / 'app/src/main/assets').rglob('*'):
            if path.is_file():
                path.unlink()
        self.assert_refused('app/src/main/assets', 'tools/assets/fetch-assets.sh')

    def test_incomplete_signing_names_the_missing_key_never_the_password(self):
        write(self.fixture.main / 'local.properties', 'dictation.keystorePassword=' + PASSWORD + '\n')
        self.assert_refused('dictation.keyAlias', 'tools/set-signing-password.sh')

    def test_a_real_file_in_the_worktree_is_never_replaced(self):
        own = write(self.fixture.root / 'local.properties', 'sdk.dir=/own\n')
        self.assert_refused('local.properties exists')
        self.assertEqual(own.read_text(), 'sdk.dir=/own\n')

    def test_missing_robolectric_android_jar_names_the_cache_and_how_to_fill_it(self):
        (self.fixture.m2 / 'org/robolectric/android-all-instrumented/16-robolectric-13921718-i7' / JAR).unlink()
        self.assert_refused('Maven cache', 'android-all-instrumented', 'python3 tools/prepare_checkout.py --fill')

    def test_missing_gradle_distribution_names_it_and_how_to_fill_it(self):
        dist = self.fixture.shared / 'wrapper/dists/gradle-9.8.1-bin'
        os.rename(dist, dist.with_name('gone'))
        self.assert_refused('gradle-9.8.1-bin', 'outside the sandbox', 'python3 tools/prepare_checkout.py --fill')

    def test_fresh_home_gets_the_distribution_linked_from_the_read_only_copy_without_its_lock(self):
        code, output = self.fixture.prepare()
        self.assertEqual(code, 0, output)
        seeded = self.fixture.gradle_home / 'wrapper/dists/gradle-9.8.1-bin/abc'
        self.assertEqual(sorted(p.name for p in seeded.iterdir()), ['gradle-9.8.1', 'gradle-9.8.1-bin.zip.ok'])
        jar = seeded / 'gradle-9.8.1/lib/gradle.jar'
        self.assertTrue(jar.samefile(self.fixture.source / 'wrapper/dists/gradle-9.8.1-bin/abc/gradle-9.8.1/lib/gradle.jar'))

    def test_home_without_the_wrapper_marker_is_seeded_again(self):
        self.fixture.prepare()
        marker = self.fixture.gradle_home / 'wrapper/dists/gradle-9.8.1-bin/abc/gradle-9.8.1-bin.zip.ok'
        marker.unlink()
        code, output = self.fixture.prepare()
        self.assertEqual(code, 0, output)
        self.assertIn('seeded', output)
        self.assertTrue(marker.is_file())

    def test_robolectric_found_only_in_the_read_only_copy(self):
        (self.fixture.source / 'caches/modules-2/files-2.1/org.robolectric/robolectric/4.17/31a1/robolectric-4.17.jar').unlink()
        code, output = self.fixture.prepare()
        self.assertEqual(code, 0, output)

    def test_upgraded_robolectric_replaces_only_its_own_stale_link(self):
        self.fixture.prepare()
        jars = self.fixture.root / '.gradle/robolectric'
        stale = jars / 'android-all-instrumented-16-robolectric-11111111-i7.jar'
        stale.symlink_to(self.fixture.m2 / 'elsewhere.jar')
        code, output = self.fixture.prepare()
        self.assertEqual(code, 0, output)
        self.assertEqual([p.name for p in jars.iterdir()], [JAR])

    def test_every_cached_instrumentation_level_of_the_required_build_is_linked(self):
        other = 'android-all-instrumented-16-robolectric-13921718-i6.jar'
        write(self.fixture.m2 / 'org/robolectric/android-all-instrumented/16-robolectric-13921718-i6' / other, 'i6')
        code, output = self.fixture.prepare()
        self.assertEqual(code, 0, output)
        self.assertEqual(sorted(p.name for p in (self.fixture.root / '.gradle/robolectric').iterdir()),
                         [other, JAR])

    def test_main_checkout_links_only_the_robolectric_jar(self):
        self.fixture.root = self.fixture.main
        code, output = self.fixture.prepare()
        self.assertEqual(code, 0, output)
        self.assertFalse((self.fixture.main / 'local.properties').is_symlink())
        self.assertTrue((self.fixture.main / '.gradle/robolectric' / JAR).is_symlink())


class RefreshTests(unittest.TestCase):
    """The read-only copy: downloads only, no locks, swapped in whole."""

    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.fixture = Fixture(Path(directory.name))

    def files(self, root):
        return sorted(str(p.relative_to(root)) for p in root.rglob('*') if p.is_file())

    def test_copy_holds_downloads_and_dists_without_locks(self):
        shared, source = self.fixture.shared, self.fixture.source
        self.assertTrue(shared.is_symlink())
        files = self.files(shared.resolve())
        self.assertIn('modules-2/metadata-2.107/module-metadata.bin', files)
        self.assertIn('wrapper/dists/gradle-9.8.1-bin/abc/gradle-9.8.1-bin.zip.ok', files)
        self.assertFalse([f for f in files if f.endswith(('.lock', '.lck', 'gc.properties'))], files)
        jar = 'modules-2/files-2.1/org.robolectric/robolectric/4.17/31a1/robolectric-4.17.jar'
        self.assertTrue((shared / jar).samefile(source / 'caches' / jar), 'downloads are hardlinks')
        index = 'modules-2/metadata-2.107/module-metadata.bin'
        self.assertFalse((shared / index).samefile(source / 'caches' / index), 'indexes rewritten in place are copied')

    def test_refresh_swaps_the_link_and_keeps_the_previous_copy_for_running_builds(self):
        shared = self.fixture.shared
        first = shared.resolve()
        for _ in range(3):
            self.assertEqual(refresh(self.fixture.source, shared, io.StringIO()), 0)
        generations = sorted(p.name for p in shared.parent.glob(shared.name + '.*'))
        self.assertEqual(len(generations), 2, generations)
        self.assertNotIn(first.name, generations)
        self.assertEqual(shared.resolve().name, generations[-1])

    def test_cleanup_keeps_two_complete_copies_and_ignores_partial_ones(self):
        shared = self.fixture.shared
        partial = shared.with_name(shared.name + '.1-partial')
        partial.mkdir()
        for _ in range(3):
            self.assertEqual(refresh(self.fixture.source, shared, io.StringIO()), 0)
        complete = [p for p in shared.parent.glob(shared.name + '.*') if p.name.split('.')[-1].isdigit()]
        self.assertEqual(len(complete), 2, complete)
        self.assertTrue(partial.is_dir(), 'a partial copy may belong to a refresh still running')

    def test_fill_builds_every_gate_and_apk_task_into_the_shared_home_then_refreshes(self):
        calls = []
        def run(command, cwd, env):
            calls.append((command, cwd, env['GRADLE_USER_HOME']))
            return subprocess.CompletedProcess(command, 0)
        out = io.StringIO()
        before = self.fixture.shared.resolve()
        self.assertEqual(fill(self.fixture.main, self.fixture.source, self.fixture.shared, out, run), 0, out.getvalue())
        [(command, cwd, home)] = calls
        self.assertEqual(command[1:], list(FILL_TASKS))
        self.assertTrue(set(GATE_TASKS) <= set(FILL_TASKS))
        self.assertTrue({'assembleRelease', 'assembleBenchmark', 'assembleBenchmarkAndroidTest'} <= set(FILL_TASKS))
        self.assertNotIn('--offline', command)
        self.assertEqual((cwd, home), (self.fixture.main, str(self.fixture.source)))
        self.assertNotEqual(self.fixture.shared.resolve(), before, 'refreshed')

    def test_failed_fill_keeps_the_copy(self):
        out = io.StringIO()
        before = self.fixture.shared.resolve()
        run = lambda command, cwd, env: subprocess.CompletedProcess(command, 1)
        self.assertEqual(fill(self.fixture.main, self.fixture.source, self.fixture.shared, out, run), 1)
        self.assertEqual(self.fixture.shared.resolve(), before)

    def test_missing_source_changes_nothing(self):
        out = io.StringIO()
        before = self.fixture.shared.resolve()
        self.assertEqual(refresh(self.fixture.source / 'absent', self.fixture.shared, out), 1)
        self.assertIn('python3 tools/prepare_checkout.py --fill', out.getvalue())
        self.assertEqual(self.fixture.shared.resolve(), before)


class GradlewTests(unittest.TestCase):
    """Every ./gradlew run, direct or from quality.py, gets this checkout's own home."""

    def environment(self, **extra):
        with tempfile.TemporaryDirectory() as directory:
            java = Path(directory) / 'bin/java'
            write(java, '#!/bin/sh\necho "$GRADLE_USER_HOME|$GRADLE_RO_DEP_CACHE"\n').chmod(0o755)
            env = {'PATH': os.environ['PATH'], 'HOME': '/fake-home', 'JAVA_HOME': directory, **extra}
            output = subprocess.check_output([str(ROOT / 'gradlew'), 'help'], env=env, text=True)
        return output.strip().split('|')

    def test_gradlew_sets_this_checkouts_home_and_the_shared_read_only_copy(self):
        self.assertEqual(self.environment(), [str(ROOT / GRADLE_HOME), str(Path('/fake-home') / SHARED)])

    def test_an_explicit_home_wins_so_the_shared_downloads_can_be_filled(self):
        self.assertEqual(self.environment(GRADLE_USER_HOME='/fake-home/.gradle')[0], '/fake-home/.gradle')


if __name__ == '__main__':
    unittest.main()
