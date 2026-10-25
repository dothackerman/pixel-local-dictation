import os
import subprocess
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from quality import staged_errors


class StagedQualityTests(unittest.TestCase):
    def setUp(self):
        # Commit hooks export repository variables. Every Git call in this test,
        # including staged_errors(), must operate only on its temporary repository.
        environment = {key: value for key, value in os.environ.items()
                       if not key.startswith('GIT_')}
        environment.update(GIT_CONFIG_GLOBAL=os.devnull, GIT_CONFIG_NOSYSTEM='1')
        isolation = patch.dict(os.environ, environment, clear=True)
        isolation.start()
        self.addCleanup(isolation.stop)

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
