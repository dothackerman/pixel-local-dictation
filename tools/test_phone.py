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
                               'pid': None},
                         TEST: {'installed': False, 'versionCode': 0, 'versionName': None,
                                'apk_path': '/data/app/y/base.apk', 'uid': 10123, 'granted': [], 'pid': None}}
        for futo in phone.FUTO:
            self.packages[futo] = {'installed': False}
        self.data = {'files/lab/variant.json': b'{}', 'files/benchmark/take1.wav': b'RIFF', 'shared_prefs/p.xml': b'<x/>',
                     'no_backup/history/1.json': b'{"text":"owner"}', 'files/test/dictionary.json': b'[]'}
        self.ime_state = {'enabled': True, 'selected': False, 'bound': False, 'input_shown': False}
        self.calls = []
        self.during_run = lambda: None
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
        if not self.builds[self.packages[PKG]['sha256']]['debuggable']:
            raise phone.Failed('run-as: package not debuggable: ' + PKG)

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
        kept = {k: sha(v) for k, v in self.data.items()
                if not any(k == skipped or k.startswith(skipped + '/') for skipped in NOT_FINGERPRINTED)}
        return {'digest': sha(json.dumps(kept, sort_keys=True).encode()), 'files': len(kept)}

    def logcat(self, uid, since=None, crash=False):
        self.calls.append(('logcat', uid, since, crash))
        return 'log for uid %s' % uid

    def device_time(self):
        return '10-21 10:00:00.000'

    def apk_info(self, path):
        return dict(self.builds[sha(Path(path).read_bytes())])

    def install(self, path):
        content = Path(path).read_bytes()
        self.place(content)
        self.calls.append(('install', self.builds[sha(content)]['package']))

    def uninstall(self, pkg):
        self.packages[pkg] = {'installed': False}
        self.calls.append(('uninstall', pkg))

    def stop(self):
        self.calls.append(('stop',))

    def push(self, directory, rel, changed, removed):
        self.run_as()
        self.calls.append(('push', tuple(sorted(changed)), tuple(sorted(removed))))
        for item in removed:
            self.data.pop(rel + '/' + item, None)
        for item in changed:
            self.data[rel + '/' + item] = (Path(directory) / item).read_bytes()

    def tar(self, *rels):
        self.run_as()
        files = {k: v for k, v in self.data.items() if any(k == r or k.startswith(r + '/') for r in rels)}
        return tar_bytes(files) if files else b''

    def pull_apk(self, remote, local):
        for pkg, info in self.packages.items():
            if info.get('apk_path') == remote:
                Path(local).write_bytes(self.contents[pkg])
                self.calls.append(('pull_apk', pkg))

    def replace_data(self, archive):
        self.run_as()
        self.data = {}
        if archive:
            with tarfile.open(fileobj=io.BytesIO(archive)) as a:
                for m in a:
                    if m.isfile():
                        self.data[m.name] = a.extractfile(m).read()

    def instrument(self, classes, extras, timeout):
        self.calls.append(('instrument', classes, tuple(extras)))
        self.data['files/lab/result.json'] = b'{"ok":true}'
        self.during_run()
        return 'INSTRUMENTATION_STATUS_CODE: 0\nOK (1 test)\nINSTRUMENTATION_CODE: -1\n'


