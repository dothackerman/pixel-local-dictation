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

PKG, TEST = phone.PKG, phone.TEST
BUILD = 'commit=' + '1' * 40 + ';hash=' + '2' * 64
LAB, OWNER = 'layout=test;' + BUILD, 'layout=owner;' + BUILD
OTHER_LAB = 'layout=test;commit=' + '3' * 40 + ';hash=' + '4' * 64
# ADR-028 as built: lab folder and test folders; plus what Android and androidx write on their own.
INPUTS = 'files/lab/labrun-inputs'  # where `measure` places copies of owner recordings and receipts
LAB_RUN_CLASS = PKG + '.labrun.PipelineTest'
NOT_FINGERPRINTED = ('files/lab', 'files/test', 'no_backup/test', 'shared_prefs/test_history_notices.xml',
                     'shared_prefs/test_settings.xml', 'cache', 'code_cache', 'files/profileInstalled')


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
    """In-memory phone: app data as {relpath: bytes}, installed APKs as bytes.

    Like the phone, it refuses `run-as` when the installed app is not debuggable."""

    def __init__(self):
        self.builds = {}  # sha256 of APK bytes -> what the build tools report for it
        self.contents = {}  # package -> installed APK bytes
        self.packages = {PKG: {'installed': False, 'versionCode': 12, 'versionName': '1',
                               'apk_path': '/data/app/x/base.apk', 'uid': 10123, 'granted': ['android.permission.RECORD_AUDIO'],
                               'pid': None, 'first_install': '2026-10-01 08:00:00'},
                         TEST: {'installed': False, 'versionCode': 0, 'versionName': None,
                                'apk_path': '/data/app/y/base.apk', 'uid': 10123, 'granted': [], 'pid': None}}
        for futo in phone.FUTO:
            self.packages[futo] = {'installed': False}
        self.data = {'files/lab/variant.json': b'{}', 'files/lab/encoder.onnx': b'onnx',
                     'files/benchmark/take1.wav': b'RIFF', 'shared_prefs/p.xml': b'<x/>',
                     'no_backup/history/1.json': b'{"text":"owner"}', 'files/test/dictionary.json': b'[]'}
        self.fail = set()  # the adapter calls that fail, as a broken transport would
        self.ime_state = {'enabled': True, 'selected': False, 'bound': False, 'input_shown': False}
        self.calls = []
        self.during_run = lambda: None
        self.report = 'INSTRUMENTATION_STATUS: stream=LAB RUN completed: fake\n'  # what a Lab run's harness reports
        self.seen_inputs = None  # the input folder as the instrumented class found it
        self.place(self.build(PKG, LAB, True, b'lab app'))
        self.place(self.build(TEST, LAB, True, b'lab tests'))

    def build(self, package, marker, debuggable, content, signer='s'):
        """An APK the build tools can read; returns its bytes."""
        self.builds[sha(content)] = {'package': package, 'versionCode': 12 if package == PKG else 0, 'sha256': sha(content),
                                     'signer': signer, 'permissions': [], 'debuggable': debuggable, 'marker': marker}
        return content

    def place(self, content):
        """As if installed earlier, by whatever means."""
        package = self.builds[sha(content)]['package']
        self.contents[package] = content
        self.packages[package].update(installed=True, sha256=sha(content))

    def run_as(self):
        if not self.packages[PKG]['installed']:
            raise phone.Failed('run-as: unknown package: ' + PKG)
        if not self.builds[self.packages[PKG]['sha256']]['debuggable']:
            raise phone.Failed('run-as: package not debuggable: ' + PKG)

    def attempt(self, call):
        if call in self.fail:
            raise phone.Failed(call + ': transport error')

    def outside(self, skip):
        return {k: v for k, v in self.data.items() if not any(k == s or k.startswith(s + '/') for s in skip)}

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

    def ime(self):
        return dict(self.ime_state)

    def inventory(self, rel):
        self.run_as()
        files = {k[len(rel) + 1:]: sha(v) for k, v in self.data.items() if k.startswith(rel + '/')}
        return files or None

    def fingerprint(self):
        self.run_as()
        self.calls.append(('fingerprint',))
        lines = []
        for path, content in self.outside(NOT_FINGERPRINTED).items():
            lines += ['%s  ./%s' % (sha(content), path), '%d ./%s' % (len(content), path)]
        return {'digest': sha(''.join(line + '\n' for line in sorted(lines)).encode()), 'files': len(lines) // 2}

    def logcat(self, uid, since=None, crash=False):
        self.calls.append(('logcat', uid, since, crash))
        return 'log for uid %s' % uid

    def device_time(self):
        return '10-21 10:00:00.000'

    def apk_info(self, path):
        return dict(self.builds[sha(Path(path).read_bytes())])

    def install(self, path, downgrade=False):
        self.attempt('install')
        content = Path(path).read_bytes()
        self.place(content)
        self.calls.append(('install', self.builds[sha(content)]['package']) + (('downgrade',) if downgrade else ()))
        # What Android and androidx write on every update.
        self.data.update({'code_cache/startup': sha(content).encode(), 'files/profileInstalled': b'1'})

    def uninstall(self, pkg):
        self.packages[pkg]['installed'] = False
        self.packages[pkg].pop('sha256', None)
        self.calls.append(('uninstall', pkg))

    def stop(self):
        self.calls.append(('stop',))

    def push(self, directory, changed, removed):
        self.run_as()
        self.calls.append(('push', tuple(sorted(changed)), tuple(sorted(removed))))
        for item in removed:
            self.data.pop('files/lab/' + item, None)
        for item in changed:
            self.data['files/lab/' + item] = (Path(directory) / item).read_bytes()

    def tar(self, *rels):
        self.run_as()
        files = {k: v for k, v in self.data.items() if any(k == r or k.startswith(r + '/') for r in rels)}
        return tar_bytes(files) if files else b''

    def pull_apk(self, remote, local):
        for pkg, info in self.packages.items():
            if info.get('apk_path') == remote:
                Path(local).write_bytes(self.contents[pkg])
                self.calls.append(('pull_apk', pkg))

    def copy(self, skip):
        self.run_as()
        self.attempt('copy')
        self.calls.append(('copy',))
        files = self.outside(skip)
        return tar_bytes({'./' + path: content for path, content in files.items()}) if files else b''

    def replace(self, archive, skip):
        self.run_as()
        self.calls.append(('replace',))
        self.data = {k: v for k, v in self.data.items() if k not in self.outside(skip)}
        if archive:
            with tarfile.open(fileobj=io.BytesIO(archive)) as a:
                for m in a:
                    if m.isfile():
                        self.data[m.name[2:] if m.name.startswith('./') else m.name] = a.extractfile(m).read()

    def empty_test_folders(self):
        self.run_as()
        self.calls.append(('empty',))
        self.data = self.outside(NOT_FINGERPRINTED[1:5])

    def place_inputs(self, sources):
        self.run_as()
        self.remove_inputs()
        self.calls[-1] = ('place_inputs', tuple(sources))
        for source in sources:
            found = {k: v for k, v in self.data.items() if k == source or k.startswith(source + '/')}
            if not found:
                raise phone.Refused('no such file or folder on the phone: ' + source)
            self.data.update({INPUTS + k[len('files'):]: v for k, v in found.items()})
        self.attempt('place_inputs')
        return len(self.outside(()) ) - len(self.outside((INPUTS,)))

    def remove_inputs(self):
        self.run_as()
        self.calls.append(('remove_inputs',))
        self.data = self.outside((INPUTS,))

    def instrument(self, classes, extras, timeout):
        self.calls.append(('instrument', classes, tuple(extras)))
        self.attempt('instrument')
        self.seen_inputs = {k[len(INPUTS) + 1:]: v for k, v in self.data.items() if k.startswith(INPUTS + '/')}
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

    def visit(self, **changes):
        """A Visit record as Visit start leaves it, for the installed lab build; returns the path of its copy."""
        record = {'name': 'v1', 'started': '2026-11-24T10:00:00', 'ended': None, 'release': None,
                  'copy': {'archive': 'owner-data.tar', 'sha256': 'c' * 64},
                  'fingerprint': self.device.fingerprint(), 'model': {},
                  'lab_builds': [{'app_sha256': self.device.packages[PKG]['sha256'],
                                  'test_sha256': self.device.packages[TEST]['sha256'], 'marker': LAB}], **changes}
        folder = self.root / 'lab/visits' / record['name']
        folder.mkdir(parents=True, exist_ok=True)
        (folder / 'visit.json').write_text(json.dumps(record))
        self.device.calls.clear()
        return folder / 'owner-data.tar'

    def did(self, *kinds):
        return [c for c in self.device.calls if c[0] in kinds]

    def refused(self, expected, *argv):
        with self.assertRaises(phone.Refused) as caught:
            self.call(*argv)
        self.assertIn(expected, str(caught.exception))

    def records(self):
        return [json.loads(path.read_text()) for path in sorted((self.root / 'lab/visits').glob('*/visit.json'))]

    def lab_apks(self, tag='lab', marker=LAB):
        return [self.apk(tag + '-app.apk', PKG, marker, True), self.apk(tag + '-test.apk', TEST, marker, True)]

    def release_apk(self, tag='release'):
        return self.apk(tag + '.apk', PKG, OWNER, False)

    def with_release_installed(self, tag='release'):
        """The phone between Visits: release, the test package and test data left over from an earlier Visit."""
        path = self.release_apk(tag)
        self.device.place(Path(path).read_bytes())
        self.phone.remember([self.device.builds[sha(Path(path).read_bytes())]])  # as its own install recorded it
        self.device.calls.clear()
        return path

    def start_visit(self):
        """Release installed, then the lab build over it; returns the pulled release APK of the new Visit."""
        self.with_release_installed()
        name = self.call('install', *self.lab_apks())['visit']
        self.device.calls.clear()
        return str(self.root / 'lab/visits' / name / 'release.apk')


class PhoneTests(PhoneCase):
    def test_read_only_verbs_expose_only_our_app_and_allowlisted_facts(self):
        info = self.call('app')
        self.assertEqual(set(info), {'app', 'test', 'ime', 'futo', 'lab', 'visit'})
        self.assertEqual((info['app']['layout'], info['visit']), ('test', None))
        self.assertNotIn('apk_path', info['app'])
        self.assertNotIn('apk_path', info['test'])
        self.assertEqual(set(info['futo']), set(phone.FUTO))
        device = self.call('device')
        self.assertEqual(set(device['props']), set(phone.PROPS))

    def test_pull_reads_test_output_recordings_and_timing_receipts_and_refuses_traversal(self):
        self.device.data.update({'files/timing-receipts/1.jsonl': b'{}', 'files/test/recordings/t.wav': b'RIFF'})
        result = self.call('pull', 'files/lab', 'files/benchmark', 'files/timing-receipts', 'files/test/recordings')
        self.assertEqual(result['files'], 5)
        self.assertTrue((Path(result['path']) / 'files/benchmark/take1.wav').exists())
        single = self.call('pull', 'files/benchmark/take1.wav', '--to', str(self.root / 'one'))
        self.assertEqual(single['files'], 1)
        for bad in ('shared_prefs', 'no_backup/history', 'files/dictionary', 'files/lab/../../shared_prefs',
                    'files/../shared_prefs/p.xml', '/files/lab'):
            with self.assertRaises(phone.Refused):
                self.call('pull', bad)

    def test_pull_takes_only_what_was_asked_for_from_the_archive(self):
        self.device.tar = lambda *rels: tar_bytes({'files/lab/a': b'1', 'shared_prefs/settings.xml': b'<owner/>'})
        with self.assertRaises(phone.Refused):
            self.call('pull', 'files/lab', '--to', str(self.root / 'out'))
        self.assertFalse((self.root / 'out').exists())

    def test_push_sends_only_changed_files_and_refuses_symlinks_and_other_targets(self):
        self.visit()
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
        self.visit()
        result = self.call('run', PKG + '.NormalBaselineTest', 'provider=webgpu')
        self.assertEqual(result['outcome'], 'passed')
        self.assertIn('result.json', result['lab'])
        folder = Path(result['path'])
        self.assertTrue((folder / 'meta.json').exists() and (folder / 'logcat.txt').exists())
        self.assertEqual(self.did('instrument'), [('instrument', PKG + '.NormalBaselineTest', (('provider', 'webgpu'),))])
        self.assertEqual(self.did('logcat')[0][:2], ('logcat', 10123))
        meta = json.loads((folder / 'meta.json').read_text())
        self.assertEqual(set(meta) >= {'verb', 'ts', 'app_sha256', 'test_sha256', 'lab_sha256'}, True)
        for bad in (['run', 'Foo;rm'], ['run', PKG + '.NormalBaselineTest', 'noequals'], ['run', PKG + '.NormalBaselineTest', '1bad=x']):
            with self.assertRaises(phone.Refused):
                self.call(*bad)

    def test_check_accepts_or_refuses_apks_without_touching_the_phone(self):
        app = self.apk('app.apk', PKG, LAB, True)
        test = self.apk('test.apk', TEST, LAB, True, signer='other')
        with self.assertRaises(phone.Refused):
            self.call('check', app, test)
        test = self.apk('test.apk', TEST, LAB, True)
        result = self.call('check', app, test)
        self.assertEqual([r['package'] for r in result], [PKG, TEST])
        self.assertEqual(self.device.calls, [])

    def test_install_refuses_network_permissions_foreign_packages_and_live_keyboard(self):
        self.visit()
        app = self.apk('app.apk', PKG, LAB, True)
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
        self.assertEqual(self.did('install'), [('install', PKG), ('install', TEST)])

    def test_stop_and_uninstall_gates(self):
        self.visit()
        self.device.ime_state['input_shown'] = True
        with self.assertRaises(phone.Refused):
            self.call('stop')
        self.device.ime_state['input_shown'] = False
        self.assertEqual(self.call('stop')['outcome'], 'stopped')
        with self.assertRaises(phone.Refused):  # no token
            self.call('uninstall')
        (self.root / 'lab').mkdir(exist_ok=True)
        (self.root / 'lab/.allow-uninstall').touch()
        with self.assertRaises(phone.Refused):  # IME still enabled
            self.call('uninstall')
        self.device.ime_state['enabled'] = False
        self.assertEqual(self.call('uninstall')['outcome'], 'uninstalled')
        self.assertFalse((self.root / 'lab/.allow-uninstall').exists())
        self.assertFalse(self.device.packages[PKG]['installed'])

    def test_unknown_verbs_and_extra_arguments_are_refused(self):
        for argv in (['inspect'], ['status', 'x'], ['pull'], ['restore'], ['logs', 'x']):
            with self.assertRaises(phone.Refused):
                self.call(*argv)
        self.assertIn('usage', self.call('help'))

    def test_help_teaches_the_owner_session_order_and_receipt_rollover(self):
        usage = self.call('help')['usage']
        for text in ('PhoneReadyTest', 'one batched run', '.prev.jsonl'):
            self.assertIn(text, usage)


class MarkerTests(PhoneCase):
    """ADR-028: debuggable <=> test layout, non-debuggable <=> owner layout, test APK marker = app marker."""

    REFUSED = {  # case -> (app marker, app debuggable, test APK marker or ... for no test APK)
        'a debuggable build with the owner layout': (OWNER, True, ...),
        'a release build with the test layout': (LAB, False, ...),
        'a 0.2.5-like build: debuggable, no marker': (None, True, None),
        'a release build without a marker': (None, False, ...),
        'a malformed marker': ('layout=test;commit=abc;hash=def', True, ...),
        'a test APK without a marker': (LAB, True, None),
        'a test APK from another build': (LAB, True, OTHER_LAB),
        'a test APK beside a release build': (OWNER, False, LAB),
    }

    def pair(self, case):
        app_marker, debuggable, test_marker = self.REFUSED[case]
        apks = [self.apk('app.apk', PKG, app_marker, debuggable)]
        return apks + ([] if test_marker is ... else [self.apk('test.apk', TEST, test_marker, True)])

    def test_check_refuses_each_disagreement_of_marker_and_build(self):
        for case in self.REFUSED:
            with self.subTest(case), self.assertRaises(phone.Refused):
                self.call('check', *self.pair(case))
        self.assertEqual(self.device.calls, [])

    def test_check_accepts_a_lab_build_with_its_tests_and_a_release_build_alone(self):
        lab = self.call('check', self.apk('app.apk', PKG, LAB, True), self.apk('test.apk', TEST, LAB, True))
        self.assertEqual([(i['marker'], i['debuggable']) for i in lab], [(LAB, True), (LAB, True)])
        release = self.call('check', self.apk('release.apk', PKG, OWNER, False))
        self.assertEqual([(i['marker'], i['debuggable']) for i in release], [(OWNER, False)])

    def test_install_refuses_each_disagreement_and_installs_nothing(self):
        for case in self.REFUSED:
            with self.subTest(case), self.assertRaises(phone.Refused):
                self.call('install', *self.pair(case))
        self.assertEqual(self.did('install'), [])

    def test_run_needs_no_apk_pull_for_a_build_install_recorded(self):
        self.visit()
        self.call('install', self.apk('app.apk', PKG, LAB, True), self.apk('test.apk', TEST, LAB, True))
        self.device.calls.clear()
        self.assertEqual(self.call('run', PKG + '.NormalBaselineTest')['outcome'], 'passed')
        self.assertEqual(self.did('pull_apk'), [])

    def test_an_installed_build_nobody_recorded_is_read_from_its_pulled_apk_once(self):
        self.visit()
        self.assertEqual(self.call('run', PKG + '.NormalBaselineTest')['outcome'], 'passed')
        self.assertEqual(self.did('pull_apk'), [('pull_apk', PKG), ('pull_apk', TEST)])
        self.device.calls.clear()
        self.call('run', PKG + '.NormalBaselineTest')
        self.assertEqual(self.did('pull_apk'), [])


class VisitGuardTests(PhoneCase):
    """`run`, `push`, `stop` only in an open Visit on a lab build whose owner data equals the Visit copy."""

    def verbs(self):
        folder = self.root / 'push-source'
        folder.mkdir(exist_ok=True)
        (folder / 'new.json').write_text('{}')
        return {'run': ['run', PKG + '.NormalBaselineTest'], 'push': ['push', str(folder)], 'stop': ['stop'],
                'measure': ['measure', LAB_RUN_CLASS, 'inputs=files/benchmark']}

    def assert_all_refused(self, expected):
        before = dict(self.device.data)
        for verb, argv in self.verbs().items():
            with self.subTest(verb):
                with self.assertRaises(phone.Refused) as caught:
                    self.call(*argv)
                self.assertIn(expected, str(caught.exception))
        self.assertEqual(self.did('instrument', 'push', 'stop', 'place_inputs', 'remove_inputs'), [])
        self.assertEqual(self.device.data, before)

    def test_refused_without_a_visit(self):
        self.assert_all_refused('no Visit is open')

    def test_refused_after_the_visit_ended(self):
        self.visit(ended='2026-11-24T11:00:00')
        self.assert_all_refused('no Visit is open')

    def test_refused_on_a_0_2_5_like_build(self):
        self.visit()
        self.device.place(self.device.build(PKG, None, True, b'0.2.5'))
        self.assert_all_refused('no build marker')

    def test_refused_on_the_release_build_without_trying_run_as(self):
        self.visit()
        self.device.place(self.device.build(PKG, OWNER, False, b'release'))
        self.assert_all_refused('release build')

    def test_refused_when_the_test_package_is_missing_or_from_another_build(self):
        self.visit()
        self.device.place(self.device.build(TEST, OTHER_LAB, True, b'other tests'))
        self.assert_all_refused('another build')
        self.device.packages[TEST] = {'installed': False}
        self.assert_all_refused('test package is not installed')

    def test_refused_on_a_lab_build_the_visit_does_not_list(self):
        self.visit()
        self.device.place(self.device.build(PKG, OTHER_LAB, True, b'other lab app'))
        self.device.place(self.device.build(TEST, OTHER_LAB, True, b'other lab tests'))
        self.assert_all_refused('not part of Visit v1')

    def test_refused_when_owner_data_differs_from_the_visit_copy_and_names_the_copy(self):
        copy = self.visit()
        self.device.data['no_backup/history/1.json'] = b'{"text":"changed"}'
        self.assert_all_refused(str(copy))

    def test_the_newest_open_visit_counts(self):
        self.visit(name='old', started='2026-11-01T10:00:00', fingerprint={'digest': 'e' * 64, 'files': 1})
        self.visit(name='new', started='2026-11-24T10:00:00')
        self.assertEqual(self.call('stop')['outcome'], 'stopped')

    def test_a_malformed_visit_record_is_refused_not_guessed(self):
        self.visit()
        (self.root / 'lab/visits/v1/visit.json').write_text(json.dumps({'name': 'v1', 'ended': None}))
        self.assert_all_refused('Visit record v1')

    def test_run_and_measure_are_refused_while_the_keyboard_is_in_use(self):
        self.visit()
        self.device.ime_state['bound'] = True
        for argv in (['run', PKG + '.NormalBaselineTest'], ['measure', LAB_RUN_CLASS, 'inputs=files/benchmark']):
            with self.subTest(argv[0]):
                self.refused('in use', *argv)
        self.assertEqual(self.did('instrument', 'place_inputs'), [])

    def test_run_refuses_classes_of_the_lab_run_package(self):
        self.visit()
        for classes in (PKG + '.labrun.PipelineRun', PKG + '.LabTest,' + PKG + '.labrun.deep.Trace#collect'):
            with self.subTest(classes):
                with self.assertRaises(phone.Refused) as caught:
                    self.call('run', classes)
                self.assertIn('Lab run', str(caught.exception))
        self.assertEqual(self.did('instrument'), [])
        self.assertEqual(self.call('run', PKG + '.labrunner.NotALabRun')['outcome'], 'passed')

    def test_run_fingerprints_before_and_after_and_fails_on_a_change_naming_the_copy(self):
        copy = self.visit()
        self.device.during_run = lambda: self.device.data.update({'files/dictionary/revision.json': b'[]'})
        with self.assertRaises(phone.Failed) as caught:
            self.call('run', PKG + '.NormalBaselineTest')
        self.assertIn(str(copy), str(caught.exception))
        kinds = [c[0] for c in self.device.calls if c[0] in ('fingerprint', 'instrument')]
        self.assertEqual(kinds, ['fingerprint', 'instrument', 'fingerprint'])
        saved = list((self.root / 'lab/runs').glob('*-run-*/instrumentation.txt'))
        self.assertEqual(len(saved), 1, 'the output of the failed run is kept')

    def test_run_passes_when_only_lab_test_and_system_locations_change(self):
        self.visit()
        self.device.during_run = lambda: self.device.data.update({
            'files/lab/receipts.jsonl': b'{}', 'files/test/dictionary.json': b'["a"]', 'no_backup/test/history/1.json': b'{}',
            'shared_prefs/test_settings.xml': b'<s/>', 'shared_prefs/test_history_notices.xml': b'<n/>',
            'code_cache/com.android.skia.shaders_cache': b'x', 'cache/scratch.wav': b'x', 'files/profileInstalled': b'1'})
        self.assertEqual(self.call('run', PKG + '.NormalBaselineTest')['outcome'], 'passed')


class MeasureTests(PhoneCase):
    """`measure` runs Lab run classes only, on copies of owner recordings and receipts that it places in
    the lab folder and removes again; it reports completed or incomplete, never passed or failed."""

    def setUp(self):
        super().setUp()
        self.device.data.update({'files/benchmark/take1.json': b'{}', 'files/timing-receipts/1.jsonl': b'{"ms":1}'})
        self.copy = self.visit()

    def order(self):
        return [c[0] for c in self.did('fingerprint', 'place_inputs', 'instrument', 'remove_inputs')]

    def test_measure_refuses_classes_outside_the_lab_run_package(self):
        for classes in (PKG + '.NormalBaselineTest', LAB_RUN_CLASS + ',' + PKG + '.HistoryProcessTest#x',
                        PKG + '.labrunner.NotALabRun', PKG + '.labrun', 'Foo;rm'):
            with self.subTest(classes):
                self.refused('', 'measure', classes, 'inputs=files/benchmark')
        self.refused('only Lab run classes', 'measure', PKG + '.NormalBaselineTest')
        self.assertEqual(self.did('instrument', 'place_inputs', 'remove_inputs'), [])

    def test_inputs_are_copied_on_the_phone_for_the_run_and_removed_afterwards(self):
        owner = self.device.outside(NOT_FINGERPRINTED)
        result = self.call('measure', LAB_RUN_CLASS + '#savedTakesThroughTheServicePipeline',
                           'inputs=files/benchmark,files/timing-receipts/1.jsonl', 'label=a')
        self.assertEqual(self.device.seen_inputs, {'benchmark/take1.wav': b'RIFF', 'benchmark/take1.json': b'{}',
                                                   'timing-receipts/1.jsonl': b'{"ms":1}'})
        self.assertEqual((result['outcome'], result['inputs']), ('completed', 3))
        self.assertEqual(self.device.outside((INPUTS,)), {**self.device.data}, 'no input copy stays on the phone')
        self.assertEqual(self.device.outside(NOT_FINGERPRINTED), owner)
        self.assertEqual(self.did('instrument'),
                         [('instrument', LAB_RUN_CLASS + '#savedTakesThroughTheServicePipeline', (('label', 'a'),))])
        self.assertEqual(self.did('place_inputs'), [('place_inputs', ('files/benchmark', 'files/timing-receipts/1.jsonl'))])

    def test_owner_data_is_fingerprinted_before_the_copy_and_after_the_removal(self):
        self.call('measure', LAB_RUN_CLASS, 'inputs=files/benchmark')
        self.assertEqual(self.order(), ['fingerprint', 'place_inputs', 'instrument', 'remove_inputs', 'fingerprint'])

    def test_a_change_of_owner_data_fails_the_call_names_the_copy_and_still_removes_the_inputs(self):
        self.device.during_run = lambda: self.device.data.update({'files/benchmark/take1.wav': b'RIFX'})
        with self.assertRaises(phone.Failed) as caught:
            self.call('measure', LAB_RUN_CLASS, 'inputs=files/benchmark')
        self.assertIn(str(self.copy), str(caught.exception))
        self.assertEqual(self.order()[-2:], ['remove_inputs', 'fingerprint'])
        self.assertEqual(self.device.outside((INPUTS,)), self.device.data)
        self.assertEqual(len(list((self.root / 'lab/runs').glob('*-measure-*/instrumentation.txt'))), 1)

    def test_the_inputs_are_removed_when_the_copy_or_the_run_breaks_off(self):
        for broken in ('place_inputs', 'instrument'):
            with self.subTest(broken):
                self.device.fail = {broken}
                with self.assertRaises(phone.Failed):
                    self.call('measure', LAB_RUN_CLASS, 'inputs=files/benchmark')
                self.assertEqual(self.did('remove_inputs')[-1:], [('remove_inputs',)])
                self.assertEqual(self.device.outside((INPUTS,)), self.device.data)

    def test_what_an_earlier_measure_left_in_the_input_folder_is_gone_before_the_run(self):
        self.device.data[INPUTS + '/benchmark/old.wav'] = b'left over'
        self.call('measure', LAB_RUN_CLASS, 'inputs=files/timing-receipts')
        self.assertEqual(self.device.seen_inputs, {'timing-receipts/1.jsonl': b'{"ms":1}'})
        self.call('measure', LAB_RUN_CLASS)
        self.assertEqual(self.device.seen_inputs, {}, 'a measure without inputs finds an empty input folder')

    def test_inputs_are_owner_recordings_or_receipts_and_nothing_else(self):
        for bad in ('inputs=files/dictionary', 'inputs=no_backup/history', 'inputs=shared_prefs/settings.xml',
                    'inputs=files/benchmark/../dictionary', 'inputs=/files/benchmark', 'inputs=files/lab/model',
                    'inputs=files/test/recordings', 'inputs=', 'inputs=files/benchmark,',
                    'inputs=files/benchmark,files/benchmark/take1.wav', 'inputs=files/benchmark,files/benchmark'):
            with self.subTest(bad), self.assertRaises(phone.Refused):
                self.call('measure', LAB_RUN_CLASS, bad)
        self.assertEqual(self.did('instrument', 'place_inputs'), [])
        self.refused('no such file or folder', 'measure', LAB_RUN_CLASS, 'inputs=files/benchmark/absent.wav')
        self.assertEqual(self.did('instrument'), [])
        self.assertEqual(self.device.outside((INPUTS,)), self.device.data)

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

    def test_the_result_names_the_output_files_never_inputs_or_model_files(self):
        def lab_run():
            self.device.data.update({'files/lab/pipeline.json': b'{}', 'files/lab/issue56/probe.json': b'{}',
                                     'files/lab/variant.json': b'{"changed":1}', 'files/lab/encoder.onnx': b'onnx'})
        self.device.during_run = lab_run
        result = self.call('measure', LAB_RUN_CLASS, 'inputs=files/benchmark')
        self.assertEqual(result['outputs'], ['issue56/probe.json', 'pipeline.json', 'result.json', 'variant.json'])
        self.assertEqual(result['model_differs_from_visit_start'], [])
        folder = Path(result['path'])
        self.assertTrue((folder / 'instrumentation.txt').exists() and (folder / 'logcat.txt').exists())
        meta = json.loads((folder / 'meta.json').read_text())
        self.assertEqual((meta['verb'], meta['inputs']), ('measure', ['files/benchmark']))

    def test_refused_when_a_speech_model_file_lies_in_the_input_folder(self):
        (self.model / 'labrun-inputs').mkdir()
        (self.model / 'labrun-inputs/encoder.onnx').write_bytes(b'onnx')
        self.refused('speech-model file', 'measure', LAB_RUN_CLASS)
        self.assertEqual(self.did('instrument', 'place_inputs', 'remove_inputs'), [])


class CallerTests(PhoneCase):
    """Device verbs only from the main checkout; `help` and host-only `check` from anywhere."""

    def setUp(self):
        super().setUp()
        self.main = self.root / 'main'
        (self.main / '.git').mkdir(parents=True)
        (self.main / 'tools').mkdir()
        self.worktree = self.main / '.claude/worktrees/agent-1'
        (self.worktree / 'x').mkdir(parents=True)
        (self.worktree / '.git').write_text('gitdir: ' + str(self.main / '.git/worktrees/agent-1'))

    def main_call(self, *argv):
        output = io.StringIO()
        common = subprocess.CompletedProcess([], 0, str(self.main / '.git') + '\n', '')
        with patch.object(phone.subprocess, 'run', return_value=common), \
             patch.object(phone, 'Device', return_value=self.device), \
             patch.object(phone.os, 'umask'), redirect_stdout(output):
            status = phone.main(list(argv))
        return status, output.getvalue()

    def test_device_verbs_run_from_the_main_checkout_and_keep_state_there(self):
        status, output = self.main_call('--caller', str(self.main / 'tools'), 'app')
        self.assertEqual(status, 0)
        self.assertEqual(json.loads((self.main / 'corpus/lab/last.json').read_text()), json.loads(output))

    def test_device_verbs_refuse_from_a_worktree_outside_the_checkout_and_without_a_caller(self):
        outside = self.root / 'elsewhere'
        outside.mkdir()
        other = self.root / 'other-clone'
        (other / '.git').mkdir(parents=True)
        cases = {'worktree': ['--caller', str(self.worktree)], 'folder inside a worktree': ['--caller', str(self.worktree / 'x')],
                 'outside any checkout': ['--caller', str(outside)], 'another clone': ['--caller', str(other)],
                 'no caller directory': [], 'relative caller': ['--caller', '.'],
                 'a second caller after the one the shim passed': ['--caller', str(self.worktree), '--caller', str(self.main)]}
        for verb in (['status'], ['device'], ['app'], ['crash'], ['logs'], ['stop'], ['uninstall'], ['run', PKG + '.NormalBaselineTest'],
                     ['measure', LAB_RUN_CLASS], ['push', str(outside)], ['pull', 'files/lab'], ['snapshot', 'a'],
                     ['restore', 'a'],
                     ['install', 'app.apk']):
            for case, caller in cases.items():
                with self.subTest(verb=verb[0], case=case), self.assertRaises(phone.Refused):
                    self.main_call(*caller, *verb)
        self.assertEqual(self.device.calls, [])
        self.assertFalse((self.main / 'corpus').exists())

    def test_check_and_help_work_from_a_worktree_without_lock_or_state(self):
        app, test = self.apk('app.apk', PKG, LAB, True), self.apk('test.apk', TEST, LAB, True)
        for caller in (['--caller', str(self.worktree)], []):
            with self.subTest(caller=caller):
                status, output = self.main_call(*caller, 'check', app, test)
                self.assertEqual(status, 0)
                self.assertEqual([i['package'] for i in json.loads(output)], [PKG, TEST])
                self.assertIn('status', self.main_call(*caller, 'help')[1])
        self.assertEqual(self.device.calls, [])
        self.assertFalse((self.main / 'corpus').exists())
        self.assertFalse((self.worktree / 'corpus').exists())

    def test_check_from_a_worktree_still_refuses_a_lab_app_with_tests_from_another_build(self):
        app, test = self.apk('app.apk', PKG, LAB, True), self.apk('test.apk', TEST, OTHER_LAB, True)
        with self.assertRaises(phone.Refused):
            self.main_call('--caller', str(self.worktree), 'check', app, test)


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
        device.package(PKG)
        device.logcat(10123, since='10-21 10:00:00.000')
        device.private('ls files/lab')
        for argv in runner.argv[2:]:
            self.assertEqual(argv[:3], ['/sdk/platform-tools/adb', '-s', 'SERIAL123'])
        joined = ['\x00'.join(a) for a in runner.argv]
        self.assertTrue(any('--uid\x0010123' in j for j in joined))
        self.assertTrue(all('run-as %s sh -c' % PKG in j for j in joined if 'run-as' in j))
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
            self.assertEqual(device.package(PKG)['uid'], expected)
        device, _ = self.device({**answers, 'dumpsys package': (0, '  versionCode=12\n  versionName=null\n', '')})
        with self.assertRaises(phone.Failed):
            device.package(PKG)
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
                """adb shell `run-as PKG sh -c CODE` runs CODE here, in a folder standing in for the app's data."""
                words = shlex.split(argv[-1])
                if argv[3:4] == ['shell'] and words[:4] == ['run-as', PKG, 'sh', '-c']:
                    return subprocess.run(['sh', '-c', words[4]], cwd=data, capture_output=True, text=True)
                answer = phone.MODEL if 'ro.product.model' in argv[-1] else 'List of devices attached\nS\tdevice\n'
                return subprocess.CompletedProcess(argv, 0, answer, '')

            device = phone.Device('/sdk', runner=on_phone)
            for rel in ('no_backup/history/1.json', 'files/dictionary/revision 1.json', 'shared_prefs/settings.xml',
                        'files/benchmark/take.wav', 'databases/d.db', *(skipped + '/x' for skipped in NOT_FINGERPRINTED[:3]),
                        *NOT_FINGERPRINTED[3:5], 'cache/x', 'code_cache/x', 'files/profileInstalled'):
                write(rel, b'before')
            first = device.fingerprint()
            self.assertEqual(first['files'], 5)
            self.assertRegex(first['digest'], '^[0-9a-f]{64}$')
            for rel in (*(skipped + '/x' for skipped in NOT_FINGERPRINTED[:3]), *NOT_FINGERPRINTED[3:5],
                        'cache/x', 'code_cache/x', 'files/profileInstalled', 'files/lab/model/new.onnx', 'files/test/new'):
                write(rel, b'after')
            self.assertEqual(device.fingerprint(), first, 'lab folder, test folders and system files do not count')
            seen = {first['digest']}
            changes = (lambda: write('no_backup/history/1.json', b'BEFORE'),  # same size, other content
                       lambda: write('shared_prefs/test_settings.xml.owner', b''),  # a new empty file beside a test file
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

    def test_tar_validation_rejects_traversal_links_and_foreign_dirs(self):
        phone.validate_tar(tar_bytes({'files/lab/a': b'1', 'shared_prefs/x.xml': b'2'}))
        for bad in ({'files/../x': b''}, {'/files/x': b''}, {'cache/x': b''}, {'./files/lab/model.onnx': b''},
                    {'files/test/x': b''}, {'shared_prefs/test_settings.xml': b''}):
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

    def test_install_downgrades_only_when_asked_and_package_facts_hold_the_first_install_time(self):
        dump = '  versionCode=12\n    userId=10123\n    firstInstallTime=2026-10-01 08:00:00\n    lastUpdateTime=2026-11-25 09:00:00\n'
        device, runner = self.device({'pm path': (0, 'package:/data/app/x/base.apk\n', ''), 'pidof': (1, '', ''),
                                      'sha256sum': (0, 'c' * 64 + '  x\n', ''), 'dumpsys package': (0, dump, '')})
        self.assertEqual(device.package(PKG)['first_install'], '2026-10-01 08:00:00')
        device.install('/host/app.apk')
        device.install('/host/old.apk', downgrade=True)
        self.assertEqual([argv[3:] for argv in runner.argv[-2:]],
                         [['install', '-r', '/host/app.apk'], ['install', '-r', '-d', '/host/old.apk']])


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
            assert words[:4] == ['run-as', PKG, 'sh', '-c'] and len(words) == 5, words
            return subprocess.run(['sh', '-c', self.local(words[4])], cwd=self.data, capture_output=True, text=text)
        if command[0] == 'push':
            Path(self.local(command[2])).write_bytes(Path(command[1]).read_bytes())
        elif command[0] == 'shell' and command[1].startswith('rm -f -- /data/local/tmp/'):
            Path(self.local(command[1].split()[-1])).unlink()
        answer = phone.MODEL if 'ro.product.model' in command[-1] else 'List of devices attached\nS\tdevice\n'
        return subprocess.CompletedProcess(argv, 0, answer if text else answer.encode(), '' if text else b'')


class AdapterOnLocalFilesTests(unittest.TestCase):
    """Copy, fingerprint, replace and emptying as the phone's shell would run them (GNU tools here, toybox there)."""

    OWNER = ('no_backup/history/1.json', 'files/dictionary/revision 1.json', 'shared_prefs/settings.xml',
             'files/benchmark/take.wav', 'databases/d.db', 'app_other/unknown-store')
    LEFT_ALONE = ('files/lab/model/encoder.onnx', 'files/lab/receipts.jsonl', 'files/test/dictionary/revision.json',
                  'no_backup/test/history/1.json', 'shared_prefs/test_settings.xml',
                  'shared_prefs/test_history_notices.xml', 'cache/x', 'code_cache/x', 'files/profileInstalled')

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

    def test_a_copy_of_owner_data_holds_exactly_what_the_fingerprint_sees(self):
        before = self.files()
        payload = self.device.copy(phone.OWNER_SKIP)
        self.assertEqual(sorted(phone.validate_tar(payload, outside=phone.OWNER_SKIP)), sorted(self.OWNER))
        self.assertEqual(phone.tar_fingerprint(payload), self.device.fingerprint())
        self.assertEqual(self.files(), before, 'the copy leaves nothing behind in app data')
        with tarfile.open(fileobj=io.BytesIO(payload)) as archive:
            held = {m.name[2:] if m.name.startswith('./') else m.name: archive.extractfile(m).read() for m in archive if m.isfile()}
        self.assertEqual(held, {rel: rel.encode() for rel in self.OWNER})

    def test_the_host_notices_a_copy_that_lacks_a_file_or_holds_other_bytes(self):
        complete = self.device.fingerprint()
        for held in (self.OWNER[1:], self.OWNER + ('files/new',)):
            self.assertNotEqual(phone.tar_fingerprint(tar_bytes({rel: rel.encode() for rel in held})), complete)
        other = {rel: rel.encode() for rel in self.OWNER}
        other['databases/d.db'] = b'databases/D.DB'
        self.assertNotEqual(phone.tar_fingerprint(tar_bytes(other)), complete)

    def test_app_data_without_owner_files_copies_to_nothing_and_still_matches(self):
        for rel in self.OWNER:
            (self.data / rel).unlink()
        payload = self.device.copy(phone.OWNER_SKIP)
        self.assertEqual(payload, b'')
        self.assertEqual(phone.tar_fingerprint(payload), self.device.fingerprint())

    def test_a_switch_copy_leaves_out_only_the_named_model_files_and_system_files(self):
        skip = ('files/lab/model/encoder.onnx',) + phone.SYSTEM_FILES
        payload = self.device.copy(skip)
        held = set(phone.validate_tar(payload, outside=skip))
        self.assertEqual(held, set(self.OWNER + self.LEFT_ALONE[1:6]))
        self.assertEqual(phone.tar_fingerprint(payload, leave_out=phone.EXCLUDED), self.device.fingerprint())

    def test_a_copy_must_leave_the_cache_out(self):
        with self.assertRaises(phone.Refused):
            self.device.copy(('files/lab',))
        self.assertEqual(self.runner.argv, [])

    def test_replace_puts_owner_data_back_and_never_touches_what_it_leaves_alone(self):
        payload, fingerprint = self.device.copy(phone.OWNER_SKIP), self.device.fingerprint()
        self.write('no_backup/history/1.json', b'changed')
        self.write('no_backup/history/2.json', b'a take that came later')
        self.write('app_new/store', b'a store that came later')
        (self.data / 'databases/d.db').unlink()
        for rel in self.LEFT_ALONE:
            self.write(rel, b'written during the Visit')
        self.device.replace(payload, phone.OWNER_SKIP)
        self.assertEqual(self.device.fingerprint(), fingerprint)
        self.assertEqual(self.files(), {**{rel: rel.encode() for rel in self.OWNER},
                                        **{rel: b'written during the Visit' for rel in self.LEFT_ALONE}})
        self.assertEqual(list(self.uploads.iterdir()), [], 'the uploaded archive is removed')

    def test_replace_with_an_empty_copy_removes_owner_files_only(self):
        self.device.replace(b'', phone.OWNER_SKIP)
        self.assertEqual(set(self.files()), set(self.LEFT_ALONE))

    def test_a_switch_restore_keeps_only_the_named_model_files_and_system_files(self):
        skip = ('files/lab/model/encoder.onnx',) + phone.SYSTEM_FILES
        payload = self.device.copy(skip)
        self.write('files/lab/model/encoder.onnx', b'the model as it is now')
        self.write('files/test/dictionary/later.json', b'written by the lab build')
        self.write('files/lab/later.jsonl', b'written by the lab build')
        self.device.replace(payload, skip)
        files = self.files()
        self.assertEqual(files.pop('files/lab/model/encoder.onnx'), b'the model as it is now')
        self.assertEqual(files, {rel: rel.encode() for rel in self.OWNER + self.LEFT_ALONE[1:]})

    def test_inputs_are_copied_inside_app_data_and_removed_without_touching_anything_else(self):
        self.write('files/benchmark/sub/take 2.wav', b'second')
        self.write('files/timing-receipts/1.jsonl', b'{}')
        self.write(INPUTS + '/benchmark/left-over.wav', b'from an earlier measure')
        before = self.files()
        before.pop(INPUTS + '/benchmark/left-over.wav')
        count = self.device.place_inputs(['files/benchmark', 'files/timing-receipts/1.jsonl'])
        self.assertEqual(count, 3)
        self.assertEqual(self.files(), {**before, INPUTS + '/benchmark/take.wav': b'files/benchmark/take.wav',
                                        INPUTS + '/benchmark/sub/take 2.wav': b'second',
                                        INPUTS + '/timing-receipts/1.jsonl': b'{}'})
        self.assertFalse(any(argv[1:2] != ['-s'] or argv[3] not in ('shell',) for argv in self.runner.argv[2:]),
                         'the copies are made on the phone: nothing is pushed or pulled')
        self.device.remove_inputs()
        self.assertEqual(self.files(), before)
        self.device.remove_inputs()  # nothing to remove is no failure
        self.assertEqual(self.device.place_inputs([]), 0)

    def test_inputs_that_are_missing_or_hold_a_link_are_refused_and_nothing_is_copied(self):
        self.write(INPUTS + '/benchmark/left-over.wav', b'from an earlier measure')
        (self.data / 'files/timing-receipts').mkdir()
        (self.data / 'files/timing-receipts/link').symlink_to('/etc/hostname')
        before = self.files()
        for sources in (['files/benchmark/absent.wav'], ['files/benchmark', 'files/timing-receipts']):
            with self.subTest(sources), self.assertRaises(phone.Refused):
                self.device.place_inputs(sources)
            self.assertEqual({k: v for k, v in self.files().items() if not k.startswith(INPUTS)},
                             {k: v for k, v in before.items() if not k.startswith(INPUTS)})
            self.assertFalse(any(k.startswith(INPUTS + '/benchmark/take') for k in self.files()))

    def test_emptying_the_test_folders_removes_them_and_nothing_else(self):
        self.device.empty_test_folders()
        self.assertEqual(set(self.files()), set(self.OWNER + self.LEFT_ALONE[:2] + self.LEFT_ALONE[6:]))
        self.device.empty_test_folders()  # nothing to empty is no failure



def archive_names(path):
    return set(phone.validate_tar(Path(path).read_bytes()))


class VisitStartTests(PhoneCase):
    """Installing the lab build over release: pull, install, copy, then empty."""

    def test_visit_start_pulls_the_release_installs_copies_and_only_then_empties(self):
        release = self.with_release_installed()
        result = self.call('install', *self.lab_apks())
        self.assertEqual(self.did('pull_apk', 'install', 'copy', 'empty'),
                         [('pull_apk', PKG), ('install', PKG), ('install', TEST), ('copy',), ('empty',)])
        [record] = self.records()
        folder = self.root / 'lab/visits' / record['name']
        self.assertEqual((result['visit'], result['visit_started'], record['ended']), (record['name'], True, None))
        self.assertEqual((folder / 'release.apk').read_bytes(), Path(release).read_bytes())
        self.assertEqual(record['release'], {'apk': 'release.apk', 'sha256': sha(b'release.apk')})
        self.assertEqual(record['copy'], {'archive': 'owner-data.tar', 'sha256': sha((folder / 'owner-data.tar').read_bytes())})
        self.assertEqual(archive_names(folder / 'owner-data.tar'),
                         {'no_backup/history/1.json', 'files/benchmark/take1.wav', 'shared_prefs/p.xml'})
        self.assertEqual(record['fingerprint'], self.device.fingerprint())
        self.assertEqual(record['model'], {'encoder.onnx': sha(b'onnx')})
        self.assertEqual(record['lab_builds'], [{'app_sha256': sha(b'lab-app.apk'), 'test_sha256': sha(b'lab-test.apk'),
                                                 'marker': LAB}])
        self.assertNotIn('files/test/dictionary.json', self.device.data)
        self.assertEqual(self.device.data['files/lab/variant.json'], b'{}', 'the lab folder is not a test folder')
        self.assertEqual(self.call('run', PKG + '.NormalBaselineTest')['outcome'], 'passed')
        self.assertEqual(self.call('app')['visit'], record['name'])

    def test_no_visit_starts_without_the_kept_model_copy(self):
        self.with_release_installed()
        (self.model / 'encoder.onnx').unlink()
        self.model.rmdir()
        self.refused('kept model copy', 'install', *self.lab_apks())
        self.assertEqual((self.did('pull_apk', 'install', 'copy', 'empty'), self.records()), ([], []))

    def test_a_failed_install_leaves_no_visit_behind(self):
        self.with_release_installed()
        self.device.fail = {'install'}
        with self.assertRaises(phone.Failed):
            self.call('install', *self.lab_apks())
        self.assertEqual(list((self.root / 'lab/visits').iterdir()), [])
        self.assertEqual(self.device.packages[PKG]['sha256'], sha(b'release.apk'))

    def test_a_visit_without_its_copy_runs_nothing_until_the_lab_build_is_installed_again(self):
        self.with_release_installed()
        self.device.fail = {'copy'}
        with self.assertRaises(phone.Failed):
            self.call('install', *self.lab_apks())
        self.assertEqual(self.did('empty'), [], 'nothing is emptied before the copy exists')
        self.assertIn('files/test/dictionary.json', self.device.data)
        for argv in (['run', PKG + '.NormalBaselineTest'], ['stop'], ['push', str(self.model)]):
            self.refused('no copy of owner data yet', *argv)
        self.device.fail = set()
        self.device.calls.clear()
        self.call('install', *self.lab_apks())
        self.assertEqual(self.did('pull_apk', 'copy', 'empty'), [('copy',), ('empty',)], 'the release is not pulled again')
        [record] = self.records()
        self.assertEqual(record['release']['sha256'], sha(b'release.apk'))
        self.assertEqual(record['fingerprint'], self.device.fingerprint())
        self.assertEqual(self.call('run', PKG + '.NormalBaselineTest')['outcome'], 'passed')

    def test_a_visit_without_its_copy_lets_only_its_pulled_release_back(self):
        self.with_release_installed()
        self.device.fail = {'copy'}
        with self.assertRaises(phone.Failed):
            self.call('install', *self.lab_apks())
        [record] = self.records()
        self.refused('only its pulled release', 'install', self.release_apk('release-2'), '--promote')
        result = self.call('install', str(self.root / 'lab/visits' / record['name'] / 'release.apk'))
        self.assertEqual((result['visit_ended'], result['promoted']), (record['name'], False))
        self.assertIn('skipped', result['after_check'])
        self.assertIsNotNone(self.records()[0]['ended'])

    def test_a_copy_that_lacks_what_the_fingerprint_sees_is_no_visit_copy(self):
        self.with_release_installed()
        self.device.copy = lambda skip: tar_bytes({'./shared_prefs/p.xml': b'<x/>'})
        with self.assertRaises(phone.Failed) as caught:
            self.call('install', *self.lab_apks())
        self.assertIn('does not hold', str(caught.exception))
        self.assertIsNone(self.records()[0]['copy'])
        self.refused('no copy of owner data yet', 'run', PKG + '.NormalBaselineTest')

    def test_a_copy_reaching_into_the_lab_or_a_test_folder_is_refused(self):
        self.with_release_installed()
        self.device.copy = lambda skip: tar_bytes({'./shared_prefs/p.xml': b'<x/>', './files/lab/encoder.onnx': b'onnx'})
        with self.assertRaises(phone.Refused):
            self.call('install', *self.lab_apks())
        self.assertIsNone(self.records()[0]['copy'])

    def test_lab_over_release_opens_a_new_record_beside_an_older_open_one(self):
        self.visit(name='old', started='2026-11-01T10:00:00')
        self.with_release_installed()
        new = self.call('install', *self.lab_apks())['visit']
        records = {record['name']: record for record in self.records()}
        self.assertEqual(set(records), {'old', new})
        self.assertIsNone(records['old']['ended'])
        self.assertEqual(self.did('pull_apk', 'copy'), [('pull_apk', PKG), ('copy',)])
        self.assertEqual(self.call('app')['visit'], new)
        self.assertEqual(self.call('run', PKG + '.NormalBaselineTest')['outcome'], 'passed')

    def test_a_lab_build_goes_on_only_with_its_test_apk_and_never_as_a_promotion(self):
        self.with_release_installed()
        app, test = self.lab_apks()
        self.refused('test APK', 'install', app)
        self.refused('--promote', 'install', app, test, '--promote')
        self.assertEqual((self.did('install'), self.records()), ([], []))

    def test_a_lab_build_on_a_phone_without_the_app_starts_a_visit_that_pulled_no_release(self):
        self.device.uninstall(PKG)
        self.device.uninstall(TEST)
        self.device.calls.clear()
        self.call('install', *self.lab_apks())
        [record] = self.records()
        self.assertEqual((record['release'], self.did('pull_apk')), (None, []))
        self.refused('--promote', 'install', self.release_apk())


class LabOverLabTests(PhoneCase):
    def test_a_later_lab_build_keeps_the_visit_without_pull_or_copy_and_empties_again(self):
        self.start_visit()
        self.device.data['files/test/dictionary.json'] = b'["written by a test"]'
        result = self.call('install', *self.lab_apks('lab2', OTHER_LAB))
        self.assertEqual(self.did('pull_apk', 'install', 'copy', 'empty'), [('install', PKG), ('install', TEST), ('empty',)])
        self.assertNotIn('files/test/dictionary.json', self.device.data)
        [record] = self.records()
        self.assertEqual((result['visit'], result['visit_started']), (record['name'], False))
        self.assertEqual([build['app_sha256'] for build in record['lab_builds']], [sha(b'lab-app.apk'), sha(b'lab2-app.apk')])
        self.assertEqual(record['lab_builds'][1]['marker'], OTHER_LAB)
        self.assertEqual(self.call('run', PKG + '.NormalBaselineTest')['outcome'], 'passed')
        self.call('install', *self.lab_apks('lab2', OTHER_LAB))
        self.assertEqual(len(self.records()[0]['lab_builds']), 2, 'the same build is listed once')

    def test_refused_when_owner_data_differs_from_the_copy(self):
        self.start_visit()
        self.device.data['shared_prefs/p.xml'] = b'<changed/>'
        self.refused('restore', 'install', *self.lab_apks('lab2', OTHER_LAB))
        self.assertEqual(self.did('install', 'empty'), [])
        self.assertEqual(len(self.records()[0]['lab_builds']), 1)

    def test_fails_naming_the_copy_when_the_update_itself_changed_owner_data(self):
        self.start_visit()
        install = self.device.install

        def update(path, downgrade=False):
            install(path, downgrade)
            self.device.data['no_backup/history/1.json'] = b'{"text":"changed by the update"}'
        self.device.install = update
        with self.assertRaises(phone.Failed) as caught:
            self.call('install', *self.lab_apks('lab2', OTHER_LAB))
        self.assertIn('owner-data.tar', str(caught.exception))
        self.assertEqual(self.did('empty'), [])

    def test_refused_on_a_lab_build_outside_any_visit_or_one_the_visit_does_not_list(self):
        self.refused('outside any Visit', 'install', *self.lab_apks())
        self.visit()
        self.device.place(self.device.build(PKG, OTHER_LAB, True, b'a lab app from elsewhere'))
        self.refused('not part of Visit v1', 'install', *self.lab_apks())
        self.assertEqual(self.did('install'), [])


class VisitEndTests(PhoneCase):
    """Installing the pulled release back: after-check first, promotion only by flag."""

    def test_the_pulled_release_goes_back_after_the_after_check_and_reports_package_facts(self):
        release = self.start_visit()
        self.device.data.update({'files/lab/receipts.jsonl': b'{}', 'files/test/dictionary.json': b'["t"]'})
        result = self.call('install', release)
        self.assertEqual(self.did('uninstall', 'install'), [('install', PKG)])
        self.assertEqual(self.device.packages[PKG]['sha256'], sha(b'release.apk'))
        [record] = self.records()
        self.assertEqual((result['visit_ended'], result['promoted']), (record['name'], False))
        self.assertIsNotNone(record['ended'])
        facts = {'uid': 10123, 'first_install': '2026-10-01 08:00:00', 'microphone_granted': True,
                 'keyboard_enabled': True, 'keyboard_selected': False}
        self.assertEqual((result['facts'], result['facts_before'], result['facts_changed']), (facts, facts, []))
        self.assertIsNone(result['lab_sha256'], 'nothing reads app data once release is installed')
        self.assertEqual(self.call('app')['visit'], None)
        self.refused('release build', 'run', PKG + '.NormalBaselineTest')

    def test_visit_end_leaves_the_test_package_installed_and_nothing_runs_on_release(self):
        release = self.start_visit()
        result = self.call('install', release)
        self.assertEqual(self.did('uninstall'), [], 'uninstalling is the owner\'s, by token')
        self.assertTrue(self.device.packages[TEST]['installed'])
        self.assertEqual((result['test_sha256'], self.call('app')['test']['sha256']), (sha(b'lab-test.apk'),) * 2)
        self.refused('release build', 'run', PKG + '.NormalBaselineTest')
        self.assertEqual(self.did('instrument'), [])

    def test_package_facts_that_changed_are_named(self):
        release = self.start_visit()
        install = self.device.install

        def update(path, downgrade=False):
            install(path, downgrade)
            self.device.packages[PKG].update(granted=[], uid=10999)
            self.device.ime_state['enabled'] = False
        self.device.install = update
        self.assertEqual(self.call('install', release)['facts_changed'], ['keyboard_enabled', 'microphone_granted', 'uid'])

    def test_refused_until_owner_data_equals_the_copy(self):
        release = self.start_visit()
        name = self.records()[0]['name']
        self.device.data['no_backup/history/1.json'] = b'{"text":"changed"}'
        self.refused('restore ' + name, 'install', release)
        self.assertEqual(self.did('uninstall', 'install'), [])
        self.call('restore', name)
        self.assertEqual(self.call('install', release)['visit_ended'], name)

    def test_refused_until_the_speech_model_equals_visit_start(self):
        release = self.start_visit()
        for change in (lambda: self.device.data.update({'files/lab/encoder.onnx': b'another model'}),
                       lambda: self.device.data.pop('files/lab/encoder.onnx')):
            change()
            with self.assertRaises(phone.Refused) as caught:
                self.call('install', release)
            self.assertIn('encoder.onnx', str(caught.exception))
            self.assertIn('--model', str(caught.exception))
            self.assertEqual(self.did('uninstall', 'install'), [])
            self.call('push', str(self.model), '--model')  # recovery: the kept model copy, with the flag
            self.device.calls.clear()
        self.assertIsNotNone(self.call('install', release)['visit_ended'])

    def test_another_release_needs_the_promotion_flag(self):
        self.start_visit()
        other = self.release_apk('release-2')
        self.refused('--promote', 'install', other)
        self.assertEqual(self.did('uninstall', 'install'), [])
        self.assertIsNone(self.records()[0]['ended'])
        result = self.call('install', other, '--promote')
        self.assertEqual((result['promoted'], result['app_sha256']), (True, sha(b'release-2.apk')))
        self.assertIsNotNone(self.records()[0]['ended'])

    def test_a_promotion_still_needs_the_after_check(self):
        self.start_visit()
        self.device.data['shared_prefs/p.xml'] = b'<changed/>'
        self.refused('restore', 'install', self.release_apk('release-2'), '--promote')
        self.assertEqual(self.did('install'), [])

    def test_a_second_visit_after_a_promotion_has_its_own_pull_and_copy_and_the_first_is_kept(self):
        self.start_visit()
        self.call('install', self.release_apk('release-2'), '--promote')
        self.device.data['no_backup/history/2.json'] = b'{"text":"a take on the new release"}'
        self.device.calls.clear()
        self.call('install', *self.lab_apks())
        self.assertEqual(self.did('pull_apk', 'copy'), [('pull_apk', PKG), ('copy',)])
        first, second = self.records()
        self.assertEqual((first['release']['sha256'], second['release']['sha256']), (sha(b'release.apk'), sha(b'release-2.apk')))
        self.assertEqual((first['ended'] is None, second['ended'] is None), (False, True))
        for record, history in ((first, False), (second, True)):
            folder = self.root / 'lab/visits' / record['name']
            self.assertEqual(sha((folder / 'release.apk').read_bytes()), record['release']['sha256'])
            self.assertEqual('no_backup/history/2.json' in archive_names(folder / 'owner-data.tar'), history)
        self.assertEqual(self.call('install', str(self.root / 'lab/visits' / second['name'] / 'release.apk'))['promoted'], False)

    def test_release_over_a_lab_build_outside_any_visit_needs_the_promotion_flag(self):
        release = self.release_apk()
        self.refused('--promote', 'install', release)
        self.assertEqual(self.call('install', release, '--promote')['visit_ended'], None)


class ReleaseInstalledTests(PhoneCase):
    """Nothing may try `run-as` on release: the fake fails it, as the phone does."""

    def setUp(self):
        super().setUp()
        self.release = self.with_release_installed()

    def test_app_and_logs_answer_without_reading_app_data(self):
        info = self.call('app')
        self.assertEqual((info['app']['layout'], info['lab'], info['test']['installed']), ('owner', None, True))
        self.assertEqual(self.call('logs')['logs'], 'log for uid 10123')

    def test_the_same_release_installs_again_and_another_one_only_as_a_promotion(self):
        result = self.call('install', self.release)
        self.assertEqual((result['outcome'], result['promoted'], result['visit_ended']), ('installed', False, None))
        other = self.release_apk('release-2')
        self.refused('--promote', 'install', other)
        self.assertEqual(self.call('install', other, '--promote')['promoted'], True)

    def test_verbs_that_need_app_data_refuse_instead_of_failing(self):
        for argv in (['pull', 'files/benchmark'], ['snapshot', 'now'], ['snapshot', '--switch', 'now'], ['restore', 'v1'],
                     ['run', PKG + '.NormalBaselineTest'], ['push', str(self.model)], ['stop']):
            with self.subTest(argv[0]), self.assertRaises(phone.Refused):
                self.call(*argv)
        self.assertEqual(self.did('fingerprint', 'copy', 'replace', 'push'), [])


class OwnerDataCopyTests(PhoneCase):
    """`snapshot` and `restore` cover owner data only."""

    CHANGES = {'no_backup/history/1.json': b'{"text":"changed"}', 'files/dictionary/revision.json': b'["new"]',
               'files/lab/receipts.jsonl': b'{"run":1}', 'files/lab/encoder.onnx': b'a model pushed during the Visit',
               'files/test/dictionary.json': b'["test"]', 'no_backup/test/history/1.json': b'{}',
               'shared_prefs/test_settings.xml': b'<t/>'}

    def change(self):
        self.device.data.update(self.CHANGES)
        del self.device.data['files/benchmark/take1.wav']

    def test_a_snapshot_holds_owner_data_only(self):
        self.start_visit()
        self.device.data.update({'files/test/dictionary.json': b'[]', 'cache/x': b'x'})
        result = self.call('snapshot', 'mid')
        folder = self.root / 'lab/snapshots/mid'
        self.assertEqual(archive_names(folder / 'data.tar'),
                         {'no_backup/history/1.json', 'files/benchmark/take1.wav', 'shared_prefs/p.xml'})
        self.assertEqual((result['kind'], result['fingerprint']), ('owner', self.device.fingerprint()))
        self.assertEqual(sorted(path.name for path in folder.iterdir()), ['data.tar', 'snapshot.json'])
        self.refused('exists', 'snapshot', 'mid')

    def test_restore_puts_owner_data_back_and_leaves_model_test_output_and_test_folders(self):
        self.start_visit()
        visit = self.records()[0]['name']
        before = dict(self.device.data)
        self.call('snapshot', 'mid')
        for source in ('mid', visit):
            with self.subTest(source):
                self.change()
                self.device.calls.clear()
                result = self.call('restore', source)
                self.assertEqual(result['outcome'], 'restored')
                self.assertEqual(self.did('install', 'uninstall'), [])
                kept = {path: content for path, content in self.CHANGES.items() if path.split('/')[1] in ('lab', 'test')
                        or path.startswith('shared_prefs/test_')}
                self.assertEqual(len(kept), 5)
                self.assertEqual(self.device.data, {**before, **kept})
                self.assertEqual(self.call('run', PKG + '.NormalBaselineTest')['outcome'], 'passed')
                self.device.data.pop('files/lab/result.json')

    def test_restore_refuses_a_copy_that_changed_or_reaches_outside_owner_data(self):
        self.start_visit()
        self.call('snapshot', 'mid')
        folder = self.root / 'lab/snapshots/mid'
        record = json.loads((folder / 'snapshot.json').read_text())
        good = (folder / 'data.tar').read_bytes()
        owner = {'no_backup/history/1.json': b'{"text":"owner"}', 'files/benchmark/take1.wav': b'RIFF', 'shared_prefs/p.xml': b'<x/>'}
        self.assertEqual(phone.tar_fingerprint(tar_bytes(owner)), record['fingerprint'])
        cases = {'other bytes, old digest': (tar_bytes({**owner, 'shared_prefs/p.xml': b'<y/>'}), False),
                 'other bytes, new digest': (tar_bytes({**owner, 'shared_prefs/p.xml': b'<y/>'}), True),
                 'a file less': (tar_bytes({'shared_prefs/p.xml': b'<x/>'}), True),
                 'traversal': (tar_bytes({**owner, '../../etc/passwd': b'x'}), True),
                 'a model file': (tar_bytes({**owner, 'files/lab/encoder.onnx': b'x'}), True),
                 'a test folder file': (tar_bytes({**owner, 'files/test/dictionary.json': b'x'}), True)}
        for case, (payload, with_digest) in cases.items():
            (folder / 'data.tar').write_bytes(payload)
            (folder / 'snapshot.json').write_text(json.dumps({**record, 'data_sha256': sha(payload) if with_digest
                                                               else record['data_sha256']}))
            with self.subTest(case), self.assertRaises(phone.Refused):
                self.call('restore', 'mid')
        self.assertEqual(self.did('replace', 'stop'), [])
        (folder / 'data.tar').write_bytes(good)
        (folder / 'snapshot.json').write_text(json.dumps(record))
        self.assertEqual(self.call('restore', 'mid')['outcome'], 'restored')

    def test_restore_refuses_snapshots_from_before_the_guard_and_unknown_names(self):
        self.start_visit()
        old = self.root / 'lab/snapshots/issue30-before'
        old.mkdir(parents=True)
        payload = tar_bytes({'files/lab/encoder.onnx': b'onnx', 'shared_prefs/p.xml': b'<x/>'})
        (old / 'data.tar').write_bytes(payload)
        (old / 'snapshot.json').write_text(json.dumps({'app_sha256': 'a' * 64, 'data_sha256': sha(payload)}))
        self.refused('before ADR-028', 'restore', 'issue30-before')
        self.refused('no Visit and no snapshot', 'restore', 'nothing')
        self.refused('no switch copy', 'restore', '--switch', 'issue30-before')
        self.assertEqual(self.did('replace'), [])

    def test_restore_fails_when_owner_data_does_not_equal_the_copy_afterwards(self):
        self.start_visit()
        self.device.replace = lambda archive, skip: None
        self.device.data['shared_prefs/p.xml'] = b'<changed/>'
        with self.assertRaises(phone.Failed):
            self.call('restore', self.records()[0]['name'])

    def test_restore_is_refused_while_the_keyboard_is_in_use(self):
        self.start_visit()
        self.device.ime_state['bound'] = True
        self.refused('in use', 'restore', self.records()[0]['name'])


class ModelSafePushTests(PhoneCase):
    """`push` writes the lab folder only and leaves the Visit's speech-model files alone without --model."""

    def setUp(self):
        super().setUp()
        self.start_visit()
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
        result = self.push('--model')
        self.assertEqual(self.device.data['files/lab/encoder.onnx'], b'another model')
        self.assertEqual(result['model_differs_from_visit_start'], ['encoder.onnx'])
        (self.source / 'encoder.onnx').unlink()
        result = self.push('--model')
        self.assertNotIn('files/lab/encoder.onnx', self.device.data)
        self.assertEqual((result['model_files_kept'], result['model_differs_from_visit_start']), (0, ['encoder.onnx']))
        (self.source / 'encoder.onnx').write_bytes(b'onnx')
        self.refused('--model', 'push', str(self.source))  # putting it back is a model write too
        self.assertEqual(self.push('--model')['model_differs_from_visit_start'], [])

    def test_a_file_that_was_no_model_file_at_visit_start_is_not_protected(self):
        (self.source / 'variant.json').write_text('{"v":3}')
        self.assertEqual(self.push()['sent'], 2)
        (self.source / 'variant.json').unlink()
        self.assertEqual(self.push()['removed'], 1)

    def test_push_has_no_target_but_the_lab_folder(self):
        for argv in (['push', str(self.source), 'dictionary'], ['push', str(self.source), '../shared_prefs'], ['push', '--model']):
            with self.assertRaises(phone.Refused):
                self.call(*argv)
        self.assertEqual(self.did('push'), [])


class ForgetTests(PhoneCase):
    def test_forget_deletes_record_release_and_copy_of_an_ended_visit_only(self):
        release = self.start_visit()
        name = self.records()[0]['name']
        folder = self.root / 'lab/visits' / name
        self.refused('has not ended', 'forget', name)
        self.assertTrue((folder / 'owner-data.tar').is_file() and (folder / 'release.apk').is_file())
        kept = self.root / 'kept-release.apk'
        kept.write_bytes(Path(release).read_bytes())
        self.call('install', str(kept))
        self.assertTrue((folder / 'owner-data.tar').is_file(), 'ending a Visit deletes nothing')
        self.assertEqual(self.call('forget', name), {'outcome': 'forgotten', 'visit': name})
        self.assertFalse(folder.exists())
        self.refused('no Visit named', 'forget', name)

    def test_forget_touches_one_visit_and_refuses_other_names(self):
        self.visit(name='old', ended='2026-11-02T10:00:00')
        self.visit(name='open')
        self.call('forget', 'old')
        self.assertEqual([record['name'] for record in self.records()], ['open'])
        for bad in ('../snapshots', 'open/..', '.', 'nothing'):
            with self.subTest(bad), self.assertRaises(phone.Refused):
                self.call('forget', bad)
        self.assertTrue((self.root / 'lab/visits/open/visit.json').is_file())


class SwitchTests(PhoneCase):
    """The one-time switch from the unmarked debuggable build (0.2.5) to marked builds."""

    DATA = {'files/lab/encoder.onnx': b'onnx', 'files/lab/startup.jsonl': b'{"old":"trace"}',
            'files/benchmark/take1.wav': b'RIFF', 'files/dictionary/revision.json': b'["Oriol"]',
            'no_backup/history/1.json': b'{"text":"owner"}', 'shared_prefs/history_notices.xml': b'<n/>',
            'shared_prefs/settings.xml': b'<s/>', 'cache/x': b'x'}

    def setUp(self):
        super().setUp()
        self.device.place(self.device.build(PKG, None, True, b'0.2.5 app'))
        self.device.place(self.device.build(TEST, None, True, b'0.2.5 tests'))
        self.device.data = dict(self.DATA)
        self.device.calls.clear()

    def test_the_switch_copy_holds_all_app_data_but_the_model_and_both_apks(self):
        result = self.call('snapshot', '--switch', 'switch')
        folder = self.root / 'lab/snapshots/switch'
        self.assertEqual(archive_names(folder / 'data.tar'), set(self.DATA) - {'files/lab/encoder.onnx', 'cache/x'})
        self.assertEqual(((folder / 'app.apk').read_bytes(), (folder / 'test.apk').read_bytes()), (b'0.2.5 app', b'0.2.5 tests'))
        self.assertEqual((result['kind'], result['fingerprint'], result['model_files']),
                         ('switch', self.device.fingerprint(), ['files/lab/encoder.onnx']))
        self.assertEqual(self.call('snapshot', 'switch-again', '--switch')['kind'], 'switch')

    def test_the_switch_copy_is_refused_and_not_kept_when_it_lacks_a_part_of_owner_data(self):
        for what, path in (('History', 'no_backup/history/1.json'), ('Dictionary', 'files/dictionary/revision.json'),
                           ('History notices', 'shared_prefs/history_notices.xml'), ('settings', 'shared_prefs/settings.xml')):
            self.device.data = {k: v for k, v in self.DATA.items() if k != path}
            with self.subTest(what):
                self.refused('holds no ' + what, 'snapshot', '--switch', 'switch')
                self.assertFalse((self.root / 'lab/snapshots/switch').exists())

    def test_switch_copies_are_for_the_unmarked_build_and_plain_snapshots_are_not(self):
        self.refused('--switch', 'snapshot', 'plain')
        (self.model / 'encoder.onnx').unlink()
        self.model.rmdir()
        self.refused('kept model copy', 'snapshot', '--switch', 'switch')
        self.assertEqual(self.did('copy'), [])
        self.device.place(self.device.build(PKG, LAB, True, b'lab app'))
        self.refused('unmarked', 'snapshot', '--switch', 'switch')

    def test_the_lab_build_goes_over_the_unmarked_build_only_with_a_current_switch_copy(self):
        lab = self.lab_apks()
        self.refused('no switch copy', 'install', *lab)
        self.call('snapshot', '--switch', 'switch')
        self.device.data['no_backup/history/2.json'] = b'{"text":"a take after the copy"}'
        self.device.calls.clear()
        self.refused('changed since the switch copy switch', 'install', *lab)
        self.assertEqual((self.did('install'), self.records()), ([], []))
        self.call('snapshot', '--switch', 'switch-2')
        self.device.calls.clear()
        result = self.call('install', *lab)
        self.assertEqual(self.did('install', 'copy', 'empty'), [('install', PKG), ('install', TEST), ('copy',), ('empty',)])
        [record] = self.records()
        self.assertEqual((result['visit_started'], record['release']), (True, None))
        self.assertEqual(record['fingerprint'], json.loads((self.root / 'lab/snapshots/switch-2/snapshot.json').read_text())['fingerprint'])
        self.assertEqual(self.call('run', PKG + '.NormalBaselineTest')['outcome'], 'passed')

    def test_a_switch_copy_of_another_apk_does_not_count(self):
        self.call('snapshot', '--switch', 'switch')
        self.device.place(self.device.build(PKG, None, True, b'0.2.4 app'))
        self.refused('no switch copy', 'install', *self.lab_apks())

    def test_the_switch_fails_when_the_update_changed_owner_data(self):
        self.call('snapshot', '--switch', 'switch')
        install = self.device.install

        def update(path, downgrade=False):
            install(path, downgrade)
            self.device.data.pop('files/dictionary/revision.json', None)
        self.device.install = update
        with self.assertRaises(phone.Failed) as caught:
            self.call('install', *self.lab_apks())
        self.assertIn('restore --switch switch', str(caught.exception))
        self.assertIsNone(self.records()[0]['copy'])
        self.refused('no copy of owner data yet', 'run', PKG + '.NormalBaselineTest')

    def test_visit_end_after_a_switch_start_needs_the_promotion_flag(self):
        self.call('snapshot', '--switch', 'switch')
        self.call('install', *self.lab_apks())
        release = self.release_apk()
        self.device.calls.clear()
        self.refused('--promote', 'install', release)
        self.assertEqual(self.did('install', 'uninstall'), [])
        result = self.call('install', release, '--promote')
        self.assertEqual((result['promoted'], result['visit_ended']), (True, self.records()[0]['name']))

    def test_release_never_goes_straight_over_the_unmarked_build_and_nothing_runs_on_it(self):
        self.refused('lab build', 'install', self.release_apk(), '--promote')
        self.refused('no build marker', 'run', PKG + '.NormalBaselineTest')
        self.assertEqual(self.did('install', 'instrument'), [])

    def test_switch_restore_reinstalls_the_unmarked_apks_and_leaves_model_files(self):
        self.call('snapshot', '--switch', 'switch')
        self.call('install', *self.lab_apks())
        self.device.data.update({'no_backup/history/1.json': b'{"text":"changed"}', 'files/lab/encoder.onnx': b'the model now',
                                 'files/lab/startup.jsonl': b'{"new":"trace"}', 'files/test/dictionary.json': b'["test"]',
                                 'shared_prefs/test_settings.xml': b'<t/>', 'databases/new.db': b'x'})
        del self.device.data['shared_prefs/settings.xml']
        self.device.packages[PKG]['granted'] = []
        self.device.calls.clear()
        self.refused('restore --switch switch', 'restore', 'switch')
        result = self.call('restore', '--switch', 'switch')
        self.assertEqual(self.did('install'), [('install', PKG, 'downgrade'), ('install', TEST, 'downgrade')])
        self.assertEqual((self.device.packages[PKG]['sha256'], self.device.packages[TEST]['sha256']), (sha(b'0.2.5 app'), sha(b'0.2.5 tests')))
        system = {path: content for path, content in self.device.data.items() if path.startswith('code_cache/')
                  or path == 'files/profileInstalled'}
        self.assertEqual(self.device.data, {**self.DATA, **system, 'files/lab/encoder.onnx': b'the model now'})
        self.assertEqual(result['not_restored']['granted_permissions_missing'], ['android.permission.RECORD_AUDIO'])
        self.device.calls.clear()
        self.call('restore', '--switch', 'switch')
        self.assertEqual(self.did('install'), [], 'the retained APK is reinstalled only when another build is installed')

    def test_switch_restore_refuses_changed_apks_or_archive(self):
        self.call('snapshot', '--switch', 'switch')
        folder = self.root / 'lab/snapshots/switch'
        good = (folder / 'app.apk').read_bytes()
        (folder / 'app.apk').write_bytes(b'another app')
        self.refused('retained APK', 'restore', '--switch', 'switch')
        (folder / 'app.apk').write_bytes(good)
        record = json.loads((folder / 'snapshot.json').read_text())
        payload = tar_bytes({path: content for path, content in self.DATA.items() if path != 'cache/x'})
        (folder / 'data.tar').write_bytes(payload)
        (folder / 'snapshot.json').write_text(json.dumps({**record, 'data_sha256': sha(payload)}))
        self.refused('outside its place', 'restore', '--switch', 'switch')  # it holds the model file
        self.assertEqual(self.did('install', 'replace'), [])


class UsageTests(PhoneCase):
    def test_help_holds_the_visit_steps_the_switch_and_every_verb(self):
        usage = self.call('help')['usage']
        for text in ('install APP.apk TEST.apk', '--promote', 'push DIR [--model]', 'forget NAME', 'snapshot --switch NAME',
                     'restore --switch NAME', 'corpus/lab/model', 'corpus/lab/visits/NAME/release.apk', 'restore NAME',
                     'files/timing-receipts', 'emptied', 'One-time switch', 'measure CLASS', 'inputs=PATH',
                     'completed or incomplete', 'files/lab/labrun-inputs'):
            self.assertIn(text, usage)
        steps = [usage.index(step) for step in ('the release APK is pulled', 'the lab build installed', 'copied at once',
                                                'test folders are emptied', 'release.apk ends the Visit')]
        self.assertEqual(steps, sorted(steps))
        self.assertNotIn('files/NAME', usage)

    def test_arguments_of_the_lifecycle_verbs(self):
        for argv in (['install'], ['install', '--promote'], ['install', 'a', 'b', 'c'], ['forget'], ['forget', 'a', 'b'],
                     ['snapshot', '--switch'], ['restore', 'a', 'b'], ['push'], ['measure'], ['run']):
            with self.subTest(argv), self.assertRaises(phone.Refused):
                self.call(*argv)


if __name__ == '__main__':
    unittest.main()
