"""Behavior at the phone command interface; never connects to a phone."""
import importlib.machinery
import importlib.util
from pathlib import Path
import tempfile
import unittest
import hashlib
import shutil
import threading
import json
import subprocess


SOURCE = Path(__file__).with_name('phone')
loader = importlib.machinery.SourceFileLoader('phone_commands', str(SOURCE))
spec = importlib.util.spec_from_loader(loader.name, loader)
phone = importlib.util.module_from_spec(spec)
loader.exec_module(phone)


class InaccessiblePhone:
    """Scripted adapter at the external device seam; no internal mocks."""
    def inspect(self):
        return {'device': 'pixel-test', 'apk': {'sha256': 'a' * 64},
                'lab': {'accessible': False}}


class InspectionTests(unittest.TestCase):
    def test_missing_private_access_does_not_claim_a_recoverable_baseline(self):
        with tempfile.TemporaryDirectory() as directory:
            result = phone.run(['inspect'], device=InaccessiblePhone(), state=Path(directory))
            self.assertEqual(result['lab_status'], 'inaccessible')
            self.assertFalse(result['recoverable'])
            self.assertEqual(list(Path(directory).iterdir()), [])


class DirectoryPhone:
    """External adapter with real temporary files; no internal collaborators mocked."""
    def __init__(self, root):
        self.root = root
        self.serial = 'pixel-test'
        self.apk = {'sha256': 'a' * 64, 'version_code': 12}
        self.token = None
        self.disconnect_after_move = None
        self.corrupt_stage = False
        self.disconnect_on_removal = False
        self.copy_markers = set()

    def inventory(self, name):
        path = self.root / name
        return {'accessible': True, 'exists': path.exists(), 'files': {
            p.relative_to(path).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
            for p in sorted(path.rglob('*')) if p.is_file()}}

    def inspect(self):
        return dict(device=self.serial, apk=dict(self.apk), owner=self.token,
                    **{name: self.inventory(name) for name in ('lab', 'original', 'stage', 'discard', 'disposal')})

    def idle(self):
        pass

    def claim(self, token):
        if self.token not in (None, token):
            raise phone.Refused('different owner')
        self.token = token

    def release(self, token):
        if self.token != token:
            raise phone.Refused('different owner')
        self.token = None

    def stage(self, source, copy_id):
        self.copy_markers.add(copy_id)
        shutil.copytree(source, self.root / 'stage')
        if self.corrupt_stage:
            (self.root / 'stage' / 'model' / 'encoder.onnx').write_bytes(b'bad')

    def stage_quiescent(self, copy_id):
        self.assert_copy_marker(copy_id)

    def cleanup_copy(self, copy_id):
        self.assert_copy_marker(copy_id)
        self.copy_markers.discard(copy_id)

    def assert_copy_marker(self, copy_id):
        if not isinstance(copy_id, str) or len(copy_id) != 32:
            raise AssertionError('invalid copy marker')

    def move(self, source, target):
        (self.root / source).rename(self.root / target)
        if self.disconnect_after_move == target:
            self.disconnect_after_move = None
            raise ConnectionError('disconnected after completed device move')

    def remove(self, name):
        path = self.root / name
        if self.disconnect_on_removal and (path / 'variant.json').exists():
            (path / 'variant.json').unlink()
            self.disconnect_on_removal = False
            raise ConnectionError('disconnected during recursive deletion')
        shutil.rmtree(self.root / name, ignore_errors=True)

    def cleanup_transfer(self, token):
        pass

    def instrument(self, test_apk, full=False):
        (self.root / 'lab' / 'result.json').write_text('{"takes":[{"text":"synthetic"}]}')
        return 'INSTRUMENTATION_STATUS_CODE: 0\nOK (1 test)\nINSTRUMENTATION_CODE: -1'

    def clear_result(self):
        (self.root / 'lab' / 'result.json').unlink(missing_ok=True)

    def result(self):
        return (self.root / 'lab' / 'result.json').read_bytes()

    def apk_info(self, path):
        return dict(sha256=hashlib.sha256(path.read_bytes()).hexdigest(), version_code=12,
                    package='ch.og.dictation', payload='same-code', signer='same-signer',
                    manifest='same-behavior')

    def install(self, path):
        info = self.apk_info(path)
        self.apk = {k: info[k] for k in ('sha256', 'version_code')}


class LabTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.device = DirectoryPhone(self.root / 'device')
        self.device.root.mkdir()
        self.state = self.root / 'receipts'
        self.candidate = self.root / 'candidate'
        self.write_variant(self.candidate, b'new')

    def write_variant(self, directory, model):
        (directory / 'model').mkdir(parents=True)
        (directory / 'model' / 'encoder.onnx').write_bytes(model)
        (directory / 'variant.json').write_text('{"name":"synthetic"}')

    def command(self, *args):
        return phone.run(list(args), device=self.device, state=self.state)

    def test_recovery_returns_original_lab_after_candidate_staging(self):
        self.write_variant(self.device.root / 'lab', b'old')
        original = self.command('inspect')['lab']
        self.command('push-variant', str(self.candidate), 'run-one')
        self.assertNotEqual(self.command('inspect')['lab'], original)
        self.command('recover', 'run-one')
        self.assertEqual(self.command('inspect')['lab'], original)
        self.assertEqual(self.device.copy_markers, set())

    def test_unfinished_copy_must_be_quiescent_before_recovery_changes_lab(self):
        class DetachedCopy(DirectoryPhone):
            copying = True
            def stage(self, source, copy_id):
                super().stage(source, copy_id)
                raise ConnectionError('host disconnected while copy may still run')
            def stage_quiescent(self, copy_id):
                super().stage_quiescent(copy_id)
                if self.copying:
                    raise phone.Refused('copy may still be running')
        self.device = DetachedCopy(self.device.root)
        self.write_variant(self.device.root / 'lab', b'old')
        original = self.command('inspect')['lab']
        with self.assertRaises(ConnectionError):
            self.command('push-variant', str(self.candidate), 'run-one')
        with self.assertRaises(phone.Refused):
            self.command('recover', 'run-one')
        self.assertEqual(self.command('inspect')['lab'], original)
        self.assertTrue(self.command('inspect')['stage']['exists'])
        self.device.copying = False
        self.command('recover', 'run-one')
        self.assertEqual(self.command('inspect')['lab'], original)
        self.assertEqual(self.device.copy_markers, set())

    def test_multiple_candidates_keep_the_initial_baseline(self):
        self.write_variant(self.device.root / 'lab', b'old')
        original = self.command('inspect')['lab']
        self.command('push-variant', str(self.candidate), 'run-one')
        (self.candidate / 'model' / 'encoder.onnx').write_bytes(b'next')
        self.command('push-variant', str(self.candidate), 'run-one')
        self.command('recover', 'run-one')
        self.assertEqual(self.command('inspect')['lab'], original)

    def test_disconnect_between_directory_moves_is_recoverable_and_retryable(self):
        self.write_variant(self.device.root / 'lab', b'old')
        original = self.command('inspect')['lab']
        self.device.disconnect_after_move = 'original'
        with self.assertRaises(ConnectionError):
            self.command('push-variant', str(self.candidate), 'run-one')
        self.command('recover', 'run-one')
        self.assertEqual(self.command('inspect')['lab'], original)
        self.assertFalse(self.command('inspect')['stage']['exists'])
        self.assertEqual(self.command('recover', 'run-one')['outcome'], 'recovered')

    def test_recovery_refuses_a_different_device_without_touching_its_lab(self):
        self.write_variant(self.device.root / 'lab', b'old')
        self.command('push-variant', str(self.candidate), 'run-one')
        candidate = self.command('inspect')['lab']
        self.device.serial = 'different-pixel'
        with self.assertRaises(phone.Refused):
            self.command('recover', 'run-one')
        self.assertEqual(self.command('inspect')['lab'], candidate)

    def test_conflicting_run_and_changed_lab_are_refused_without_overwrite(self):
        self.write_variant(self.device.root / 'lab', b'old')
        self.command('push-variant', str(self.candidate), 'run-one')
        with self.assertRaises(phone.Refused):
            self.command('push-variant', str(self.candidate), 'run-two')
        (self.device.root / 'lab' / 'model' / 'encoder.onnx').write_bytes(b'external edit')
        with self.assertRaises(phone.Refused):
            self.command('recover', 'run-one')
        self.assertEqual((self.device.root / 'lab' / 'model' / 'encoder.onnx').read_bytes(), b'external edit')

    def test_same_size_corrupt_transfer_keeps_original_and_can_be_recovered(self):
        self.write_variant(self.device.root / 'lab', b'old')
        original = self.command('inspect')['lab']
        self.device.corrupt_stage = True
        with self.assertRaises(phone.Refused):
            self.command('push-variant', str(self.candidate), 'run-one')
        self.assertEqual(self.command('inspect')['lab'], original)
        self.command('recover', 'run-one')
        self.assertEqual(self.command('inspect')['lab'], original)

    def test_invalid_run_token_and_symlink_candidate_are_refused_before_writes(self):
        with self.assertRaises(phone.Refused):
            self.command('push-variant', str(self.candidate), '../escape')
        self.assertFalse(self.state.exists())
        (self.candidate / 'model' / 'link.onnx').symlink_to('/etc/hosts')
        with self.assertRaises(phone.Refused):
            self.command('push-variant', str(self.candidate), 'run-one')
        self.assertFalse(self.state.exists())

    def test_overlapping_commands_are_refused_before_the_second_observes_device(self):
        entered, release = threading.Event(), threading.Event()
        outer = self
        class BlockingPhone(DirectoryPhone):
            def inspect(self):
                if threading.current_thread().name == 'first-operation':
                    entered.set()
                    release.wait(3)
                return super().inspect()
        self.device = BlockingPhone(self.device.root)
        errors = []
        def first():
            try:
                outer.command('push-variant', str(outer.candidate), 'run-one')
            except Exception as error:
                errors.append(error)
        worker = threading.Thread(target=first, name='first-operation')
        worker.start()
        self.assertTrue(entered.wait(2))
        try:
            with self.assertRaises(phone.Refused):
                self.command('push-variant', str(self.candidate), 'run-two')
        finally:
            release.set()
            worker.join(4)
        self.assertEqual(errors, [])

    def test_lab_results_can_be_collected_without_losing_original_baseline(self):
        self.write_variant(self.device.root / 'lab', b'old')
        original = self.command('inspect')['lab']
        self.command('push-variant', str(self.candidate), 'run-one')
        apk = self.root / 'test.apk'
        apk.write_bytes(b'synthetic instrumentation')
        self.command('lab', 'run-one', str(apk))
        result = self.command('pull-results', 'run-one')
        self.assertEqual(json.loads(Path(result['path']).read_text())['takes'][0]['text'], 'synthetic')
        self.command('recover', 'run-one')
        self.assertEqual(self.command('inspect')['lab'], original)

    def test_build_and_lab_can_be_recovered_in_one_session(self):
        baseline, candidate = self.root / 'original.apk', self.root / 'candidate.apk'
        baseline.write_bytes(b'original apk')
        candidate.write_bytes(b'candidate apk')
        self.device.install(baseline)
        original_apk = self.command('inspect')['apk']
        self.write_variant(self.device.root / 'lab', b'old')
        original_lab = self.command('inspect')['lab']
        self.command('install', str(candidate), str(baseline), 'run-one')
        self.command('push-variant', str(self.candidate), 'run-one')
        self.command('recover', 'run-one')
        observed = self.command('inspect')
        self.assertEqual(observed['apk'], original_apk)
        self.assertEqual(observed['lab'], original_lab)

    def test_inspection_does_not_claim_recoverability_after_device_drift(self):
        self.command('push-variant', str(self.candidate), 'run-one')
        self.device.serial = 'different-pixel'
        self.assertFalse(self.command('inspect')['recoverable'])

    def test_a_completed_run_identity_cannot_replace_its_original_receipt(self):
        self.command('push-variant', str(self.candidate), 'run-one')
        self.command('recover', 'run-one')
        with self.assertRaises(phone.Refused):
            self.command('push-variant', str(self.candidate), 'run-one')

    def test_install_refuses_manifest_behavior_changes(self):
        class ChangedManifest(DirectoryPhone):
            def apk_info(self, path):
                metadata = super().apk_info(path)
                if path.read_bytes() == b'candidate apk':
                    metadata['manifest'] = 'different exported application behavior'
                return metadata
        self.device = ChangedManifest(self.device.root)
        baseline, candidate = self.root / 'original.apk', self.root / 'candidate.apk'
        baseline.write_bytes(b'original apk')
        candidate.write_bytes(b'candidate apk')
        self.device.install(baseline)
        original = self.command('inspect')['apk']
        with self.assertRaises(phone.Refused):
            self.command('install', str(candidate), str(baseline), 'run-one')
        self.assertEqual(self.command('inspect')['apk'], original)

    def test_receipt_directory_cannot_redirect_writes_through_a_symlink(self):
        elsewhere = self.root / 'unrelated'
        elsewhere.mkdir()
        self.state.symlink_to(elsewhere, target_is_directory=True)
        with self.assertRaises(phone.Refused):
            self.command('push-variant', str(self.candidate), 'run-one')
        self.assertEqual(list(elsewhere.iterdir()), [])

    def test_foreign_device_owner_refuses_staging_before_changing_lab(self):
        self.write_variant(self.device.root / 'lab', b'old')
        original = self.command('inspect')['lab']
        self.device.token = 'stale-owner'
        with self.assertRaises(phone.Refused):
            self.command('push-variant', str(self.candidate), 'new-run')
        self.assertEqual(self.command('inspect')['lab'], original)
        self.assertFalse((self.state / 'active.json').exists())

    def test_install_uses_verified_private_snapshot_when_build_output_changes(self):
        candidate, original = self.root / 'candidate.apk', self.root / 'original.apk'
        candidate.write_bytes(b'candidate apk')
        original.write_bytes(b'original apk')
        class ChangingBuild(DirectoryPhone):
            def idle(self):
                candidate.write_bytes(b'incompatible concurrent build')
        self.device = ChangingBuild(self.device.root)
        self.device.install(original)
        expected = hashlib.sha256(b'candidate apk').hexdigest()
        self.command('install', str(candidate), str(original), 'run-one')
        self.assertEqual(self.command('inspect')['apk']['sha256'], expected)
        self.command('recover', 'run-one')
        self.assertEqual(self.command('inspect')['apk']['sha256'], hashlib.sha256(b'original apk').hexdigest())

    def test_recovery_retry_finishes_after_original_release_removes_private_access(self):
        original, candidate = self.root / 'original.apk', self.root / 'candidate.apk'
        original.write_bytes(b'original apk')
        candidate.write_bytes(b'candidate apk')
        original_hash = hashlib.sha256(b'original apk').hexdigest()
        class ReleasePhone(DirectoryPhone):
            private_available = True
            disconnect_after_release = False
            def inspect(self):
                if self.private_available:
                    return super().inspect()
                return dict(device=self.serial, apk=dict(self.apk), owner=None,
                            **{name: {'accessible': False} for name in ('lab','original','stage','discard','disposal')})
            def inventory(self, name):
                if not self.private_available:
                    raise phone.Refused('release private storage is inaccessible')
                return super().inventory(name)
            def install(self, path):
                super().install(path)
                if self.disconnect_after_release and self.apk['sha256'] == original_hash:
                    self.private_available = False
                    self.disconnect_after_release = False
                    raise ConnectionError('connection lost after installing original release')
        self.device = ReleasePhone(self.device.root)
        self.device.install(original)
        self.write_variant(self.device.root / 'lab', b'old')
        self.command('install', str(candidate), str(original), 'run-one')
        self.command('push-variant', str(self.candidate), 'run-one')
        self.device.disconnect_after_release = True
        with self.assertRaises(ConnectionError):
            self.command('recover', 'run-one')
        self.assertEqual(self.command('recover', 'run-one')['lab_verification'], 'recorded-before-apk-restore')
        self.assertEqual(self.command('inspect')['lab_status'], 'inaccessible')
        self.assertEqual(self.command('recover', 'run-one')['outcome'], 'recovered')

    def test_a_missing_preserved_original_does_not_get_replaced_by_a_candidate(self):
        self.write_variant(self.device.root / 'lab', b'old')
        self.command('push-variant', str(self.candidate), 'run-one')
        active = self.command('inspect')['lab']
        shutil.rmtree(self.device.root / 'original')
        with self.assertRaises(phone.Refused):
            self.command('push-variant', str(self.candidate), 'run-one')
        self.assertEqual(self.command('inspect')['lab'], active)

    def test_skipped_lab_cannot_report_old_staged_results_as_a_new_measurement(self):
        class SkippedPhone(DirectoryPhone):
            def instrument(self, test_apk, full=False):
                return 'INSTRUMENTATION_STATUS_CODE: -4\nOK (1 test)\nINSTRUMENTATION_CODE: -1'
        self.device = SkippedPhone(self.device.root)
        (self.candidate / 'result.json').write_text('{"takes":[{"text":"old result"}]}')
        self.command('push-variant', str(self.candidate), 'run-one')
        test_apk = self.root / 'test.apk'
        test_apk.write_bytes(b'synthetic instrumentation')
        with self.assertRaises(phone.Refused):
            self.command('lab', 'run-one', str(test_apk))
        self.assertNotIn('result.json', self.command('inspect')['lab']['files'])

    def test_recovery_can_resume_after_partial_recursive_disposal(self):
        self.write_variant(self.device.root / 'lab', b'old')
        original = self.command('inspect')['lab']
        self.command('push-variant', str(self.candidate), 'run-one')
        self.device.disconnect_on_removal = True
        with self.assertRaises(ConnectionError):
            self.command('recover', 'run-one')
        self.command('recover', 'run-one')
        self.assertEqual(self.command('inspect')['lab'], original)


