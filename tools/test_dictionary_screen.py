import unittest
from pathlib import Path
import tempfile
from types import SimpleNamespace

import dictionary_screen
from dictionary_boost import PhraseTree


class ScreenTests(unittest.TestCase):
    def test_existing_screen_evidence_is_never_reused(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            output = root / 'corpus/run'
            output.mkdir(parents=True)
            original = output / 'manifest.json'
            original.write_text('retained evidence')
            with self.assertRaises(FileExistsError):
                dictionary_screen.run(SimpleNamespace(root=root, output=output))
            self.assertEqual(original.read_text(), 'retained evidence')

    def test_held_word_is_not_boosted_through_a_retained_longer_entry(self):
        entries = ['Alice', 'Alice Smith', 'Cedar']
        kept = dictionary_screen.held_out_entries(entries, [('en', 'Alice visited home')])
        paths = {'Alice': [1], 'Alice Smith': [1, 2], 'Cedar': [3]}
        tree = PhraseTree([paths[entry] for entry in kept])
        self.assertEqual(tree.step(0, 1)[0], 0)
        self.assertEqual(tree.step(0, 3)[0], 1)

