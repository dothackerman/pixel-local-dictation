"""Typed public phone protocol with fabricated DirectoryPhone only; never phone IO."""
import base64
import hashlib
import importlib.machinery
import importlib.util
import json
from pathlib import Path
import struct
import types
import tempfile
import unittest
from unittest.mock import patch

from test_phone import DiagnosticPhone

SOURCE = Path(__file__).with_name('proposals') / 'phone-short-encoder-v3.py'
loader = importlib.machinery.SourceFileLoader('short_encoder_proposal', str(SOURCE))
spec = importlib.util.spec_from_loader(loader.name, loader)
phone = importlib.util.module_from_spec(spec)
loader.exec_module(phone)

ROOT = Path(__file__).resolve().parents[1]

def sha(data):
    return hashlib.sha256(data).hexdigest()

class ShortPhone(DiagnosticPhone):
    mode = 'complete'
    calls = 0
    busy = False

    def apk_info(self, path):
        value = super().apk_info(path)
        if value['package'] == 'ch.og.dictation.test':
            value['version_code'] = 0
        return value

    def idle(self):
        if self.busy:
            raise phone.Refused('fabricated app still running')

    def short_encoder_result(self):
        return self.result()

    def instrument(self, test_apk, full=False, native_profile=False, short_encoder_attempt=None):
        self.calls += 1
        if native_profile or short_encoder_attempt is None:
            raise AssertionError('short checkpoint must use reserved typed attempt, never role profiling')
        config = self.root / 'lab/encoder-probe.json'
        plan = json.loads(config.read_text())
        if self.mode == 'death':
            raise ConnectionError('fabricated process death without result')
        if self.mode == 'timeout':
            self.busy = True
            raise TimeoutError('fabricated native timeout; process may still run')
        if self.mode == 'partial':
            (self.root / 'lab/result.json').write_bytes(b'{"protocol":"short-encoder-v1",')
            raise ConnectionError('fabricated process death during result write')
        if plan['protocol'] == 'encoder-placement-v1':
            (self.root / 'lab/result.json').write_text(json.dumps(self.placement(plan, config, short_encoder_attempt)))
            if self.mode == 'drift':
                (self.root / 'lab/unexpected.bin').write_bytes(b'unknown generated data')
            return 'INSTRUMENTATION_STATUS_CODE: 0\nOK (1 test)\nINSTRUMENTATION_CODE: -1'
        result = dict(schema=1, protocol=plan['protocol'], attempt_id=short_encoder_attempt,
            eligible=False, scope='short synthetic feasibility only; full-take checks not run',
            outcome='complete', checkpoint='outputs-materialized', pid=100 + self.calls,
            processStartElapsedMs=1000 + self.calls, freeze_sha256=sha(config.read_bytes()), frozen=plan,
            build_sha256=plan['builds']['app_sha256'], test_build_sha256=plan['builds']['test_sha256'],
            runtime_version='1.31.0', runtime_core_sha256=plan['runtime']['core_sha256'],
            runtime_jni_sha256=plan['runtime']['jni_sha256'], optimization='ALL_OPT',
            execution_mode='SEQUENTIAL', intra_op_threads=4, inter_op_threads=1, profiling=False,
            cpu_fallback_disabled=plan['provider']=='webgpu' and plan['protocol']=='short-encoder-v1',
            initialization_elapsed_ms=1.0,
            initialization_complete=True, cold_load_ms=1.0, run_to_host_output_ms=2.0,
            adapter_driver_proof='unproven', fp32_shader_proof='unproven', residual_node_assignment='unproven',
            memory=[dict(phase=p, pssKb=100, highWaterKb=200, thermalStatus=0)
                    for p in ('before-load','after-load','after-output','finally')])
        if plan['provider'] == 'webgpu':
            result['provider_options'] = dict(enableGraphCapture='0', enableInt64='1',
                                             preferredLayout='NCHW', dawnBackendType='Vulkan')
        if (plan['protocol'] == 'short-encoder-v2' and plan['provider'] == 'webgpu'
                and self.mode not in ('v2-unlisted', 'failed', 'artifact-failed')):
            listing = self.placement(plan, config, short_encoder_attempt)
            result.update({key: listing[key] for key in ('placement','placement_read_ms','residual_node_assignment')})
        if self.mode == 'v2-strict':
            result['cpu_fallback_disabled'] = True
        if self.mode == 'v2-incomplete':
            result['placement']['providers']['CPUExecutionProvider']['declared'] = 3
            result['placement']['complete'] = False
            result['residual_node_assignment'] = 'incomplete'
        if self.mode == 'v2-run-failed':
            result.update(outcome='failed', checkpoint='run-and-host-output', failure='fabricated unsupported kernel',
                          run_to_failure_ms=2.5)
            for key in ('outputs','run_to_host_output_ms'):
                result.pop(key,None)
        if self.mode == 'v2-claimed':
            result.pop('placement')
            result.update(outcome='failed', checkpoint='session-create', failure='fabricated failure',
                          initialization_complete=False)
        if self.mode == 'v2-overflow':
            result['placement_read_ms'] = 10**400
        if self.mode == 'v2-cpu-listed':
            result['placement'] = self.placement(plan, config, short_encoder_attempt)['placement']
        result['outputs'] = [dict(file=name, dtype=dtype, shape=shape, sha256=sha(data),
            raw_base64=base64.b64encode(data).decode()) for name,dtype,shape,data in (
            ('encoder-probe-output0.f32','FLOAT',[1,1024,3],bytes(12288)),
            ('encoder-probe-output1.i64','INT64',[1],struct.pack('<q',3)))]
        if self.mode in ('failed','artifact-failed','run-failed'):
            result.update(outcome='failed', checkpoint='verify' if self.mode=='artifact-failed' else 'session-create',
                          failure='fabricated unsupported operator/type', initialization_complete=False)
            if self.mode=='run-failed':
                result.update(checkpoint='run-and-host-output',initialization_complete=True,run_to_failure_ms=2.5)
            for key in ('outputs','cold_load_ms','run_to_host_output_ms'):
                result.pop(key,None)
        (self.root / 'lab/result.json').write_text(json.dumps(result))
        if self.mode == 'drift':
            (self.root / 'lab/unexpected.bin').write_bytes(b'unknown generated data')
        return 'INSTRUMENTATION_STATUS_CODE: 0\nOK (1 test)\nINSTRUMENTATION_CODE: -1'

    def placement(self, plan, config, attempt):
        cpu = [['Gather','/pre_encode/Gather'],['Unsqueeze','']]
        gpu = [['MatMul','/layers.0/MatMul']]
        result = dict(schema=1, protocol='encoder-placement-v1', attempt_id=attempt, eligible=False,
            scope='placement listing only; Run not executed', outcome='complete', checkpoint='placement-listed',
            pid=100 + self.calls, processStartElapsedMs=1000 + self.calls, freeze_sha256=sha(config.read_bytes()),
            frozen=plan, build_sha256=plan['builds']['app_sha256'], test_build_sha256=plan['builds']['test_sha256'],
            runtime_version='1.31.0', runtime_core_sha256=plan['runtime']['core_sha256'],
            runtime_jni_sha256=plan['runtime']['jni_sha256'], optimization='ALL_OPT', execution_mode='SEQUENTIAL',
            intra_op_threads=4, inter_op_threads=1, profiling=False, cpu_fallback_disabled=False,
            provider_options=dict(enableGraphCapture='0', enableInt64='1', preferredLayout='NCHW', dawnBackendType='Vulkan'),
            initialization_elapsed_ms=1.0, initialization_complete=True, cold_load_ms=1.0, placement_read_ms=0.5,
            adapter_driver_proof='unproven', fp32_shader_proof='unproven', residual_node_assignment='listed',
            memory=[dict(phase=p, pssKb=100, highWaterKb=200, thermalStatus=0) for p in ('before-load','after-load','finally')],
            placement=dict(complete=True, log_lines=9,
                providers=dict(CPUExecutionProvider=dict(declared=2, all=False, nodes=cpu),
                               WebGpuExecutionProvider=dict(declared=1, all=False, nodes=gpu)),
                causes=dict(kernel_not_found=[['Gather','/pre_encode/Gather']], cpu_preferred=[''])))
        if self.mode == 'incomplete':
            result['placement']['providers']['CPUExecutionProvider']['declared'] = 3
            result['placement']['complete'] = False
            result['residual_node_assignment'] = 'incomplete'
        elif self.mode == 'lying-complete':
            result['placement']['providers']['CPUExecutionProvider']['declared'] = 3
        elif self.mode == 'strict':
            result['cpu_fallback_disabled'] = True
        elif self.mode == 'ran':
            result['outputs'] = []
        elif self.mode == 'extra-key':
            result['placement']['sentinels'] = True
        elif self.mode == 'all-with-nodes':
            result['placement']['providers']['WebGpuExecutionProvider']['all'] = True
        elif self.mode == 'empty-op':
            result['placement']['providers']['WebGpuExecutionProvider']['nodes'][0][0] = ''
        elif self.mode == 'after-output':
            result['memory'][1]['phase'] = 'after-output'
        elif self.mode == 'failed-with-placement':
            result.update(outcome='failed', checkpoint='placement-read', failure='fabricated close failure')
        elif self.mode == 'read-failed':
            for key in ('placement','placement_read_ms','residual_node_assignment'):
                result.pop(key)
            result.update(outcome='failed', checkpoint='placement-read', failure='fabricated logcat refused')
        return result

class ShortContractTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.device = ShortPhone(self.root / 'device')
        self.device.root.mkdir()
        self.state = self.root / 'state'
        self.packages = [self.root / name for name in ('candidate.apk','original.apk','test-candidate.apk','test-original.apk')]
        for path,data in zip(self.packages,(b'app candidate',b'app original',b'test candidate',b'test original')):
            path.write_bytes(data)
        self.device.install(self.packages[1]);self.device.install_test(self.packages[3])
        (self.device.root / 'lab').mkdir()
        (self.device.root / 'lab/original.txt').write_bytes(b'fabricated original lab')
        self.baseline = self.command('inspect')
        self.source = self.root / 'candidate'
        (self.source / 'model').mkdir(parents=True)
        files={'audio_signal.f32':bytes(8704),'length.i64':struct.pack('<q',17),
               'variant.json':(ROOT/'variants/beam4-fp32-gate.json').read_bytes(),
               'model/encoder.onnx':b'fabricated graph','model/encoder.weights':b'fabricated weights'}
        for name,data in files.items():(self.source/name).write_bytes(data)
        self.plan=dict(schema=1,protocol='short-encoder-v1',input_kind='synthetic silence; no recordings',
            shape=[1,128,17],true_length=17,provider='cpu',eligible=False,
            scope='short feasibility checkpoint; full take not run',atol=1e-4,rtol=1e-3,
            model={name:sha(files['model/'+name]) for name in ('encoder.onnx','encoder.weights')},
            builds=dict(app_sha256=sha(self.packages[0].read_bytes()),test_sha256=sha(self.packages[2].read_bytes())),
            inputs={name:sha(files[name]) for name in ('audio_signal.f32','length.i64')},
            staging_envelope={'variant.json':sha(files['variant.json'])},
            runtime=dict(version='1.31.0',official_aar_sha256='72683787a80d0608a3121b967d8e1239aef48286826661c953211626690540a7',
                core_sha256='8005a741e57117b4d7b476417706c0e9af13cf1b89db47e60f0cbc56e1af91e0',
                jni_sha256='5f2f1a99fd9f65fd6271eed825632f4ae5ada95f5cc13d66605888ccb80cdc29',
                source_commit='402979ac1bef89dcd55add41eaa7277c407599eb'),
            sources={name:'a'*64 for name in ('tools/encoder_probe.py','tools/encoder_conversion.py',
                'app/src/encoderProbe/java/ch/og/dictation/EncoderProbe.java',
                'app/src/androidTest/kotlin/ch/og/dictation/LabTest.kt','app/build.gradle.kts',
                'tools/proposals/phone-short-encoder-v3.py')})
        self.plan['sources']['tools/proposals/phone-short-encoder-v3.py']=sha(SOURCE.read_bytes())
        # Only the fake-device test process translates this fixed public model
        # pin to fabricated bytes. Production has no override or extra model route.
        model_policy=patch.object(phone,'SHORT_MODEL',dict(self.plan['model']))
        model_policy.start();self.addCleanup(model_policy.stop)
        (self.source/'encoder-probe.json').write_text(json.dumps(self.plan))

    def command(self,*args):return phone.run(list(args),device=self.device,state=self.state)
    def stage(self):
        self.command('profile-install',*map(str,self.packages),'short-one')
        self.command('push-variant',str(self.source),'short-one')
    def restored(self):
        current=self.command('inspect')
        for key in ('apk','test_apk','lab'):self.assertEqual(self.baseline[key],current[key])
        self.assertIsNone(current['owner']);self.assertIsNone(current['recovery'])

    def test_short_success_has_reserved_attempt_retrieval_and_exact_recovery(self):
        self.stage()
        result=self.command('lab','short-one',str(self.packages[2]))
        self.assertEqual('complete',result['outcome'])
        self.assertEqual(1,self.device.calls)
        exported=self.command('pull-results','short-one')
        value=json.loads(Path(exported['path']).read_text())
        self.assertEqual(self.plan['protocol'],value['protocol'])
        self.assertEqual('complete',value['outcome'])
        self.assertFalse(value['profiling'])
        self.assertFalse(self.command('inspect')['recovery']['profile_complete'])
        self.command('recover','short-one');self.restored()

    def test_disconnect_after_recorded_success_can_retrieve_first_valid_receipt(self):
        self.stage()
        with patch.object(self.device,'short_encoder_result',side_effect=ConnectionError('capture disconnected')):
            with self.assertRaises(ConnectionError):self.command('lab','short-one',str(self.packages[2]))
        self.assertTrue(self.command('inspect')['recovery']['short_encoder']['attempt']['instrumentation_success'])
        result=self.command('pull-results','short-one')
        self.assertEqual('complete',result['outcome'])
        self.assertEqual(1,self.device.calls)
        self.command('recover','short-one');self.restored()

    def test_native_and_artifact_failures_are_preserved_without_role_profile_or_retry(self):
        for mode in ('failed','artifact-failed','run-failed'):
            with self.subTest(mode=mode):
                self.setUp()
                self.device.mode=mode;self.stage()
                self.assertEqual('failed',self.command('lab','short-one',str(self.packages[2]))['outcome'])
                export=self.command('pull-results','short-one')
                value=json.loads(Path(export['path']).read_text())
                self.assertEqual('failed',value['outcome'])
                self.assertIn('unsupported',value['failure'])
                if mode=='run-failed':self.assertEqual(2.5,value['run_to_failure_ms'])
                with self.assertRaises(phone.Refused):self.command('lab','short-one',str(self.packages[2]))
                with self.assertRaises(phone.Refused):self.command('push-variant',str(self.source),'short-one')
                self.assertEqual(1,self.device.calls)
                self.command('recover','short-one');self.restored()

    def test_death_and_partial_result_stay_unresolved_but_recover_with_available_evidence(self):
        for mode in ('death','partial'):
            with self.subTest(mode=mode):
                self.setUp()
                self.device.mode=mode;self.stage()
                with self.assertRaises(ConnectionError):self.command('lab','short-one',str(self.packages[2]))
                current=self.command('inspect')['recovery']
                identity=current['short_encoder']['attempt']['id']
                self.assertEqual('unresolved-execution',current['short_encoder']['attempt']['state'])
                with self.assertRaises(phone.Refused):self.command('lab','short-one',str(self.packages[2]))
                export=self.command('pull-results','short-one')
                value=json.loads(Path(export['path']).read_text())
                self.assertEqual(identity,value['attempt_id'])
                self.assertEqual('unresolved-execution',value['outcome'])
                self.assertEqual(mode=='partial',value['result_present'])
                if mode=='partial':self.assertEqual(b'{"protocol":"short-encoder-v1",',base64.b64decode(value['payload_base64']))
                self.command('recover','short-one');self.restored()
                self.assertTrue(Path(export['path']).exists())

    def test_timeout_does_not_stop_native_work_or_allow_disposal_until_quiescent(self):
        self.device.mode='timeout';self.stage()
        with self.assertRaises(TimeoutError):self.command('lab','short-one',str(self.packages[2]))
        self.assertTrue(self.device.busy)
        journal=(self.state/'active.json').read_bytes()
        with self.assertRaises(phone.Refused):self.command('recover','short-one')
        self.assertEqual(journal,(self.state/'active.json').read_bytes())
        self.assertTrue((self.device.root/'original').exists())
        self.device.busy=False  # External operator verified app stop; wrapper never stops it implicitly.
        self.command('recover','short-one');self.restored()

    def test_unknown_generated_file_blocks_acceptance_retrieval_and_recovery(self):
        self.device.mode='drift';self.stage()
        with self.assertRaises(phone.Refused):self.command('lab','short-one',str(self.packages[2]))
        original=self.device.inventory('original')
        with self.assertRaises(phone.Refused):self.command('pull-results','short-one')
        with self.assertRaises(phone.Refused):self.command('recover','short-one')
        self.assertEqual(original,self.device.inventory('original'))
        self.assertTrue((self.device.root/'lab/unexpected.bin').is_file())

    def test_success_also_blocks_duplicate_lab_and_restaging(self):
        self.stage();self.command('lab','short-one',str(self.packages[2]))
        with self.assertRaises(phone.Refused):self.command('lab','short-one',str(self.packages[2]))
        with self.assertRaises(phone.Refused):self.command('push-variant',str(self.source),'short-one')
        with self.assertRaises(phone.Refused):self.command('pull-profile','short-one')
        self.assertEqual(1,self.device.calls)
        self.command('recover','short-one');self.restored()

    def test_changed_result_after_completion_is_unresolved_and_original_export_survives(self):
        self.stage()
        first=self.command('lab','short-one',str(self.packages[2]))
        original=Path(first['path']).read_bytes()
        path=self.device.root/'lab/result.json'
        value=json.loads(path.read_bytes())
        value.update(outcome='failed',checkpoint='session-create',failure='changed but schema-valid result')
        path.write_text(json.dumps(value))
        second=self.command('pull-results','short-one')
        self.assertEqual('unresolved-execution',second['outcome'])
        self.assertEqual(original,Path(first['path']).read_bytes())
        self.command('recover','short-one');self.restored()

