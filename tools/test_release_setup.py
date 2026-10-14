import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from datetime import datetime, timezone, timedelta

import measurement_identity as identity
import record_release_result as recorder
from wrap_historical_measurement import wrap
from check_release_1a import launch_errors


class SetupEvidenceTests(unittest.TestCase):
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

    def test_launch_requires_real_window_fresh_session_and_restoration_artifact(self):
        now = datetime(2026, 10, 14, 10, tzinfo=timezone(timedelta(hours=2)))
        manifest = {'source_commit': 'commit', 'limits': {'restoration_margin_seconds': 900}}
        with tempfile.TemporaryDirectory() as directory:
            artifact = Path(directory) / 'restore.apk'
            artifact.write_bytes(b'fixture')
            launch = {'authorized_by': 'Oriol', 'authorized': True, 'source_commit': 'commit',
                      'session_id': 'fresh', 'hook_confirmed': True, 'guard_tests_passed': True,
                      'device_connection_and_versions_checked': True, 'checked_at': now.isoformat(),
                      'phone_window': {'timezone': 'Europe/Zurich', 'start': now.isoformat(),
                                       'end': (now+timedelta(hours=3)).isoformat()},
                      'restoration': {'owner': 'Oriol', 'procedure': 'Owner restores the approved build',
                                      'artifact_path': str(artifact), 'artifact_sha256': identity.sha(artifact)}}
            self.assertEqual(launch_errors(manifest, launch, 'fresh', now), [])
            self.assertTrue(launch_errors(manifest, launch, 'old', now))
            artifact.write_bytes(b'changed')
            self.assertIn('restoration artifact hash mismatch', launch_errors(manifest, launch, 'fresh', now))
            launch['phone_window']['end'] = now.isoformat()
            self.assertIn('phone window has no measurement time', launch_errors(manifest, launch, 'fresh', now))
            launch['phone_window'] = 'TBD'
            self.assertTrue(launch_errors(manifest, launch, 'fresh', now))

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

    def test_duplicate_device_result_preserves_raw_but_cannot_become_repeat(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            phone = root / 'corpus/lab/phone'
            phone.mkdir(parents=True)
            source = phone / 'returned.json'
            source.write_text(json.dumps({'provenance': {'run_id': 'one', 'process_start_id': 'one'}}))
            runs = [{'id': str(n), 'platform': 'phone', 'mode': 'persistent',
                     'output': str(root / f'{n}.json'), 'raw_output': str(root / f'{n}.raw.json')}
                    for n in (1, 2)]
            with patch.object(recorder, 'ROOT', root):
                recorder.record({'runs': runs}, '1', source)
                with self.assertRaisesRegex(ValueError, 'reused'):
                    recorder.record({'runs': runs}, '2', source)
            self.assertEqual(Path(runs[1]['raw_output']).read_bytes(), source.read_bytes())
            self.assertFalse(Path(runs[1]['output']).exists())


if __name__ == '__main__':
    unittest.main()
