import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import measurement_identity as identity
import record_release_result as recorder


class SetupEvidenceTests(unittest.TestCase):
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