class DiagnosticPhone(DirectoryPhone):
    """Synthetic app/test packages; all external operations stay in temporary files."""
    test_apk = None
    disconnect_after_test_install = False

    def apk_info(self, path):
        info = super().apk_info(path)
        is_test = path.read_bytes().startswith(b'test ')
        info.update(package='ch.og.dictation.test' if is_test else 'ch.og.dictation',
                    target='ch.og.dictation' if is_test else None,
                    debuggable=True, diagnostic_manifest='same-allowed-behavior')
        return info

    def app_identity(self, package='ch.og.dictation'):
        if package == 'ch.og.dictation.test':
            return dict(self.test_apk)
        if package == 'ch.og.dictation':
            return dict(self.apk)
        raise AssertionError('unexpected package')

    def install_test(self, path):
        info = self.apk_info(path)
        if info['package'] != 'ch.og.dictation.test':
            raise AssertionError('unexpected test package')
        self.test_apk = phone.identity(info)
        if self.disconnect_after_test_install:
            self.disconnect_after_test_install = False
            raise ConnectionError('disconnected after test installation completed')


class DiagnosticInstallTests(unittest.TestCase):
    command = LabTests.command
    write_variant = LabTests.write_variant

    def setUp(self):
        LabTests.setUp(self)
        self.device = DiagnosticPhone(self.device.root)
        self.packages = [self.root / name for name in (
            'candidate.apk', 'original.apk', 'test-candidate.apk', 'test-original.apk')]
        for path, payload in zip(self.packages, (b'app candidate', b'app original',
                                                b'test candidate', b'test original')):
            path.write_bytes(payload)
        self.device.install(self.packages[1])
        self.device.install_test(self.packages[3])
        self.write_variant(self.device.root / 'lab', b'old')
        self.original = self.command('inspect')

    def provision(self):
        return self.command('profile-install', *map(str, self.packages), 'diagnostic-run')

    def assert_restored(self):
        current = self.command('inspect')
        for field in ('apk', 'test_apk', 'lab'):
            self.assertEqual(current[field], self.original[field], field)
        self.assertIsNone(current['owner'])
        self.assertIsNone(current['recovery'])

    def test_diagnostic_pair_and_lab_restore_both_exact_original_packages(self):
        self.assertEqual(self.provision()['outcome'], 'installed-diagnostic-pair')
        current = self.command('inspect')
        self.assertNotEqual(current['apk'], self.original['apk'])
        self.assertNotEqual(current['test_apk'], self.original['test_apk'])
        self.command('push-variant', str(self.candidate), 'diagnostic-run')
        self.command('recover', 'diagnostic-run')
        self.assert_restored()
        self.assertEqual(self.command('recover', 'diagnostic-run')['outcome'], 'recovered')

    def test_disconnect_after_pair_install_can_restore_originals(self):
        self.device.disconnect_after_test_install = True
        with self.assertRaises(ConnectionError):
            self.provision()
        self.command('recover', 'diagnostic-run')
        self.assert_restored()

    def test_instrumentation_drift_blocks_recovery_without_touching_lab(self):
        self.provision()
        self.device.test_apk['sha256'] = 'f' * 64
        original = self.device.inventory('original')
        self.assertFalse(self.command('inspect')['recoverable'])
        with self.assertRaises(phone.Refused):
            self.command('recover', 'diagnostic-run')
        self.assertEqual(self.device.inventory('original'), original)

    def test_wrong_test_target_is_rejected_before_install_or_lab_snapshot(self):
        original_info = self.device.apk_info
        def wrong_target(path):
            info = original_info(path)
            if path.read_bytes() == b'test candidate':
                info['target'] = 'another.app'
            return info
        self.device.apk_info = wrong_target
        with self.assertRaises(phone.Refused):
            self.provision()
        self.assert_restored()


