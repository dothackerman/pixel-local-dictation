import io
from pathlib import Path
import tempfile
import unittest
import zipfile

from prepare_checkout import prepare

PASSWORD = 'not-a-real-password-71'
JAR = 'android-all-instrumented-16-robolectric-13921718-i7.jar'


def write(path, text=''):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text)
    return path


class Fixture:
    """A main checkout, a fresh worktree and filled Gradle and Maven caches."""

    def __init__(self, base):
        self.main, self.root = base / 'main', base / 'main/build/wt'
        self.gradle_home, self.m2 = base / 'gradle-home', base / 'm2'
        for checkout in (self.main, self.root):
            write(checkout / 'gradle/wrapper/gradle-wrapper.properties',
                  'distributionUrl=https\\://services.gradle.org/distributions/gradle-9.8.1-bin.zip\n')
            write(checkout / 'app/build.gradle.kts', 'testImplementation("org.robolectric:robolectric:4.17")\n')
        write(self.main / 'local.properties', 'dictation.keystore=/k/release.jks\ndictation.keyAlias=dictation\n'
              'dictation.keystorePassword=' + PASSWORD + '\n')
        write(self.main / 'app/libs/sherpa-onnx-1.13.8.aar', 'aar')
        write(self.main / 'app/src/main/assets/parakeet-v3/tokens.txt', 'tokens')
        (self.gradle_home / 'wrapper/dists/gradle-9.8.1-bin').mkdir(parents=True)
        robolectric = self.gradle_home / 'caches/modules-2/files-2.1/org.robolectric/robolectric/4.17/31a1/robolectric-4.17.jar'
        robolectric.parent.mkdir(parents=True)
        with zipfile.ZipFile(robolectric, 'w') as jar:
            jar.writestr('org/robolectric/plugins/DefaultSdkProvider.class', b'\xca\xfe 10818077 13921718 \x00')
        write(self.m2 / 'org/robolectric/android-all-instrumented/16-robolectric-13921718-i7' / JAR, 'android')
        write(self.m2 / 'org/robolectric/android-all-instrumented/16-robolectric-11111111-i7/'
              'android-all-instrumented-16-robolectric-11111111-i7.jar', 'stale android')

    def prepare(self):
        out = io.StringIO()
        code = prepare(self.root, self.main, self.gradle_home, self.m2, out)
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
        self.assert_refused('Maven cache', 'android-all-instrumented', './gradlew testBenchmarkUnitTest')

    def test_missing_gradle_distribution_names_it_and_how_to_fill_it(self):
        (self.fixture.gradle_home / 'wrapper/dists/gradle-9.8.1-bin').rmdir()
        self.assert_refused('gradle-9.8.1-bin', 'outside the sandbox')

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


if __name__ == '__main__':
    unittest.main()