class PlacementContractTests(ShortContractTests):
    """encoder-placement-v1: WebGPU runtime session creation listing; never Run or outputs."""
    def setUp(self):
        super().setUp()
        self.plan.update(protocol='encoder-placement-v1', provider='webgpu',
                         scope='placement listing only; Run not executed')
        (self.source/'encoder-probe.json').write_text(json.dumps(self.plan))

    def lab(self, mode):
        self.device.mode = mode; self.stage()
        return self.command('lab','short-one',str(self.packages[2]))

    def test_complete_and_incomplete_listings_are_accepted_once_and_recover(self):
        for mode, listed in (('complete', 'listed'), ('incomplete', 'incomplete')):
            with self.subTest(mode=mode):
                self.setUp()
                self.assertEqual('complete', self.lab(mode)['outcome'])
                value = json.loads(Path(self.command('pull-results','short-one')['path']).read_text())
                self.assertEqual('encoder-placement-v1', value['protocol'])
                self.assertEqual(listed, value['residual_node_assignment'])
                self.assertFalse(value['cpu_fallback_disabled'])
                self.assertNotIn('outputs', value)
                with self.assertRaises(phone.Refused):self.command('lab','short-one',str(self.packages[2]))
                self.assertEqual(1, self.device.calls)
                self.command('recover','short-one');self.restored()

    def test_failed_placement_read_is_preserved(self):
        self.assertEqual('failed', self.lab('read-failed')['outcome'])
        self.command('recover','short-one');self.restored()

    def test_inconsistent_strict_or_run_receipts_stay_unresolved(self):
        for mode in ('lying-complete','strict','ran','extra-key','all-with-nodes','empty-op',
                     'after-output','failed-with-placement'):
            with self.subTest(mode=mode):
                self.setUp()
                self.assertEqual('unresolved-execution', self.lab(mode)['outcome'])
                self.command('recover','short-one');self.restored()

    def test_placement_declaration_requires_webgpu(self):
        self.plan['provider'] = 'cpu'
        (self.source/'encoder-probe.json').write_text(json.dumps(self.plan))
        self.command('profile-install',*map(str,self.packages),'short-one')
        with self.assertRaises(phone.Refused):self.command('push-variant',str(self.source),'short-one')
        self.command('recover','short-one');self.restored()

    # Inherited short-encoder-v1 receipt tests do not apply to this protocol.
    test_short_success_has_reserved_attempt_retrieval_and_exact_recovery = None
    test_disconnect_after_recorded_success_can_retrieve_first_valid_receipt = None
    test_native_and_artifact_failures_are_preserved_without_role_profile_or_retry = None
    test_changed_result_after_completion_is_unresolved_and_original_export_survives = None