class DiagnosticManifestTests(unittest.TestCase):
    base = 'E: manifest\n  E: application\n    A: android:debuggable(0x0101000f)=true'

    def fingerprint(self, extra='', package='ch.og.dictation'):
        return phone.diagnostic_manifest(self.base + extra, package)

    def driver(self, name, required='false', extra=''):
        return ('\n    E: uses-native-library\n'
                '      A: android:name(0x01010003)="' + name + '" (Raw: "' + name + '")\n'
                '      A: android:required(0x0101028e)=' + required + extra)

    def test_only_four_optional_known_driver_declarations_are_ignored(self):
        for name in ('libOpenCL.so', 'libOpenCL-pixel.so', 'libedgetpu_litert.so', 'libedgetpu_util.so'):
            with self.subTest(name=name):
                self.assertEqual(self.fingerprint(self.driver(name)), self.fingerprint())
                self.assertEqual(self.fingerprint(self.driver(name).replace(
                    'android:', 'http://schemas.android.com/apk/res/android:')), self.fingerprint())

    def test_required_unknown_or_extended_driver_declarations_remain_constrained(self):
        for declaration in (self.driver('libOpenCL.so', 'true'), self.driver('libother.so'),
                            self.driver('libOpenCL.so', extra='\n      A: android:extra="yes"')):
            with self.subTest(declaration=declaration):
                self.assertNotEqual(self.fingerprint(declaration), self.fingerprint())

    def test_native_extraction_difference_is_allowed_only_for_test_application(self):
        extraction = '\n    A: android:extractNativeLibs(0x010104ea)=true'
        self.assertEqual(self.fingerprint(extraction, 'ch.og.dictation.test'),
                         self.fingerprint(package='ch.og.dictation.test'))
        self.assertNotEqual(self.fingerprint(extraction), self.fingerprint())
        changed_debug = self.base.replace('debuggable(0x0101000f)=true', 'debuggable(0x0101000f)=false')
        self.assertNotEqual(phone.diagnostic_manifest(changed_debug, 'ch.og.dictation.test'),
                            self.fingerprint(package='ch.og.dictation.test'))


