"""Host check (ADR-028): storage, clipboard and shell calls only inside Storage."""
from pathlib import Path
import tempfile
import unittest

import storage_guard

PLANTED = {
    'files folder': ['val f = File(context.filesDir, "x")', 'getFilesDir()'],
    'no-backup folder': ['File(noBackupFilesDir, "history")', 'context.getNoBackupFilesDir()'],
    'cache folder': ['File(context.cacheDir, "x")', 'getCacheDir()'],
    'code-cache folder': ['context.codeCacheDir', 'getCodeCacheDir()'],
    'data folder': ['File(applicationInfo.dataDir, "no_backup/history")', 'context.getDataDir()'],
    'external folders': ['context.getExternalFilesDir(null)', 'context.externalCacheDir', 'externalMediaDirs.first()',
                         'context.obbDir', 'Environment.getExternalStorageDirectory()'],
    'device-protected context': ['context.createDeviceProtectedStorageContext()'],
    'preferences': ['context.getSharedPreferences("settings", 0)', 'activity.getPreferences(0)',
                    'PreferenceManager.getDefaultSharedPreferences(context)', 'deleteSharedPreferences("settings")'],
    'databases': ['context.openOrCreateDatabase("d", 0, null)', 'context.getDatabasePath("d")',
                  'context.deleteDatabase("d")', 'context.databaseList()'],
    'file streams by name': ['openFileOutput("x", 0)', 'openFileInput("x")', 'getFileStreamPath("x")',
                             'context.deleteFile("x")', 'context.fileList()'],
    'private directories': ['context.getDir("x", 0)'],
    'app-data absolute paths': ['File("/data/data/ch.og.dictation/no_backup/history")',
                                'File("/data/user/0/ch.og.dictation/files")', '"/data/user_de/0/ch.og.dictation"',
                                'File("/sdcard/x")', 'File("/storage/emulated/0/x")'],
    'clipboard service': ['getSystemService(Context.CLIPBOARD_SERVICE)', 'getSystemService(ClipboardManager::class.java)'],
    'process execution': ['Runtime.getRuntime().exec("ls")', 'ProcessBuilder("ls").start()', 'runtime.exec(arrayOf("ls"))'],
    'test-automation shell': ['instrumentation.uiAutomation.executeShellCommand("ls")', 'UiDevice.getInstance(i)'],
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
        call = 'val f = File(context.noBackupFilesDir, "history")\n'
        root = self.tree({
            'app/src/main/kotlin/ch/og/dictation/storage/Storage.kt': call,
            'app/src/benchmark/kotlin/ch/og/dictation/storage/TestLayout.kt': call,
            'app/src/test/kotlin/ch/og/dictation/WiringTest.kt': call,
            'app/src/main/kotlin/ch/og/dictation/A.kt': '// filesDir is not used\n/* cacheDir\n noBackupFilesDir */\nval x = 1\n',
        })
        self.assertEqual([], storage_guard.problems(root, {}))
        device = self.tree({'app/src/androidTest/kotlin/ch/og/dictation/storage/Sneak.kt': call})
        self.assertEqual(1, len(storage_guard.problems(device, {})))

    def test_a_named_exception_covers_only_its_file_and_kind_and_must_still_be_needed(self):
        name = 'app/src/main/kotlin/ch/og/dictation/A.kt'
        root = self.tree({name: 'val a = context.filesDir\nval b = context.cacheDir\n'})
        self.assertEqual([f'{name}:2: cache folder: val b = context.cacheDir'],
                         storage_guard.problems(root, {name: {'files folder': 'lab folder'}}))
        self.assertEqual([], storage_guard.problems(root, {name: {'files folder': 'lab folder', 'cache folder': 'x'}}))
        self.assertEqual([f'{name}: exception no longer needed: preferences (settings)'],
                         storage_guard.problems(root, {name: {'files folder': 'lab folder', 'cache folder': 'x',
                                                              'preferences': 'settings'}}))
        self.assertEqual(['app/src/main/kotlin/Gone.kt: exception no longer needed: files folder (lab folder)'],
                         storage_guard.problems(self.tree({}), {'app/src/main/kotlin/Gone.kt': {'files folder': 'lab folder'}}))

    def test_this_tree_passes_without_any_exception(self):
        """Every store is behind Storage (#80): app, lab-build and device-test sources reach none directly."""
        self.assertEqual({}, storage_guard.EXCEPTIONS)
        self.assertEqual([], storage_guard.problems())

    def test_a_manifest_without_the_build_marker_fails(self):
        entry = '<meta-data android:name="ch.og.dictation.build" android:value="${buildMarker}" />'
        manifests = {'app/src/main/AndroidManifest.xml': f'<manifest><application>{entry}</application></manifest>',
                     'app/src/androidTest/AndroidManifest.xml': '<manifest><application/></manifest>'}
        self.assertEqual(['app/src/androidTest/AndroidManifest.xml: no build marker entry'],
                         storage_guard.marker_problems(self.tree(manifests)))
        self.assertEqual([], storage_guard.marker_problems())

    def test_the_wrappers_excluded_locations_must_equal_the_test_layout(self):
        layout = '{"labFolder": "files/lab", "testFolders": ["files/test", "no_backup/test", "shared_prefs/test_settings.xml"]}'
        wrapper = "PKG = 'x'\nEXCLUDED = ('files/lab', 'files/test', 'no_backup/test',\n            %s)\n"
        tree = lambda entry: self.tree({'app/src/benchmark/test-folders.json': layout, 'tools/phone': wrapper % entry})
        self.assertEqual([], storage_guard.wrapper_problems(tree("'shared_prefs/test_settings.xml'")))
        self.assertEqual(['tools/phone: EXCLUDED lacks shared_prefs/test_settings.xml of the test layout'],
                         storage_guard.wrapper_problems(tree("")))
        self.assertEqual(['tools/phone: EXCLUDED names files/dictionary, which the test layout does not'],
                         storage_guard.wrapper_problems(tree("'shared_prefs/test_settings.xml', 'files/dictionary'")))
        missing = self.tree({'app/src/benchmark/test-folders.json': layout, 'tools/phone': "PKG = 'x'\nEXCLUDED = FOLDERS\n"})
        self.assertEqual(['tools/phone: EXCLUDED must be one literal tuple of locations'], storage_guard.wrapper_problems(missing))
        self.assertEqual([], storage_guard.wrapper_problems())


if __name__ == '__main__':
    unittest.main()
