import subprocess
from pathlib import Path
import tempfile
import unittest

from quality import staged_errors


class StagedQualityTests(unittest.TestCase):
    def test_checkout_must_match_staged_source_inputs(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            subprocess.run(['git', 'init', '-q', str(root)], check=True)
            (root / 'tools').mkdir()
            source = root / 'tools/source.py'
            source.write_text('original')
            subprocess.run(['git', 'add', 'tools/source.py'], cwd=root, check=True)
            self.assertEqual(staged_errors(root), [])
            source.write_text('unstaged')
            self.assertTrue(staged_errors(root))
            subprocess.run(['git', 'add', 'tools/source.py'], cwd=root, check=True)
            self.assertEqual(staged_errors(root), [])
            (root / 'notes.md').write_text('unrelated notes')
            self.assertEqual(staged_errors(root), [])
            (root / 'tools/test_new.py').write_text('new test')
            self.assertTrue(staged_errors(root))