class StopTransport(phone.AndroidPhone):
    """Only the external Android command transport is replaced."""
    def __init__(self, root):
        super().__init__(root)
        self.calls = []
        self.current_user = '0'
        self.ime = 'org.example.keyboard/.Ime'
        self.bound = self.ime
        self.ime_dump = None
        self.process_dump = 'ACTIVITY MANAGER RUNNING PROCESSES (dumpsys activity processes)\n  mDeviceIdleTempAllowlist=[]\n'
        self.running = True
        self.stop_fails = False
        self.restart = False
        self.change_selection = False
        self.fail_calls = set()
        self.pidof_failure = False

    def bridge(self, *args, check=True, text=True, timeout=300):
        self.calls.append(args)
        if args in self.fail_calls:
            raise subprocess.CalledProcessError(1, args)
        if args == ('devices',):
            out = 'List of devices attached\npixel-test\tdevice\n'
        elif args == ('shell', 'getprop ro.product.model'):
            out = 'Pixel 10 Pro XL\n'
        elif args in (('shell', 'pm path ch.og.dictation'),
                      ('shell', 'pm path ch.og.dictation.test')):
            out = 'package:/data/app/base.apk\n'
        elif args == ('shell', 'sha256sum -- /data/app/base.apk'):
            out = 'a' * 64 + '  /data/app/base.apk\n'
        elif args in (('shell', 'dumpsys package ch.og.dictation'),
                      ('shell', 'dumpsys package ch.og.dictation.test')):
            out = 'versionCode=12\n'
        elif args == ('shell', 'am get-current-user'):
            out = self.current_user + '\n'
        elif args == ('shell', 'dumpsys input_method'):
            out = self.ime_dump if self.ime_dump is not None else (
                'mCurrentUserId=0\nmCurMethodId=' + self.ime + '\nmCurId=' + self.bound + '\n')
        elif args == ('shell', 'dumpsys activity processes'):
            out = self.process_dump
        elif len(args) == 2 and args[0] == 'shell' and args[1].startswith('settings --user 0 get secure '):
            key = args[1].split()[-1]
            out = (self.ime if key == 'default_input_method' else 'unchanged') + '\n'
        elif args == ('shell', 'am force-stop --user 0 ch.og.dictation'):
            if self.stop_fails:
                raise phone.Refused('external stop failed')
            self.running = self.restart
            if self.change_selection:
                self.ime = 'org.changed.keyboard/.Ime'
            out = ''
        elif args == ('shell', 'pidof ch.og.dictation'):
            if self.pidof_failure:
                return subprocess.CompletedProcess(args, 127, '', 'pidof failed')
            out = '123\n' if self.running else ''
            return subprocess.CompletedProcess(args, 0 if self.running else 1, out, '')
        elif len(args) == 2 and args[0] == 'shell' and args[1].startswith('run-as ch.og.dictation '):
            out = '' if 'readlink' in args[1] else 'absent\n'
        else:
            raise AssertionError(args)
        return subprocess.CompletedProcess(args, 0, out, '')


class StopTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.state = Path(self.temporary.name) / 'state'
        self.device = StopTransport(Path(self.temporary.name))

    def command(self, *args):
        return phone.run(list(args), device=self.device, state=self.state)

    def stopped(self):
        return ('shell', 'am force-stop --user 0 ch.og.dictation') in self.device.calls

    def test_explicit_stop_terminates_only_the_primary_users_app_without_recovery_writes(self):
        self.assertEqual(self.command('stop-app')['outcome'], 'stopped')
        self.assertTrue(self.stopped())
        self.assertFalse(self.device.running)
        self.assertEqual([p.name for p in self.state.iterdir()], ['command.lock'])

    def test_stop_refuses_arguments_before_contacting_the_phone(self):
        with self.assertRaises(phone.Refused):
            self.command('stop-app', 'other.package')
        self.assertEqual(self.device.calls, [])

    def test_stop_refuses_selected_or_bound_dictation_and_unknown_ime_state(self):
        for field, value in [('ime', 'ch.og.dictation/.DictationImeService'),
                             ('bound', 'ch.og.dictation/.DictationImeService'),
                             ('ime_dump', 'permission denied'), ('current_user', '10')]:
            with self.subTest(field=field):
                self.device = StopTransport(Path(self.temporary.name))
                setattr(self.device, field, value)
                with self.assertRaises(phone.Refused):
                    self.command('stop-app')
                self.assertFalse(self.stopped())

    def test_unrecognized_ime_user_context_reports_counts_without_private_dump_values(self):
        self.device.ime_dump = 'mCurMethodId=org.example.keyboard/.Ime\nmCurId=org.example.keyboard/.Ime\n'
        with self.assertRaises(phone.Refused) as error:
            self.command('stop-app')
        self.assertIn('"parsed_user_fields": 0', str(error.exception))
        self.assertIn('"selected_fields": 1', str(error.exception))
        self.assertIn('"binding_fields": 1', str(error.exception))
        self.assertNotIn('org.example', str(error.exception))
        self.assertFalse(self.stopped())

    def test_android17_ime_fields_allow_only_unselected_unbound_dictation(self):
        keyboard = 'org.example.keyboard/.Ime'
        dictation = 'ch.og.dictation/.DictationImeService'
        for selected, bound in ((keyboard, keyboard), (dictation, keyboard), (keyboard, dictation)):
            with self.subTest(selected=selected, bound=bound):
                self.device = StopTransport(Path(self.temporary.name))
                self.device.ime_dump = ('Input Method Manager Service state:\n  mCurrentImeUserId=0\n'
                    '  UserId=0\n    mBindingController:\n      mSelectedImeId=' + selected +
                    '\n      mCurImeId=' + bound + '\n')
                if selected == keyboard and bound == keyboard:
                    self.assertEqual(self.command('stop-app')['outcome'], 'stopped')
                else:
                    with self.assertRaises(phone.Refused):
                        self.command('stop-app')
                    self.assertFalse(self.stopped())

    def test_stop_refuses_instrumentation_and_unrecognized_process_dump(self):
        for value in ('', 'permission denied',
                      'ACTIVITY MANAGER RUNNING PROCESSES (dumpsys activity processes)\n',
                      self.device.process_dump + '  mDeviceIdleTempAllowlist=[]\n',
                      self.device.process_dump + 'DUMP TIMEOUT\n',
                      self.device.process_dump + '  Active instrumentation:\n    Instrumentation #0: unrelated.runner\n'):
            with self.subTest(value=value):
                self.device = StopTransport(Path(self.temporary.name))
                self.device.process_dump = value
                with self.assertRaises(phone.Refused):
                    self.command('stop-app')
                self.assertFalse(self.stopped())

    def test_failed_stop_or_restarted_process_never_reports_success(self):
        for field in ('stop_fails', 'restart', 'change_selection'):
            with self.subTest(field=field):
                self.device = StopTransport(Path(self.temporary.name))
                setattr(self.device, field, True)
                with self.assertRaises(phone.Refused):
                    self.command('stop-app')

    def test_failed_precondition_reads_never_stop_the_app(self):
        for command in ('dumpsys input_method', 'dumpsys activity processes',
                        'settings --user 0 get secure default_input_method'):
            with self.subTest(command=command):
                self.device = StopTransport(Path(self.temporary.name))
                self.device.fail_calls.add(('shell', command))
                with self.assertRaises(subprocess.CalledProcessError):
                    self.command('stop-app')
                self.assertFalse(self.stopped())

    def test_failed_process_probe_never_claims_the_app_stopped(self):
        self.device.pidof_failure = True
        with self.assertRaises(phone.Refused):
            self.command('stop-app')

    def test_stop_preserves_the_existing_recovery_receipt(self):
        self.state.mkdir()
        receipt = self.state / 'active.json'
        receipt.write_text(json.dumps({'run': 'owned', 'device': 'pixel-test',
                           'apk': {'sha256': 'a' * 64, 'version_code': 12}, 'phase': 'active'}))
        before = receipt.read_bytes()
        self.command('stop-app')
        self.assertEqual(receipt.read_bytes(), before)

    def test_inspect_and_staging_never_stop_implicitly(self):
        self.command('inspect')
        candidate = Path(self.temporary.name) / 'candidate'
        (candidate / 'model').mkdir(parents=True)
        (candidate / 'variant.json').write_text('{}')
        with self.assertRaises(phone.Refused):
            self.command('push-variant', str(candidate), 'a-run')
        self.assertFalse(self.stopped())


if __name__ == '__main__':
    unittest.main()
