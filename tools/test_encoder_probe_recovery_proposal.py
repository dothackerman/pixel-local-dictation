"""User-applied proposal through public recover, DirectoryPhone only. No phone contact.

Only policy digests are translated in this test process to fabricated payloads.
The production proposal keeps the exact real incident identities. Actual result
bytes are unavailable through the unchanged wrapper; these tests establish the
recovery protocol using deterministic synthetic evidence, not captured tensors.
"""
import contextlib
import base64
import copy
import hashlib
import importlib.machinery
import importlib.util
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from test_phone import DiagnosticPhone

SOURCE = Path(__file__).with_name('proposals') / 'phone-encoder-probe-recovery.py'
loader = importlib.machinery.SourceFileLoader('encoder_recovery_proposal', str(SOURCE))
spec = importlib.util.spec_from_loader(loader.name, loader)
proposal = importlib.util.module_from_spec(spec)
loader.exec_module(proposal)

RUN = 'issue4-short-cpu-20261020-03'
PROFILE = 'encoder-probe-profile_2026-10-20_20-19-16_968.json'
APP = '253982573618d6c4231259f80402e84698387c84db8735103fb7de30b15533d4'
TEST = '4ebde0968ae2908658037a32549dd09cfc45c6df82c3666c609870ae1410367b'

def sha(data):
    return hashlib.sha256(data).hexdigest()

class FakeRecoveryPhone(DiagnosticPhone):
    def apk_info(self, path):
        info = super().apk_info(path)
        if info['package'] == 'ch.og.dictation.test':
            info['version_code'] = 0
        return info

    def __init__(self, root):
        super().__init__(root)
        self.mutations = []
        self.busy = False
        self.large_profile = False
        self.large_result = False
        self.fail_before_move = False

    def idle(self):
        if self.busy:
            raise proposal.Refused('dictation process exists')

    def encoder_probe_recovery_result(self):
        if self.large_result:
            return b'x' * (1024 * 1024 + 1)
        return self.result()

    def encoder_probe_recovery_profile(self):
        if self.large_profile:
            return b'x' * (64 * 1024 * 1024 + 1)
        return (self.root / 'lab' / PROFILE).read_bytes()

    def move(self, source, target):
        if self.fail_before_move:
            self.fail_before_move = False
            raise ConnectionError('fabricated disconnection before first restore move')
        self.mutations.append(('move', source, target))
        return super().move(source, target)

    def remove(self, name):
        self.mutations.append(('remove', name))
        return super().remove(name)

    def install(self, path):
        self.mutations.append(('install',))
        return super().install(path)

    def install_test(self, path):
        self.mutations.append(('install-test',))
        return super().install_test(path)

    def release(self, token):
        self.mutations.append(('release', token))
        return super().release(token)

