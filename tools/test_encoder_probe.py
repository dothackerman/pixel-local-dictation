"""Public short synthetic probe preparation, no inference or personal evidence."""
import json
import base64
import copy
import hashlib
import os
import zipfile
import xml.etree.ElementTree as ET
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import numpy as np

ROOT = Path(__file__).resolve().parents[1]

class ProbePreparationTests(unittest.TestCase):
    @unittest.skipUnless(os.environ.get("ORT131_AAR"), "set ORT131_AAR for official package integration test")
    def test_published_runtime_package_has_no_network_permissions_or_initializer(self):
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp)
            stock = folder / "fabricated-sherpa.aar"
            with zipfile.ZipFile(stock, "w") as archive:
                archive.writestr("jni/arm64-v8a/libonnxruntime.so", b"fabricated old core")
            out = folder / "runtime"
            completed = subprocess.run([sys.executable, str(ROOT / "tools/encoder_probe.py"), "package",
                "--ort-aar", os.environ["ORT131_AAR"], "--sherpa-aar", str(stock), "--out", str(out)],
                capture_output=True, text=True)
            self.assertEqual(0, completed.returncode, completed.stderr)
            with zipfile.ZipFile(out / "ort131-java-jni.aar") as archive:
                manifest = ET.fromstring(archive.read("AndroidManifest.xml"))
                self.assertEqual(["uses-sdk"], [child.tag for child in manifest],
                    "runtime manifest must declare no permissions or components")
                self.assertEqual("36", manifest.find("uses-sdk").get("{http://schemas.android.com/apk/res/android}targetSdkVersion"))
                self.assertNotIn("jni/arm64-v8a/libonnxruntime.so", archive.namelist())
                import hashlib
                self.assertEqual("5f2f1a99fd9f65fd6271eed825632f4ae5ada95f5cc13d66605888ccb80cdc29",
                    hashlib.sha256(archive.read("jni/arm64-v8a/libonnxruntime4j_jni.so")).hexdigest())
            with zipfile.ZipFile(out / "sherpa-ort131.aar") as archive:
                self.assertEqual("8005a741e57117b4d7b476417706c0e9af13cf1b89db47e60f0cbc56e1af91e0",
                    hashlib.sha256(archive.read("jni/arm64-v8a/libonnxruntime.so")).hexdigest())

    def test_freezes_short_synthetic_bytes_before_any_execution(self):
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp)
            model = folder / 'model'
            model.mkdir()
            (model / 'encoder.onnx').write_bytes(b'fabricated graph identity')
            (model / 'encoder.weights').write_bytes(b'fabricated weight identity')
            out = folder / 'frozen'
            command = [sys.executable, str(ROOT / 'tools/encoder_probe.py'), 'freeze',
                       '--model', str(model), '--out', str(out),
                       '--app-apk', str(model / 'encoder.onnx'), '--test-apk', str(model / 'encoder.weights')]
            result = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)
            manifest = json.loads((out / 'encoder-probe.json').read_text())
            self.assertEqual('short-encoder-v1', manifest['protocol'])
            self.assertEqual([1, 128, 17], manifest['shape'])
            self.assertEqual(17, manifest['true_length'])
            self.assertEqual(bytes(8704), (out / 'audio_signal.f32').read_bytes())
            self.assertEqual(b'\x11\0\0\0\0\0\0\0', (out / 'length.i64').read_bytes())
            self.assertTrue((out / 'model').is_dir())
            self.assertFalse((out / 'model').is_symlink())
            self.assertEqual(b'fabricated graph identity', (out / 'model/encoder.onnx').read_bytes())
            self.assertEqual(b'fabricated weight identity', (out / 'model/encoder.weights').read_bytes())
            variant = (ROOT / 'variants/beam4-fp32-gate.json').read_bytes()
            self.assertEqual(variant, (out / 'variant.json').read_bytes())
            self.assertEqual({'variant.json': hashlib.sha256(variant).hexdigest()}, manifest['staging_envelope'])
            self.assertEqual({'audio_signal.f32', 'length.i64'}, set(manifest['inputs']))
            self.assertFalse(manifest['eligible'])
            self.assertEqual('synthetic silence; no recordings', manifest['input_kind'])
            repeated = subprocess.run(command, capture_output=True, text=True)
            self.assertNotEqual(0, repeated.returncode)
            placement = command[:-2] + ['--test-apk', str(model / 'encoder.weights'), '--protocol', 'encoder-placement-v1']
            placement[placement.index(str(out))] = str(folder / 'placement-cpu')
            self.assertNotEqual(0, subprocess.run(placement, capture_output=True, text=True).returncode)
            placement[placement.index(str(folder / 'placement-cpu'))] = str(folder / 'placement')
            result = subprocess.run(placement + ['--provider', 'webgpu'], capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)
            manifest = json.loads((folder / 'placement/encoder-probe.json').read_text())
            self.assertEqual(('encoder-placement-v1', 'webgpu', 'placement listing only; Run not executed'),
                             (manifest['protocol'], manifest['provider'], manifest['scope']))
            self.assertIn('tools/proposals/phone-short-encoder-v3.py', manifest['sources'])

    def test_direct_tensor_comparison_keeps_android_reference_scale_and_never_advances(self):
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp)
            for name, value in (("cpu", 1000.0), ("gpu", 1000.5)):
                run = folder / name
                run.mkdir()
                np.full((1,1024,3), value, dtype="<f4").tofile(run / "encoder-probe-output0.f32")
                np.array([3], dtype="<i8").tofile(run / "encoder-probe-output1.i64")
                outputs = [{"file": file, "shape": shape, "dtype": dtype,
                            "sha256": hashlib.sha256((run / file).read_bytes()).hexdigest(),
                            "raw_base64": base64.b64encode((run / file).read_bytes()).decode()}
                           for file, dtype, shape in (("encoder-probe-output0.f32", "FLOAT", [1,1024,3]),
                                                      ("encoder-probe-output1.i64", "INT64", [1]))]
                runtime = dict(version='1.31.0',official_aar_sha256='72683787a80d0608a3121b967d8e1239aef48286826661c953211626690540a7',
                    core_sha256='8005a741e57117b4d7b476417706c0e9af13cf1b89db47e60f0cbc56e1af91e0',
                    jni_sha256='5f2f1a99fd9f65fd6271eed825632f4ae5ada95f5cc13d66605888ccb80cdc29',
                    source_commit='402979ac1bef89dcd55add41eaa7277c407599eb')
                result = {"schema":1, "protocol":"short-encoder-v1", "attempt_id": ('a' if name=='cpu' else 'b')*32,
                    "pid": 100 if name=='cpu' else 101, "processStartElapsedMs":1000 if name=='cpu' else 2000,
                    "freeze_sha256":('a' if name=='cpu' else 'b')*64, "eligible":False,
                    "outcome": "complete", "runtime_version": "1.31.0", "outputs": outputs,
                    "frozen": {"protocol":"short-encoder-v1", "schema":1, "shape":[1,128,17],"true_length":17,
                        "eligible":False,"input_kind":"synthetic silence; no recordings",
                        "scope":"short feasibility checkpoint; full take not run","atol":1e-4,"rtol":1e-3,
                        "staging_envelope":{"variant.json":hashlib.sha256((ROOT/'variants/beam4-fp32-gate.json').read_bytes()).hexdigest()},
                        "provider": "cpu" if name == "cpu" else "webgpu",
                        "inputs": {"audio_signal.f32":"e8b31e302d11fbf7da124b537ba2d44f88e165da03c6557e2b0f6dc486e025bb",
                                   "length.i64":"35e3a6176b50da27fcb868bf840f0e76290bd9bf55c40545e67bac66353d0674"},
                        "model": {"encoder.onnx":"2144d185b85e7c75077a19afbd8b77f0cb8daf814844c79f213e68e8fbf5a725",
                                  "encoder.weights":"9a22d372c51455c34f13405da2520baefb7125bd16981397561423ed32d24f36"},
                        "builds": {"app_sha256": "a"*64, "test_sha256": "b"*64},
                        "runtime": runtime, "sources": {key:"a"*64 for key in ('tools/encoder_probe.py','tools/encoder_conversion.py',
                            'tools/proposals/phone-short-encoder-v3.py','app/src/encoderProbe/java/ch/og/dictation/EncoderProbe.java',
                            'app/src/androidTest/kotlin/ch/og/dictation/LabTest.kt','app/build.gradle.kts')}},
                    "build_sha256": "a"*64, "test_build_sha256": "b"*64,
                    "runtime_core_sha256":runtime['core_sha256'],"runtime_jni_sha256":runtime['jni_sha256'],
                    "optimization": "ALL_OPT", "execution_mode": "SEQUENTIAL",
                    "intra_op_threads": 4, "inter_op_threads": 1, "profiling": False,
                    "cpu_fallback_disabled":name=='gpu', "checkpoint":"outputs-materialized",
                    "initialization_complete":True,"initialization_elapsed_ms":1.0,"cold_load_ms":1.0,
                    "run_to_host_output_ms":2.0,"adapter_driver_proof":"unproven",
                    "fp32_shader_proof":"unproven","residual_node_assignment":"unproven",
                    "memory":[dict(phase=p,pssKb=100,highWaterKb=200,thermalStatus=0)
                              for p in ('before-load','after-load','after-output','finally')]}
                if name=='gpu':result['provider_options']=dict(enableGraphCapture='0',enableInt64='1',preferredLayout='NCHW',dawnBackendType='Vulkan')
                (run / "result.json").write_text(json.dumps(result))
            command = [sys.executable, str(ROOT / "tools/encoder_probe.py"), "compare",
                       "--reference", str(folder / "cpu"), "--candidate", str(folder / "gpu")]
            compared = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(0, compared.returncode, compared.stderr)
            verdict = json.loads(compared.stdout)
            self.assertTrue(verdict["tensor_verdict"]["passed"])
            self.assertFalse(verdict["eligible"])
            self.assertEqual("unproven", verdict["physical_fp32_proof"])
            for run in (folder / "cpu", folder / "gpu"):
                (run / "encoder-probe-output0.f32").unlink()
                (run / "encoder-probe-output1.i64").unlink()
            json_only = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(0, json_only.returncode, json_only.stderr)
            self.assertTrue(json.loads(json_only.stdout)["tensor_verdict"]["passed"])
            candidate_path = folder / "gpu/result.json"
            original = json.loads(candidate_path.read_text())
            mutations = [
                lambda r: r["frozen"]["builds"].update(app_sha256="changed-app"),
                lambda r: r["frozen"]["builds"].update(test_sha256="changed-test"),
                lambda r: r["frozen"]["sources"].update(harness="changed-source"),
                lambda r: r["frozen"]["runtime"].update(core_sha256="changed-core"),
                lambda r: r.update(build_sha256="changed-installed-app"),
                lambda r: r.update(test_build_sha256="changed-installed-test"),
                lambda r: r.update(runtime_core_sha256="changed-installed-core"),
                lambda r: r.update(runtime_jni_sha256="changed-installed-jni"),
                lambda r: r.update(intra_op_threads=2),
                lambda r: r.update(optimization="NO_OPT"),
                lambda r: r.update(protocol="historical"),
                lambda r: r.pop("attempt_id"),
                lambda r: r.update(attempt_id="a"*32),
                lambda r: r.update(pid=100,processStartElapsedMs=1000),
                lambda r: r.update(cpu_fallback_disabled=False),
                lambda r: r["provider_options"].update(enableInt64="0"),
                lambda r: r["outputs"][0].update(shape=[1,3,1024]),
                lambda r: r.pop("initialization_elapsed_ms"),
                lambda r: r['frozen'].update(eligible=True),
                lambda r: r['frozen'].update(atol=1.0),
                lambda r: r['frozen'].update(rtol=1.0),
                lambda r: r['frozen'].update(input_kind='personal'),
                lambda r: r['frozen'].update(scope='full take'),
                lambda r: r['frozen'].update(staging_envelope={'variant.json':'0'*64}),
            ]
            for index, mutate in enumerate(mutations):
                with self.subTest(changed_identity=index):
                    changed = copy.deepcopy(original)
                    mutate(changed)
                    candidate_path.write_text(json.dumps(changed))
                    mismatched = subprocess.run(command, capture_output=True, text=True)
                    self.assertEqual(2, mismatched.returncode, mismatched.stdout)
            candidate_path.write_text(json.dumps(original))
            (folder / "gpu/encoder-probe-output0.f32").write_bytes(bytes(4))
            corrupted = subprocess.run(command, capture_output=True, text=True)
            self.assertNotEqual(0, corrupted.returncode)
            self.assertIn("output changed", corrupted.stderr)

