"""Phone wrapper behaviour through its adapter seam; never contacts a phone.

FakeDevice stands in for the adb adapter. ArgvDevice drives the real adapter
with a scripted `runner` to check the exact adb argv it builds.
"""
import hashlib
import importlib.machinery
import importlib.util
import io
import json
import subprocess
import tarfile
import tempfile
import unittest
from pathlib import Path

SOURCE = Path(__file__).with_name('phone')
loader = importlib.machinery.SourceFileLoader('phone_module', str(SOURCE))
spec = importlib.util.spec_from_loader(loader.name, loader)
phone = importlib.util.module_from_spec(spec)
loader.exec_module(phone)

PKG, TEST = phone.PKG, phone.TEST


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
    """In-memory phone: app data as {relpath: bytes}, APKs as {package: info}."""

    def __init__(self):
        self.packages = {PKG: {'installed': True, 'versionCode': 12, 'versionName': '1', 'sha256': 'a' * 64,
                               'apk_path': '/data/app/x/base.apk', 'uid': 10123, 'granted': ['android.permission.RECORD_AUDIO'],
                               'pid': None},
                         TEST: {'installed': True, 'versionCode': 0, 'versionName': None, 'sha256': 'b' * 64,
                                'apk_path': '/data/app/y/base.apk', 'uid': 10123, 'granted': [], 'pid': None}}
        for futo in phone.FUTO:
            self.packages[futo] = {'installed': False}
        self.data = {'files/lab/variant.json': b'{}', 'files/benchmark/take1.wav': b'RIFF', 'shared_prefs/p.xml': b'<x/>'}
        self.ime_state = {'enabled': True, 'selected': False, 'bound': False, 'input_shown': False}
        self.calls = []
        self.apks = {}  # host path -> info

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
        files = {k[len(rel) + 1:]: sha(v) for k, v in self.data.items() if k.startswith(rel + '/')}
        return files or None

    def logcat(self, uid, since=None, crash=False):
        self.calls.append(('logcat', uid, since, crash))
        return 'log for uid %s' % uid

    def device_time(self):
        return '10-21 10:00:00.000'

    def apk_info(self, path):
        return dict(self.apks[str(path)])

    def install(self, path):
        info = self.apks[str(path)]
        self.packages[info['package']].update(installed=True, sha256=info['sha256'])
        self.calls.append(('install', info['package']))

    def uninstall(self, pkg):
        self.packages[pkg] = {'installed': False}
        self.calls.append(('uninstall', pkg))

    def stop(self):
        self.calls.append(('stop',))

    def push(self, directory, rel, changed, removed):
        self.calls.append(('push', tuple(sorted(changed)), tuple(sorted(removed))))
        for item in removed:
            self.data.pop(rel + '/' + item, None)
        for item in changed:
            self.data[rel + '/' + item] = (Path(directory) / item).read_bytes()

    def tar(self, *rels):
        files = {k: v for k, v in self.data.items() if any(k == r or k.startswith(r + '/') for r in rels)}
        return tar_bytes(files) if files else b''

    def pull_apk(self, remote, local):
        for pkg, info in self.packages.items():
            if info.get('apk_path') == remote:
                Path(local).write_bytes(pkg.encode())

    def replace_data(self, archive):
        self.data = {}
        if archive:
            with tarfile.open(fileobj=io.BytesIO(archive)) as a:
                for m in a:
                    if m.isfile():
                        self.data[m.name] = a.extractfile(m).read()

    def instrument(self, classes, extras, timeout):
        self.calls.append(('instrument', classes, tuple(extras)))
        self.data['files/lab/result.json'] = b'{"ok":true}'
        return 'INSTRUMENTATION_STATUS_CODE: 0\nOK (1 test)\nINSTRUMENTATION_CODE: -1\n'


class PhoneTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.device = FakeDevice()
        self.phone = phone.Phone(self.device, self.root / 'lab')
        # FakeDevice.pull_apk writes the package name as APK bytes; align the installed hashes.
        for pkg in (PKG, TEST):
            self.device.packages[pkg]['sha256'] = sha(pkg.encode())

    def call(self, *argv):
        return phone.dispatch(list(argv), self.phone)

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
        self.assertEqual(self.device.calls[-1], ('push', ('encoder-probe.json',), ()))
        (source / 'leak').symlink_to('/etc/hostname')
        with self.assertRaises(phone.Refused):
            self.call('push', str(source))
        with self.assertRaises(phone.Refused):
            self.call('push', str(source), '../shared_prefs')

    def test_run_records_output_logcat_and_lab_inventory(self):
        result = self.call('run', PKG + '.LabTest', 'provider=webgpu')
        self.assertEqual(result['outcome'], 'passed')
        self.assertIn('result.json', result['lab'])
        folder = Path(result['path'])
        self.assertTrue((folder / 'meta.json').exists() and (folder / 'logcat.txt').exists())
        self.assertEqual(self.device.calls[0], ('instrument', PKG + '.LabTest', (('provider', 'webgpu'),)))
        self.assertEqual(self.device.calls[1][:2], ('logcat', 10123))
        meta = json.loads((folder / 'meta.json').read_text())
        self.assertEqual(set(meta) >= {'verb', 'ts', 'app_sha256', 'test_sha256', 'lab_sha256'}, True)
        for bad in (['run', 'Foo;rm'], ['run', PKG + '.LabTest', 'noequals'], ['run', PKG + '.LabTest', '1bad=x']):
            with self.assertRaises(phone.Refused):
                self.call(*bad)

    def test_check_accepts_or_refuses_apks_without_touching_the_phone(self):
        app, test = self.root / 'app.apk', self.root / 'test.apk'
        app.write_bytes(b'app')
        test.write_bytes(b'test')
        self.device.apks = {str(app): {'package': PKG, 'sha256': sha(b'app'), 'signer': 's', 'versionCode': 12, 'permissions': []},
                            str(test): {'package': TEST, 'sha256': sha(b'test'), 'signer': 'other', 'versionCode': 0, 'permissions': []}}
        with self.assertRaises(phone.Refused):
            self.call('check', str(app), str(test))
        self.device.apks[str(test)]['signer'] = 's'
        result = self.call('check', str(app), str(test))
        self.assertEqual([r['package'] for r in result], [PKG, TEST])
        self.assertEqual(self.device.calls, [])

    def test_install_refuses_network_permissions_foreign_packages_and_live_keyboard(self):
        app, test = self.root / 'app.apk', self.root / 'test.apk'
        app.write_bytes(b'app')
        test.write_bytes(b'test')
        self.device.apks = {str(app): {'package': PKG, 'sha256': sha(b'app'), 'signer': 's', 'versionCode': 12, 'permissions': []},
                            str(test): {'package': TEST, 'sha256': sha(b'test'), 'signer': 's', 'versionCode': 0, 'permissions': []}}
        self.device.ime_state['bound'] = True
        with self.assertRaises(phone.Refused):
            self.call('install', str(app), str(test))
        self.device.ime_state['bound'] = False
        self.device.apks[str(test)]['signer'] = 'other'
        with self.assertRaises(phone.Refused):
            self.call('install', str(app), str(test))
        self.device.apks[str(test)]['signer'] = 's'
        result = self.call('install', str(app), str(test))
        self.assertEqual(result['outcome'], 'installed')
        self.assertEqual(result['app_sha256'], sha(b'app'))
        self.assertEqual([c for c in self.device.calls if c[0] == 'install'], [('install', PKG), ('install', TEST)])

    def test_snapshot_and_restore_round_trip_and_report_what_is_not_restored(self):
        snap = self.call('snapshot', 'baseline')
        self.assertEqual(snap['outcome'], 'snapshotted')
        with self.assertRaises(phone.Refused):
            self.call('snapshot', 'baseline')
        self.device.data['files/lab/extra.bin'] = b'x'
        self.device.packages[PKG]['sha256'] = 'changed'
        self.device.packages[PKG]['granted'] = []
        self.device.apks[str(self.root / 'lab/snapshots/baseline/app.apk')] = {'package': PKG, 'sha256': sha(PKG.encode()), 'signer': 's'}
        result = self.call('restore', 'baseline')
        self.assertEqual(result['outcome'], 'restored')
        self.assertNotIn('files/lab/extra.bin', self.device.data)
        self.assertEqual(self.device.data['shared_prefs/p.xml'], b'<x/>')
        self.assertEqual(result['not_restored']['granted_permissions_missing'], ['android.permission.RECORD_AUDIO'])
        self.assertEqual([c for c in self.device.calls if c[0] == 'install'], [('install', PKG)])

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
