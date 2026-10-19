import tempfile
from pathlib import Path
import unittest

import measurement_identity as identity
from wrap_historical_measurement import wrap


class MeasurementIdentityTests(unittest.TestCase):
    def test_historical_report_requires_measured_input_and_artifact_hashes(self):
        manifest = {'provenance': {'build_sha256': 'app', 'test_build_sha256': 'test', 'configuration_sha256': 'config'},
                    'artifacts': {'model/encoder.onnx': 'model'},
                    'inputs': [{'path': '../benchmark/take.wav', 'sha256': 'audio'}]}
        raw = {'measurementProtocol': 'historical-full-v1', 'buildSha256': 'app',
               'testBuildSha256': 'test', 'configurationSha256': 'config', 'runId': 'run', 'processStartId': 'process'}
        with self.assertRaisesRegex(ValueError, 'measured artifact'):
            wrap(raw, manifest)
        raw['verifiedArtifacts'] = manifest['artifacts']
        raw['verifiedInputs'] = {'../benchmark/take.wav': 'changed'}
        with self.assertRaisesRegex(ValueError, 'measured input'):
            wrap(raw, manifest)
        raw['verifiedInputs']['../benchmark/take.wav'] = 'audio'
        self.assertEqual(wrap(raw, manifest)['outcome'], 'complete')


    def test_guard_audio_change_changes_quality_identity(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            guard = root / 'models/guard'
            (guard / 'en').mkdir(parents=True)
            (guard / 'refs.tsv').write_text('id\tlang\tseconds\ttext\nclip\ten\t1\thello\n')
            audio = guard / 'en/clip.wav'
            audio.write_bytes(b'original')
            original = identity.quality_inputs(root, [])
            audio.write_bytes(b'changed')
            self.assertNotEqual(original, identity.quality_inputs(root, []))


