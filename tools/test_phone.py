"""Phone wrapper behaviour through its adapter seam; never contacts a phone.

FakeDevice stands in for the adb adapter. ArgvDevice drives the real adapter
with a scripted `runner` to check the exact adb argv it builds.
"""
from contextlib import redirect_stdout
import hashlib
import importlib.machinery
import importlib.util
import io
import json
import shlex
import shutil
import subprocess
import tarfile
import tempfile
import unittest
from unittest.mock import patch
from pathlib import Path

SOURCE = Path(__file__).with_name('phone')
loader = importlib.machinery.SourceFileLoader('phone_module', str(SOURCE))
spec = importlib.util.spec_from_loader(loader.name, loader)
phone = importlib.util.module_from_spec(spec)
loader.exec_module(phone)

RELEASE, APP, TEST, SWITCH_TEST = phone.PKG, phone.LAB_APP, phone.TEST, phone.SWITCH_TEST
NS = phone.PKG  # the code namespace, which class names keep
BUILD = 'commit=' + '1' * 40 + ';hash=' + '2' * 64
LAB, OWNER = 'layout=test;' + BUILD, 'layout=owner;' + BUILD
OTHER_LAB = 'layout=test;commit=' + '3' * 40 + ';hash=' + '4' * 64
# The lab folder; plus what Android and androidx write on their own.
LAB_RUN_CLASS = NS + '.labrun.PipelineTest'
NOT_FINGERPRINTED = ('files/lab', 'cache', 'code_cache', 'files/profileInstalled')


def sha(data):
    return hashlib.sha256(data).hexdigest()


def tar_bytes(files):
    buffer = io.BytesIO()
    with tarfile.open(fileobj=buffer, mode='w') as archive:
        for path, data in files.items():
            info = tarfile.TarInfo(path)
            info.size = len(data)
            archive.addfile(info, io.BytesIO(data))
    return buffer.getvalue()


