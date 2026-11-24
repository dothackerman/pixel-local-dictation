#!/usr/bin/env python3
"""Host check (ADR-028): only Storage resolves app storage, writes the clipboard or runs a shell.

Checked: app, lab-build and device-test sources. Storage is `ch/og/dictation/storage/` in the app's
source sets. A banned call anywhere else needs a named exception; EXCEPTIONS holds the stores not
yet behind Storage and shrinks with each one that moves. An exception nothing needs any more fails too.

python3 tools/storage_guard.py   prints each problem; exit 1 when any exists.
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCES = ('app/src/main', 'app/src/release', 'app/src/benchmark', 'app/src/androidTest')
STORAGE = tuple(f'{source}/kotlin/ch/og/dictation/storage/' for source in SOURCES[:3])
MANIFESTS = ('app/src/main/AndroidManifest.xml', 'app/src/androidTest/AndroidManifest.xml')
MARKER = re.compile(r'android:name="ch\.og\.dictation\.build"\s+android:value="\$\{buildMarker\}"')

RULES = {name: re.compile(pattern) for name, pattern in {
    'files folder': r'\bfilesDir\b|\bgetFilesDir\b',
    'no-backup folder': r'\bnoBackupFilesDir\b|\bgetNoBackupFilesDir\b',
    'cache folder': r'\bcacheDir\b|\bgetCacheDir\b',
    'code-cache folder': r'\bcodeCacheDir\b|\bgetCodeCacheDir\b',
    'data folder': r'\bdataDir\b|\bgetDataDir\b',
    'external folders': r'\b(get)?[eE]xternal(Files|Cache|Media)Dirs?\b|\b(get)?[oO]bbDirs?\b|\bEnvironment\s*\.'
                        r'|\bgetExternalStorage',
    'device-protected context': r'\bcreate(Device|Credential)ProtectedStorageContext\b',
    'preferences': r'\b(get|delete|moveTo|moveFrom)?SharedPreferences(From)?\s*\(|\bgetPreferences\s*\(|\bPreferenceManager\b',
    'databases': r'\b(openOrCreateDatabase|getDatabasePath|deleteDatabase|databaseList|moveDatabaseFrom)\b'
                 r'|\bSQLite(Database|OpenHelper)\b',
    'file streams by name': r'\b(openFileInput|openFileOutput|getFileStreamPath|deleteFile|fileList)\s*\(',
    'private directories': r'\bgetDir\s*\(',
    'app-data absolute paths': r'/data/(data|user|user_de|local)\b|/sdcard\b|/storage/emulated\b',
    'clipboard service': r'\bCLIPBOARD_SERVICE\b|\bClipboardManager\b',
    'process execution': r'\bProcessBuilder\b|\.\s*exec\s*\(',
    'test-automation shell': r'\bexecuteShellCommand(Rw|Rwe)?\b|\bUiDevice\b',
}.items()}

# path -> {banned kind: the store it still reaches directly}. Stores not yet behind Storage (#73);
# History is, so nothing here names the no-backup folder.
_MAIN = 'app/src/main/kotlin/ch/og/dictation/'
_LAB = 'app/src/benchmark/kotlin/ch/og/dictation/probe/'
_DEVICE = 'app/src/androidTest/kotlin/ch/og/dictation/'
EXCEPTIONS = {
    _MAIN + 'DictationImeService.kt': {'files folder': 'lab folder'},
    _MAIN + 'diagnostics/BenchmarkStore.kt': {'files folder': 'recordings', 'preferences': 'settings'},
    _MAIN + 'diagnostics/StartupTrace.kt': {'files folder': 'lab folder'},
    _MAIN + 'recognition/Engine.kt': {'files folder': 'lab folder, Dictionary'},
    _MAIN + 'history/Clipboard.kt': {'clipboard service': 'clipboard'},
    _MAIN + 'history/HistoryNotices.kt': {'preferences': 'History notices'},
    _LAB + 'CheckpointProducerService.kt': {'files folder': 'lab folder'},
    _LAB + 'EditorProbeActivity.kt': {'files folder': 'lab folder'},
    _LAB + 'SyntheticFixtureAudio.kt': {'files folder': 'lab folder'},
    _DEVICE + 'DictionaryRuntimeUpdateTest.kt': {'files folder': 'Dictionary'},
    _DEVICE + 'DictionarySettingsTest.kt': {'files folder': 'lab folder', 'cache folder': 'scratch files'},
    _DEVICE + 'EditorGeometryTest.kt': {'files folder': 'lab folder'},
    _DEVICE + 'GuidedVisitTest.kt': {'files folder': 'lab folder, recordings'},
    _DEVICE + 'HistoryActivityTest.kt': {'clipboard service': 'clipboard'},
    _DEVICE + 'HistoryClearTest.kt': {'clipboard service': 'clipboard'},
    _DEVICE + 'LabTest.kt': {'files folder': 'lab folder, recordings'},
    _DEVICE + 'MeasurementRun.kt': {'files folder': 'lab folder, recordings', 'cache folder': 'scratch files'},
    _DEVICE + 'NormalBaselineTest.kt': {'files folder': 'lab folder'},
    _DEVICE + 'PanelPerformanceTest.kt': {'files folder': 'lab folder'},
    _DEVICE + 'PhoneReadyTest.kt': {'files folder': 'lab folder'},
    _DEVICE + 'PipelineTest.kt': {'files folder': 'recordings'},
    _DEVICE + 'PrivacyProbeTest.kt': {'files folder': 'recordings'},
    _DEVICE + 'RecordingPanelTest.kt': {'files folder': 'lab folder'},
    _DEVICE + 'RecoveryTimingTest.kt': {'files folder': 'lab folder'},
    _DEVICE + 'ReplayTest.kt': {'files folder': 'recordings'},
    _DEVICE + 'RotationContinuityDemoTest.kt': {'files folder': 'lab folder'},
    _DEVICE + 'RotationFinishingDemoTest.kt': {'files folder': 'lab folder'},
    _DEVICE + 'SelfRotationProbeTest.kt': {'files folder': 'lab folder'},
    _DEVICE + 'SettingsHistoryScreenshotTest.kt': {'files folder': 'lab folder', 'clipboard service': 'clipboard'},
    _DEVICE + 'StartupTraceTest.kt': {'files folder': 'lab folder', 'cache folder': 'scratch files'},
}


def code(source):
    """Source with comments blanked, line structure kept; strings stay (paths live in them)."""
    blank = lambda match: re.sub(r'[^\n]', ' ', match.group(0))
    return re.sub(r'(?<![:"\w])//[^\n]*', '', re.sub(r'/\*.*?\*/', blank, source, flags=re.S))


def problems(root=ROOT, exceptions=None):
    root = Path(root)
    exceptions = EXCEPTIONS if exceptions is None else exceptions
    found, used = [], set()
    for source in SOURCES:
        for path in sorted((root / source).rglob('*')):
            name = path.relative_to(root).as_posix()
            if path.suffix not in ('.kt', '.java') or name.startswith(STORAGE):
                continue
            text = path.read_text(encoding='utf-8')
            for number, (line, original) in enumerate(zip(code(text).splitlines(), text.splitlines()), 1):
                for rule, pattern in RULES.items():
                    if not pattern.search(line):
                        continue
                    if rule in exceptions.get(name, {}):
                        used.add((name, rule))
                    else:
                        found.append(f'{name}:{number}: {rule}: {original.strip()}')
    for name in sorted(exceptions):
        for rule, store in exceptions[name].items():
            if (name, rule) not in used:
                found.append(f'{name}: exception no longer needed: {rule} ({store})')
    return found


def marker_problems(root=ROOT):
    """App and test manifest both carry the build marker entry Gradle fills in."""
    return [f'{name}: no build marker entry' for name in MANIFESTS
            if not MARKER.search((Path(root) / name).read_text(encoding='utf-8'))]


def main():
    found = problems() + marker_problems()
    if found:
        print('\n'.join(found), file=sys.stderr)
    return 1 if found else 0


if __name__ == '__main__':
    raise SystemExit(main())
