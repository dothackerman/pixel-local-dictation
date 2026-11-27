"""Host check (ADR-029, slimmed by #100): clipboard only inside Storage, no shell anywhere."""
from pathlib import Path
import tempfile
import unittest

import storage_guard

PLANTED = {
    'clipboard service': ['getSystemService(Context.CLIPBOARD_SERVICE)', 'getSystemService(ClipboardManager::class.java)'],
    'process execution': ['Runtime.getRuntime().exec("ls")', 'ProcessBuilder("ls").start()', 'runtime.exec(arrayOf("ls"))'],
    'test-automation shell': ['instrumentation.uiAutomation.executeShellCommand("pm clear ch.og.dictation")',
                              'UiDevice.getInstance(i)'],
}


class GuardTests(unittest.TestCase):
    def tree(self, files):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        root = Path(directory.name)
        for name, text in files.items():
            (root / name).parent.mkdir(parents=True, exist_ok=True)
            (root / name).write_text(text)
        return root

    def test_every_banned_kind_is_planted_here(self):
        self.assertEqual(set(storage_guard.RULES), set(PLANTED))

    def test_each_planted_call_fails_in_app_lab_build_and_device_test_sources(self):
        for source in ('app/src/main/kotlin/ch/og/dictation/A.kt', 'app/src/release/kotlin/ch/og/dictation/A.kt',
                       'app/src/benchmark/kotlin/ch/og/dictation/probe/A.kt',
                       'app/src/androidTest/kotlin/ch/og/dictation/ATest.kt'):
            for rule, lines in PLANTED.items():
                for line in lines:
                    root = self.tree({source: 'package x\n' + line + '\n'})
                    self.assertEqual([f'{source}:2: {rule}: {line}'], storage_guard.problems(root, {}), line)

    def test_storage_itself_comments_and_host_tests_are_not_checked(self):
        call = 'val c = getSystemService(ClipboardManager::class.java)\n'
        root = self.tree({
            'app/src/main/kotlin/ch/og/dictation/storage/Storage.kt': call,
            'app/src/benchmark/kotlin/ch/og/dictation/storage/BuildType.kt': call,
            'app/src/test/kotlin/ch/og/dictation/WiringTest.kt': call,
            'app/src/main/kotlin/ch/og/dictation/A.kt': '// ClipboardManager is not used\n/* UiDevice\n exec( */\nval x = 1\n',
        })
        self.assertEqual([], storage_guard.problems(root, {}))
        device = self.tree({'app/src/androidTest/kotlin/ch/og/dictation/storage/Sneak.kt': call})
        self.assertEqual(1, len(storage_guard.problems(device, {})))

    def test_a_named_exception_covers_only_its_file_and_kind_and_must_still_be_needed(self):
        name = 'app/src/androidTest/kotlin/ch/og/dictation/ATest.kt'
        root = self.tree({name: 'val a = UiDevice.getInstance(i)\nval b = ProcessBuilder("ls")\n'})
        self.assertEqual([f'{name}:2: process execution: val b = ProcessBuilder("ls")'],
                         storage_guard.problems(root, {name: {'test-automation shell': 'r'}}))
        self.assertEqual([f'{name}: exception no longer needed: clipboard service (r)'],
                         storage_guard.problems(root, {name: {'test-automation shell': 'r', 'process execution': 'r',
                                                              'clipboard service': 'r'}}))

    def test_this_tree_passes_without_any_exception(self):
        self.assertEqual({}, storage_guard.EXCEPTIONS)
        self.assertEqual([], storage_guard.problems())

    def test_a_manifest_without_the_build_marker_fails(self):
        entry = '<meta-data android:name="ch.og.dictation.build" android:value="${buildMarker}" />'
        manifests = {'app/src/main/AndroidManifest.xml': f'<manifest><application>{entry}</application></manifest>',
                     'app/src/androidTest/AndroidManifest.xml': '<manifest><application/></manifest>'}
        self.assertEqual(['app/src/androidTest/AndroidManifest.xml: no build marker entry'],
                         storage_guard.marker_problems(self.tree(manifests)))
        self.assertEqual([], storage_guard.marker_problems())


if __name__ == '__main__':
    unittest.main()