class FakeDevice:
    """In-memory phone: app data as {relpath: bytes} per app, installed APKs as bytes.

    `data` is the lab app's data, `release_data` release's. Like the phone, it refuses `run-as`
    when the app is not installed or not debuggable."""

    def __init__(self):
        self.builds = {}  # sha256 of APK bytes -> what the build tools report for it
        self.contents = {}  # package -> installed APK bytes
        self.packages = {RELEASE: {'installed': False, 'versionCode': 12, 'versionName': '1',
                                   'apk_path': '/data/app/r/base.apk', 'uid': 10100,
                                   'granted': ['android.permission.RECORD_AUDIO'], 'pid': None,
                                   'first_install': '2026-10-01 08:00:00', 'last_update': '2026-11-20 08:00:00'},
                         APP: {'installed': False, 'versionCode': 12, 'versionName': '1',
                               'apk_path': '/data/app/x/base.apk', 'uid': 10123, 'granted': ['android.permission.RECORD_AUDIO'],
                               'pid': None, 'first_install': '2026-11-25 08:00:00'},
                         TEST: {'installed': False, 'versionCode': 0, 'versionName': None,
                                'apk_path': '/data/app/y/base.apk', 'uid': 10124, 'granted': [], 'pid': None},
                         SWITCH_TEST: {'installed': False, 'versionCode': 0, 'versionName': None,
                                       'apk_path': '/data/app/z/base.apk', 'uid': 10101, 'granted': [], 'pid': None}}
        for futo in phone.FUTO:
            self.packages[futo] = {'installed': False}
        self.data = {'files/lab/variant.json': b'{}', 'files/lab/encoder.onnx': b'onnx',
                     'files/benchmark/take1.wav': b'RIFF', 'shared_prefs/p.xml': b'<x/>',
                     'no_backup/history/1.json': b'{"text":"lab"}', 'files/dictionary/revision.json': b'[]'}
        self.release_data = {'files/lab/encoder.onnx': b'onnx', 'files/dictionary/revision.json': b'["Oriol"]',
                             'no_backup/history/1.json': b'{"text":"owner"}', 'shared_prefs/settings.xml': b'<s/>'}
        self.fail = set()  # the adapter calls that fail, as a broken transport would
        self.ime_state = {'enabled': True, 'selected': False, 'bound': False, 'input_shown': False}  # the lab keyboard
        self.release_ime = {'enabled': True, 'selected': False, 'bound': False, 'input_shown': False}
        self.calls = []
        self.during_run = lambda: None
        self.report = 'INSTRUMENTATION_STATUS: stream=LAB RUN completed: fake\n'  # what a Lab run's harness reports
        self.place(self.build(APP, LAB, True, b'lab app'))
        self.place(self.build(TEST, LAB, True, b'lab tests'))

    def build(self, package, marker, debuggable, content, signer='s'):
        """An APK the build tools can read; returns its bytes."""
        self.builds[sha(content)] = {'package': package, 'versionCode': 0 if package in (TEST, SWITCH_TEST) else 12,
                                     'sha256': sha(content), 'signer': signer, 'permissions': [],
                                     'debuggable': debuggable, 'marker': marker}
        return content

    def place(self, content):
        """As if installed earlier, by whatever means."""
        package = self.builds[sha(content)]['package']
        self.contents[package] = content
        self.packages[package].update(installed=True, sha256=sha(content))

    def run_as(self, of=APP):
        if not self.packages[of]['installed']:
            raise phone.Failed('run-as: unknown package: ' + of)
        if not self.builds[self.packages[of]['sha256']]['debuggable']:
            raise phone.Failed('run-as: package not debuggable: ' + of)
        self.calls.append(('run_as', of))

    def of(self, package):
        return self.release_data if package == RELEASE else self.data

    def attempt(self, call):
        if call in self.fail:
            raise phone.Failed(call + ': transport error')

    def outside(self, skip, of=APP):
        return {k: v for k, v in self.of(of).items() if not any(k == s or k.startswith(s + '/') for s in skip)}

    def connect(self):
        pass

    def props(self):
        return {p: 'v' for p in phone.PROPS}

    def thermal(self):
        return 0

    def driver_libs(self):
        return ['libOpenCL.so']

    def package(self, pkg):
        if pkg not in self.packages:
            raise phone.Refused('package outside scope')
        return dict(self.packages[pkg])

    def ime(self, of=APP):
        return dict(self.release_ime if of == RELEASE else self.ime_state)

    def inventory(self, rel):
        self.run_as()
        files = {k[len(rel) + 1:]: sha(v) for k, v in self.data.items() if k.startswith(rel + '/')}
        return files or None

    def fingerprint(self, of=APP):
        self.run_as(of)
        self.calls.append(('fingerprint',))
        lines = []
        for path, content in self.outside(NOT_FINGERPRINTED, of).items():
            lines += ['%s  ./%s' % (sha(content), path), '%d ./%s' % (len(content), path)]
        return {'digest': sha(''.join(line + '\n' for line in sorted(lines)).encode()), 'files': len(lines) // 2}

    def logcat(self, uid, since=None, crash=False):
        self.calls.append(('logcat', uid, since, crash))
        return 'log for uid %s' % uid

    def device_time(self):
        return '10-21 10:00:00.000'

    def apk_info(self, path):
        return dict(self.builds[sha(Path(path).read_bytes())])

    def install(self, path):
        self.attempt('install')
        content = Path(path).read_bytes()
        package = self.builds[sha(content)]['package']
        self.place(content)
        self.calls.append(('install', package))
        if package == RELEASE:
            self.packages[RELEASE]['last_update'] = '2026-11-25 12:00:00'
        if package in (RELEASE, APP):  # what Android and androidx write on every update
            self.of(package).update({'code_cache/startup': sha(content).encode(), 'files/profileInstalled': b'1'})

    def uninstall(self, pkg):
        self.packages[pkg]['installed'] = False
        self.packages[pkg].pop('sha256', None)
        self.calls.append(('uninstall', pkg))
        if pkg == APP:
            self.data = {}  # Android removes the app's data with it

    def stop(self, of=APP):
        self.calls.append(('stop',) + (() if of == APP else (of,)))

    def push(self, directory, changed, removed):
        self.run_as()
        self.calls.append(('push', tuple(sorted(changed)), tuple(sorted(removed))))
        for item in removed:
            self.data.pop('files/lab/' + item, None)
        for item in changed:
            self.data['files/lab/' + item] = (Path(directory) / item).read_bytes()

    def tar(self, *rels, of=APP):
        self.run_as(of)
        files = {k: v for k, v in self.of(of).items() if any(k == r or k.startswith(r + '/') for r in rels)}
        return tar_bytes(files) if files else b''

    def pull_apk(self, remote, local):
        for pkg, info in self.packages.items():
            if info.get('apk_path') == remote:
                Path(local).write_bytes(self.contents[pkg])
                self.calls.append(('pull_apk', pkg))

    def copy(self, skip, of=APP):
        self.run_as(of)
        self.attempt('copy')
        self.calls.append(('copy',))
        files = self.outside(skip, of)
        return tar_bytes({'./' + path: content for path, content in files.items()}) if files else b''

    def replace(self, archive, skip, of=APP):
        self.run_as(of)
        self.calls.append(('replace',))
        data = {k: v for k, v in self.of(of).items() if k not in self.outside(skip, of)}
        if archive:
            with tarfile.open(fileobj=io.BytesIO(archive)) as a:
                for m in a:
                    if m.isfile():
                        data[m.name[2:] if m.name.startswith('./') else m.name] = a.extractfile(m).read()
        if of == RELEASE:
            self.release_data = data
        else:
            self.data = data

    def instrument(self, classes, extras, timeout):
        self.calls.append(('instrument', classes, tuple(extras)))
        self.attempt('instrument')
        self.data['files/lab/result.json'] = b'{"ok":true}'
        self.during_run()
        return self.report + 'INSTRUMENTATION_STATUS_CODE: 0\nOK (1 test)\nINSTRUMENTATION_CODE: -1\n'


class PhoneCase(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.device = FakeDevice()
        self.phone = phone.Phone(self.device, self.root / 'lab')
        self.model = self.root / 'lab/model'  # the kept model copy: its files are the speech-model files
        self.model.mkdir(parents=True)
        (self.model / 'encoder.onnx').write_bytes(b'onnx')

    def call(self, *argv):
        return phone.dispatch(list(argv), self.phone)

    def apk(self, file_name, package, marker, debuggable, **facts):
        path = self.root / file_name
        path.write_bytes(self.device.build(package, marker, debuggable, file_name.encode(), **facts))
        return str(path)

    def did(self, *kinds):
        return [c for c in self.device.calls if c[0] in kinds]

    def refused(self, expected, *argv):
        with self.assertRaises(phone.Refused) as caught:
            self.call(*argv)
        self.assertIn(expected, str(caught.exception))

    def lab_apks(self, tag='lab', marker=LAB):
        return [self.apk(tag + '-app.apk', APP, marker, True), self.apk(tag + '-test.apk', TEST, marker, True)]

    def release_apk(self, tag='release'):
        return self.apk(tag + '.apk', RELEASE, OWNER, False)

    def with_release_installed(self, tag='release'):
        """Release installed and known to the wrapper, as after the switch."""
        path = self.release_apk(tag)
        self.device.place(Path(path).read_bytes())
        self.phone.remember([self.device.builds[sha(Path(path).read_bytes())]])  # as its own install recorded it
        self.device.calls.clear()
        return path

    def without_lab_app(self):
        """No lab app installed; what lies in `data` stands for what a fresh lab app finds."""
        for package in (APP, TEST):
            self.device.packages[package]['installed'] = False
            self.device.packages[package].pop('sha256', None)

    def lab_beside_release(self):
        """Release installed, then the lab app beside it."""
        self.with_release_installed()
        self.without_lab_app()
        self.call('install', *self.lab_apks())
        self.device.calls.clear()

class PhoneTests(PhoneCase):
    def test_read_only_verbs_expose_only_our_app_and_allowlisted_facts(self):
        info = self.call('app')
        self.assertEqual(set(info), {'release', 'app', 'test', 'ime', 'futo', 'lab'})
        self.assertEqual((info['release']['layout'], info['app']['layout']), (None, 'test'))
        self.assertEqual(set(info['ime']), {'release', 'lab'})
        for key in ('release', 'app', 'test'):
            self.assertNotIn('apk_path', info[key])
        self.assertEqual(set(info['futo']), set(phone.FUTO))
        device = self.call('device')
        self.assertEqual(set(device['props']), set(phone.PROPS))

    def test_pull_reads_test_output_recordings_and_timing_receipts_and_refuses_traversal(self):
        self.device.data.update({'files/timing-receipts/1.jsonl': b'{}'})
        result = self.call('pull', 'files/lab', 'files/benchmark', 'files/timing-receipts')
        self.assertEqual(result['files'], 4)
        self.assertTrue((Path(result['path']) / 'files/benchmark/take1.wav').exists())
        single = self.call('pull', 'files/benchmark/take1.wav', '--to', str(self.root / 'one'))
        self.assertEqual(single['files'], 1)
        for bad in ('shared_prefs', 'no_backup/history', 'files/dictionary', 'files/lab/../../shared_prefs',
                    'files/../shared_prefs/p.xml', '/files/lab'):
            with self.assertRaises(phone.Refused):
                self.call('pull', bad)

    def test_pull_takes_only_what_was_asked_for_from_the_archive(self):
        self.device.tar = lambda *rels, of: tar_bytes({'files/lab/a': b'1', 'shared_prefs/settings.xml': b'<owner/>'})
        with self.assertRaises(phone.Refused):
            self.call('pull', 'files/lab', '--to', str(self.root / 'out'))
        self.assertFalse((self.root / 'out').exists())

    def test_push_sends_only_changed_files_and_refuses_symlinks_and_other_targets(self):
        source = self.root / 'src'
        (source / 'fixtures').mkdir(parents=True)
        (source / 'fixtures/continuous.wav').write_bytes(b'RIFF')
        (source / 'encoder-probe.json').write_text('{}')
        (source / 'encoder.onnx').write_bytes(b'onnx')
        result = self.call('push', str(source))
        self.assertEqual((result['outcome'], result['files'], result['sent'], result['removed']), ('pushed', 3, 2, 1))
        self.assertEqual(set(self.device.inventory('files/lab')), {'fixtures/continuous.wav', 'encoder-probe.json', 'encoder.onnx'})
        # Unchanged directory: nothing is sent. One edited file: only that file travels.
        self.assertEqual(self.call('push', str(source))['outcome'], 'unchanged')
        (source / 'encoder-probe.json').write_text('{"v":2}')
        again = self.call('push', str(source))
        self.assertEqual((again['outcome'], again['sent'], again['removed']), ('pushed', 1, 0))
        self.assertEqual(self.did('push')[-1], ('push', ('encoder-probe.json',), ()))
        with self.assertRaises(phone.Refused):  # the lab folder is the only target
            self.call('push', str(source), '../shared_prefs')
        with self.assertRaises(phone.Refused):
            self.call('push', str(source), 'dictionary')
        (source / 'leak').symlink_to('/etc/hostname')
        with self.assertRaises(phone.Refused):
            self.call('push', str(source))

    def test_run_records_output_logcat_and_lab_inventory(self):
        result = self.call('run', NS + '.NormalBaselineTest', 'provider=webgpu')
        self.assertEqual(result['outcome'], 'passed')
        self.assertIn('result.json', result['lab'])
        folder = Path(result['path'])
        self.assertTrue((folder / 'meta.json').exists() and (folder / 'logcat.txt').exists())
        self.assertEqual(self.did('instrument'), [('instrument', NS + '.NormalBaselineTest', (('provider', 'webgpu'),))])
        self.assertEqual(self.did('logcat')[0][:2], ('logcat', 10123))
        meta = json.loads((folder / 'meta.json').read_text())
        self.assertEqual(set(meta) >= {'verb', 'ts', 'app_sha256', 'test_sha256', 'lab_sha256'}, True)
        for bad in (['run', 'Foo;rm'], ['run', NS + '.NormalBaselineTest', 'noequals'], ['run', NS + '.NormalBaselineTest', '1bad=x']):
            with self.assertRaises(phone.Refused):
                self.call(*bad)

    def test_check_accepts_or_refuses_apks_without_touching_the_phone(self):
        app = self.apk('app.apk', APP, LAB, True)
        test = self.apk('test.apk', TEST, LAB, True, signer='other')
        with self.assertRaises(phone.Refused):
            self.call('check', app, test)
        test = self.apk('test.apk', TEST, LAB, True)
        result = self.call('check', app, test)
        self.assertEqual([r['package'] for r in result], [APP, TEST])
        self.assertEqual(self.device.calls, [])

    def test_install_refuses_network_permissions_foreign_packages_and_live_keyboard(self):
        app = self.apk('app.apk', APP, LAB, True)
        test = self.apk('test.apk', TEST, LAB, True, signer='other')
        with self.assertRaises(phone.Refused):
            self.call('install', app, test)
        test = self.apk('test.apk', TEST, LAB, True)
        self.device.ime_state['bound'] = True
        with self.assertRaises(phone.Refused):
            self.call('install', app, test)
        self.device.ime_state['bound'] = False
        result = self.call('install', app, test)
        self.assertEqual(result['outcome'], 'installed')
        self.assertEqual(result['app_sha256'], sha(b'app.apk'))
        self.assertEqual(self.did('install'), [('install', APP), ('install', TEST)])

    def test_stop_and_uninstall_gates(self):
        self.device.ime_state['input_shown'] = True
        with self.assertRaises(phone.Refused):
            self.call('stop')
        self.device.ime_state['input_shown'] = False
        self.assertEqual(self.call('stop')['outcome'], 'stopped')
        self.device.ime_state['bound'] = True
        self.refused('lab keyboard is in use', 'uninstall')
        self.device.ime_state['bound'] = False
        self.assertEqual(self.call('uninstall')['outcome'], 'uninstalled')
        self.assertFalse(self.device.packages[APP]['installed'])

    def test_uninstall_removes_the_lab_app_and_the_0_2_5_tests_never_release(self):
        self.with_release_installed()
        self.device.place(self.device.build(SWITCH_TEST, None, True, b'0.2.5 tests'))
        result = self.call('uninstall')
        self.assertEqual(self.did('uninstall'), [('uninstall', TEST), ('uninstall', APP), ('uninstall', SWITCH_TEST)])
        self.assertTrue(self.device.packages[RELEASE]['installed'])
        self.assertEqual(result, {'outcome': 'uninstalled', 'release_changed': []})

    def test_uninstall_leaves_the_0_2_5_tests_while_release_is_still_the_unmarked_build(self):
        self.device.place(self.device.build(RELEASE, None, True, b'0.2.5 app'))
        self.device.place(self.device.build(SWITCH_TEST, None, True, b'0.2.5 tests'))
        self.call('uninstall')
        self.assertEqual(self.did('uninstall'), [('uninstall', TEST), ('uninstall', APP)])
        self.assertTrue(self.device.packages[SWITCH_TEST]['installed'], 'the switch copy still needs it')

    def test_unknown_verbs_and_extra_arguments_are_refused(self):
        for argv in (['inspect'], ['status', 'x'], ['pull'], ['restore'], ['logs', 'x'], ['forget', 'a'],
                     ['snapshot', 'a'], ['restore', 'a']):
            with self.assertRaises(phone.Refused):
                self.call(*argv)
        self.assertIn('usage', self.call('help'))

    def test_help_teaches_the_owner_session_order_and_receipt_rollover(self):
        usage = self.call('help')['usage']
        for text in ('PhoneReadyTest', 'one batched run', '.prev.jsonl', 'named phone batch', 'device_classes.py'):
            self.assertIn(text, usage)


class MarkerTests(PhoneCase):
    """ADR-028: debuggable <=> test layout, non-debuggable <=> owner layout, test APK marker = app marker."""

    REFUSED = {  # case -> (package, app marker, app debuggable, test APK marker or ... for no test APK)
        'a debuggable build with the owner layout': (RELEASE, OWNER, True, ...),
        'a release build with the test layout': (APP, LAB, False, ...),
        'a 0.2.5-like build: debuggable, no marker': (RELEASE, None, True, None),
        'a release build without a marker': (RELEASE, None, False, ...),
        'a malformed marker': (APP, 'layout=test;commit=abc;hash=def', True, ...),
        'a test APK without a marker': (APP, LAB, True, None),
        'a test APK from another build': (APP, LAB, True, OTHER_LAB),
        'a test APK beside a release build': (RELEASE, OWNER, False, LAB),
        'a lab build under the release package (#98)': (RELEASE, LAB, True, LAB),
        'a release build under the lab app package': (APP, OWNER, False, ...),
    }

    def pair(self, case):
        package, app_marker, debuggable, test_marker = self.REFUSED[case]
        apks = [self.apk('app.apk', package, app_marker, debuggable)]
        return apks + ([] if test_marker is ... else [self.apk('test.apk', TEST, test_marker, True)])

    def test_check_refuses_each_disagreement_of_marker_and_build(self):
        for case in self.REFUSED:
            with self.subTest(case), self.assertRaises(phone.Refused):
                self.call('check', *self.pair(case))
        self.assertEqual(self.device.calls, [])

    def test_check_accepts_a_lab_build_with_its_tests_and_a_release_build_alone(self):
        lab = self.call('check', self.apk('app.apk', APP, LAB, True), self.apk('test.apk', TEST, LAB, True))
        self.assertEqual([(i['marker'], i['debuggable']) for i in lab], [(LAB, True), (LAB, True)])
        release = self.call('check', self.apk('release.apk', RELEASE, OWNER, False))
        self.assertEqual([(i['marker'], i['debuggable']) for i in release], [(OWNER, False)])

    def test_install_refuses_each_disagreement_and_installs_nothing(self):
        for case in self.REFUSED:
            with self.subTest(case), self.assertRaises(phone.Refused):
                self.call('install', *self.pair(case))
        self.assertEqual(self.did('install'), [])

    def test_run_needs_no_apk_pull_for_a_build_install_recorded(self):
        self.call('install', self.apk('app.apk', APP, LAB, True), self.apk('test.apk', TEST, LAB, True))
        self.device.calls.clear()
        self.assertEqual(self.call('run', NS + '.NormalBaselineTest')['outcome'], 'passed')
        self.assertEqual(self.did('pull_apk'), [])

    def test_an_installed_build_nobody_recorded_is_read_from_its_pulled_apk_once(self):
        self.assertEqual(self.call('run', NS + '.NormalBaselineTest')['outcome'], 'passed')
        self.assertEqual(self.did('pull_apk'), [('pull_apk', APP), ('pull_apk', TEST)])
        self.device.calls.clear()
        self.call('run', NS + '.NormalBaselineTest')
        self.assertEqual(self.did('pull_apk'), [])


class LabBuildGuardTests(PhoneCase):
    """`run`, `push`, `measure` only on an installed lab build whose markers agree."""

    def verbs(self):
        folder = self.root / 'push-source'
        folder.mkdir(exist_ok=True)
        (folder / 'new.json').write_text('{}')
        return {'run': ['run', NS + '.NormalBaselineTest'], 'push': ['push', str(folder)],
                'measure': ['measure', LAB_RUN_CLASS]}

    def assert_all_refused(self, expected):
        before = dict(self.device.data)
        for verb, argv in self.verbs().items():
            with self.subTest(verb):
                with self.assertRaises(phone.Refused) as caught:
                    self.call(*argv)
                self.assertIn(expected, str(caught.exception))
        self.assertEqual(self.did('instrument', 'push'), [])
        self.assertEqual(self.device.data, before)

    def test_refused_without_the_lab_app(self):
        self.without_lab_app()
        self.assert_all_refused('lab app is not installed')

    def test_refused_on_a_0_2_5_like_build(self):
        self.device.place(self.device.build(APP, None, True, b'0.2.5'))
        self.assert_all_refused('no build marker')

    def test_refused_on_the_release_build_without_trying_run_as(self):
        self.device.place(self.device.build(APP, OWNER, False, b'release'))
        self.assert_all_refused('release build')

    def test_refused_when_the_test_package_is_missing_or_from_another_build(self):
        self.device.place(self.device.build(TEST, OTHER_LAB, True, b'other tests'))
        self.assert_all_refused('another build')
        self.device.packages[TEST] = {'installed': False}
        self.assert_all_refused('test package is not installed')

    def test_run_and_measure_are_refused_while_the_keyboard_is_in_use(self):
        self.device.ime_state['bound'] = True
        for argv in (['run', NS + '.NormalBaselineTest'], ['measure', LAB_RUN_CLASS]):
            with self.subTest(argv[0]):
                self.refused('in use', *argv)
        self.assertEqual(self.did('instrument'), [])

    def test_run_refuses_classes_of_the_lab_run_package(self):
        for classes in (NS + '.labrun.PipelineRun', NS + '.LabTest,' + NS + '.labrun.deep.Trace#collect'):
            with self.subTest(classes):
                with self.assertRaises(phone.Refused) as caught:
                    self.call('run', classes)
                self.assertIn('Lab run', str(caught.exception))
        self.assertEqual(self.did('instrument'), [])
        self.assertEqual(self.call('run', NS + '.labrunner.NotALabRun')['outcome'], 'passed')

    def test_run_takes_no_fingerprint_and_any_lab_data_may_change(self):
        self.device.during_run = lambda: self.device.data.update({'files/dictionary/revision.json': b'[]',
                                                                  'no_backup/history/2.json': b'{}'})
        self.assertEqual(self.call('run', NS + '.NormalBaselineTest')['outcome'], 'passed')
        self.assertEqual(self.did('fingerprint', 'copy'), [])


class MeasureTests(PhoneCase):
    """`measure` runs Lab run classes only, on what `push` put in the lab folder; it reports completed
    or incomplete, never passed or failed."""

    def test_measure_refuses_classes_outside_the_lab_run_package(self):
        for classes in (NS + '.NormalBaselineTest', LAB_RUN_CLASS + ',' + NS + '.HistoryProcessTest#x',
                        NS + '.labrunner.NotALabRun', NS + '.labrun', 'Foo;rm'):
            with self.subTest(classes):
                self.refused('', 'measure', classes)
        self.refused('only Lab run classes', 'measure', NS + '.NormalBaselineTest')
        self.assertEqual(self.did('instrument'), [])

    def test_measure_runs_the_classes_with_their_extras_and_copies_nothing(self):
        result = self.call('measure', LAB_RUN_CLASS + '#savedTakesThroughTheServicePipeline', 'label=a')
        self.assertEqual(result['outcome'], 'completed')
        self.assertEqual(self.did('instrument'),
                         [('instrument', LAB_RUN_CLASS + '#savedTakesThroughTheServicePipeline', (('label', 'a'),))])
        self.assertEqual(self.did('fingerprint', 'copy', 'tar', 'push'), [])

    def test_inputs_are_refused_not_passed_on(self):
        self.refused('inputs= is gone', 'measure', LAB_RUN_CLASS, 'inputs=files/benchmark')
        self.assertEqual(self.did('instrument'), [])

    def test_the_outcome_is_completed_only_when_every_lab_run_said_so(self):
        done, short = 'INSTRUMENTATION_STATUS: stream=LAB RUN completed: a\n', 'INSTRUMENTATION_STATUS: stream=LAB RUN incomplete: b\n'
        for report, expected in ((done, 'completed'), (done + done, 'completed'), (done + short, 'incomplete'),
                                 (short, 'incomplete'), ('', 'incomplete')):
            with self.subTest(report):
                self.device.report = report
                result = self.call('measure', LAB_RUN_CLASS)
                self.assertEqual(result['outcome'], expected)
                self.assertNotIn(result['outcome'], ('passed', 'failed'))
        self.device.report = done
        self.device.instrument = lambda *a: done + 'INSTRUMENTATION_RESULT: shortMsg=Process crashed.\nINSTRUMENTATION_CODE: 0\n'
        self.assertEqual(self.call('measure', LAB_RUN_CLASS)['outcome'], 'incomplete')

    def test_the_result_names_the_output_files_never_model_files(self):
        def lab_run():
            self.device.data.update({'files/lab/pipeline.json': b'{}', 'files/lab/issue56/probe.json': b'{}',
                                     'files/lab/variant.json': b'{"changed":1}', 'files/lab/encoder.onnx': b'onnx2'})
        self.device.during_run = lab_run
        result = self.call('measure', LAB_RUN_CLASS)
        self.assertEqual(result['outputs'], ['issue56/probe.json', 'pipeline.json', 'result.json', 'variant.json'])
        folder = Path(result['path'])
        self.assertTrue((folder / 'instrumentation.txt').exists() and (folder / 'logcat.txt').exists())
        self.assertEqual(json.loads((folder / 'meta.json').read_text())['verb'], 'measure')


class CallerTests(PhoneCase):
    """State and lock are the corpus/lab of the main checkout the shim names (--home); the caller's
    directory and git play no part."""

    def setUp(self):
        super().setUp()
        self.main = self.root / 'main'
        (self.main / '.git').mkdir(parents=True)

    def main_call(self, *argv):
        output = io.StringIO()
        with patch.object(phone.subprocess, 'run', side_effect=AssertionError('no git, no subprocess')), \
             patch.object(phone, 'Device', return_value=self.device), \
             patch.object(phone.os, 'umask'), redirect_stdout(output):
            status = phone.main(list(argv))
        return status, output.getvalue()

    def test_device_verbs_keep_state_in_the_named_main_checkout(self):
        status, output = self.main_call('--home', str(self.main), 'app')
        self.assertEqual(status, 0)
        self.assertEqual(json.loads((self.main / 'corpus/lab/last.json').read_text()), json.loads(output))

    def test_device_verbs_refuse_without_a_main_checkout_named(self):
        worktree = self.root / 'worktree'
        worktree.mkdir()
        (worktree / '.git').write_text('gitdir: ' + str(self.main / '.git/worktrees/w'))
        for home in ([], ['--home', 'main'], ['--home', str(worktree)], ['--home', str(self.root / 'none')]):
            with self.subTest(home=home), self.assertRaises(phone.Refused):
                self.main_call(*home, 'app')
        self.assertEqual(self.device.calls, [])
        self.assertFalse((self.main / 'corpus').exists())

    def test_a_second_caller_is_refused_while_one_holds_the_lock(self):
        held = phone.Phone(self.device, self.main / 'corpus/lab').lock()
        self.addCleanup(held.close)
        for verb in (['app'], ['run', NS + '.NormalBaselineTest'], ['uninstall']):
            with self.subTest(verb[0]), self.assertRaises(phone.Refused) as caught:
                self.main_call('--home', str(self.main), *verb)
            self.assertIn('another phone command is running', str(caught.exception))
        self.assertEqual(self.did('instrument', 'uninstall'), [])
        held.close()
        self.assertEqual(self.main_call('--home', str(self.main), 'app')[0], 0)

    def test_check_and_help_need_no_lock_or_state(self):
        app, test = self.apk('app.apk', APP, LAB, True), self.apk('test.apk', TEST, LAB, True)
        for home in ([], ['--home', str(self.main)]):
            with self.subTest(home=home):
                status, output = self.main_call(*home, 'check', app, test)
                self.assertEqual(status, 0)
                self.assertEqual([i['package'] for i in json.loads(output)], [APP, TEST])
                self.assertIn('status', self.main_call(*home, 'help')[1])
        self.assertEqual(self.device.calls, [])
        self.assertFalse((self.main / 'corpus').exists())

    def test_a_second_home_or_a_caller_flag_is_no_verb(self):
        for argv in (['--home', str(self.main), '--home', str(self.main), 'app'], ['--home', str(self.main), '--caller', 'x']):
            with self.subTest(argv), self.assertRaises(phone.Refused):
                self.main_call(*argv)


class ScriptedRunner:
    """Scripted subprocess.run replacement; records every argv."""

    def __init__(self, answers):
        self.answers = answers
        self.argv = []

    def __call__(self, argv, capture_output, text, timeout, input=None):
        self.argv.append(argv)
        for key, (code, out, err) in self.answers.items():
            if key in ' '.join(argv):
                return subprocess.CompletedProcess(argv, code, out if text else out.encode(), err if text else err.encode())
        return subprocess.CompletedProcess(argv, 0, '' if text else b'', '' if text else b'')


class AdapterArgvTests(unittest.TestCase):
    def device(self, answers=None):
        runner = ScriptedRunner({'devices': (0, 'List of devices attached\nSERIAL123\tdevice\n', ''),
                                 'ro.product.model': (0, phone.MODEL + '\n', ''), **(answers or {})})
        return phone.Device('/sdk', runner=runner), runner

    def test_every_argv_starts_with_adb_and_serial_and_scopes_to_our_package(self):
        device, runner = self.device({'pm path': (0, 'package:/data/app/x/base.apk\n', ''),
                                      'sha256sum': (0, 'c' * 64 + '  /data/app/x/base.apk\n', ''),
                                      'dumpsys package': (0, 'versionCode=12 versionName=1\n userId=10123\n', ''),
                                      'pidof': (1, '', '')})
        device.package(APP)
        device.logcat(10123, since='10-21 10:00:00.000')
        device.private('ls files/lab')
        for argv in runner.argv[2:]:
            self.assertEqual(argv[:3], ['/sdk/platform-tools/adb', '-s', 'SERIAL123'])
        joined = ['\x00'.join(a) for a in runner.argv]
        self.assertTrue(any('--uid\x0010123' in j for j in joined))
        self.assertTrue(all('run-as %s sh -c' % APP in j for j in joined if 'run-as' in j))
        self.assertFalse(any('logcat' in j and '--uid' not in j and 'crash' not in j for j in joined))

    def test_replace_uploads_before_deleting_and_never_streams_stdin(self):
        device, runner = self.device()
        device.replace(b'archive', phone.OWNER_SKIP)
        joined = [' '.join(a) for a in runner.argv]
        upload = next(i for i, j in enumerate(joined) if ' push ' in j and '/data/local/tmp/' in j)
        wipe = next(i for i, j in enumerate(joined) if 'rm -f -- {} +' in j)
        self.assertLess(upload, wipe)
        self.assertIn('tar xf /data/local/tmp/', joined[wipe])
        self.assertNotIn('rm -rf', joined[wipe])
        self.assertFalse(any('exec-out' in j for j in joined))
        self.assertTrue(any(j.endswith('rm -f -- ' + joined[upload].split()[-1]) for j in joined[wipe:]))

    def test_failed_upload_leaves_app_data_untouched(self):
        device, runner = self.device({' push ': (1, '', 'no space left')})
        with self.assertRaises(phone.Failed):
            device.replace(b'archive', phone.OWNER_SKIP)
        self.assertFalse(any('rm -f -- {} +' in ' '.join(a) for a in runner.argv))

    def test_missing_package_is_an_answer_not_a_failure(self):
        device, runner = self.device({'pm path': (1, '', '')})
        self.assertEqual(device.package(phone.FUTO[1]), {'installed': False})
        self.assertEqual(device.package(TEST), {'installed': False})

    def test_uid_is_parsed_from_grapheneos_dump_or_refused_never_guessed_as_root(self):
        answers = {'pm path': (0, 'package:/data/app/x/base.apk\n', ''), 'sha256sum': (0, 'c' * 64 + '  x\n', ''),
                   'pidof': (1, '', '')}
        for dump, expected in (('  versionCode=12\n    appId=10123\n', 10123), ('  versionCode=12\n    userId=10456\n', 10456)):
            device, _ = self.device({**answers, 'dumpsys package': (0, dump, '')})
            self.assertEqual(device.package(APP)['uid'], expected)
        device, _ = self.device({**answers, 'dumpsys package': (0, '  versionCode=12\n  versionName=null\n', '')})
        with self.assertRaises(phone.Failed):
            device.package(APP)
        with self.assertRaises(phone.Refused):
            device.logcat(0)
        with self.assertRaises(phone.Refused):
            device.logcat(None)

    def test_failures_name_the_command_redact_serial_and_adb_path_and_distinguish_from_refusals(self):
        device, runner = self.device({'am force-stop': (1, '', 'error: /sdk/platform-tools/adb SERIAL123 boom')})
        with self.assertRaises(phone.Failed) as caught:
            device.stop()
        self.assertNotIn('SERIAL123', str(caught.exception))
        self.assertNotIn('/sdk/platform-tools', str(caught.exception))
        self.assertTrue(str(caught.exception).startswith('shell am force-stop'))
        with self.assertRaises(phone.Refused):
            device.package('com.other.app')

    def test_two_devices_or_wrong_model_are_rejected_before_any_command(self):
        device, runner = self.device({'devices': (0, 'List of devices attached\nA\tdevice\nB\tdevice\n', '')})
        with self.assertRaises(phone.Failed):
            device.shell('id')
        device, runner = self.device({'ro.product.model': (0, 'Pixel 7\n', '')})
        with self.assertRaises(phone.Refused):
            device.shell('id')
        self.assertEqual(len(runner.argv), 2)

    def test_unauthorized_phone_failure_names_its_state_not_its_serial(self):
        device, runner = self.device({'devices': (0, 'List of devices attached\nSECRET9\tunauthorized\n', '')})
        with self.assertRaises(phone.Failed) as caught:
            device.shell('id')
        message = str(caught.exception)
        self.assertIn('unauthorized', message)
        self.assertIn('USB debugging', message)
        self.assertNotIn('SECRET9', message)

    def test_fingerprint_runs_as_our_app_and_sees_every_change_outside_the_excluded_locations(self):
        with tempfile.TemporaryDirectory() as folder:
            data = Path(folder)

            def write(rel, content):
                (data / rel).parent.mkdir(parents=True, exist_ok=True)
                (data / rel).write_bytes(content)

            def on_phone(argv, capture_output, text, timeout, input=None):
                """adb shell `run-as APP sh -c CODE` runs CODE here, in a folder standing in for the app's data."""
                words = shlex.split(argv[-1])
                if argv[3:4] == ['shell'] and words[:4] == ['run-as', APP, 'sh', '-c']:
                    return subprocess.run(['sh', '-c', words[4]], cwd=data, capture_output=True, text=True)
                answer = phone.MODEL if 'ro.product.model' in argv[-1] else 'List of devices attached\nS\tdevice\n'
                return subprocess.CompletedProcess(argv, 0, answer, '')

            device = phone.Device('/sdk', runner=on_phone)
            for rel in ('no_backup/history/1.json', 'files/dictionary/revision 1.json', 'shared_prefs/settings.xml',
                        'files/benchmark/take.wav', 'databases/d.db', 'files/lab/x', 'cache/x', 'code_cache/x',
                        'files/profileInstalled'):
                write(rel, b'before')
            first = device.fingerprint()
            self.assertEqual(first['files'], 5)
            self.assertRegex(first['digest'], '^[0-9a-f]{64}$')
            for rel in ('files/lab/x', 'cache/x', 'code_cache/x', 'files/profileInstalled', 'files/lab/model/new.onnx'):
                write(rel, b'after')
            self.assertEqual(device.fingerprint(), first, 'lab folder and system files do not count')
            seen = {first['digest']}
            changes = (lambda: write('no_backup/history/1.json', b'BEFORE'),  # same size, other content
                       lambda: write('shared_prefs/settings.xml.new', b''),  # a new empty file
                       lambda: (data / 'files/benchmark/take.wav').rename(data / 'files/benchmark/take2.wav'),
                       lambda: (data / 'databases/d.db').unlink(),
                       lambda: (data / 'files/link').symlink_to('/etc/hostname'),
                       lambda: write('files/labs/x', b'not the lab folder'))
            for change in changes:
                change()
                digest = device.fingerprint()['digest']
                self.assertNotIn(digest, seen)
                seen.add(digest)

    def test_apk_facts_include_the_build_marker_and_debuggability(self):
        marker = 'layout=test;commit=' + '3' * 40 + ';hash=' + '4' * 64
        entry = ('          E: meta-data (line=108)\n'
                 '            A: http://schemas.android.com/apk/res/android:name(0x01010003)="ch.og.dictation.build" (Raw: "ch.og.dictation.build")\n'
                 '            A: http://schemas.android.com/apk/res/android:value(0x01010024)="%s" (Raw: "%s")\n' % (marker, marker))
        other = ('              E: meta-data (line=155)\n'
                 '                A: http://schemas.android.com/apk/res/android:name(0x01010003)="androidx.startup.X" (Raw: "x")\n'
                 '                A: http://schemas.android.com/apk/res/android:value(0x01010024)="androidx.startup" (Raw: "androidx.startup")\n')
        with tempfile.TemporaryDirectory() as folder:
            sdk, apk = Path(folder) / 'sdk', Path(folder) / 'app.apk'
            (sdk / 'build-tools/37.0.0').mkdir(parents=True)
            for tool in ('aapt2', 'apksigner'):
                (sdk / 'build-tools/37.0.0' / tool).touch()
            apk.write_bytes(b'apk')
            for case, badging_tail, tree, expected in (
                    ('lab build', 'application-debuggable\n', other + entry, (True, marker)),
                    ('release', '', entry.replace('layout=test', 'layout=owner'), (False, marker.replace('test', 'owner'))),
                    ('0.2.5-like', 'application-debuggable\n', other, (True, None))):
                def tools(argv, **_):
                    out = {'badging': "package: name='ch.og.dictation' versionCode='12' versionName='0.2.5'\n" + badging_tail,
                           'xmltree': 'N: android=http://schemas.android.com/apk/res/android\n  E: manifest\n' + tree,
                           'verify': 'Signer #1 certificate SHA-256 digest: ' + 'a' * 64 + '\n'}[argv[2] if 'dump' in argv else argv[1]]
                    return subprocess.CompletedProcess(argv, 0, out, '')
                with self.subTest(case), patch.object(phone.subprocess, 'run', side_effect=tools):
                    info = phone.Device(sdk).apk_info(apk)
                    self.assertEqual((info['debuggable'], info['marker']), expected)

    def test_the_crash_buffer_keeps_the_lab_apps_lines_only(self):
        crash = ('E AndroidRuntime: Process: %s, PID: 1\nE AndroidRuntime: at owner.Frame\n' % RELEASE
                 + '\n'.join('x%d' % i for i in range(50))
                 + '\nE AndroidRuntime: Process: %s:checkpoint_producer, PID: 2\nE AndroidRuntime: at lab.Frame\n' % APP)
        device, _ = self.device({'logcat': (0, crash, '')})
        text = device.logcat(None, crash=True)
        self.assertIn('lab.Frame', text)
        self.assertNotIn('owner.Frame', text)

    def test_tar_validation_rejects_traversal_links_and_foreign_dirs(self):
        phone.validate_tar(tar_bytes({'files/lab/a': b'1', 'shared_prefs/x.xml': b'2'}))
        for bad in ({'files/../x': b''}, {'/files/x': b''}, {'cache/x': b''}, {'./files/lab/model.onnx': b''},
                    {'files/lab/x': b''}):
            with self.assertRaises(phone.Refused):
                phone.validate_tar(tar_bytes(bad), outside=phone.OWNER_SKIP)
        with self.assertRaises(phone.Refused):
            phone.validate_tar(tar_bytes({'shared_prefs/x.xml': b''}), inside=['files/lab'])
        buffer = io.BytesIO()
        with tarfile.open(fileobj=buffer, mode='w') as archive:
            link = tarfile.TarInfo('files/lab/link')
            link.type = tarfile.SYMTYPE
            link.linkname = '/etc/passwd'
            archive.addfile(link)
        with self.assertRaises(phone.Refused):
            phone.validate_tar(buffer.getvalue())

    def test_install_never_downgrades_and_package_facts_hold_the_first_install_time(self):
        dump = '  versionCode=12\n    userId=10123\n    firstInstallTime=2026-10-01 08:00:00\n    lastUpdateTime=2026-11-25 09:00:00\n'
        device, runner = self.device({'pm path': (0, 'package:/data/app/x/base.apk\n', ''), 'pidof': (1, '', ''),
                                      'sha256sum': (0, 'c' * 64 + '  x\n', ''), 'dumpsys package': (0, dump, '')})
        self.assertEqual(device.package(APP)['first_install'], '2026-10-01 08:00:00')
        device.install('/host/app.apk')
        self.assertEqual(runner.argv[-1][3:], ['install', '-r', '/host/app.apk'])

    def test_release_facts_come_from_the_package_manager_and_release_is_never_uninstalled_or_run_as(self):
        dump = ('  versionCode=12\n    userId=10100\n    firstInstallTime=2026-10-01 08:00:00\n'
                '    lastUpdateTime=2026-11-25 09:00:00\n')
        device, runner = self.device({'pm path': (0, 'package:/data/app/r/base.apk\n', ''), 'pidof': (1, '', ''),
                                      'sha256sum': (0, 'c' * 64 + '  x\n', ''), 'dumpsys package': (0, dump, '')})
        facts = device.package(RELEASE)
        self.assertEqual((facts['sha256'], facts['versionCode'], facts['last_update']), ('c' * 64, 12, '2026-11-25 09:00:00'))
        self.assertNotIn('signing', facts)
        self.assertTrue(any(argv[3:] == ['shell', 'dumpsys package ' + RELEASE] for argv in runner.argv))
        for call in (lambda: device.uninstall(RELEASE), lambda: device.uninstall('com.other.app'), lambda: device.ime('com.other.app')):
            with self.assertRaises(phone.Refused):
                call()
        device.private('ls files/lab')
        device.stop()
        joined = [' '.join(argv) for argv in runner.argv]
        self.assertIn('am force-stop ' + APP, joined[-1])
        self.assertFalse(any('run-as %s ' % RELEASE in j for j in joined), 'the lab app is the default')


class LocalPhone:
    """Stands in for adb: what the adapter runs with `run-as` runs here, with the laptop's sh, find, tar and
    sha256sum, in a folder that stands in for the app's data. `adb push` lands in a second folder."""

    def __init__(self, data, tmp):
        self.data, self.tmp, self.argv = Path(data), Path(tmp), []

    def local(self, text):
        return text.replace('/data/local/tmp/', str(self.tmp) + '/')

    def __call__(self, argv, capture_output, text, timeout, input=None):
        self.argv.append(argv)
        command = argv[3:] if argv[1:2] == ['-s'] else argv[1:]
        if command[0] in ('shell', 'exec-out') and command[1].startswith('run-as '):
            words = shlex.split(command[1])
            assert words[:4] == ['run-as', APP, 'sh', '-c'] and len(words) == 5, words
            return subprocess.run(['sh', '-c', self.local(words[4])], cwd=self.data, capture_output=True, text=text)
        if command[0] == 'push':
            Path(self.local(command[2])).write_bytes(Path(command[1]).read_bytes())
        elif command[0] == 'shell' and command[1].startswith('rm -f -- /data/local/tmp/'):
            Path(self.local(command[1].split()[-1])).unlink()
        answer = phone.MODEL if 'ro.product.model' in command[-1] else 'List of devices attached\nS\tdevice\n'
        return subprocess.CompletedProcess(argv, 0, answer if text else answer.encode(), '' if text else b'')


class AdapterOnLocalFilesTests(unittest.TestCase):
    """Copy, fingerprint and replace as the phone's shell would run them (GNU tools here, toybox there)."""

    OWNER = ('no_backup/history/1.json', 'files/dictionary/revision 1.json', 'shared_prefs/settings.xml',
             'files/benchmark/take.wav', 'databases/d.db', 'app_other/unknown-store')
    LEFT_ALONE = ('files/lab/model/encoder.onnx', 'files/lab/receipts.jsonl', 'cache/x', 'code_cache/x',
                  'files/profileInstalled')

    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.data, uploads = Path(tmp.name) / 'data', Path(tmp.name) / 'uploads'
        self.data.mkdir()
        uploads.mkdir()
        self.uploads = uploads
        self.runner = LocalPhone(self.data, uploads)
        self.device = phone.Device('/sdk', runner=self.runner)
        for rel in self.OWNER + self.LEFT_ALONE:
            self.write(rel, rel.encode())

    def write(self, rel, content):
        (self.data / rel).parent.mkdir(parents=True, exist_ok=True)
        (self.data / rel).write_bytes(content)

    def files(self):
        return {path.relative_to(self.data).as_posix(): path.read_bytes() for path in self.data.rglob('*') if path.is_file()}

    def test_the_host_notices_a_copy_that_lacks_a_file_or_holds_other_bytes(self):
        complete = self.device.fingerprint()
        for held in (self.OWNER[1:], self.OWNER + ('files/new',)):
            self.assertNotEqual(phone.tar_fingerprint(tar_bytes({rel: rel.encode() for rel in held})), complete)
        other = {rel: rel.encode() for rel in self.OWNER}
        other['databases/d.db'] = b'databases/D.DB'
        self.assertNotEqual(phone.tar_fingerprint(tar_bytes(other)), complete)

    def test_a_switch_copy_leaves_out_only_the_named_model_files_and_system_files(self):
        skip = ('files/lab/model/encoder.onnx',) + phone.SYSTEM_FILES
        payload = self.device.copy(skip)
        held = set(phone.validate_tar(payload, outside=skip))
        self.assertEqual(held, set(self.OWNER + self.LEFT_ALONE[1:2]))
        self.assertEqual(phone.tar_fingerprint(payload, leave_out=phone.EXCLUDED), self.device.fingerprint())

    def test_a_copy_must_leave_the_cache_out(self):
        with self.assertRaises(phone.Refused):
            self.device.copy(('files/lab',))
        self.assertEqual(self.runner.argv, [])

    def test_a_switch_restore_keeps_only_the_named_model_files_and_system_files(self):
        skip = ('files/lab/model/encoder.onnx',) + phone.SYSTEM_FILES
        payload = self.device.copy(skip)
        self.write('files/lab/model/encoder.onnx', b'the model as it is now')
        self.write('files/dictionary/later.json', b'written by the lab build')
        self.write('files/lab/later.jsonl', b'written by the lab build')
        self.device.replace(payload, skip)
        files = self.files()
        self.assertEqual(files.pop('files/lab/model/encoder.onnx'), b'the model as it is now')
        self.assertEqual(files, {rel: rel.encode() for rel in self.OWNER + self.LEFT_ALONE[1:]})


def archive_names(path):
    return set(phone.validate_tar(Path(path).read_bytes()))


class LabInstallTests(PhoneCase):
    """Installing the lab app beside release: release is never touched (#98)."""

    def setUp(self):
        super().setUp()
        self.release = self.with_release_installed()
        self.without_lab_app()
        self.release_before = (dict(self.device.release_data), dict(self.device.packages[RELEASE]))

    def assert_release_untouched(self):
        self.assertEqual((self.device.release_data, self.device.packages[RELEASE]), self.release_before)
        self.assertNotIn(('run_as', RELEASE), self.device.calls)

    def test_the_lab_app_installs_beside_release_and_copies_nothing(self):
        lab = dict(self.device.data)
        result = self.call('install', *self.lab_apks())
        self.assertEqual(self.did('pull_apk', 'install', 'copy', 'fingerprint'), [('install', APP), ('install', TEST)])
        self.assertEqual((result['outcome'], result['release_changed']), ('installed', []))
        self.assertEqual(self.device.data, {**lab, 'code_cache/startup': sha(b'lab-app.apk').encode(),
                                            'files/profileInstalled': b'1'}, 'lab data is left as it is')
        self.assertFalse((self.root / 'lab/visits').exists())
        self.assertEqual(self.call('run', NS + '.NormalBaselineTest')['outcome'], 'passed')
        self.assert_release_untouched()

    def test_a_later_lab_build_goes_over_the_first(self):
        self.call('install', *self.lab_apks())
        self.device.data['no_backup/history/1.json'] = b'{"text":"written by a test"}'
        self.call('install', *self.lab_apks('lab2', OTHER_LAB))
        self.assertEqual(self.device.packages[APP]['sha256'], sha(b'lab2-app.apk'))
        self.assertEqual(self.call('run', NS + '.NormalBaselineTest')['outcome'], 'passed')
        self.assert_release_untouched()

    def test_a_failed_install_leaves_release_alone(self):
        self.device.fail = {'install'}
        with self.assertRaises(phone.Failed):
            self.call('install', *self.lab_apks())
        self.assert_release_untouched()

    def test_a_lab_build_goes_on_only_with_its_test_apk_and_never_as_a_promotion(self):
        app, test = self.lab_apks()
        self.refused('test APK', 'install', app)
        self.refused('--promote', 'install', app, test, '--promote')
        self.assertEqual(self.did('install'), [])

    def test_a_lab_build_on_a_phone_without_release(self):
        self.device.packages[RELEASE] = {'installed': False}
        self.assertEqual(self.call('install', *self.lab_apks())['release_changed'], [])
        self.refused('--promote', 'install', self.release_apk())

    def test_refused_over_a_release_build_in_the_lab_app_package(self):
        self.device.place(self.device.build(APP, OWNER, False, b'a release build as lab app'))
        self.refused('owner layout', 'install', *self.lab_apks())
        self.assertEqual(self.did('install'), [])


class ReleaseUpdateTests(PhoneCase):
    """Release goes on as an update of release; the lab app and its Visit play no part (#98)."""

    FACTS = {'uid': 10100, 'first_install': '2026-10-01 08:00:00', 'microphone_granted': True,
             'keyboard_enabled': True, 'keyboard_selected': False}

    def test_another_release_is_a_promotion_and_reports_package_facts(self):
        self.with_release_installed()
        other = self.release_apk('release-2')
        self.refused('--promote', 'install', other)
        self.assertEqual(self.did('install'), [])
        result = self.call('install', other, '--promote')
        self.assertEqual(self.did('install', 'uninstall'), [('install', RELEASE)])
        self.assertEqual((result['promoted'], result['release_sha256'], result['switch']), (True, sha(b'release-2.apk'), None))
        self.assertEqual((result['facts'], result['facts_before'], result['facts_changed']), (self.FACTS, self.FACTS, []))
        self.assertNotIn(('run_as', RELEASE), self.device.calls)

    def test_package_facts_that_changed_are_named(self):
        self.with_release_installed()
        install = self.device.install

        def update(path):
            install(path)
            self.device.packages[RELEASE].update(granted=[], uid=10999)
            self.device.release_ime['enabled'] = False
        self.device.install = update
        self.assertEqual(self.call('install', self.release_apk('release-2'), '--promote')['facts_changed'],
                         ['keyboard_enabled', 'microphone_granted', 'uid'])

    def test_a_release_update_leaves_the_lab_app_alone(self):
        self.lab_beside_release()
        lab = dict(self.device.data)
        self.call('install', self.release_apk('release-2'), '--promote')
        self.assertEqual(self.device.data, lab)
        self.assertEqual(self.call('run', NS + '.NormalBaselineTest')['outcome'], 'passed')

    def test_a_release_update_is_refused_while_the_dictation_keyboard_is_in_use(self):
        self.with_release_installed()
        self.device.ime_state['bound'] = True  # the lab keyboard does not count
        self.device.release_ime['bound'] = True
        self.refused('dictation keyboard is in use', 'install', self.release_apk('release-2'), '--promote')
        self.device.release_ime['bound'] = False
        self.assertEqual(self.call('install', self.release_apk('release-2'), '--promote')['outcome'], 'installed')


class ReleaseInstalledTests(PhoneCase):
    """After the switch no verb reads or writes release data: the fake fails `run-as` on release, as the
    phone does, and records every `run-as`."""

    def setUp(self):
        super().setUp()
        self.release = self.with_release_installed()

    def test_a_release_mark_that_cannot_be_read_fails_instead_of_passing(self):
        for field in ('sha256', 'versionCode', 'last_update'):
            with self.subTest(field):
                self.device.packages[RELEASE][field] = None
                self.without_lab_app()
                with self.assertRaises(phone.Failed) as caught:
                    self.call('install', *self.lab_apks())
                self.assertIn(field, str(caught.exception))
                self.assertEqual(self.did('install'), [])
                self.device.packages[RELEASE][field] = {'sha256': sha(b'release.apk'), 'versionCode': 12,
                                                        'last_update': '2026-11-20 08:00:00'}[field]

    def test_app_reports_release_facts_from_the_package_manager_and_logs_are_the_lab_apps(self):
        info = self.call('app')
        self.assertEqual((info['release']['layout'], info['app']['layout'], info['test']['installed']), ('owner', 'test', True))
        self.assertEqual({key: info['release'][key] for key in ('sha256', 'versionCode', 'last_update')},
                         {'sha256': sha(b'release.apk'), 'versionCode': 12, 'last_update': '2026-11-20 08:00:00'})
        self.assertEqual(self.call('logs')['logs'], 'log for uid 10123')
        self.assertNotIn(('run_as', RELEASE), self.device.calls)

    def test_the_same_release_installs_again_and_another_one_only_as_a_promotion(self):
        result = self.call('install', self.release)
        self.assertEqual((result['outcome'], result['promoted']), ('installed', False))
        other = self.release_apk('release-2')
        self.refused('--promote', 'install', other)
        self.assertEqual(self.call('install', other, '--promote')['promoted'], True)

    def test_a_whole_visit_and_every_other_verb_leave_release_alone(self):
        owner, package = dict(self.device.release_data), dict(self.device.packages[RELEASE])
        self.without_lab_app()
        self.device.data = {}
        source = self.root / 'model-and-fixture'
        shutil.copytree(self.model, source)
        (source / 'fixture.wav').write_bytes(b'RIFF')
        self.call('install', *self.lab_apks())
        for argv in (['push', str(source), '--model'], ['run', NS + '.NormalBaselineTest'], ['pull', 'files/lab'],
                     ['measure', LAB_RUN_CLASS], ['stop'], ['app'], ['logs'], ['crash']):
            with self.subTest(argv[0]):
                self.call(*argv)
        for argv in (['pull', '--switch', 'files/benchmark'], ['snapshot', '--switch', 'now'], ['restore', '--switch', 'mid']):
            with self.subTest(argv[0]), self.assertRaises(phone.Refused):
                self.call(*argv)
        self.assertEqual(self.call('uninstall')['release_changed'], [])
        self.assertNotIn(('run_as', RELEASE), self.device.calls)
        self.assertEqual((self.device.release_data, self.device.packages[RELEASE]), (owner, package))
        self.assertNotIn(RELEASE, [call[1] for call in self.did('install', 'uninstall', 'pull_apk')])


class ModelSafePushTests(PhoneCase):
    """`push` writes the lab folder only and leaves speech-model files alone without --model."""

    def setUp(self):
        super().setUp()
        self.lab_beside_release()
        self.device.data['files/lab/receipts.jsonl'] = b'{"run":1}'
        self.source = self.root / 'src'
        self.source.mkdir()
        (self.source / 'variant.json').write_text('{"v":2}')
        (self.source / 'fixture.wav').write_bytes(b'RIFF')

    def push(self, *flags):
        return self.call('push', str(self.source), *flags)

    def test_a_model_file_the_directory_lacks_is_kept_and_test_output_is_removed(self):
        result = self.push()
        self.assertEqual((result['sent'], result['removed'], result['model_files_kept']), (2, 1, 1))
        self.assertEqual(self.device.inventory('files/lab'),
                         {'variant.json': sha(b'{"v":2}'), 'fixture.wav': sha(b'RIFF'), 'encoder.onnx': sha(b'onnx')})
        self.assertEqual(self.push()['outcome'], 'unchanged')

    def test_overwriting_a_model_file_is_refused_and_nothing_is_sent(self):
        (self.source / 'encoder.onnx').write_bytes(b'another model')
        with self.assertRaises(phone.Refused) as caught:
            self.push()
        self.assertIn('encoder.onnx', str(caught.exception))
        self.assertIn('--model', str(caught.exception))
        self.assertEqual(self.did('push'), [])
        self.assertEqual(self.device.data['files/lab/receipts.jsonl'], b'{"run":1}')

    def test_the_model_flag_overwrites_and_deletes_and_the_kept_copy_recovers(self):
        (self.source / 'encoder.onnx').write_bytes(b'another model')
        self.push('--model')
        self.assertEqual(self.device.data['files/lab/encoder.onnx'], b'another model')
        (self.source / 'encoder.onnx').unlink()
        self.assertEqual(self.push('--model')['model_files_kept'], 0)
        self.assertNotIn('files/lab/encoder.onnx', self.device.data)
        (self.source / 'encoder.onnx').write_bytes(b'onnx')
        self.refused('--model', 'push', str(self.source))  # putting it back is a model write too
        self.push('--model')
        self.assertEqual(self.device.data['files/lab/encoder.onnx'], b'onnx')

    def test_the_lab_apps_first_model_copy_needs_the_model_flag_and_is_kept_afterwards(self):
        self.device.data.pop('files/lab/encoder.onnx')
        (self.source / 'encoder.onnx').write_bytes(b'onnx')
        self.refused('--model', 'push', str(self.source))
        self.assertEqual(self.push('--model')['sent'], 3)
        (self.source / 'encoder.onnx').unlink()
        self.assertEqual(self.push()['model_files_kept'], 1)
        self.assertEqual(self.device.data['files/lab/encoder.onnx'], b'onnx')

    def test_a_file_outside_the_kept_model_copy_is_not_protected(self):
        (self.source / 'variant.json').write_text('{"v":3}')
        self.assertEqual(self.push()['sent'], 2)
        (self.source / 'variant.json').unlink()
        self.assertEqual(self.push()['removed'], 1)

    def test_push_has_no_target_but_the_lab_folder(self):
        for argv in (['push', str(self.source), 'dictionary'], ['push', str(self.source), '../shared_prefs'], ['push', '--model']):
            with self.assertRaises(phone.Refused):
                self.call(*argv)
        self.assertEqual(self.did('push'), [])


class SwitchTests(PhoneCase):
    """The one-time switch of release from the unmarked debuggable build (0.2.5) to the marked release.
    The lab app is another package and plays no part in it."""

    DATA = {'files/lab/encoder.onnx': b'onnx', 'files/lab/startup.jsonl': b'{"old":"trace"}',
            'files/benchmark/take1.wav': b'RIFF', 'files/dictionary/revision.json': b'["Oriol"]',
            'no_backup/history/1.json': b'{"text":"owner"}', 'shared_prefs/history_notices.xml': b'<n/>',
            'shared_prefs/settings.xml': b'<s/>', 'cache/x': b'x'}

    def setUp(self):
        super().setUp()
        self.device.place(self.device.build(RELEASE, None, True, b'0.2.5 app'))
        self.device.place(self.device.build(SWITCH_TEST, None, True, b'0.2.5 tests'))
        self.device.release_data = dict(self.DATA)
        self.device.calls.clear()

    def switch_copy(self, label='switch'):
        result = self.call('snapshot', '--switch', label)
        self.device.calls.clear()
        return result

    def test_the_switch_copy_holds_all_app_data_but_the_model_and_both_apks(self):
        result = self.call('snapshot', '--switch', 'switch')
        folder = self.root / 'lab/snapshots/switch'
        self.assertEqual(archive_names(folder / 'data.tar'), set(self.DATA) - {'files/lab/encoder.onnx', 'cache/x'})
        self.assertEqual(((folder / 'app.apk').read_bytes(), (folder / 'test.apk').read_bytes()), (b'0.2.5 app', b'0.2.5 tests'))
        self.assertEqual((result['kind'], result['fingerprint'], result['model_files']),
                         ('switch', self.device.fingerprint(of=RELEASE), ['files/lab/encoder.onnx']))
        self.assertEqual(self.call('snapshot', 'switch-again', '--switch')['kind'], 'switch')

    def test_the_switch_copy_is_refused_and_not_kept_when_it_lacks_a_part_of_owner_data(self):
        for what, path in (('History', 'no_backup/history/1.json'), ('Dictionary', 'files/dictionary/revision.json'),
                           ('History notices', 'shared_prefs/history_notices.xml'), ('settings', 'shared_prefs/settings.xml')):
            self.device.release_data = {k: v for k, v in self.DATA.items() if k != path}
            with self.subTest(what):
                self.refused('holds no ' + what, 'snapshot', '--switch', 'switch')
                self.assertFalse((self.root / 'lab/snapshots/switch').exists())

    def test_switch_copies_are_for_the_unmarked_build_and_there_is_no_other_snapshot(self):
        self.refused('snapshot --switch NAME', 'snapshot', 'plain')
        self.assertFalse((self.root / 'lab/snapshots/plain').exists())
        (self.model / 'encoder.onnx').unlink()
        self.model.rmdir()
        self.refused('kept model copy', 'snapshot', '--switch', 'switch')
        self.device.place(self.device.build(RELEASE, OWNER, False, b'release'))
        self.refused('unmarked', 'snapshot', '--switch', 'switch')

    def test_pull_switch_reads_legacy_recordings_from_the_unmarked_build_only(self):
        result = self.call('pull', '--switch', 'files/benchmark', '--to', str(self.root / 'legacy'))
        self.assertEqual(((self.root / 'legacy/files/benchmark/take1.wav').read_bytes(), result['files']), (b'RIFF', 1))
        self.device.place(self.device.build(RELEASE, OWNER, False, b'release'))
        self.phone.remember([self.device.builds[sha(b'release')]])
        self.refused('unmarked build', 'pull', '--switch', 'files/benchmark')

    def test_release_goes_over_the_unmarked_build_only_with_promote_and_a_current_switch_copy(self):
        release = self.release_apk()
        self.refused('--promote', 'install', release)
        self.refused('no switch copy', 'install', release, '--promote')
        self.switch_copy()
        self.device.release_data['no_backup/history/2.json'] = b'{"text":"a take after the copy"}'
        self.refused('changed since the switch copy switch', 'install', release, '--promote')
        self.assertEqual(self.did('install'), [])
        self.switch_copy('switch-2')
        result = self.call('install', release, '--promote')
        self.assertEqual(self.did('install'), [('install', RELEASE)])
        self.assertEqual((result['switch'], result['promoted'], result['facts_changed']), ('switch-2', True, []))
        self.assertEqual(self.call('app')['release']['layout'], 'owner')

    def test_a_switch_copy_of_another_apk_does_not_count(self):
        self.switch_copy()
        self.device.place(self.device.build(RELEASE, None, True, b'0.2.4 app'))
        self.refused('no switch copy', 'install', self.release_apk(), '--promote')

    def test_after_the_switch_nothing_reads_release_data(self):
        self.switch_copy()
        self.call('install', self.release_apk(), '--promote')
        self.device.calls.clear()
        for argv in (['pull', '--switch', 'files/benchmark'], ['snapshot', '--switch', 'again']):
            with self.subTest(argv[0]), self.assertRaises(phone.Refused):
                self.call(*argv)
        self.call('app')
        self.assertNotIn(('run_as', RELEASE), self.device.calls)

    def test_the_lab_app_installs_beside_the_unmarked_build_without_touching_it(self):
        self.without_lab_app()
        self.call('install', *self.lab_apks())
        self.assertEqual(self.device.packages[RELEASE]['sha256'], sha(b'0.2.5 app'))
        self.assertEqual(self.device.release_data, self.DATA)
        self.assertNotIn(('run_as', RELEASE), self.device.calls)
        self.assertEqual(self.call('run', NS + '.NormalBaselineTest')['outcome'], 'passed')

    def test_switch_restore_puts_the_unmarked_builds_data_back_and_leaves_model_files(self):
        self.switch_copy()
        self.device.release_data.update({'no_backup/history/1.json': b'{"text":"changed"}',
                                         'files/lab/encoder.onnx': b'the model now', 'databases/new.db': b'x'})
        del self.device.release_data['shared_prefs/settings.xml']
        self.refused('restore --switch NAME', 'restore', 'switch')
        self.call('restore', '--switch', 'switch')
        self.assertEqual(self.did('install'), [])
        self.assertEqual(self.device.release_data, {**self.DATA, 'files/lab/encoder.onnx': b'the model now'})

    def test_switch_restore_is_refused_once_release_is_marked(self):
        self.switch_copy()
        self.call('install', self.release_apk(), '--promote')
        owner = dict(self.device.release_data)
        self.device.calls.clear()
        self.refused('release', 'restore', '--switch', 'switch')
        self.assertEqual(self.did('install', 'replace', 'stop'), [])
        self.assertEqual(self.device.release_data, owner)
        self.assertNotIn(('run_as', RELEASE), self.device.calls)

    def test_switch_restore_is_refused_over_another_unmarked_build(self):
        self.switch_copy()
        self.device.place(self.device.build(RELEASE, None, True, b'0.2.4 app'))
        self.refused('not the build of switch copy', 'restore', '--switch', 'switch')
        self.assertEqual(self.did('install', 'replace'), [])

    def test_switch_restore_refuses_a_changed_archive(self):
        self.switch_copy()
        folder = self.root / 'lab/snapshots/switch'
        record = json.loads((folder / 'snapshot.json').read_text())
        payload = tar_bytes({path: content for path, content in self.DATA.items() if path != 'cache/x'})
        (folder / 'data.tar').write_bytes(payload)
        (folder / 'snapshot.json').write_text(json.dumps({**record, 'data_sha256': sha(payload)}))
        self.refused('outside its place', 'restore', '--switch', 'switch')  # it holds the model file
        self.assertEqual(self.did('install', 'replace'), [])


class UsageTests(PhoneCase):
    def test_help_holds_the_visit_steps_the_switch_and_every_verb(self):
        usage = self.call('help')['usage']
        for text in ('install APP.apk TEST.apk', '--promote', 'push DIR [--model]', 'snapshot --switch NAME',
                     'restore --switch NAME', 'pull --switch', 'corpus/lab/model', 'files/timing-receipts',
                     'One-time switch', 'measure CLASS', 'Reports completed', 'files/lab/labrun-inputs',
                     APP, TEST, 'corpus/lab/model --model once', 'Any checkout or worktree'):
            self.assertIn(text, usage)
        # The owner's work, which agents never do (#98).
        for text in ('agents never change phone settings or FUTO', 'enable "Local dictation lab"', 'the microphone',
                     'FUTO\'s voice input to "Local dictation lab"', 'back to "Local dictation" afterwards'):
            self.assertIn(text, usage)
        for gone in ('forget', 'allow-uninstall', 'fingerprinted', 'inputs=', 'main checkout only', 'files/NAME'):
            self.assertNotIn(gone, usage)

    def test_arguments_of_the_lifecycle_verbs(self):
        for argv in (['install'], ['install', '--promote'], ['install', 'a', 'b', 'c'], ['forget', 'a'],
                     ['snapshot', '--switch'], ['restore', '--switch', 'a', 'b'], ['push'], ['measure'], ['run']):
            with self.subTest(argv), self.assertRaises(phone.Refused):
                self.call(*argv)


if __name__ == '__main__':
    unittest.main()