class PhoneCase(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.device = FakeDevice()
        self.phone = phone.Phone(self.device, self.root / 'lab')

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

    def did(self, kind):
        return [c for c in self.device.calls if c[0] == kind]


class PhoneTests(PhoneCase):
    def test_read_only_verbs_expose_only_our_app_and_allowlisted_facts(self):
        info = self.call('app')
        self.assertEqual(set(info), {'app', 'test', 'ime', 'futo', 'lab'})
        self.assertNotIn('apk_path', info['app'])
        self.assertNotIn('apk_path', info['test'])
        self.assertEqual(set(info['futo']), set(phone.FUTO))
        device = self.call('device')
        self.assertEqual(set(device['props']), set(phone.PROPS))

    def test_pull_is_limited_to_lab_and_benchmark_and_refuses_traversal(self):
        result = self.call('pull', 'files/lab', 'files/benchmark')
        self.assertEqual(result['files'], 2)
        self.assertTrue((Path(result['path']) / 'files/benchmark/take1.wav').exists())
        single = self.call('pull', 'files/benchmark/take1.wav', '--to', str(self.root / 'one'))
        self.assertEqual(single['files'], 1)
        for bad in ('shared_prefs', 'files/lab/../../shared_prefs', 'files/../shared_prefs/p.xml', '/files/lab'):
            with self.assertRaises(phone.Refused):
                self.call('pull', bad)

    def test_push_replaces_lab_and_refuses_symlinks(self):
        self.visit()
        source = self.root / 'src'
        (source / 'model').mkdir(parents=True)
        (source / 'model/encoder.onnx').write_bytes(b'onnx')
        (source / 'encoder-probe.json').write_text('{}')
        result = self.call('push', str(source))
        self.assertEqual((result['outcome'], result['files'], result['sent'], result['removed']), ('pushed', 2, 2, 1))
        self.assertEqual(set(self.device.inventory('files/lab')), {'model/encoder.onnx', 'encoder-probe.json'})
        # Unchanged directory: nothing is sent. One edited file: only that file travels.
        self.assertEqual(self.call('push', str(source))['outcome'], 'unchanged')
        (source / 'encoder-probe.json').write_text('{"v":2}')
        again = self.call('push', str(source))
        self.assertEqual((again['outcome'], again['sent'], again['removed']), ('pushed', 1, 0))
        self.assertEqual(self.did('push')[-1], ('push', ('encoder-probe.json',), ()))
        (source / 'leak').symlink_to('/etc/hostname')
        with self.assertRaises(phone.Refused):
            self.call('push', str(source))
        with self.assertRaises(phone.Refused):
            self.call('push', str(source), '../shared_prefs')

    def test_run_records_output_logcat_and_lab_inventory(self):
        self.visit()
        result = self.call('run', PKG + '.LabTest', 'provider=webgpu')
        self.assertEqual(result['outcome'], 'passed')
        self.assertIn('result.json', result['lab'])
        folder = Path(result['path'])
        self.assertTrue((folder / 'meta.json').exists() and (folder / 'logcat.txt').exists())
        self.assertEqual(self.did('instrument'), [('instrument', PKG + '.LabTest', (('provider', 'webgpu'),))])
        self.assertEqual(self.did('logcat')[0][:2], ('logcat', 10123))
        meta = json.loads((folder / 'meta.json').read_text())
        self.assertEqual(set(meta) >= {'verb', 'ts', 'app_sha256', 'test_sha256', 'lab_sha256'}, True)
        for bad in (['run', 'Foo;rm'], ['run', PKG + '.LabTest', 'noequals'], ['run', PKG + '.LabTest', '1bad=x']):
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

    def test_snapshot_and_restore_round_trip_and_report_what_is_not_restored(self):
        snap = self.call('snapshot', 'baseline')
        self.assertEqual(snap['outcome'], 'snapshotted')
        with self.assertRaises(phone.Refused):
            self.call('snapshot', 'baseline')
        self.device.data['files/lab/extra.bin'] = b'x'
        self.device.place(self.device.build(PKG, LAB, True, b'a later lab app'))
        self.device.packages[PKG]['granted'] = []
        self.device.calls.clear()
        result = self.call('restore', 'baseline')
        self.assertEqual(result['outcome'], 'restored')
        self.assertNotIn('files/lab/extra.bin', self.device.data)
        self.assertEqual(self.device.data['shared_prefs/p.xml'], b'<x/>')
        self.assertEqual(result['not_restored']['granted_permissions_missing'], ['android.permission.RECORD_AUDIO'])
        self.assertEqual(self.did('install'), [('install', PKG)])

    def test_restore_refuses_tampered_snapshot(self):
        self.call('snapshot', 'base')
        folder = self.root / 'lab/snapshots/base'
        (folder / 'data.tar').write_bytes(tar_bytes({'../../etc/passwd': b'x'}))
        with self.assertRaises(phone.Refused):
            self.call('restore', 'base')
        record = json.loads((folder / 'snapshot.json').read_text())
        record['data_sha256'] = sha((folder / 'data.tar').read_bytes())
        (folder / 'snapshot.json').write_text(json.dumps(record))
        with self.assertRaises(phone.Refused):  # traversal member
            self.call('restore', 'base')

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
        self.call('install', self.apk('app.apk', PKG, LAB, True), self.apk('test.apk', TEST, LAB, True))
        self.visit()
        self.assertEqual(self.call('run', PKG + '.LabTest')['outcome'], 'passed')
        self.assertEqual(self.did('pull_apk'), [])

    def test_an_installed_build_nobody_recorded_is_read_from_its_pulled_apk_once(self):
        self.visit()
        self.assertEqual(self.call('run', PKG + '.LabTest')['outcome'], 'passed')
        self.assertEqual(self.did('pull_apk'), [('pull_apk', PKG), ('pull_apk', TEST)])
        self.device.calls.clear()
        self.call('run', PKG + '.LabTest')
        self.assertEqual(self.did('pull_apk'), [])


class VisitGuardTests(PhoneCase):
    """`run`, `push`, `stop` only in an open Visit on a lab build whose owner data equals the Visit copy."""

    def verbs(self):
        folder = self.root / 'push-source'
        folder.mkdir(exist_ok=True)
        (folder / 'new.json').write_text('{}')
        return {'run': ['run', PKG + '.LabTest'], 'push': ['push', str(folder)], 'stop': ['stop']}

    def assert_all_refused(self, expected):
        before = dict(self.device.data)
        for verb, argv in self.verbs().items():
            with self.subTest(verb):
                with self.assertRaises(phone.Refused) as caught:
                    self.call(*argv)
                self.assertIn(expected, str(caught.exception))
        self.assertEqual(self.did('instrument') + self.did('push') + self.did('stop'), [])
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

    def test_run_is_refused_while_the_keyboard_is_in_use(self):
        self.visit()
        self.device.ime_state['bound'] = True
        with self.assertRaises(phone.Refused) as caught:
            self.call('run', PKG + '.LabTest')
        self.assertIn('in use', str(caught.exception))
        self.assertEqual(self.did('instrument'), [])

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
            self.call('run', PKG + '.LabTest')
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
        self.assertEqual(self.call('run', PKG + '.LabTest')['outcome'], 'passed')


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
        for verb in (['status'], ['device'], ['app'], ['crash'], ['logs'], ['stop'], ['uninstall'], ['run', PKG + '.LabTest'],
                     ['push', str(outside)], ['pull', 'files/lab'], ['snapshot', 'a'], ['restore', 'a'],
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

    def test_replace_data_uploads_before_wiping_and_never_streams_stdin(self):
        device, runner = self.device()
        device.replace_data(b'archive')
        joined = [' '.join(a) for a in runner.argv]
        upload = next(i for i, j in enumerate(joined) if ' push ' in j and '/data/local/tmp/' in j)
        wipe = next(i for i, j in enumerate(joined) if 'rm -rf -- files shared_prefs databases' in j)
        self.assertLess(upload, wipe)
        self.assertIn('tar xf /data/local/tmp/', joined[wipe])
        self.assertFalse(any('exec-out' in j for j in joined))
        self.assertTrue(any(j.endswith('rm -f -- ' + joined[upload].split()[-1]) for j in joined[wipe:]))

    def test_failed_upload_leaves_app_data_untouched(self):
        device, runner = self.device({' push ': (1, '', 'no space left')})
        with self.assertRaises(phone.Failed):
            device.replace_data(b'archive')
        self.assertFalse(any('rm -rf -- files' in ' '.join(a) for a in runner.argv))

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
        for bad in ({'files/../x': b''}, {'/files/x': b''}, {'cache/x': b''}):
            with self.assertRaises(phone.Refused):
                phone.validate_tar(tar_bytes(bad))
        buffer = io.BytesIO()
        with tarfile.open(fileobj=buffer, mode='w') as archive:
            link = tarfile.TarInfo('files/lab/link')
            link.type = tarfile.SYMTYPE
            link.linkname = '/etc/passwd'
            archive.addfile(link)
        with self.assertRaises(phone.Refused):
            phone.validate_tar(buffer.getvalue())


if __name__ == '__main__':
    unittest.main()
