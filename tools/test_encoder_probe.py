"""Public short synthetic probe preparation, no inference or personal evidence."""
import json
import base64
import copy
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
            self.assertEqual([1, 128, 17], manifest['shape'])
            self.assertEqual(17, manifest['true_length'])
            self.assertEqual(bytes(8704), (out / 'audio_signal.f32').read_bytes())
            self.assertEqual(b'\x11\0\0\0\0\0\0\0', (out / 'length.i64').read_bytes())
            self.assertFalse(manifest['eligible'])
            self.assertEqual('synthetic silence; no recordings', manifest['input_kind'])
            repeated = subprocess.run(command, capture_output=True, text=True)
            self.assertNotEqual(0, repeated.returncode)

    def test_direct_tensor_comparison_keeps_android_reference_scale_and_never_advances(self):
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp)
            for name, value in (("cpu", 1000.0), ("gpu", 1000.5)):
                run = folder / name
                run.mkdir()
                np.array([value], dtype="<f4").tofile(run / "output.f32")
                np.array([17], dtype="<i8").tofile(run / "length.i64")
                import hashlib
                outputs = [{"file": file, "shape": [1], "dtype": dtype,
                            "sha256": hashlib.sha256((run / file).read_bytes()).hexdigest(),
                            "raw_base64": base64.b64encode((run / file).read_bytes()).decode()}
                           for file, dtype in (("output.f32", "FLOAT"), ("length.i64", "INT64"))]
                result = {"outcome": "complete", "runtime_version": "1.31.0", "outputs": outputs,
                          "frozen": {"provider": "cpu" if name == "cpu" else "webgpu",
                                     "inputs": {"fabricated": "same"}, "model": {"fabricated": "same"},
                                     "builds": {"app_sha256": "app", "test_sha256": "test"},
                                     "runtime": {"version": "1.31.0", "core_sha256": "core", "jni_sha256": "jni"},
                                     "sources": {"harness": "source"}},
                          "build_sha256": "app", "test_build_sha256": "test",
                          "runtime_core_sha256": "core", "runtime_jni_sha256": "jni",
                          "optimization": "ALL_OPT", "execution_mode": "SEQUENTIAL",
                          "intra_op_threads": 4, "inter_op_threads": 1, "profiling": True}
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
                (run / "output.f32").unlink()
                (run / "length.i64").unlink()
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
            ]
            for index, mutate in enumerate(mutations):
                with self.subTest(changed_identity=index):
                    changed = copy.deepcopy(original)
                    mutate(changed)
                    candidate_path.write_text(json.dumps(changed))
                    mismatched = subprocess.run(command, capture_output=True, text=True)
                    self.assertEqual(2, mismatched.returncode, mismatched.stdout)
            candidate_path.write_text(json.dumps(original))
            (folder / "gpu/output.f32").write_bytes(bytes(4))
            corrupted = subprocess.run(command, capture_output=True, text=True)
            self.assertNotEqual(0, corrupted.returncode)
            self.assertIn("output changed", corrupted.stderr)

if __name__ == '__main__':
    unittest.main()
