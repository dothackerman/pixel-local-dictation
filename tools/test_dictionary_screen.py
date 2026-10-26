import unittest
from pathlib import Path
import tempfile
import subprocess
import sys
from types import SimpleNamespace
from unittest.mock import patch

import dictionary_screen
from dictionary_boost import PhraseTree


class ScreenTests(unittest.TestCase):
    def test_failed_evaluator_checks_leave_no_evidence_or_model_work(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            output = root / 'corpus/run'
            with patch('subprocess.run', side_effect=subprocess.CalledProcessError(1, ['unittest'])), \
                    patch.object(dictionary_screen, 'Decoder') as decoder:
                with self.assertRaises(subprocess.CalledProcessError):
                    dictionary_screen.run(SimpleNamespace(root=root, output=output))
            self.assertFalse(output.exists())
            decoder.assert_not_called()

    def test_existing_screen_evidence_is_never_reused(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            output = root / 'corpus/run'
            output.mkdir(parents=True)
            original = output / 'manifest.json'
            original.write_text('retained evidence')
            with patch('subprocess.run', wraps=subprocess.run) as checks:
                with self.assertRaises(FileExistsError):
                    dictionary_screen.run(SimpleNamespace(root=root, output=output))
            self.assertEqual(checks.call_args.args[0], [sys.executable, '-m', 'unittest', '-q',
                             'test_dictionary_score', 'test_dictionary_screen.HeldOutTests'])
            self.assertEqual(checks.call_args.kwargs['cwd'], Path(dictionary_screen.__file__).resolve().parent)
            self.assertEqual(original.read_text(), 'retained evidence')


class HeldOutTests(unittest.TestCase):
    def test_held_word_is_not_boosted_through_a_retained_longer_entry(self):
        entries = ['Alice', 'Alice Smith', 'Cedar']
        kept = dictionary_screen.held_out_entries(entries, [('en', 'Alice visited home')])
        paths = {'Alice': [1], 'Alice Smith': [1, 2], 'Cedar': [3]}
        tree = PhraseTree([paths[entry] for entry in kept])
        self.assertEqual(tree.step(0, 1)[0], 0)
        self.assertEqual(tree.step(0, 3)[0], 1)