class RecoveryProposalTests(unittest.TestCase):
    @contextlib.contextmanager
    def case(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            state = root / 'state'
            (state / 'apks').mkdir(parents=True)
            device = FakeRecoveryPhone(root / 'device')
            original = device.root / 'original'
            original.mkdir(parents=True)
            (original / 'original-only.txt').write_bytes(b'fabricated original lab')
            baseline = device.inventory('original')
            lab = device.root / 'lab'
            (lab / 'model').mkdir(parents=True)
            config = b'{"provider":"cpu","fabricated":true}'
            for name, data in {'variant.json': b'{}', 'encoder-probe.json': config,
                'audio_signal.f32': bytes(16), 'length.i64': bytes(8),
                'model/encoder.onnx': b'fabricated graph', 'model/encoder.weights': b'fabricated weights'}.items():
                (lab / name).write_bytes(data)
            candidate = device.inventory('lab')
            app_original, test_original = b'app original', b'test original'
            rollback = state / 'apks' / (sha(app_original) + '.apk')
            test_rollback = state / 'apks' / (sha(test_original) + '.apk')
            rollback.write_bytes(app_original)
            test_rollback.write_bytes(test_original)
            original_app = dict(sha256=sha(app_original), version_code=12)
            original_test = dict(sha256=sha(test_original), version_code=0)
            device.apk = dict(sha256=APP, version_code=12)
            device.test_apk = dict(sha256=TEST, version_code=0)
            device.token = RUN
            receipt = dict(run=RUN, kind='lab', diagnostic=True, device=device.serial,
                apk=device.apk, test_apk=device.test_apk, original=baseline,
                before=dict(accessible=True, exists=False, files={}), candidate=candidate,
                original_apk=original_app, original_test_apk=original_test,
                rollback=str(rollback), test_rollback=str(test_rollback),
                phase='running', profile_complete=False, copy_pending=False)
            proposal.save(state / 'active.json', receipt)
            generated = {'encoder-probe-output0.f32': bytes(4), 'encoder-probe-output1.i64': bytes(8),
                         'encoder-probe-started': b'', PROFILE: b'[{"name":"fabricated ORT trace only"}]'}
            result = json.dumps(dict(schema=1, eligible=False, outcome='complete',
                freeze_sha256=sha(config), build_sha256=APP, test_build_sha256=TEST,
                frozen=dict(provider='cpu'), outputs=[dict(file=name, sha256=sha(generated[name]),
                    raw_base64=base64.b64encode(generated[name]).decode())
                    for name in ('encoder-probe-output0.f32', 'encoder-probe-output1.i64')])).encode()
            profile = b'[{"name":"fabricated ORT trace only"}]'
            for name, data in dict(generated, **{'result.json': result}).items():
                (lab / name).write_bytes(data)
            policy = dict(run=RUN, app_sha256=APP, test_sha256=TEST,
                original_app_sha256=sha(app_original), original_test_sha256=sha(test_original),
                config_sha256=sha(config), result_sha256=sha(result),
                generated={name: sha(data) for name, data in generated.items()})
            # The baseline copy deliberately lacks the fixed policy: red test
            # still exercises its actual public recover path without changing it.
            with patch.object(proposal, 'ENCODER_PROBE_RECOVERY', policy, create=True):
                yield SimpleNamespace(root=root, state=state, device=device, baseline=baseline,
                    receipt=receipt, result=result, profile=profile, policy=policy,
                    original_app=original_app, original_test=original_test)

    def test_known_incomplete_probe_can_export_exact_evidence_and_restore_baseline(self):
        with self.case() as c:
            recovered = proposal.run(['recover', RUN], device=c.device, state=c.state)
            self.assertEqual('recovered', recovered['outcome'])
            self.assertEqual(c.baseline, c.device.inventory('lab'))
            self.assertEqual(c.original_app, c.device.apk)
            self.assertEqual(c.original_test, c.device.test_apk)
            self.assertIsNone(c.device.token)
            self.assertFalse((c.state / 'active.json').exists())
            preserved = json.loads((c.state / (RUN + '.json')).read_text())
            self.assertFalse(preserved['profile_complete'])
            self.assertNotIn('profile_binding', preserved)
            exported = preserved['encoder_probe_recovery']
            self.assertEqual(c.result, Path(exported['result']['path']).read_bytes())
            self.assertEqual(c.profile, Path(exported['ort_profile']['path']).read_bytes())
            self.assertEqual('not evaluated', exported['tensor_verdict'])
            self.assertEqual('running', exported['phase_before_recovery'])
            self.assertEqual(c.receipt['candidate'], exported['candidate_before'])
            self.assertEqual('recovered', proposal.run(['recover', RUN], device=c.device, state=c.state)['outcome'])

    def test_changed_required_files_or_identity_refuse_without_device_or_journal_mutation(self):
        names = ['variant.json', 'encoder-probe.json', 'audio_signal.f32', 'length.i64',
                 'model/encoder.onnx', 'model/encoder.weights', 'result.json',
                 'encoder-probe-output0.f32', 'encoder-probe-output1.i64',
                 'encoder-probe-started', PROFILE]
        mutations = [(name, lambda c, name=name: (c.device.root / 'lab' / name).write_bytes(b'changed'))
                     for name in names]
        mutations += [('missing ' + name, lambda c, name=name: (c.device.root / 'lab' / name).unlink())
                      for name in names]
        mutations += [
            ('unknown file', lambda c: (c.device.root / 'lab' / 'unexpected.txt').write_bytes(b'unknown')),
            ('owner', lambda c: setattr(c.device, 'token', 'foreign-owner')),
            ('missing owner', lambda c: setattr(c.device, 'token', None)),
            ('device', lambda c: setattr(c.device, 'serial', 'different-pixel')),
            ('app', lambda c: c.device.apk.update(sha256='0' * 64)),
            ('test app', lambda c: c.device.test_apk.update(sha256='0' * 64)),
            ('baseline', lambda c: (c.device.root / 'original/original-only.txt').write_bytes(b'changed')),
            ('rollback', lambda c: Path(c.receipt['rollback']).write_bytes(b'changed')),
            ('test rollback', lambda c: Path(c.receipt['test_rollback']).write_bytes(b'changed')),
            ('busy', lambda c: setattr(c.device, 'busy', True)),
            ('oversized result', lambda c: setattr(c.device, 'large_result', True)),
            ('oversized ORT profile', lambda c: setattr(c.device, 'large_profile', True)),
        ]
        for name, mutate in mutations:
            with self.subTest(name=name), self.case() as c:
                mutate(c)
                before = c.device.inspect()
                journal = (c.state / 'active.json').read_bytes()
                with self.assertRaises(proposal.Refused):
                    proposal.run(['recover', RUN], device=c.device, state=c.state)
                self.assertEqual([], c.device.mutations)
                self.assertEqual(before, c.device.inspect())
                self.assertEqual(journal, (c.state / 'active.json').read_bytes())
                self.assertFalse((c.state / 'results').exists())

    def test_other_run_and_completed_profile_do_not_gain_drift_tolerance(self):
        for field, value in [('run', 'ordinary-run'), ('profile_complete', True), ('phase', 'active')]:
            with self.subTest(field=field), self.case() as c:
                c.receipt[field] = value
                if field == 'run':
                    c.device.token = value
                proposal.save(c.state / 'active.json', c.receipt)
                journal = (c.state / 'active.json').read_bytes()
                with self.assertRaises(proposal.Refused):
                    proposal.run(['recover', c.receipt['run']], device=c.device, state=c.state)
                self.assertEqual([], c.device.mutations)
                self.assertEqual(journal, (c.state / 'active.json').read_bytes())

    def test_recovery_disconnect_keeps_incomplete_evidence_and_can_resume(self):
        with self.case() as c:
            c.device.fail_before_move = True
            with self.assertRaises(ConnectionError):
                proposal.run(['recover', RUN], device=c.device, state=c.state)
            interrupted = json.loads((c.state / 'active.json').read_text())
            self.assertFalse(interrupted['profile_complete'])
            self.assertNotIn('profile_binding', interrupted)
            self.assertEqual('running', interrupted['encoder_probe_recovery']['phase_before_recovery'])
            self.assertEqual(c.result, Path(interrupted['encoder_probe_recovery']['result']['path']).read_bytes())
            self.assertEqual(c.profile, Path(interrupted['encoder_probe_recovery']['ort_profile']['path']).read_bytes())
            self.assertEqual('recovered', proposal.run(['recover', RUN], device=c.device, state=c.state)['outcome'])
            self.assertEqual(c.baseline, c.device.inventory('lab'))

    def test_interrupted_pending_export_is_preserved_and_does_not_poison_recovery(self):
        with self.case() as c:
            folder = c.state / 'results'
            folder.mkdir()
            partial = folder / (RUN + '-encoder-probe-recovery.json-' + '0' * 32 + '.pending')
            partial.write_bytes(b'fabricated interrupted partial export')
            self.assertEqual('recovered', proposal.run(['recover', RUN], device=c.device, state=c.state)['outcome'])
            self.assertEqual(b'fabricated interrupted partial export', partial.read_bytes())
            self.assertEqual(c.result, (folder / (RUN + '-encoder-probe-recovery.json')).read_bytes())

    def test_wrong_result_binding_or_embedded_tensor_refuses_before_device_mutation(self):
        mutations = [
            lambda value: value.update(freeze_sha256='wrong freeze'),
            lambda value: value.update(build_sha256='wrong build'),
            lambda value: value.update(test_build_sha256='wrong test build'),
            lambda value: value.update(eligible=True),
            lambda value: value['frozen'].update(provider='webgpu'),
            lambda value: value['outputs'][0].update(raw_base64=base64.b64encode(b'wrong tensor').decode()),
        ]
        for index, mutate in enumerate(mutations):
            with self.subTest(index=index), self.case() as c:
                value = json.loads(c.result)
                mutate(value)
                changed = json.dumps(value).encode()
                (c.device.root / 'lab/result.json').write_bytes(changed)
                c.policy['result_sha256'] = sha(changed)  # Fabricated policy only in this test process.
                journal = (c.state / 'active.json').read_bytes()
                with self.assertRaises(proposal.Refused):
                    proposal.run(['recover', RUN], device=c.device, state=c.state)
                self.assertEqual([], c.device.mutations)
                self.assertEqual(journal, (c.state / 'active.json').read_bytes())

if __name__ == '__main__':
    unittest.main()