class PlacementCheckTests(unittest.TestCase):
    """ADR-023 as amended, on fabricated listings; graph types are given, not parsed."""
    STEPS = {'/Gather': ('Gather', [7, 7, 7]), '/Unsqueeze': ('Unsqueeze', [7, 7, 7]),
             '/MatMul': ('MatMul', [1, 1, 1]), '/Where': ('Where', [9, 1, 1, 1])}

    def listing(self, missing, preferred, declared, webgpu=2):
        sys.path.insert(0, str(ROOT / 'tools'))
        import encoder_probe
        return encoder_probe.placement_check(
            {'providers': {'CPUExecutionProvider': {'declared': declared, 'all': False, 'nodes': []},
                           'WebGpuExecutionProvider': {'declared': webgpu, 'all': False, 'nodes': []}},
             'causes': {'kernel_not_found': [['Gather', name] for name in missing], 'cpu_preferred': preferred}},
            self.STEPS)

    def test_shape_work_named_by_causes_with_matching_count_passes(self):
        result = self.listing(['/Gather'], ['/Unsqueeze'], 2)
        self.assertTrue(result['passed'], result['failures'])

    def test_count_mismatch_duplicate_unknown_or_tensor_math_fails(self):
        for missing, preferred, declared in ((['/Gather'], ['/Unsqueeze'], 3), (['/Gather'], ['/Gather'], 2),
                                             (['/Gone'], [], 1), ([], ['/MatMul'], 1), ([], ['/Where'], 1),
                                             ([], [''], 1), (['/MatMul'], [], 1)):
            with self.subTest(missing=missing, preferred=preferred, declared=declared):
                self.assertFalse(self.listing(missing, preferred, declared)['passed'])
        self.assertFalse(self.listing(['/Gather'], ['/Unsqueeze'], 2, webgpu=0)['passed'])

if __name__ == '__main__':
    unittest.main()
