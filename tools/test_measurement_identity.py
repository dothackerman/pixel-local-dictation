import tempfile
from pathlib import Path
import unittest

import measurement_identity as identity


class MeasurementIdentityTests(unittest.TestCase):
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



    def test_reference_identity_tracks_private_fixture_without_retired_docs(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name in ('corpus/takes.json', 'models/guard/refs.tsv',
                         'corpus/references/baseline-v1.json'):
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text('{}')
            fixture = root / 'corpus/references/baseline-v1.json'
            before = identity.reference_files(root)
            self.assertIn(str(fixture), before)
            fixture.write_text('{"schema_version": 1}')
            self.assertNotEqual(identity.digest(before),
                                identity.digest(identity.reference_files(root)))
