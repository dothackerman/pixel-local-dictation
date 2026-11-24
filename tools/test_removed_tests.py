"""Removed-test check (#77): a host or device test name gone since the base commit needs a mapping entry."""
import json
from pathlib import Path
import subprocess
import tempfile
import unittest

import removed_tests

HOST = 'app/src/test/kotlin/ch/og/dictation/panel/PanelTest.kt'
DEVICE = 'app/src/androidTest/kotlin/ch/og/dictation/PanelDeviceTest.kt'


def kotlin(package, name, *tests):
    body = '\n'.join(f'    {test}() {{}}' for test in tests)
    return f'package {package}\n\nimport org.junit.Test\n\nclass {name} {{\n{body}\n}}\n'


class RemovedTestCheck(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        self.git('init', '-q')
        self.write(HOST, kotlin('ch.og.dictation.panel', 'PanelTest', '@Test fun showsStop', '@Test fun showsBack'))
        self.write(DEVICE, kotlin('ch.og.dictation', 'PanelDeviceTest',
                                  '@Test @Config(qualifiers = "land") fun landscapeKeepsControls',
                                  '@Test\n    fun `ring stays inside`'))
        self.git('add', '.')
        self.git('commit', '-q', '-m', 'base')

    def git(self, *arguments):
        subprocess.run(['git', '-c', 'user.name=t', '-c', 'user.email=t@t', '-c', 'commit.gpgsign=false',
                        *arguments], cwd=self.root, check=True)

    def write(self, name, text):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)

    def mapping(self, entries):
        self.write(removed_tests.MAPPING, json.dumps(entries))

    def problems(self):
        return removed_tests.problems(self.root, 'HEAD')

    def test_unchanged_tests_pass(self):
        self.assertEqual([], self.problems())

    def test_removed_host_test_without_mapping_fails(self):
        self.write(HOST, kotlin('ch.og.dictation.panel', 'PanelTest', '@Test fun showsStop'))
        self.assertEqual(['removed test ch.og.dictation.panel.PanelTest.showsBack has no entry in '
                          + removed_tests.MAPPING], self.problems())

    def test_removed_device_test_with_multi_line_annotation_and_quoted_name_fails(self):
        (self.root / DEVICE).unlink()
        self.assertEqual(['removed test ch.og.dictation.PanelDeviceTest.landscapeKeepsControls has no entry in '
                          + removed_tests.MAPPING,
                          'removed test ch.og.dictation.PanelDeviceTest.ring stays inside has no entry in '
                          + removed_tests.MAPPING], self.problems())

    def test_removed_test_mapped_to_an_existing_test_passes(self):
        self.write(HOST, kotlin('ch.og.dictation.panel', 'PanelTest', '@Test fun showsStop'))
        self.mapping({'ch.og.dictation.panel.PanelTest.showsBack': 'ch.og.dictation.panel.PanelTest.showsStop'})
        self.assertEqual([], self.problems())

    def test_renamed_class_counts_as_removed(self):
        (self.root / HOST).unlink()
        self.write(HOST.replace('PanelTest', 'PanelFrameTest'),
                   kotlin('ch.og.dictation.panel', 'PanelFrameTest', '@Test fun showsStop', '@Test fun showsBack'))
        self.assertEqual(['removed test ch.og.dictation.panel.PanelTest.showsBack has no entry in ' + removed_tests.MAPPING,
                          'removed test ch.og.dictation.panel.PanelTest.showsStop has no entry in ' + removed_tests.MAPPING],
                         self.problems())

    def test_device_test_moved_to_the_host_under_the_same_name_is_not_removed(self):
        (self.root / DEVICE).unlink()
        self.write('app/src/test/kotlin/ch/og/dictation/PanelDeviceTest.kt',
                   kotlin('ch.og.dictation', 'PanelDeviceTest', '@Test fun landscapeKeepsControls',
                          '@Test fun `ring stays inside`'))
        self.assertEqual([], self.problems())

    def test_mapping_to_a_test_that_does_not_exist_fails(self):
        self.write(HOST, kotlin('ch.og.dictation.panel', 'PanelTest', '@Test fun showsStop'))
        self.mapping({'ch.og.dictation.panel.PanelTest.showsBack': 'ch.og.dictation.panel.PanelTest.gone'})
        self.assertEqual(['removed test ch.og.dictation.panel.PanelTest.showsBack is mapped to '
                          'ch.og.dictation.panel.PanelTest.gone, which is no test'], self.problems())

    def test_retired_test_passes_with_a_reason_only(self):
        self.write(HOST, kotlin('ch.og.dictation.panel', 'PanelTest', '@Test fun showsStop'))
        self.mapping({'ch.og.dictation.panel.PanelTest.showsBack': 'retired: '})
        self.assertEqual(1, len(self.problems()))
        self.mapping({'ch.og.dictation.panel.PanelTest.showsBack': 'retired: the Back arrow is gone (#0)'})
        self.assertEqual([], self.problems())

    def test_second_class_in_a_file_names_its_own_tests(self):
        self.write(HOST, kotlin('ch.og.dictation.panel', 'PanelTest', '@Test fun showsStop', '@Test fun showsBack')
                   + '\nclass OtherTest {\n    @Test fun third() {}\n}\n')
        self.git('add', '.')
        self.git('commit', '-q', '-m', 'second class')
        self.write(HOST, kotlin('ch.og.dictation.panel', 'PanelTest', '@Test fun showsStop', '@Test fun showsBack'))
        self.assertEqual(['removed test ch.og.dictation.panel.OtherTest.third has no entry in ' + removed_tests.MAPPING],
                         self.problems())


class RepositoryTests(unittest.TestCase):
    def test_this_checkout_names_host_and_device_tests(self):
        names = removed_tests.current_names(removed_tests.ROOT)
        self.assertIn('ch.og.dictation.panel.PanelFrameTest.heldTextOwnsStatusAndBack', names)
        self.assertIn('ch.og.dictation.PhoneReadyTest.phoneIsReadyForDeviceSuites', names)
        self.assertIn('ch.og.dictation.storage.LabBuildStorageTest.historyResolvesInsideTheTestFolders', names)
        self.assertIn('ch.og.dictation.storage.ReleaseStorageTest.historyIsTheOwnersHistory', names)


if __name__ == '__main__':
    unittest.main()