class ShortV2ContractTests(ShortContractTests):
    """short-encoder-v2: v1 receipts, but WebGPU runs non-strict with its placement listing."""
    def use(self, provider):
        self.plan.update(protocol='short-encoder-v2', provider=provider)
        (self.source/'encoder-probe.json').write_text(json.dumps(self.plan))

    def setUp(self):
        super().setUp()
        self.use('webgpu')

    def test_cpu_and_webgpu_runs_complete_with_outputs_and_recover(self):
        for provider in ('cpu', 'webgpu'):
            with self.subTest(provider=provider):
                self.setUp(); self.use(provider); self.stage()
                self.assertEqual('complete', self.command('lab','short-one',str(self.packages[2]))['outcome'])
                value = json.loads(Path(self.command('pull-results','short-one')['path']).read_text())
                self.assertEqual(('short-encoder-v2', False, 2),
                                 (value['protocol'], value['cpu_fallback_disabled'], len(value['outputs'])))
                self.assertEqual(provider == 'webgpu', 'placement' in value)
                self.command('recover','short-one');self.restored()

    def test_incomplete_listing_and_failure_after_listing_are_accepted(self):
        for mode, outcome in (('v2-incomplete','complete'), ('v2-run-failed','failed')):
            with self.subTest(mode=mode):
                self.setUp(); self.device.mode = mode; self.stage()
                self.assertEqual(outcome, self.command('lab','short-one',str(self.packages[2]))['outcome'])
                self.command('recover','short-one');self.restored()

    def test_unlisted_strict_or_cpu_listing_stays_unresolved(self):
        for provider, mode in (('webgpu','v2-unlisted'), ('webgpu','v2-strict'), ('cpu','v2-cpu-listed'),
                               ('webgpu','v2-claimed'), ('webgpu','v2-overflow')):
            with self.subTest(mode=mode):
                self.setUp(); self.use(provider); self.device.mode = mode; self.stage()
                self.assertEqual('unresolved-execution', self.command('lab','short-one',str(self.packages[2]))['outcome'])
                self.command('recover','short-one');self.restored()

def load_tests(loader, tests, pattern):
    # Bind the entire historical fixture to the proposal, including its exception
    # class and fake device methods; monkeypatching only the module is insufficient.
    source = Path(__file__).with_name('test_phone.py')
    module = types.ModuleType('short_encoder_ordinary_contract')
    module.__file__ = str(source)
    text = source.read_text().replace("SOURCE = Path(__file__).with_name('phone')",
                                      'SOURCE = Path(' + repr(str(SOURCE)) + ')', 1)
    exec(compile(text, str(source), 'exec'), module.__dict__)
    tests.addTests(loader.loadTestsFromModule(module))
    return tests

if __name__=='__main__':unittest.main()
